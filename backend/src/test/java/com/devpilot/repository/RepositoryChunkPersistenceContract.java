package com.devpilot.repository;

import com.devpilot.user.UserAccount;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.NestedExceptionUtils;
import static org.junit.jupiter.api.Assertions.*;

@Transactional
abstract class RepositoryChunkPersistenceContract {
    @Autowired RepositoryChunkStore chunks;
    @Autowired EntityManager em;
    UUID repositoryId;
    static final String COMMIT = "a".repeat(40);

    @BeforeEach
    void repository() {
        UserAccount user = new UserAccount();
        user.email = UUID.randomUUID() + "@example.com";
        user.name = "Chunk persistence test";
        user.passwordHash = "synthetic-password-hash";
        user.role = "USER";
        em.persist(user);
        TrackedRepository repository = new TrackedRepository();
        repository.ownerId = user.id;
        repository.githubId = 42L;
        repository.fullName = "test/service";
        repository.defaultBranch = "main";
        em.persist(repository);
        em.flush();
        repositoryId = repository.id;
    }

    RepositoryChunk chunk(String path, int index) throws Exception {
        RepositoryChunk chunk = new RepositoryChunk();
        chunk.repositoryId = repositoryId;
        chunk.commitSha = COMMIT;
        chunk.sourcePath = path;
        chunk.chunkIndex = index;
        chunk.content = "A Java service validates login credentials.\n// Unicode: café";
        chunk.contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(chunk.content.getBytes(StandardCharsets.UTF_8)));
        chunk.embeddingModel = "gemini-embedding-2";
        chunk.embedding = new float[RepositoryChunk.DIMENSIONS];
        for (int i = 0; i < chunk.embedding.length; i++) chunk.embedding[i] = (i - 384) / 1024.0f;
        return chunk;
    }

    @Test
    void roundTripsContentMetadataAnd768FiniteValues() throws Exception {
        RepositoryChunk original = chunk("src/Auth.java", 0);
        UUID id = chunks.saveAndFlush(original).id;
        em.clear();
        RepositoryChunk loaded = chunks.findById(id).orElseThrow();
        assertEquals(repositoryId, loaded.repositoryId);
        assertEquals(COMMIT, loaded.commitSha);
        assertEquals(original.sourcePath, loaded.sourcePath);
        assertEquals(0, loaded.chunkIndex);
        assertEquals(original.content, loaded.content);
        assertEquals(original.contentHash, loaded.contentHash);
        assertEquals(original.embeddingModel, loaded.embeddingModel);
        assertNotNull(loaded.createdAt);
        // PostgreSQL/H2 timestamps have microsecond precision and may round nanoseconds.
        assertTrue(Math.abs(java.time.Duration.between(original.createdAt, loaded.createdAt).toNanos()) < 1000);
        assertArrayEquals(original.embedding, loaded.embedding);
        assertEquals(768, loaded.embedding.length);
        for (float value : loaded.embedding) assertTrue(Float.isFinite(value));
    }

    @Test
    void listsOnlyRequestedRepositoryRevisionInFileAndChunkOrder() throws Exception {
        chunks.save(chunk("src/B.java", 0));
        chunks.save(chunk("src/A.java", 1));
        chunks.save(chunk("src/A.java", 0));
        RepositoryChunk otherRevision = chunk("src/A.java", 0);
        otherRevision.commitSha = "b".repeat(40);
        chunks.save(otherRevision);
        UUID firstRepository = repositoryId;
        repository();
        chunks.saveAndFlush(chunk("src/A.java", 0));
        em.clear();
        var page = chunks.findByRepositoryIdAndCommitShaOrderBySourcePathAscChunkIndexAsc(
                firstRepository, COMMIT, PageRequest.of(0, 2));
        assertEquals(3, page.getTotalElements());
        assertEquals(List.of("src/A.java:0", "src/A.java:1"), page.stream()
                .map(c -> c.sourcePath + ":" + c.chunkIndex).toList());
        var next = chunks.findByRepositoryIdAndCommitShaOrderBySourcePathAscChunkIndexAsc(
                firstRepository, COMMIT, PageRequest.of(1, 2));
        assertEquals("src/B.java", next.getContent().getFirst().sourcePath);
    }

    @Test
    void rejectsDuplicateChunkPosition() throws Exception {
        chunks.saveAndFlush(chunk("src/Auth.java", 0));
        RepositoryChunk duplicate = chunk("src/Auth.java", 0);
        assertThrows(DataIntegrityViolationException.class, () -> chunks.saveAndFlush(duplicate));
    }

    @ParameterizedTest @ValueSource(ints={0, 767, 769})
    void rejectsWrongDimensions(int dimensions) throws Exception {
        RepositoryChunk invalid = chunk("src/Auth.java", 0);
        invalid.embedding = new float[dimensions];
        var failure = assertThrows(RuntimeException.class, () -> chunks.saveAndFlush(invalid));
        assertInstanceOf(IllegalArgumentException.class, NestedExceptionUtils.getMostSpecificCause(failure));
    }

    @ParameterizedTest @ValueSource(floats={Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void rejectsNonfiniteValues(float value) throws Exception {
        RepositoryChunk invalid = chunk("src/Auth.java", 0);
        invalid.embedding[0] = value;
        var failure = assertThrows(RuntimeException.class, () -> chunks.saveAndFlush(invalid));
        assertInstanceOf(IllegalArgumentException.class, NestedExceptionUtils.getMostSpecificCause(failure));
    }

    @Test
    void validatesUpdatesAsWellAsInserts() throws Exception {
        UUID id = chunks.saveAndFlush(chunk("src/Auth.java", 0)).id;
        em.clear();
        RepositoryChunk loaded = chunks.findById(id).orElseThrow();
        loaded.chunkIndex = -1;
        var failure = assertThrows(RuntimeException.class, () -> em.flush());
        assertInstanceOf(IllegalArgumentException.class, NestedExceptionUtils.getMostSpecificCause(failure));
    }
}
