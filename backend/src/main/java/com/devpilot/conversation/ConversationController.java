package com.devpilot.conversation;
import com.devpilot.common.*;
import com.devpilot.repository.*;
import java.util.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/v1/repositories/{repo}/conversations") public class ConversationController {
    private final RepositoryAccess access;
    private final ConversationStore conversations;
    private final MessageStore messages;
    private final ConversationService service;
    public ConversationController(RepositoryAccess a,ConversationStore c,MessageStore m,ConversationService s) {
        access=a;
        conversations=c;
        messages=m;
        service=s;
    }
    public record NewConversation(@NotBlank @Size(max=200)String title) {
    }
    public record Send(@NotBlank @Size(max=4000)String content) {
    }
    @GetMapping Page<ConversationView> list(@PathVariable UUID repo,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {
        access.require(repo);
        return conversations.findByRepositoryId(repo,Pages.of(page,size)).map(ConversationView::of);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) ConversationView create(@PathVariable UUID repo,@Valid @RequestBody NewConversation r) {
        access.require(repo);
        Conversation c=new Conversation();
        c.repositoryId=repo;
        c.title=r.title();
        return ConversationView.of(conversations.save(c));
    }
    @GetMapping("/{id}/messages") Page<ChatMessageView> messages(@PathVariable UUID repo,@PathVariable UUID id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {
        access.require(repo);
        conversations.findByIdAndRepositoryId(id,repo).orElseThrow(Problem::notFound);
        return messages.findByConversationId(id,Pages.of(page,size)).map(ChatMessageView::of);
    }
    @PostMapping("/{id}/messages") List<ChatMessageView> send(@PathVariable UUID repo,@PathVariable UUID id,@Valid @RequestBody Send r) {
        access.require(repo);
        return service.send(repo,id,r.content());
    }
}
