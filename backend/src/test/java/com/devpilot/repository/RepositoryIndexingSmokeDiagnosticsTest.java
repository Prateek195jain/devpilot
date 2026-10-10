package com.devpilot.repository;

import com.devpilot.ai.EmbeddingFailure;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryIndexingSmokeDiagnosticsTest {
    @Test void retainsOriginalCauseButSanitizesMessagesAcrossCauseAndSuppressedChains() {
        String sensitive = "private-source-and-credentials-123";
        var root = new IllegalArgumentException("PKIX path building failed " + sensitive);
        var original = new IllegalStateException("Stored credential cannot be decrypted " + sensitive, root);
        original.addSuppressed(new RuntimeException(sensitive));
        var failure = new RepositoryIndexingSmokeIT.SafeSmokeFailure("indexing", original);
        assertSame(original, failure.getCause());
        assertTrue(failure.getMessage().contains("during indexing: java.lang.IllegalStateException"));
        assertTrue(failure.getMessage().contains("Stored credential cannot be decrypted"));
        assertFalse(failure.getMessage().contains(sensitive));
        var writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        assertFalse(writer.toString().contains(sensitive));
        assertTrue(writer.toString().contains("PKIX path building failed"));
        assertTrue(writer.toString().contains("Suppressed:"));
        var bytes = new ByteArrayOutputStream();
        failure.printStackTrace(new PrintStream(bytes));
        assertFalse(bytes.toString().contains(sensitive));
    }

    @Test void reportsSafeEmbeddingCategoryAndHandlesCauseCycles() {
        var embedding = new EmbeddingFailure(EmbeddingFailure.Kind.AUTHENTICATION);
        var failure = new RepositoryIndexingSmokeIT.SafeSmokeFailure("indexing", embedding);
        assertTrue(failure.getMessage().contains("Embedding request failed: AUTHENTICATION"));
        assertSame(embedding, failure.getCause());
        var first = new RuntimeException("unknown-sensitive-message");
        var second = new RuntimeException("another-sensitive-message", first);
        first.initCause(second);
        var writer = new StringWriter();
        new RepositoryIndexingSmokeIT.SafeSmokeFailure("indexing", first).printStackTrace(new PrintWriter(writer));
        assertFalse(writer.toString().contains("unknown-sensitive-message"));
        assertFalse(writer.toString().contains("another-sensitive-message"));
    }
}
