package com.devpilot.documentation;
import com.devpilot.common.*;
import com.devpilot.repository.*;
import java.util.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
@RestController @RequestMapping("/api/v1/repositories/{repo}/documents") public class DocumentController {
    private final RepositoryAccess access;
    private final DocumentStore docs;
    private final DocumentService service;
    public DocumentController(RepositoryAccess a,DocumentStore d,DocumentService s) {
        access=a;
        docs=d;
        service=s;
    }
    public record Generate(@NotNull @Pattern(regexp="PROJECT_OVERVIEW|ARCHITECTURE|API_DOCUMENTATION|AUTHENTICATION_FLOW|DATABASE_DESIGN|MODULE_DOCUMENTATION|SETUP_GUIDE|README")String kind) {
    }
    @GetMapping Page<GeneratedDocumentView> list(@PathVariable UUID repo,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {
        access.require(repo);
        return docs.findByRepositoryId(repo,Pages.of(page,size)).map(GeneratedDocumentView::of);
    }
    @GetMapping("/{id}") GeneratedDocumentView get(@PathVariable UUID repo,@PathVariable UUID id) {
        access.require(repo);
        return GeneratedDocumentView.of(docs.findByIdAndRepositoryId(id,repo).orElseThrow(Problem::notFound));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) GeneratedDocumentView generate(@PathVariable UUID repo,@Valid @RequestBody Generate r) {
        access.require(repo);
        return service.generate(repo,r.kind());
    }
}
