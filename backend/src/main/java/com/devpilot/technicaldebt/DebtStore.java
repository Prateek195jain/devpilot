package com.devpilot.technicaldebt;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
public interface DebtStore extends JpaRepository<TechnicalDebt,UUID> {
    @Query("select d from TechnicalDebt d where d.repositoryId=:repo and (:status is null or d.status=:status) and (:severity is null or d.severity=:severity)") Page<TechnicalDebt> search(UUID repo,String status,String severity,Pageable p);
    Optional<TechnicalDebt> findByIdAndRepositoryId(UUID id,UUID repositoryId);
    Optional<TechnicalDebt> findByRepositoryIdAndSourceFindingId(UUID repositoryId,UUID findingId);
    long countByRepositoryId(UUID repositoryId);
    long countByRepositoryIdAndStatus(UUID repositoryId,String status);
    @Query("select d.severity,count(d) from TechnicalDebt d where d.repositoryId=:repo group by d.severity") List<Object[]> severities(UUID repo);
    @Query("select d.category,count(d) from TechnicalDebt d where d.repositoryId=:repo group by d.category") List<Object[]> categories(UUID repo);
}
