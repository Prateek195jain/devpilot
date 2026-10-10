package com.devpilot.repository;

import com.devpilot.common.Problem;
import com.devpilot.github.IntegrationService;
import com.devpilot.github.SourceControlProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RepositorySourceAcquisitionService {
    private final RepositoryAccess access;
    private final IntegrationService integration;
    private final SourceControlProvider provider;
    private final SourceFilePolicy policy;

    public RepositorySourceAcquisitionService(RepositoryAccess access, IntegrationService integration, SourceControlProvider provider, SourceFilePolicy policy) {
        this.access = access;
        this.integration = integration;
        this.provider = provider;
        this.policy = policy;
    }

    /** Uses the authenticated user's repository ownership check before reading credentials. */
    public RepositorySources acquire(UUID repositoryId, String commitSha) {
        if (commitSha == null || !commitSha.matches("[a-fA-F0-9]{40}")) throw Problem.bad("Expected full 40-character commit SHA");
        String commit = commitSha.toLowerCase(Locale.ROOT);
        TrackedRepository repository = access.require(repositoryId);
        String token = integration.token(repository.ownerId);
        String root = provider.commitTree(token, repository.fullName, commit);
        record Pending(String prefix, String sha) {}
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending("", root));
        SortedMap<String, SourceControlProvider.TreeEntry> candidates = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Pending current = pending.removeFirst();
            for (var entry : provider.tree(token, repository.fullName, current.sha())) {
                String path;
                try { path = policy.normalize(current.prefix() + entry.path()); }
                catch (IllegalArgumentException e) { throw Problem.upstream("GitHub invalid tree path"); }
                if (!seen.add(path)) throw Problem.upstream("GitHub duplicate tree path");
                if (entry.type().equals("tree") && policy.directoryAllowed(path)) {
                    pending.addLast(new Pending(path + "/", entry.sha()));
                } else if (entry.type().equals("blob") && Set.of("100644", "100755").contains(entry.mode()) && policy.eligible(path, entry.size())) {
                    candidates.put(path, entry);
                }
            }
        }
        List<RepositorySources.File> files = new ArrayList<>();
        candidates.forEach((path, entry) -> {
            byte[] bytes = provider.blob(token, repository.fullName, entry.sha());
            if (!policy.sizeAllowed(bytes.length)) return;
            if (bytes.length != entry.size()) throw Problem.upstream("GitHub source size mismatch");
            policy.decode(bytes).ifPresent(content -> files.add(new RepositorySources.File(path, content, bytes.length, hash(bytes))));
        });
        return new RepositorySources(repositoryId, commit, files);
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
