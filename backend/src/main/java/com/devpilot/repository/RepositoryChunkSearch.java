package com.devpilot.repository;

import java.util.List;
import java.util.UUID;

public interface RepositoryChunkSearch {
    List<RepositoryChunkMatch> search(UUID repositoryId, UUID ownerId, String commitSha,
            String model, float[] vector, int limit);
}
