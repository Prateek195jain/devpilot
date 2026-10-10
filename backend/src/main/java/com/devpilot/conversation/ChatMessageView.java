package com.devpilot.conversation;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import com.devpilot.repository.RepositorySourceReference;
public record ChatMessageView(UUID id,Instant createdAt,UUID conversationId,String role,String content,
        List<RepositorySourceReference> sources) {
    public ChatMessageView {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
    public ChatMessageView(UUID id,Instant createdAt,UUID conversationId,String role,String content) {
        this(id,createdAt,conversationId,role,content,List.of());
    }
    public static ChatMessageView of(ChatMessage e) {
        return new ChatMessageView(e.id,e.createdAt,e.conversationId,e.role,e.content,e.sources);
    }
}
