package com.devpilot.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit live-provider check; excluded by Maven's default *Test naming patterns. */
@EnabledIfEnvironmentVariable(named = "RUN_EMBEDDING_SMOKE", matches = "true")
class   EmbeddingSmokeIT {
    @Test
    void actualProviderReturns768FiniteValues() {
        boolean success = false;
        // Only this supported model may reach the report; never echo arbitrary environment values.
        String model = "gemini-embedding-2";
        try {
            if (!"google".equals(env("EMBEDDING_MODE", "disabled"))
                    || !model.equals(env("EMBEDDING_MODEL", model))
                    || Integer.parseInt(env("EMBEDDING_DIMENSIONS", "768")) != 768) {
                throw new IllegalArgumentException();
            }
            EmbeddingProvider provider = new GoogleEmbeddingProvider(
                    env("EMBEDDING_API_KEY", ""),
                    env("EMBEDDING_BASE_URL", "https://generativelanguage.googleapis.com/v1beta"),
                    model, 768, Integer.parseInt(env("EMBEDDING_TIMEOUT_SECONDS", "20")));
            // One call, no retries, no Spring application context or repository access.
            EmbeddingProvider.Embedding embedding = provider.embedQuery(
                    "A Java service validates login credentials.");
            success = model.equals(embedding.model()) && embedding.dimensions() == 768
                    && embedding.values().size() == 768
                    && embedding.values().stream().allMatch(value -> value != null && Double.isFinite(value));
        } catch (RuntimeException ignored) {
            // Do not expose configuration values, transport details, credentials, or vectors.
        }
        System.out.printf("success=%s model=%s dimensions=768%n", success, model);
        if (!success) {
            throw new AssertionError("Embedding smoke check unsuccessful");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
