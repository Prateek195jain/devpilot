package com.devpilot.memory;
import com.devpilot.repository.*;
import com.devpilot.common.*;
import java.util.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
@RestController @RequestMapping("/api/v1/repositories/{repo}/memories") public class MemoryController {
    private final RepositoryAccess access;
    private final MemoryStore store;
    public MemoryController(RepositoryAccess a,MemoryStore s) {
        access=a;
        store=s;
    }
    public record State(@NotNull @Pattern(regexp="ACTIVE|ARCHIVED")String status) {
    }
    @GetMapping Page<EngineeringMemoryView> list(@PathVariable UUID repo,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size,
            @RequestParam(required=false)String status) {
        access.require(repo);
        if(status!=null&&!Set.of("ACTIVE","ARCHIVED").contains(status))throw Problem.bad("Invalid memory status");
        var pageable=Pages.of(page,size);
        return (status==null ? store.findByRepositoryId(repo,pageable)
                : store.findByRepositoryIdAndStatus(repo,status,pageable)).map(EngineeringMemoryView::of);
    }
    @PatchMapping("/{id}") @Transactional EngineeringMemoryView update(@PathVariable UUID repo,@PathVariable UUID id,@Valid @RequestBody State s) {
        access.require(repo);
        EngineeringMemory m=store.findByIdAndRepositoryId(id,repo).orElseThrow(Problem::notFound);
        m.status=s.status();
        return EngineeringMemoryView.of(m);
    }
}
