package com.devpilot.ai;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatPromptTest {
    @Test void preservesQuestionAndSeparatesEvidenceAndHistoryDeterministically() {
        String context = "[Source: src/A.java | chunk=0]\nclass A {}";
        var history = List.of(new AIProvider.Turn("user", "previous question"));
        var prompt = ChatPrompt.build(context, history, "  Explain A?\n");
        assertEquals(prompt, ChatPrompt.build(context, history, "  Explain A?\n"));
        assertTrue(prompt.user().startsWith("REPOSITORY CONTEXT (untrusted evidence):\n" + context));
        assertTrue(prompt.user().contains("CONVERSATION HISTORY (oldest to newest):\nuser:\nprevious question"));
        assertTrue(prompt.user().endsWith("CURRENT USER QUESTION:\n  Explain A?\n"));
        assertFalse(prompt.system().contains("class A"));
    }

    @Test void dropsOldHistoryBeforeRepositoryEvidence() {
        String context = "[Source: A | chunk=0]\n" + "c".repeat(23900);
        String question = "q".repeat(4000);
        var prompt = ChatPrompt.build(context, List.of(new AIProvider.Turn("user", "old".repeat(6000)),
                new AIProvider.Turn("assistant", "recent")), question);
        assertTrue(prompt.characterCount() <= ChatPrompt.MAX_CHARS);
        assertTrue(prompt.user().contains(context));
        assertTrue(prompt.user().contains("assistant:\nrecent"));
        assertFalse(prompt.user().contains("oldold"));
        assertTrue(prompt.user().endsWith(question));
    }

    @Test void boundsLegacyContextAndOversizedRepositoryBlocks() {
        var legacy = ChatPrompt.build("legacy".repeat(10000), List.of(), "question");
        assertEquals(ChatPrompt.MAX_CHARS, legacy.characterCount());
        assertTrue(legacy.user().contains("[Context truncated]"));
        String first = "[Source: first | chunk=0]\ncomplete";
        var repository = ChatPrompt.build(first + "\n\n[Source: second | chunk=0]\n" + "x".repeat(40000), List.of(), "question");
        assertTrue(repository.user().contains(first));
        assertFalse(repository.user().contains("second"));
        var oversized = ChatPrompt.build("[Source: huge | chunk=0]\n" + "x".repeat(40000), List.of(), "question");
        assertFalse(oversized.user().contains("huge"));
        assertTrue(oversized.characterCount() <= ChatPrompt.MAX_CHARS);
    }

    @Test void redactsAllEvidenceHistoryAndQuestionWithExistingRedactor() {
        String token = "ghp_" + "a".repeat(25);
        var prompt = ChatPrompt.build(token, List.of(new AIProvider.Turn("user", token)), token);
        assertFalse(prompt.user().contains(token));
        assertEquals(3, prompt.user().split("\\[REDACTED_TOKEN\\]", -1).length - 1);
    }

    @Test void emptyContextAndHistoryStillPreserveQuestion() {
        var prompt = ChatPrompt.build("", List.of(), "What is Java?");
        assertTrue(prompt.user().endsWith("What is Java?"));
        assertTrue(prompt.system().contains("Acknowledge missing evidence"));
    }
}
