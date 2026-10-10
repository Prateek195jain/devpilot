package com.devpilot.repository;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql=false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker=true)
class RepositoryChunkPostgresTest extends RepositoryChunkPersistenceContract {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void cosineSearchScopesRanksAndBreaksTiesDeterministically() throws Exception {
        UUID firstRepository = repositoryId;
        UUID owner = em.find(TrackedRepository.class, firstRepository).ownerId;
        saveVector("z.java", 0, COMMIT, "gemini-embedding-2", 1, 0);
        saveVector("a.java", 1, COMMIT, "gemini-embedding-2", 1, 1);
        saveVector("a.java", 0, COMMIT, "gemini-embedding-2", 1, 1);
        saveVector("b.java", 0, COMMIT, "gemini-embedding-2", 1, 1);
        saveVector("opposite.java", 0, COMMIT, "gemini-embedding-2", -1, 0);
        saveVector("zero.java", 0, COMMIT, "gemini-embedding-2", 0, 0);
        saveVector("other-model.java", 0, COMMIT, "other-model", 1, 0);
        saveVector("other-commit.java", 0, "b".repeat(40), "gemini-embedding-2", 1, 0);
        repository();
        em.find(TrackedRepository.class, repositoryId).githubId = 43L;
        saveVector("other-repository.java", 0, COMMIT, "gemini-embedding-2", 1, 0);
        em.flush();
        em.clear();
        float[] query = new float[768];
        query[0] = 1;
        var matches = chunks.search(firstRepository, owner, COMMIT, "gemini-embedding-2", query, 3);
        assertEquals(List.of("z.java:0", "a.java:0", "a.java:1"), matches.stream()
                .map(match -> match.sourcePath() + ":" + match.chunkIndex()).toList());
        assertEquals(0, matches.getFirst().distance(), 1e-6);
        assertEquals(1 - 1 / Math.sqrt(2), matches.get(1).distance(), 1e-6);
        assertTrue(matches.stream().allMatch(match -> match.repositoryId().equals(firstRepository)
                && match.commitSha().equals(COMMIT) && !match.content().isBlank()));
        var all = chunks.search(firstRepository, owner, COMMIT, "gemini-embedding-2", query, 50);
        assertEquals(5, all.size());
        assertEquals(2, all.getLast().distance(), 1e-6);
        assertEquals(List.of("other-model.java"), chunks.search(firstRepository, owner, COMMIT,
                "other-model", query, 50).stream().map(RepositoryChunkMatch::sourcePath).toList());
        assertEquals(List.of("other-commit.java"), chunks.search(firstRepository, owner, "b".repeat(40),
                "gemini-embedding-2", query, 50).stream().map(RepositoryChunkMatch::sourcePath).toList());
        UUID secondOwner = em.find(TrackedRepository.class, repositoryId).ownerId;
        assertEquals(List.of("other-repository.java"), chunks.search(repositoryId, secondOwner, COMMIT,
                "gemini-embedding-2", query, 50).stream().map(RepositoryChunkMatch::sourcePath).toList());
        assertTrue(chunks.search(firstRepository, owner, "c".repeat(40), "gemini-embedding-2", query, 50).isEmpty());
        assertTrue(chunks.search(firstRepository, owner, COMMIT, "missing-model", query, 50).isEmpty());
        assertTrue(chunks.search(firstRepository, secondOwner, COMMIT, "gemini-embedding-2", query, 50).isEmpty());
        jdbc.update("update repositories set owner_id = ? where id = ?", secondOwner, firstRepository);
        assertTrue(chunks.search(firstRepository, owner, COMMIT, "gemini-embedding-2", query, 50).isEmpty());
    }

    private void saveVector(String path, int index, String commit, String model, float first, float second) throws Exception {
        RepositoryChunk row = chunk(path, index);
        row.commitSha = commit;
        row.embeddingModel = model;
        row.embedding = new float[768];
        row.embedding[0] = first;
        row.embedding[1] = second;
        chunks.saveAndFlush(row);
    }

    @ParameterizedTest
    @ValueSource(strings={"embedding = '[1,2]'::vector", "embedding = '[NaN]'::vector",
            "embedding = '[Infinity]'::vector", "embedding = null", "chunk_index = -1",
            "repository_id = '00000000-0000-0000-0000-000000000000'::uuid",
            "content_hash = 'invalid'", "commit_sha = 'main'", "content = ''", "source_path = ''"})
    void databaseRejectsInvalidDataWithoutEntityValidation(String assignment) throws Exception {
        var id = chunks.saveAndFlush(chunk("src/Auth.java", 0)).id;
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update repository_chunks set " + assignment + " where id = ?", id));
    }
}
