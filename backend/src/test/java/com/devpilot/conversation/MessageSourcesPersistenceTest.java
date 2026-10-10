package com.devpilot.conversation;

import com.devpilot.repository.RepositorySourceReference;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MessageSourcesPersistenceTest {
    @Autowired MessageStore messages;
    @Autowired EntityManager entities;

    @Test void sourcesSurviveDatabaseRoundTripAndHistoryMapping() {
        var message = new ChatMessage();
        message.conversationId = UUID.randomUUID();
        message.role = "assistant";
        message.content = "answer";
        var expected = List.of(new RepositorySourceReference("A.java",0,"a".repeat(40)),
                new RepositorySourceReference("A.java",1,"a".repeat(40)));
        message.sources = expected;
        messages.saveAndFlush(message);
        entities.clear();
        assertEquals(expected,ChatMessageView.of(messages.findById(message.id).orElseThrow()).sources());
    }
}
