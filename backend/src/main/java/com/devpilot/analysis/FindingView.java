package com.devpilot.analysis;
import java.util.UUID;
import java.time.Instant;
public record FindingView(UUID id,Instant createdAt,UUID analysisId,UUID repositoryId,String type,String severity,String title,String description,String file,Integer line,String recommendation,double confidence,String source) {
    public static FindingView of(Finding e) {
        return new FindingView(e.id,e.createdAt,e.analysisId,e.repositoryId,e.type,e.severity,e.title,e.description,e.file,e.line,e.recommendation,e.confidence,e.source);
    }
}
