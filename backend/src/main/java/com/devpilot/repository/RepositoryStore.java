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
}
