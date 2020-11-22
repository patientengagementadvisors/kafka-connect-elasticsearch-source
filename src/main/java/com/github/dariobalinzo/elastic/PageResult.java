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

import java.util.List;
import java.util.Map;

public class PageResult {
    private final String index;
    private final List<Map<String, Object>> documents;
    private final String lastCursor;

    public PageResult(String index, List<Map<String, Object>> documents, String cursorField) {
        this.index = index;
        this.documents = documents;
        if (documents.isEmpty()) {
            this.lastCursor = null;
        } else {
            Map<String, Object> lastDocument = documents.get(documents.size() - 1);
            this.lastCursor = lastDocument.get(cursorField).toString();
        }
    }

    public List<Map<String, Object>> getDocuments() {
        return documents;
    }

    public String getLastCursor() {
        return lastCursor;
    }

    public String getIndex() {
        return index;
    }
}
