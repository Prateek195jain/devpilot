package com.devpilot.documentation;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
public interface DocumentStore extends JpaRepository<GeneratedDocument,UUID> {
    Page<GeneratedDocument> findByRepositoryId(UUID repositoryId,Pageable p);
    Optional<GeneratedDocument> findFirstByRepositoryIdAndKindOrderByDocumentVersionDesc(UUID repositoryId,String kind);
    Optional<GeneratedDocument> findByIdAndRepositoryId(UUID id,UUID repositoryId);
}
