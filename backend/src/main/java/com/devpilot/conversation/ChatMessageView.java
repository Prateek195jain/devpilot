package com.devpilot.conversation;
import java.util.UUID;
import java.time.Instant;
public record ChatMessageView(UUID id,Instant createdAt,UUID conversationId,String role,String content) {
    public static ChatMessageView of(ChatMessage e) {
        return new ChatMessageView(e.id,e.createdAt,e.conversationId,e.role,e.content);
    }
}
