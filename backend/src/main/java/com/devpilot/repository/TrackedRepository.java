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
}
