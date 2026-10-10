package com.devpilot.memory;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
public interface MemoryStore extends JpaRepository<EngineeringMemory,UUID> {
    Page<EngineeringMemory> findByRepositoryId(UUID repositoryId,Pageable p);
    Page<EngineeringMemory> findByRepositoryIdAndStatus(UUID repositoryId,String status,Pageable p);
    Optional<EngineeringMemory> findByRepositoryIdAndFingerprint(UUID repositoryId,String fingerprint);
    Optional<EngineeringMemory> findByIdAndRepositoryId(UUID id,UUID repositoryId);
    List<EngineeringMemory> findTop10ByRepositoryIdAndStatusOrderByOccurrencesDesc(UUID repositoryId,String status);
}
