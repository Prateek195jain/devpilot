package com.devpilot.conversation;
import java.util.UUID;
import java.time.Instant;
public record ConversationView(UUID id,Instant createdAt,UUID repositoryId,String title) {
    public static ConversationView of(Conversation e) {
        return new ConversationView(e.id,e.createdAt,e.repositoryId,e.title);
    }
}
