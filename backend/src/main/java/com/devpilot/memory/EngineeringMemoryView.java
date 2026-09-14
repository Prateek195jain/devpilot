package com.devpilot.memory;
import java.util.UUID;
import java.time.Instant;
public record EngineeringMemoryView(UUID id,Instant createdAt,UUID repositoryId,String type,String description,int occurrences,Instant lastSeen,String status) {
    public static EngineeringMemoryView of(EngineeringMemory e) {
        return new EngineeringMemoryView(e.id,e.createdAt,e.repositoryId,e.type,e.description,e.occurrences,e.lastSeen,e.status);
    }
}
