package com.devpilot.repository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositorySourceAcquisitionSmokeDiagnosticsTest {
    @Test void retainsOriginalCauseButSanitizesNestedAndSuppressedOutput() {
        var root = new IllegalStateException("No default constructor found secret-token source-contents");
        var original = new RuntimeException("database-password encryption-key", root);
        original.addSuppressed(new RuntimeException("decrypted-credential"));
        var failure = new RepositorySourceAcquisitionSmokeIT.SafeSmokeFailure("application startup", original);
        assertSame(original, failure.getCause());

        StringWriter characters = new StringWriter();
        failure.printStackTrace(new PrintWriter(characters));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        failure.printStackTrace(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals(characters.toString(), bytes.toString(StandardCharsets.UTF_8));
        String output = characters.toString();
        assertTrue(output.contains("application startup"));
        assertTrue(output.contains("java.lang.IllegalStateException"));
        assertTrue(output.contains("No default constructor found"));
        assertTrue(output.contains("Suppressed:"));
        for (String secret : new String[] {"secret-token", "source-contents", "database-password", "encryption-key", "decrypted-credential"})
            assertFalse(output.contains(secret));
    }

    @Test void cyclicCausesDoNotPreventSafeDiagnostics() {
        var first = new RuntimeException("sensitive-first");
        var second = new RuntimeException("sensitive-second", first);
        first.initCause(second);
        var failure = new RepositorySourceAcquisitionSmokeIT.SafeSmokeFailure("source acquisition", first);
        StringWriter output = new StringWriter();
        assertDoesNotThrow(() -> failure.printStackTrace(new PrintWriter(output)));
        assertFalse(output.toString().contains("sensitive-"));
    }
}
