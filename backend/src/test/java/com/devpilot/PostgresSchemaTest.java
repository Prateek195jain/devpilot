package com.devpilot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.devpilot.repository.*;
import com.devpilot.user.UserAccount;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true) class PostgresSchemaTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.flyway.enabled",()->true);
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.database-platform",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @Test void flywaySchemaMatchesJpaMappings() {
    }

    @Autowired EntityManager entities;

    @Test @Transactional void migrationRecoversExistingRateLimitedJobsWithoutTouchingPublishedPointer() throws Exception {
        var owner=new UserAccount(); owner.email=UUID.randomUUID()+"@example.com"; owner.name="Retry migration";
        owner.passwordHash="synthetic"; owner.role="USER"; entities.persist(owner);
        var waiting=repository(owner.id,10L); waiting.indexingStatus=IndexingStatus.FAILED;
        waiting.lastIndexingErrorCategory=IndexingErrorCategory.RATE_LIMIT;
        waiting.indexingTargetCommitSha="b".repeat(40); waiting.latestIndexedCommitSha="a".repeat(40);
        var terminal=repository(owner.id,11L); terminal.indexingStatus=IndexingStatus.FAILED;
        terminal.lastIndexingErrorCategory=IndexingErrorCategory.AUTHENTICATION;
        terminal.indexingTargetCommitSha="b".repeat(40); entities.flush();
        String migration;
        try(var input=getClass().getResourceAsStream("/db/migration/V6__repository_index_retry.sql")) {
            migration=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        entities.createNativeQuery(migration.substring(migration.indexOf("update repositories"))).executeUpdate(); entities.clear();
        var recovered=entities.find(TrackedRepository.class,waiting.id);
        assertEquals(IndexingStatus.WAITING_RETRY,recovered.indexingStatus);
        assertNotNull(recovered.nextIndexingAttemptAt); assertEquals(1,recovered.indexingRetryCount);
        assertEquals("a".repeat(40),recovered.latestIndexedCommitSha);
        assertEquals("b".repeat(40),recovered.indexingTargetCommitSha);
        assertEquals(IndexingStatus.FAILED,entities.find(TrackedRepository.class,terminal.id).indexingStatus);
    }

    @Test @Transactional void historicalIndexesBackfillPerRepositoryWithDeterministicTies() throws Exception {
        var owner=new UserAccount();
        owner.email=UUID.randomUUID()+"@example.com"; owner.name="Migration test";
        owner.passwordHash="synthetic"; owner.role="USER";
        entities.persist(owner);
        var first=repository(owner.id,1L);
        var second=repository(owner.id,2L);
        var empty=repository(owner.id,3L);
        String a="a".repeat(40), b="b".repeat(40);
        chunk(first.id,b,"2026-01-02T00:00:00Z");
        chunk(first.id,a,"2026-01-02T00:00:00Z");
        chunk(second.id,a,"2026-01-01T00:00:00Z");
        chunk(second.id,b,"2026-01-03T00:00:00Z");
        entities.flush();
        String migration;
        try(var input=getClass().getResourceAsStream("/db/migration/V4__repository_indexed_commit.sql")) {
            migration=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        entities.createNativeQuery(migration.substring(migration.indexOf("UPDATE repositories"))).executeUpdate();
        entities.clear();
        assertEquals(a,entities.find(TrackedRepository.class,first.id).latestIndexedCommitSha);
        assertEquals(b,entities.find(TrackedRepository.class,second.id).latestIndexedCommitSha);
        assertNull(entities.find(TrackedRepository.class,empty.id).latestIndexedCommitSha);
    }

    private TrackedRepository repository(UUID owner, long githubId) {
        var repository=new TrackedRepository();
        repository.ownerId=owner; repository.githubId=githubId;
        repository.fullName="test/"+githubId; repository.defaultBranch="main";
        entities.persist(repository);
        return repository;
    }

    private void chunk(UUID repositoryId,String commit,String createdAt) {
        var chunk=new RepositoryChunk();
        chunk.repositoryId=repositoryId; chunk.commitSha=commit; chunk.sourcePath="A.java";
        chunk.content="class A {}"; chunk.contentHash="a".repeat(64);
        chunk.embeddingModel="test-model"; chunk.embedding=new float[RepositoryChunk.DIMENSIONS];
        chunk.createdAt=Instant.parse(createdAt);
        entities.persist(chunk);
    }
}
