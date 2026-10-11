package com.devpilot.repository;

import com.devpilot.common.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Embedded source content at an immutable repository revision. */
@Entity
@Table(name="repository_chunks", uniqueConstraints=@UniqueConstraint(
        name="uq_repository_chunks_position", columnNames={"repository_id", "commit_sha", "source_path", "chunk_index"}))
public class RepositoryChunk extends BaseEntity {
    public static final int DIMENSIONS = 768;

    // UUID references follow the existing domain entities; the migration enforces the FK.
    @Column(name="repository_id", nullable=false) public UUID repositoryId;
    @Column(name="commit_sha", nullable=false, length=64) public String commitSha;
    @Column(name="source_path", nullable=false, length=500) public String sourcePath;
    @Column(name="chunk_index", nullable=false) public int chunkIndex;
    @Column(name="content", nullable=false, columnDefinition="text") public String content;
    @Column(name="content_hash", nullable=false, length=64) public String contentHash;
    @Column(name="embedding_model", nullable=false, length=100) public String embeddingModel;
    @Column(name="embedding", nullable=false)
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length=DIMENSIONS)
    public float[] embedding;

    @Column(name="published",nullable=false) public boolean published = true;
    @Column(name="build_token") public UUID buildToken;
    @Column(name="blob_sha",length=40) public String blobSha;
    @Column(name="chunking_key",length=100) public String chunkingKey;

    @PrePersist @PreUpdate
    void validate() {
        if (repositoryId == null || commitSha == null || !commitSha.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || sourcePath == null || sourcePath.isBlank() || sourcePath.length() > 500
                || chunkIndex < 0 || content == null || content.isBlank()
                || contentHash == null || !contentHash.matches("[0-9a-f]{64}")
                || embeddingModel == null || embeddingModel.isBlank() || embeddingModel.length() > 100
                || embedding == null || embedding.length != DIMENSIONS) {
            throw new IllegalArgumentException("Invalid repository chunk metadata or dimensions");
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Repository chunk embedding must be finite");
        }
    }
}
