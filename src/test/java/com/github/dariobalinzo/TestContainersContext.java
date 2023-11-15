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

package com.github.dariobalinzo;

import com.github.dariobalinzo.elastic.ElasticConnection;
import com.github.dariobalinzo.elastic.ElasticConnectionBuilder;
import com.github.dariobalinzo.elastic.ElasticRepository;
import com.github.dariobalinzo.task.ElasticSourceTaskConfig;
import org.apache.http.HttpHost;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.opensearch.testcontainers.OpensearchContainer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class TestContainersContext {
    protected static final int TEST_PAGE_SIZE = 3;
    protected static final int MAX_TRIALS = 2;
    protected static final int RETRY_WAIT_MS = 1_000;

    protected static final String TEST_INDEX = "source_index";
    protected static final String CURSOR_FIELD = "ts";
    protected static final String NESTED_OBJECT = "nested";
    protected static final String NESTED_CURSOR_FIELD = NESTED_OBJECT + "." + CURSOR_FIELD;
    protected static final String SECONDARY_CURSOR_FIELD = "fullName.keyword";

    protected static final String ELASTICSEARCH_IMAGE = "opensearchproject/opensearch:2.11.0";

    protected static OpensearchContainer container;
    protected static ElasticConnection connection;
    protected static ElasticRepository repository;
    protected static ElasticRepository nestedRepository;
    protected static ElasticRepository secondarySortRepo;

    @BeforeClass
    public static void setupElastic() {
        // Create the elasticsearch container.
        container = new OpensearchContainer(ELASTICSEARCH_IMAGE);
        container.addEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
        container.start();

        HttpHost httpHost = HttpHost.create(container.getHttpHostAddress());
        connection = new ElasticConnectionBuilder(httpHost.getHostName(), httpHost.getPort())
                .withMaxAttempts(MAX_TRIALS)
                .withBackoff(RETRY_WAIT_MS)
                .build();

        repository = new ElasticRepository(connection, CURSOR_FIELD);
        repository.setPageSize(TEST_PAGE_SIZE);

        nestedRepository = new ElasticRepository(connection, NESTED_CURSOR_FIELD);
        nestedRepository.setPageSize(TEST_PAGE_SIZE);

        secondarySortRepo = new ElasticRepository(connection, CURSOR_FIELD, SECONDARY_CURSOR_FIELD);
        secondarySortRepo.setPageSize(TEST_PAGE_SIZE);
    }


    protected void deleteTestIndex() {
        try {
            connection.getClient().indices().delete(new DeleteIndexRequest(TEST_INDEX), RequestOptions.DEFAULT);
        } catch (Exception ignored) {

        }
    }

    protected void refreshIndex() throws IOException, InterruptedException {
        repository.refreshIndex(TEST_INDEX);
    }

    protected void insertMockData(int tsStart) throws IOException {
        insertMockData(tsStart, TEST_INDEX);
    }

    protected void insertMockData(int tsStart, String index) throws IOException {
        insertMockData(tsStart, "Test", index);
    }

    protected void insertMockData(int tsStart, String fullName, String index) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .field("fullName", fullName)
                .field(CURSOR_FIELD, tsStart)
                .field("age", 10)
                .field("non-avro-field", "non-avro-field")
                .field("avroField", "avro-field")
                .startObject()
                .field(CURSOR_FIELD, tsStart)
                .endObject()
                .endObject();

        IndexRequest indexRequest = new IndexRequest(index);
        //indexRequest.type("_doc");
        indexRequest.source(builder);

        IndexResponse response = connection.getClient().index(indexRequest, RequestOptions.DEFAULT);
        assertEquals(DocWriteResponse.Result.CREATED, response.getResult());
    }

    protected Map<String, String> getConf() {
        HttpHost httpHost = HttpHost.create(container.getHttpHostAddress());
        Map<String, String> conf = new HashMap<>();
        conf.put(ElasticSourceTaskConfig.INDICES_CONFIG, TEST_INDEX);
        conf.put(ElasticSourceConnectorConfig.TOPIC_PREFIX_CONFIG, "topic");
        conf.put(ElasticSourceConnectorConfig.INCREMENTING_FIELD_NAME_CONFIG, CURSOR_FIELD);
        conf.put(ElasticSourceConnectorConfig.POLL_INTERVAL_MS_CONFIG, String.valueOf(10));
        conf.put(ElasticSourceConnectorConfig.ES_HOST_CONF, httpHost.getHostName());
        conf.put(ElasticSourceConnectorConfig.ES_PORT_CONF, String.valueOf(httpHost.getPort()));
        conf.put(ElasticSourceConnectorConfig.BATCH_MAX_ROWS_CONFIG, String.valueOf(2));
        conf.put(ElasticSourceConnectorConfig.CONNECTION_ATTEMPTS_CONFIG, String.valueOf(MAX_TRIALS));
        conf.put(ElasticSourceConnectorConfig.CONNECTION_BACKOFF_CONFIG, String.valueOf(RETRY_WAIT_MS));
        return conf;
    }


    @AfterClass
    public static void stopElastic() {
        if (container != null) {
            container.close();
        }
    }

}
