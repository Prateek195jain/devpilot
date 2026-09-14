package com.devpilot.repository;
import com.devpilot.common.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
@Service public class RepositoryAccess {
    private final RepositoryStore store;
    public RepositoryAccess(RepositoryStore s) {
        store=s;
    }
    public TrackedRepository require(UUID id,UUID owner) {
        return store.findByIdAndOwnerId(id,owner).orElseThrow(Problem::notFound);
    }
    public TrackedRepository require(UUID id) {
        return require(id,CurrentUser.id());
    }
}
