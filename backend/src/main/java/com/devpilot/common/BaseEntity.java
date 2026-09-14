package com.devpilot.common;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@MappedSuperclass public abstract class BaseEntity {
    @Id public UUID id=UUID.randomUUID();
    @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
    @Version public long version;
}
