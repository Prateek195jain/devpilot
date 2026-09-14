package com.devpilot.repository;
import com.devpilot.common.*;
import com.devpilot.github.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class RepositoryService {
    private final RepositoryStore store;
    private final RepositoryAccess access;
    private final IntegrationService integration;
    private final SourceControlProvider provider;
    public RepositoryService(RepositoryStore s,RepositoryAccess a,IntegrationService i,SourceControlProvider p) {
        store=s;
        access=a;
        integration=i;
        provider=p;
    }
    @Transactional public TrackedRepositoryView importRepo(UUID user,String name) {
        String token=integration.token(user);
        SourceControlProvider.Repo data=provider.repository(token,name);
        TrackedRepository r=new TrackedRepository();
        r.ownerId=user;
        update(r,data);
        r.readme=provider.readme(token,r.fullName);
        return TrackedRepositoryView.of(store.save(r));
    }
    @Transactional public TrackedRepositoryView sync(UUID id) {
        TrackedRepository r=access.require(id);
        String token=integration.token(r.ownerId);
        update(r,provider.repository(token,r.fullName));
        r.readme=provider.readme(token,r.fullName);
        return TrackedRepositoryView.of(store.save(r));
    }
    private void update(TrackedRepository r,SourceControlProvider.Repo d) {
        r.githubId=d.id();
        r.fullName=d.fullName();
        r.description=d.description();
        r.defaultBranch=d.defaultBranch();
        r.language=d.language();
        r.syncedAt=Instant.now();
    }
}
