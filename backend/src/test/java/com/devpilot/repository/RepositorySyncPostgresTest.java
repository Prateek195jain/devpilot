package com.devpilot.repository;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Runs the complete sync/index transaction contract against PostgreSQL + pgvector. */
@Testcontainers(disabledWithoutDocker = true)
class RepositorySyncPostgresTest extends RepositoryIndexingServiceTest {
    @org.junit.jupiter.api.Test void candidateIsNeverExposedToExplicitTargetRetrievalAndSuccessIsComplete() {
        ready(); snapshot(B,java.util.Map.of("one.java","one\n","a.java","new\n","z.java","last\n")); target(B);
        org.mockito.Mockito.when(embeddings.embedDocument(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(embedding()).thenThrow(new com.devpilot.ai.EmbeddingFailure(com.devpilot.ai.EmbeddingFailure.Kind.RATE_LIMIT));
        sync.sync(repositoryId); org.junit.jupiter.api.Assertions.assertThrows(com.devpilot.ai.EmbeddingFailure.class,this::run);
        float[] vector=new float[768]; java.util.Arrays.fill(vector,0.25f);
        org.junit.jupiter.api.Assertions.assertTrue(chunks.search(repositoryId,owner,B,"test-model",vector,50).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1,chunks.search(repositoryId,owner,A,"test-model",vector,50).size());
        org.mockito.Mockito.doReturn(embedding()).when(embeddings).embedDocument(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        due(); run();
        var result=chunks.search(repositoryId,owner,B,"test-model",vector,50);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("one.java","a.java","z.java"),result.stream().map(RepositoryChunkMatch::sourcePath).collect(java.util.stream.Collectors.toSet()));
        org.junit.jupiter.api.Assertions.assertTrue(result.stream().allMatch(row -> B.equals(row.commitSha())));
        org.junit.jupiter.api.Assertions.assertEquals(1,chunks.search(repositoryId,owner,A,"test-model",vector,50).size());
    }
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}
