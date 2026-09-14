package com.devpilot.conversation;
import com.devpilot.common.*;
import com.devpilot.ai.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service public class ConversationService {
    private final ConversationStore conversations;
    private final MessageStore messages;
    private final AIProvider ai;
    private final ContextService context;
    private final TransactionTemplate tx;
    public ConversationService(ConversationStore c,MessageStore m,AIProvider a,ContextService x,TransactionTemplate t) {
        conversations=c;
        messages=m;
        ai=a;
        context=x;
        tx=t;
    }
    public List<ChatMessageView> send(UUID repo,UUID conversation,String question) {
        Conversation c=conversations.findByIdAndRepositoryId(conversation,repo).orElseThrow(Problem::notFound);
        long expected=c.version;
        var history=new ArrayList<>(messages.findTop12ByConversationIdOrderByCreatedAtDesc(c.id));
        Collections.reverse(history);
        String answer=ai.answer(context.build(repo),history.stream().map(m->new AIProvider.Turn(m.role,m.content)).toList(),question);
        return tx.execute(s-> {
            Conversation locked=conversations.lock(conversation).orElseThrow(Problem::notFound);if(locked.version!=expected)throw new Problem(org.springframework.http.HttpStatus.CONFLICT,"Conversation changed; retry after refreshing");locked.updatedAt=java.time.Instant.now(); ChatMessage user=new ChatMessage();user.conversationId=c.id;user.role="user";user.content=question;ChatMessage assistant=new ChatMessage();assistant.conversationId=c.id;assistant.role="assistant";assistant.content=ContextService.limit(answer,40000);assistant.createdAt=user.createdAt.plusNanos(1000000);messages.saveAll(List.of(user,assistant));return List.of(ChatMessageView.of(user),ChatMessageView.of(assistant));
        }
        );
    }
}
