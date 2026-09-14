package com.devpilot.analysis;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
public interface FindingStore extends JpaRepository<Finding,UUID> {
    Page<Finding> findByRepositoryId(UUID repositoryId,Pageable p);
    Page<Finding> findByRepositoryIdAndAnalysisId(UUID repositoryId,UUID analysisId,Pageable p);
    Optional<Finding> findByIdAndRepositoryId(UUID id,UUID repositoryId);
    long countByRepositoryIdAndSeverity(UUID repositoryId,String severity);
    List<Finding> findTop15ByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);
}
