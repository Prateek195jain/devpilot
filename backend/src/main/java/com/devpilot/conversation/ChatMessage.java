package com.devpilot.conversation;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
import com.devpilot.repository.RepositorySourceReference;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="messages") public class ChatMessage extends BaseEntity {
    @Column(name="conversation_id") public UUID conversationId;
    @Column(name="role",length=20) public String role;
    @Column(name="content",columnDefinition="text") public String content;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="sources",columnDefinition="jsonb",nullable=false)
    public List<RepositorySourceReference> sources = List.of();
}
