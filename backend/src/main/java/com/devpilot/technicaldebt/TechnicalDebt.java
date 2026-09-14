package com.devpilot.technicaldebt;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="technical_debt") public class TechnicalDebt extends BaseEntity {
    @Column(name="repository_id") public UUID repositoryId;
    @Column(name="source_finding_id") public UUID sourceFindingId;
    @Column(name="title",length=200) public String title;
    @Column(name="description",columnDefinition="text") public String description;
    @Column(name="category",length=50) public String category;
    @Column(name="severity",length=20) public String severity;
    @Column(name="status",length=20) public String status;
    @Column(name="estimated_effort",length=100) public String estimatedEffort;
    @Column(name="updated_at") public Instant updatedAt;
    @Column(name="resolved_at") public Instant resolvedAt;
}
