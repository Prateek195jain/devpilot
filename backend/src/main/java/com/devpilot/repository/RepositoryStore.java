package com.devpilot.repository;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
public interface RepositoryStore extends JpaRepository<TrackedRepository,UUID> {
    Page<TrackedRepository> findByOwnerId(UUID ownerId,Pageable p);
    Optional<TrackedRepository> findByIdAndOwnerId(UUID id,UUID ownerId);
    List<TrackedRepository> findByGithubId(Long githubId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from TrackedRepository r where r.id=:id") Optional<TrackedRepository> lock(UUID id);
    @Query("select r.id from TrackedRepository r where r.indexingStatus in (com.devpilot.repository.IndexingStatus.INDEXING, com.devpilot.repository.IndexingStatus.WAITING_RETRY) and (r.nextIndexingAttemptAt is null or r.nextIndexingAttemptAt<=:now) and (r.indexingJobToken is null or r.indexingLeaseUntil is null or r.indexingLeaseUntil<=:now) order by r.syncedAt asc, r.id asc")
    List<UUID> pendingIndexJobs(java.time.Instant now, Pageable pageable);
}
