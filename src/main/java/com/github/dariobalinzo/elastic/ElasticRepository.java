/**
 * Copyright © 2018 Dario Balinzo (dariobalinzo@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.dariobalinzo.elastic;

import com.github.dariobalinzo.elastic.response.Cursor;
import com.github.dariobalinzo.elastic.response.PageResult;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

import static com.github.dariobalinzo.elastic.ElasticJsonNaming.removeKeywordSuffix;
import static org.opensearch.index.query.QueryBuilders.*;

public final class ElasticRepository {
    private final static Logger logger = LoggerFactory.getLogger(ElasticRepository.class);

    private final ElasticConnection elasticConnection;

    private final String cursorSearchField;
    private final String secondaryCursorSearchField;
    private final CursorField cursorField;
    private final CursorField secondaryCursorField;

    private int pageSize = 5000;

    public ElasticRepository(ElasticConnection elasticConnection) {
        this(elasticConnection, "_id");
    }

    public ElasticRepository(ElasticConnection elasticConnection, String cursorField) {
        this(elasticConnection, cursorField, null);
    }

    public ElasticRepository(ElasticConnection elasticConnection, String cursorSearchField, String secondaryCursorSearchField) {
        this.elasticConnection = elasticConnection;
        this.cursorSearchField = cursorSearchField;
        this.cursorField = new CursorField(cursorSearchField);
        this.secondaryCursorSearchField = secondaryCursorSearchField;
        this.secondaryCursorField = secondaryCursorSearchField == null ? null : new CursorField(secondaryCursorSearchField);
    }

    public PageResult searchAfter(String index, Cursor cursor) throws IOException, InterruptedException {
        QueryBuilder queryBuilder = cursor.getPrimaryCursor() == null ?
                matchAllQuery() :
                buildGreaterThen(cursorSearchField, cursor.getPrimaryCursor());

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
                .query(queryBuilder)
                .size(pageSize)
                .sort(cursorSearchField, SortOrder.ASC);

        SearchRequest searchRequest = new SearchRequest(index)
                .source(searchSourceBuilder);

        SearchResponse response = executeSearch(searchRequest);

        List<Map<String, Object>> documents = extractDocuments(response);

        Cursor lastCursor;
        if (documents.isEmpty()) {
            lastCursor = Cursor.empty();
        } else {
            Map<String, Object> lastDocument = documents.get(documents.size() - 1);
            lastCursor = new Cursor(cursorField.read(lastDocument));
        }
        return new PageResult(index, documents, lastCursor);
    }

    private List<Map<String, Object>> extractDocuments(SearchResponse response) {
        return Arrays.stream(response.getHits().getHits())
                .map(hit -> {
                    Map<String, Object> sourceMap = hit.getSourceAsMap();
                    sourceMap.put("es-id", hit.getId());
                    sourceMap.put("es-index", hit.getIndex());
                    return sourceMap;
                }).collect(Collectors.toList());
    }

    public PageResult searchAfterWithSecondarySort(String index, Cursor cursor) throws IOException, InterruptedException {
        Objects.requireNonNull(secondaryCursorField);
        String primaryCursor = cursor.getPrimaryCursor();
        String secondaryCursor = cursor.getSecondaryCursor();
        boolean noPrevCursor = primaryCursor == null && secondaryCursor == null;

        QueryBuilder queryBuilder = noPrevCursor ? matchAllQuery() :
                getSecondarySortFieldQuery(primaryCursor, secondaryCursor);

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
                .query(queryBuilder)
                .size(pageSize)
                .sort(cursorSearchField, SortOrder.ASC)
                .sort(secondaryCursorSearchField, SortOrder.ASC);

        SearchRequest searchRequest = new SearchRequest(index)
                .source(searchSourceBuilder);

        SearchResponse response = executeSearch(searchRequest);

        List<Map<String, Object>> documents = extractDocuments(response);

        Cursor lastCursor;
        if (documents.isEmpty()) {
            lastCursor = Cursor.empty();
        } else {
            Map<String, Object> lastDocument = documents.get(documents.size() - 1);
            String primaryCursorValue = cursorField.read(lastDocument);
            String secondaryCursorValue = secondaryCursorField.read(lastDocument);
            lastCursor = new Cursor(primaryCursorValue, secondaryCursorValue);
        }
        return new PageResult(index, documents, lastCursor);
    }

    private QueryBuilder buildGreaterThen(String cursorField, String cursorValue) {
        return rangeQuery(cursorField).from(cursorValue, false);
    }

    private QueryBuilder getSecondarySortFieldQuery(String primaryCursor, String secondaryCursor) {
        if (secondaryCursor == null) {
            return buildGreaterThen(cursorSearchField, primaryCursor);
        }
        return boolQuery()
                .minimumShouldMatch(1)
                .should(buildGreaterThen(cursorSearchField, primaryCursor))
                .should(
                        boolQuery()
                                .filter(matchQuery(cursorSearchField, primaryCursor))
                                .filter(buildGreaterThen(secondaryCursorSearchField, secondaryCursor))
                );
    }

    private SearchResponse executeSearch(SearchRequest searchRequest) throws IOException, InterruptedException {
        int maxTrials = elasticConnection.getMaxConnectionAttempts();
        if (maxTrials <= 0) {
            throw new IllegalArgumentException("MaxConnectionAttempts should be > 0");
        }
        IOException lastError = null;
        for (int i = 0; i < maxTrials; ++i) {
            try {
                return elasticConnection.getClient()
                        .search(searchRequest, RequestOptions.DEFAULT);
            } catch (IOException e) {
                lastError = e;
                Thread.sleep(elasticConnection.getConnectionRetryBackoff());
            }
        }
        throw lastError;
    }

    public List<String> catIndices(String prefix) {
        Response resp;
        try {
            resp = elasticConnection.getClient()
                    .getLowLevelClient()
                    .performRequest(new Request("GET", "/_cat/indices"));
        } catch (IOException e) {
            logger.error("error in searching index names");
            throw new RuntimeException(e);
        }

        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.getEntity().getContent()))) {
            String line;

            while ((line = reader.readLine()) != null) {
                String index = line.split("\\s+")[2];
                if (index.startsWith(prefix)) {
                    result.add(index);
                }
            }
        } catch (IOException e) {
            logger.error("error while getting indices", e);
        }

        Collections.sort(result);

        return result;
    }

    public void refreshIndex(String index) {
        try {
            elasticConnection.getClient()
                    .getLowLevelClient()
                    .performRequest(new Request("POST", "/" + index + "/_refresh"));
        } catch (IOException e) {
            logger.error("error in refreshing index " + index);
            throw new RuntimeException(e);
        }
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
}
