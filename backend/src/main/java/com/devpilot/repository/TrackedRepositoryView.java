package com.devpilot.repository;
import java.util.UUID;
import java.time.Instant;
public record TrackedRepositoryView(UUID id,Instant createdAt,UUID ownerId,Long githubId,String fullName,String description,String defaultBranch,String language,Instant syncedAt) {
    public static TrackedRepositoryView of(TrackedRepository e) {
        return new TrackedRepositoryView(e.id,e.createdAt,e.ownerId,e.githubId,e.fullName,e.description,e.defaultBranch,e.language,e.syncedAt);
    }
}
