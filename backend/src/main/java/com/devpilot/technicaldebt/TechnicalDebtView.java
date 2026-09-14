package com.devpilot.technicaldebt;
import java.util.UUID;
import java.time.Instant;
public record TechnicalDebtView(UUID id,Instant createdAt,UUID repositoryId,UUID sourceFindingId,String title,String description,String category,String severity,String status,String estimatedEffort,Instant updatedAt,Instant resolvedAt) {
    public static TechnicalDebtView of(TechnicalDebt e) {
        return new TechnicalDebtView(e.id,e.createdAt,e.repositoryId,e.sourceFindingId,e.title,e.description,e.category,e.severity,e.status,e.estimatedEffort,e.updatedAt,e.resolvedAt);
    }
}
