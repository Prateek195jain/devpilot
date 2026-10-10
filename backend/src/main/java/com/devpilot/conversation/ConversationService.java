package com.devpilot.conversation;
import com.devpilot.common.*;
import com.devpilot.ai.*;
import com.devpilot.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service public class ConversationService {
    private final ConversationStore conversations;
    private final MessageStore messages;
    private final AIProvider ai;
    private final ContextService context;
    private final TransactionTemplate tx;
    private final RepositoryContextBuilder repositoryContext;
    private final RepositoryAccess access;
    public ConversationService(ConversationStore c,MessageStore m,AIProvider a,ContextService x,TransactionTemplate t,
            RepositoryContextBuilder repositoryContext,RepositoryAccess access) {
        conversations=c;
        messages=m;
        ai=a;
        context=x;
        tx=t;
        this.repositoryContext=repositoryContext;
        this.access=access;
    }
    public List<ChatMessageView> send(UUID repo,UUID conversation,String question) {
        return send(repo,conversation,question,null);
    }
    public List<ChatMessageView> send(UUID repo,UUID conversation,String question,String commitSha) {
        access.require(repo);
        if(question==null || question.isBlank() || question.length()>4000)throw Problem.bad("Question must contain 1 to 4000 characters");
        if(commitSha!=null && !commitSha.matches("[a-fA-F0-9]{40}"))throw Problem.bad("Expected full 40-character commit SHA");
        Conversation c=conversations.findByIdAndRepositoryId(conversation,repo).orElseThrow(Problem::notFound);
        long expected=c.version;
        var history=new ArrayList<>(messages.findTop12ByConversationIdOrderByCreatedAtDesc(c.id));
        Collections.reverse(history);
        RepositoryContext usedContext=commitSha==null ? null : repositoryContext.build(repo,commitSha,question);
        String evidence=usedContext==null ? "" : usedContext.text();
        List<RepositorySourceReference> sources=evidence.isEmpty() ? List.of() : usedContext.sources();
        if(evidence.isEmpty())evidence=(commitSha==null ? "" : "No indexed source context was found for the requested commit. Stored repository background follows; it is not evidence of that commit.\n")+context.build(repo);
        String answer=ai.answer(evidence,history.stream().map(m->new AIProvider.Turn(m.role,m.content)).toList(),question);
        return tx.execute(s-> {
            Conversation locked=conversations.lock(conversation).orElseThrow(Problem::notFound);if(locked.version!=expected)throw new Problem(org.springframework.http.HttpStatus.CONFLICT,"Conversation changed; retry after refreshing");locked.updatedAt=java.time.Instant.now(); ChatMessage user=new ChatMessage();user.conversationId=c.id;user.role="user";user.content=question;ChatMessage assistant=new ChatMessage();assistant.conversationId=c.id;assistant.role="assistant";assistant.content=ContextService.limit(answer,40000);assistant.sources=sources;assistant.createdAt=user.createdAt.plusNanos(1000000);messages.saveAll(List.of(user,assistant));return List.of(ChatMessageView.of(user),ChatMessageView.of(assistant));
        }
        );
    }
}
