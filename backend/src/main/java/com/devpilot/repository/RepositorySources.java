package com.devpilot.repository;

import java.util.List;
import java.util.UUID;

/** Immutable source snapshot; hashes cover the original UTF-8 bytes, without newline conversion. */
public record RepositorySources(UUID repositoryId, String commitSha, List<File> files) {
    public RepositorySources {
        files = List.copyOf(files);
    }
    public record File(String path, String content, long byteSize, String sha256) {}
}
