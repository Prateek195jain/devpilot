package com.devpilot.memory;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="engineering_memories") public class EngineeringMemory extends BaseEntity {
    @Column(name="repository_id") public UUID repositoryId;
    @Column(name="fingerprint",length=64) public String fingerprint;
    @Column(name="type",length=50) public String type;
    @Column(name="description",columnDefinition="text") public String description;
    @Column(name="occurrences") public int occurrences;
    @Column(name="last_seen") public Instant lastSeen;
    @Column(name="status",length=20) public String status;
}
