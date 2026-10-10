package com.devpilot.ai;

import java.util.List;

/** Independent of generation configuration. Callers must authorize and sanitize source first. */
public interface EmbeddingProvider {
    record Embedding(String model, int dimensions, List<Double> values) {
        public Embedding { values = List.copyOf(values); }
    }
    String model();
    int dimensions();
    Embedding embedDocument(String title, String content);
    Embedding embedQuery(String query);
}
