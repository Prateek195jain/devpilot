package com.devpilot.repository;

/** Safe metadata for a chunk included in an answer's repository context. */
public record RepositorySourceReference(String sourcePath, int chunkIndex, String commitSha) {}
