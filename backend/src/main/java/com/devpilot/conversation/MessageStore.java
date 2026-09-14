package com.devpilot.conversation;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
public interface MessageStore extends JpaRepository<ChatMessage,UUID> {
    Page<ChatMessage> findByConversationId(UUID conversationId,Pageable p);
    List<ChatMessage> findTop12ByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
