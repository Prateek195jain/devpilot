package com.devpilot.conversation;

import com.devpilot.ai.*;
import com.devpilot.common.Problem;
import com.devpilot.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationServiceTest {
    private final UUID repo = UUID.randomUUID();
    private final String commit = "a".repeat(40);
    private final Conversation conversation = new Conversation();
    private final ConversationStore conversations = mock(ConversationStore.class);
    private final MessageStore messages = mock(MessageStore.class);
    private final AIProvider ai = mock(AIProvider.class);
    private final ContextService legacy = mock(ContextService.class);
    private final RepositoryContextBuilder context = mock(RepositoryContextBuilder.class);
    private final RepositoryAccess access = mock(RepositoryAccess.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ConversationService service = new ConversationService(conversations,messages,ai,legacy,tx,context,access);

    @BeforeEach void setup() {
        conversation.repositoryId = repo;
        when(conversations.findByIdAndRepositoryId(conversation.id, repo)).thenReturn(Optional.of(conversation));
        when(conversations.lock(conversation.id)).thenReturn(Optional.of(conversation));
        when(messages.findTop12ByConversationIdOrderByCreatedAtDesc(conversation.id)).thenReturn(List.of());
        when(legacy.build(repo)).thenReturn("stored background");
        when(ai.answer(anyString(),anyList(),anyString())).thenReturn("answer");
        when(tx.execute(any())).thenAnswer(invocation -> invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
    }

    @Test void usesExactScopeAndQuestionKeepsHistorySeparateAndPersistsOnlyTurns() {
        String evidence = "[Source: A.java | chunk=0]\nclass A {}";
        var sources = List.of(new RepositorySourceReference("A.java",0,commit));
        when(context.build(repo,commit,"question")).thenReturn(new RepositoryContext(repo,commit,1,1,evidence.length(),evidence,sources));
        ChatMessage old = new ChatMessage(); old.role="user"; old.content="old";
        ChatMessage recent = new ChatMessage(); recent.role="assistant"; recent.content="recent";
        when(messages.findTop12ByConversationIdOrderByCreatedAtDesc(conversation.id)).thenReturn(List.of(recent,old));
        var result = service.send(repo,conversation.id,"question",commit);
        verify(ai).answer(evidence,List.of(new AIProvider.Turn("user","old"),new AIProvider.Turn("assistant","recent")),"question");
        verify(context).build(repo,commit,"question");
        verifyNoInteractions(legacy);
        assertEquals(2,result.size());
        assertEquals(List.of(),result.get(0).sources());
        assertEquals(sources,result.get(1).sources());
        verify(messages).saveAll(argThat(saved -> {
            var turns = new ArrayList<ChatMessage>();
            saved.forEach(turns::add);
            return turns.size()==2 && ChatMessageView.of(turns.get(1)).sources().equals(sources);
        }));
        var order = inOrder(access,conversations,context,ai);
        order.verify(access).require(repo);
        order.verify(conversations).findByIdAndRepositoryId(conversation.id,repo);
        order.verify(context).build(repo,commit,"question");
        order.verify(ai).answer(eq(evidence),anyList(),eq("question"));
    }

    @Test void emptyContextUsesExplicitlyLabeledStoredBackground() {
        when(context.build(repo,commit,"question")).thenReturn(new RepositoryContext(repo,commit,0,0,0,""));
        assertEquals(List.of(),service.send(repo,conversation.id,"question",commit).get(1).sources());
        verify(ai).answer(argThat(text -> text.contains("not evidence of that commit") && text.endsWith("stored background")),eq(List.of()),eq("question"));
    }

    @Test void omittedCommitPreservesLegacyChatWithoutRetrieval() {
        assertEquals(List.of(),service.send(repo,conversation.id,"question").get(1).sources());
        verify(ai).answer("stored background",List.of(),"question");
        verifyNoInteractions(context);
    }

    @Test void unauthorizedAccessFailsBeforeAnyEvidenceOrHistoryRead() {
        doThrow(Problem.notFound()).when(access).require(repo);
        assertThrows(Problem.class, () -> service.send(repo,conversation.id,"question",commit));
        verifyNoInteractions(context,legacy,messages,ai,conversations);
    }

    @Test void conversationFromAnotherRepositoryCannotLeakHistory() {
        UUID other = UUID.randomUUID();
        assertThrows(Problem.class, () -> service.send(other,conversation.id,"question",commit));
        verifyNoInteractions(context,legacy,messages,ai);
    }

    @Test void invalidCommitIsRejectedWithoutFallback() {
        assertThrows(Problem.class, () -> service.send(repo,conversation.id,"question","main"));
        verifyNoInteractions(context,legacy,messages,ai);
    }

    @Test void retrievalFailureDoesNotSilentlyFallBackOrPersist() {
        when(context.build(repo,commit,"question")).thenThrow(Problem.notFound());
        assertThrows(Problem.class, () -> service.send(repo,conversation.id,"question",commit));
        verifyNoInteractions(legacy,ai,tx);
    }
}
