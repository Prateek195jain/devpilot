package com.devpilot.repository;

import com.devpilot.common.Problem;
import com.devpilot.github.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositorySourceAcquisitionServiceTest {
    private static final String COMMIT = "a".repeat(40);
    private final RepositoryAccess access = mock(RepositoryAccess.class);
    private final IntegrationService integration = mock(IntegrationService.class);
    private final SourceControlProvider provider = mock(SourceControlProvider.class);
    private final UUID id = UUID.randomUUID();
    private final RepositorySourceAcquisitionService service = new RepositorySourceAcquisitionService(access, integration, provider, new SourceFilePolicy(10));

    private SourceControlProvider.TreeEntry entry(String path, String mode, String sha, long size) {
        return new SourceControlProvider.TreeEntry(path, mode.equals("040000") ? "tree" : "blob", mode, sha, size);
    }

    @Test void acquiresOnlyEligibleFilesInStableOrderPinnedToCommit() {
        TrackedRepository repo = new TrackedRepository();
        repo.ownerId = UUID.randomUUID(); repo.fullName = "owner/repo"; repo.defaultBranch = "moving-main";
        when(access.require(id)).thenReturn(repo);
        when(integration.token(repo.ownerId)).thenReturn("token");
        when(provider.commitTree("token", repo.fullName, COMMIT)).thenReturn("root");
        var entries = new ArrayList<>(List.of(entry("z.md", "100644", "z", 2), entry("src", "040000", "src", -1),
            entry("node_modules", "040000", "excluded", -1), entry("big.java", "100644", "big", 11),
            entry("image.png", "100644", "image", 2), entry("link.java", "120000", "link", 2),
            entry("binary.java", "100644", "binary", 1), entry("invalid.java", "100644", "invalid", 1),
            entry("actual-big.java", "100644", "actual-big", 2)));
        when(provider.tree("token", repo.fullName, "root")).thenAnswer(i -> List.copyOf(entries));
        when(provider.tree("token", repo.fullName, "src")).thenReturn(List.of(entry("./A.java", "100755", "a", 2)));
        when(provider.blob("token", repo.fullName, "a")).thenReturn("é".getBytes(StandardCharsets.UTF_8));
        when(provider.blob("token", repo.fullName, "z")).thenReturn("ok".getBytes(StandardCharsets.UTF_8));
        when(provider.blob("token", repo.fullName, "binary")).thenReturn(new byte[] {0});
        when(provider.blob("token", repo.fullName, "invalid")).thenReturn(new byte[] {(byte) 0xff});
        when(provider.blob("token", repo.fullName, "actual-big")).thenReturn(new byte[11]);
        RepositorySources first = service.acquire(id, COMMIT.toUpperCase(Locale.ROOT));
        Collections.reverse(entries);
        assertEquals(first, service.acquire(id, COMMIT));
        assertEquals(COMMIT, first.commitSha());
        assertEquals(List.of("src/A.java", "z.md"), first.files().stream().map(RepositorySources.File::path).toList());
        assertEquals("é", first.files().getFirst().content());
        assertEquals(2, first.files().getFirst().byteSize());
        assertEquals("2689367b205c16ce32ed4200942b8b8b1e262dfc70d9bc9fbc77c49699a4f1df", first.files().getLast().sha256());
        verify(provider, never()).tree("token", repo.fullName, "excluded");
        for (String sha : List.of("big", "image", "link")) verify(provider, never()).blob("token", repo.fullName, sha);
        verify(provider, times(2)).commitTree("token", repo.fullName, COMMIT);
        assertThrows(UnsupportedOperationException.class, () -> first.files().clear());
    }

    @Test void rejectsMovingRefsAndAbbreviationsBeforeNetworkAccess() {
        for (String ref : new String[] {"main", "abcdef0", "HEAD", null}) assertThrows(Problem.class, () -> service.acquire(id, ref));
        verifyNoInteractions(access, integration, provider);
    }

    @Test void unauthorizedRepositoryCannotReadCredentialsOrGithub() {
        when(access.require(id)).thenThrow(Problem.notFound());
        assertThrows(Problem.class, () -> service.acquire(id, COMMIT));
        verifyNoInteractions(integration, provider);
    }
}
