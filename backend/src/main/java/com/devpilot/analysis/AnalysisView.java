package com.devpilot.analysis;
import java.util.UUID;
import java.time.Instant;
public record AnalysisView(UUID id,Instant createdAt,UUID repositoryId,String kind,String ref,String status,String source,String summary,String error,int attempts,Instant startedAt,Instant completedAt,Instant nextAttemptAt) {
    public static AnalysisView of(Analysis e) {
        return new AnalysisView(e.id,e.createdAt,e.repositoryId,e.kind,e.ref,e.status,e.source,e.summary,e.error,e.attempts,e.startedAt,e.completedAt,e.nextAttemptAt);
    }
}
