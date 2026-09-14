package com.devpilot.documentation;
import java.util.UUID;
import java.time.Instant;
public record GeneratedDocumentView(UUID id,Instant createdAt,UUID repositoryId,String kind,int documentVersion,String content,String source) {
    public static GeneratedDocumentView of(GeneratedDocument e) {
        return new GeneratedDocumentView(e.id,e.createdAt,e.repositoryId,e.kind,e.documentVersion,e.content,e.source);
    }
}
