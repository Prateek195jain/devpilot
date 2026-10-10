package com.devpilot.repository;

import java.util.UUID;

public record RepositoryChunkMatch(UUID repositoryId, String commitSha, String sourcePath,
        int chunkIndex, String content, double distance) {}
