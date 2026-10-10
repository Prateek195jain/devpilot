package com.devpilot.repository;

import java.util.UUID;
import java.util.List;

public record RepositoryContext(UUID repositoryId, String commitSha, int matchesConsidered,
        int chunksIncluded, int characterCount, String text, List<RepositorySourceReference> sources) {
    public RepositoryContext {
        sources = List.copyOf(sources);
    }
    public RepositoryContext(UUID repositoryId, String commitSha, int matchesConsidered,
            int chunksIncluded, int characterCount, String text) {
        this(repositoryId, commitSha, matchesConsidered, chunksIncluded, characterCount, text, List.of());
    }
}
