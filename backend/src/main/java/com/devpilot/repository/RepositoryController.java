package com.devpilot.repository;
import com.devpilot.common.*;
import com.devpilot.github.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/v1/repositories") public class RepositoryController {
    private final RepositoryStore store;
    private final RepositoryAccess access;
    private final RepositoryService service;
    private final IntegrationService integration;
    private final SourceControlProvider provider;
    public RepositoryController(RepositoryStore s,RepositoryAccess a,RepositoryService v,IntegrationService i,SourceControlProvider p) {
        store=s;
        access=a;
        service=v;
        integration=i;
        provider=p;
    }
    public record Import(@NotBlank @Pattern(regexp="[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") @Size(max=200)String fullName) {
    }
    @GetMapping Page<TrackedRepositoryView> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {
        return store.findByOwnerId(CurrentUser.id(),Pages.of(page,size)).map(TrackedRepositoryView::of);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) TrackedRepositoryView create(@Valid @RequestBody Import r) {
        return service.importRepo(CurrentUser.id(),r.fullName());
    }
    @GetMapping("/{id}") TrackedRepositoryView get(@PathVariable UUID id) {
        return TrackedRepositoryView.of(access.require(id));
    }
    @PostMapping("/{id}/sync") TrackedRepositoryView sync(@PathVariable UUID id) {
        return service.sync(id);
    }
    @GetMapping("/{id}/changes") List<SourceControlProvider.Change> changes(@PathVariable UUID id,@RequestParam(defaultValue="PR")String kind,@RequestParam(defaultValue="1")int page) {
        if(!Set.of("PR","COMMIT").contains(kind)||page<1||page>1000)throw Problem.bad("Invalid kind or page");
        TrackedRepository r=access.require(id);
        return provider.changes(integration.token(r.ownerId),r.fullName,kind,page);
    }
    @GetMapping("/{id}/branches") List<String> branches(@PathVariable UUID id,@RequestParam(defaultValue="1")int page) {
        if(page<1||page>1000)throw Problem.bad("Invalid page");
        TrackedRepository r=access.require(id);
        return provider.branches(integration.token(r.ownerId),r.fullName,page);
    }
    @GetMapping("/{id}/diff") SourceControlProvider.Snapshot diff(@PathVariable UUID id,@RequestParam String kind,@RequestParam String ref) {
        if(!Set.of("PR","COMMIT").contains(kind))throw Problem.bad("Invalid kind");
        TrackedRepository r=access.require(id);
        return provider.snapshot(integration.token(r.ownerId),r.fullName,kind,ref);
    }
}
