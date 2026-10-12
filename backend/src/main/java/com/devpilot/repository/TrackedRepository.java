package com.devpilot.repository;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="repositories") public class TrackedRepository extends BaseEntity {
    @Column(name="owner_id") public UUID ownerId;
    @Column(name="github_id") public Long githubId;
    @Column(name="full_name",length=200) public String fullName;
    @Column(name="description",columnDefinition="text") public String description;
    @Column(name="default_branch",length=200) public String defaultBranch;
    @Column(name="language",length=100) public String language;
    @Column(name="readme",columnDefinition="text") public String readme;
    @Column(name="synced_at") public Instant syncedAt;
    @Column(name="latest_indexed_commit_sha",length=40) public String latestIndexedCommitSha;
    @Enumerated(EnumType.STRING) @Column(name="indexing_status",nullable=false,length=20)
    public IndexingStatus indexingStatus = IndexingStatus.NOT_INDEXED;
    @Column(name="indexing_target_commit_sha",length=40) public String indexingTargetCommitSha;
    @Enumerated(EnumType.STRING) @Column(name="last_indexing_error_category",length=40)
    public IndexingErrorCategory lastIndexingErrorCategory;
    @Column(name="indexing_job_token") public UUID indexingJobToken;
    @Column(name="indexing_lease_until") public Instant indexingLeaseUntil;
    @Column(name="next_indexing_attempt_at") public Instant nextIndexingAttemptAt;
    @Column(name="indexing_retry_count",nullable=false) public int indexingRetryCount;
}
