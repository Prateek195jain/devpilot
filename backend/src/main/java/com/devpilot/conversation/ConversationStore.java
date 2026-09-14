package com.devpilot.conversation;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
public interface ConversationStore extends JpaRepository<Conversation,UUID> {
    Page<Conversation> findByRepositoryId(UUID repositoryId,Pageable p);
    Optional<Conversation> findByIdAndRepositoryId(UUID id,UUID repositoryId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Conversation c where c.id=:id")Optional<Conversation> lock(UUID id);
}
