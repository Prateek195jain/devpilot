package com.devpilot.repository;

import com.devpilot.ai.EmbeddingFailure;
import com.devpilot.ai.EmbeddingProvider;

final class RepositoryEmbedding {
    private RepositoryEmbedding() {}

    static String model(EmbeddingProvider provider) {
        if (provider == null || provider.dimensions() != RepositoryChunk.DIMENSIONS)
            throw new EmbeddingFailure(EmbeddingFailure.Kind.CONFIGURATION);
        String model = provider.model();
        if (model == null || model.isBlank() || model.length() > 100)
            throw new EmbeddingFailure(EmbeddingFailure.Kind.CONFIGURATION);
        return model;
    }

    static float[] vector(EmbeddingProvider.Embedding embedding, String model) {
        if (embedding == null || !model.equals(embedding.model())
                || embedding.dimensions() != RepositoryChunk.DIMENSIONS
                || embedding.values().size() != RepositoryChunk.DIMENSIONS)
            throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_RESPONSE);
        float[] values = new float[RepositoryChunk.DIMENSIONS];
        for (int index = 0; index < values.length; index++) {
            double value = embedding.values().get(index);
            values[index] = (float) value;
            if (!Double.isFinite(value) || !Float.isFinite(values[index]))
                throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_RESPONSE);
        }
        return values;
    }
}
