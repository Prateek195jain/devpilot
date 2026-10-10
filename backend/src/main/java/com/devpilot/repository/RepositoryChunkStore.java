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
}
