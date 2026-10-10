package com.devpilot.repository;

import java.util.UUID;

/** Transient source chunk, independent of embeddings and persistence. Line bounds are 1-based and inclusive. */
public record SourceChunk(UUID repositoryId, String commitSha, String sourcePath, int chunkIndex,
                          String content, String contentHash, int startLine, int endLine) {}
