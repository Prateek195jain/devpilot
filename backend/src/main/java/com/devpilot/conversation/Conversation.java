package com.devpilot.conversation;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="conversations") public class Conversation extends BaseEntity {
    @Column(name="repository_id") public UUID repositoryId;
    @Column(name="title",length=200) public String title;
    @Column(name="updated_at",nullable=false) public Instant updatedAt=Instant.now();
}
