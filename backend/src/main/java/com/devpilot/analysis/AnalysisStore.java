package com.devpilot.analysis;
import java.util.*;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
public interface AnalysisStore extends JpaRepository<Analysis,UUID> {
    long countByRepositoryIdAndCreatedAtAfter(UUID repositoryId,Instant after);
    Page<Analysis> findByRepositoryId(UUID repositoryId,Pageable p);
    Optional<Analysis> findByIdAndRepositoryId(UUID id,UUID repositoryId);
    Optional<Analysis> findFirstByRepositoryIdAndKindAndRefOrderByCreatedAtDesc(UUID repositoryId,String kind,String ref);
    Optional<Analysis> findFirstByRepositoryIdAndStatusOrderByCreatedAtDesc(UUID repositoryId,String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Analysis a where a.status='QUEUED' and (a.nextAttemptAt is null or a.nextAttemptAt <= :now) order by a.createdAt") List<Analysis> claim(Instant now,Pageable p);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from Analysis a where a.id=:id") Optional<Analysis> lock(UUID id);
    @Modifying @Query("update Analysis a set a.status=case when a.attempts >= 3 then 'FAILED' else 'QUEUED' end, a.completedAt=case when a.attempts >= 3 then :now else a.completedAt end, a.nextAttemptAt=:now where a.status='RUNNING' and a.startedAt < :cutoff") int recover(Instant now,Instant cutoff);
}
