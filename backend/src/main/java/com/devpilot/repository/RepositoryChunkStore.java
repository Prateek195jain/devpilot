package com.devpilot.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RepositoryChunkStore extends JpaRepository<RepositoryChunk, UUID>, RepositoryChunkSearch {
    @Modifying(flushAutomatically = true)
    @Query("delete from RepositoryChunk c where c.repositoryId = :repositoryId and c.commitSha = :commitSha")
    int deleteCommit(UUID repositoryId, String commitSha);

    Page<RepositoryChunk> findByRepositoryIdAndCommitShaOrderBySourcePathAscChunkIndexAsc(
            UUID repositoryId, String commitSha, Pageable pageable);
    java.util.Optional<RepositoryChunk> findByRepositoryIdAndCommitShaAndSourcePathAndChunkIndex(UUID repositoryId,String commitSha,String sourcePath,int chunkIndex);
    java.util.List<RepositoryChunk> findByRepositoryIdAndCommitShaAndSourcePathOrderByChunkIndexAsc(UUID repositoryId, String commitSha, String sourcePath);
    java.util.Optional<RepositoryChunk> findFirstByRepositoryIdAndBlobShaAndEmbeddingModelAndChunkingKeyAndPublishedTrue(
            UUID repositoryId, String blobSha, String embeddingModel, String chunkingKey);
    java.util.Optional<RepositoryChunk> findFirstByRepositoryIdAndContentHashAndEmbeddingModelOrderByPublishedDesc(
            UUID repositoryId, String contentHash, String embeddingModel);
    long countByRepositoryIdAndCommitShaAndBuildToken(UUID repositoryId, String commitSha, UUID buildToken);
    long countByRepositoryIdAndCommitShaAndPublishedTrue(UUID repositoryId, String commitSha);
    @Modifying(flushAutomatically=true)
    @Query("delete from RepositoryChunk c where c.repositoryId=:repositoryId and c.commitSha=:commitSha and c.published=false and (c.buildToken is null or c.buildToken<>:token)")
    int deleteUnvisited(UUID repositoryId, String commitSha, UUID token);
    @Modifying(flushAutomatically=true)
    @Query("update RepositoryChunk c set c.published=true where c.repositoryId=:repositoryId and c.commitSha=:commitSha and c.buildToken=:token")
    int publish(UUID repositoryId, String commitSha, UUID token);
}
