package com.devpilot.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SourceFilePolicyTest {
    private final SourceFilePolicy policy = new SourceFilePolicy(10);

    @ParameterizedTest
    @ValueSource(strings = {"A.java", "a.kt", "a.js", "a.jsx", "a.ts", "a.tsx", "a.py", "a.go", "a.sql", "pom.xml", "a.json", "a.yml", "a.yaml", "a.properties", "README.md", "build.gradle", "Dockerfile"})
    void allowsEngineeringFiles(String path) { assertTrue(policy.eligible("src/" + path, 10)); }

    @ParameterizedTest
    @ValueSource(strings = {".git", "node_modules", "target", "build", "dist", "out", "vendor", "coverage", ".idea", ".next", "generated", ".gradle"})
    void excludesDirectoriesAtAnyDepth(String directory) { assertFalse(policy.eligible("nested/" + directory + "/a.java", 1)); }

    @ParameterizedTest
    @ValueSource(strings = {"image.png", "archive.zip", "a.class", "a.exe", "a.txt", "a.min.js", "package-lock.json", "java"})
    void excludesUnsupportedAndGeneratedFiles(String path) { assertFalse(policy.eligible(path, 1)); }

    @Test void sizesAndTextAreChecked() {
        assertFalse(policy.eligible("a.java", 11));
        assertFalse(policy.eligible("a.java", -1));
        assertTrue(policy.decode(new byte[] {0}).isEmpty());
        assertTrue(policy.decode(new byte[] {(byte) 0xff}).isEmpty());
        assertTrue(policy.decode(new byte[11]).isEmpty());
        assertEquals("hi\n", policy.decode(new byte[] {'h', 'i', '\n'}).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new SourceFilePolicy(1_048_577));
    }

    @Test void normalizesWithoutChangingCaseOrAllowingTraversal() {
        assertEquals("src/A.java", policy.normalize("./src//A.java"));
        for (String path : new String[] {"../a.java", "/a.java", "src/../a.java", "src\\a.java", ""})
            assertThrows(IllegalArgumentException.class, () -> policy.normalize(path));
    }
}
