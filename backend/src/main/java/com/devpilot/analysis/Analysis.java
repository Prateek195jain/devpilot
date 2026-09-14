package com.devpilot.analysis;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="analyses") public class Analysis extends BaseEntity {
    @Column(name="repository_id") public UUID repositoryId;
    @Column(name="kind",length=20) public String kind;
    @Column(name="ref",length=200) public String ref;
    @Column(name="status",length=20) public String status;
    @Column(name="source",length=40) public String source;
    @Column(name="summary",columnDefinition="text") public String summary;
    @Column(name="diff",columnDefinition="text") public String diff;
    @Column(name="error",length=500) public String error;
    @Column(name="attempts") public int attempts;
    @Column(name="started_at") public Instant startedAt;
    @Column(name="completed_at") public Instant completedAt;
    @Column(name="next_attempt_at") public Instant nextAttemptAt;
}
