package com.devpilot.conversation;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="messages") public class ChatMessage extends BaseEntity {
    @Column(name="conversation_id") public UUID conversationId;
    @Column(name="role",length=20) public String role;
    @Column(name="content",columnDefinition="text") public String content;
}
