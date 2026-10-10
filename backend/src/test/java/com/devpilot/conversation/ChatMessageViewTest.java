package com.devpilot.conversation;

import com.devpilot.repository.RepositorySourceReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatMessageViewTest {
    @Test void serializesOnlyAllowlistedSourceMetadataAndRetainsItOnHistoryViews() throws Exception {
        ChatMessage message = new ChatMessage();
        message.role = "assistant";
        message.content = "answer";
        message.sources = List.of(new RepositorySourceReference("A.java",2,"a".repeat(40)));
        var view = ChatMessageView.of(message);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(view);
        var sources = json.get("sources");
        assertEquals(1,sources.size());
        assertEquals(3,sources.get(0).size());
        assertEquals("A.java",sources.get(0).get("sourcePath").asText());
        assertEquals(2,sources.get(0).get("chunkIndex").asInt());
        assertEquals("a".repeat(40),sources.get(0).get("commitSha").asText());
        assertFalse(sources.toString().contains("embedding"));
        assertFalse(sources.toString().contains("distance"));
        assertFalse(sources.toString().contains("repositoryId"));
        assertEquals(message.sources,view.sources());
    }

    @Test void oldMessagesHaveAnEmptySourceArray() {
        var message = new ChatMessage();
        assertEquals(List.of(),ChatMessageView.of(message).sources());
        message.sources = null;
        assertEquals(List.of(),ChatMessageView.of(message).sources());
    }
}
