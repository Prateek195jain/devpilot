package com.devpilot.repository;

import com.devpilot.ai.GoogleEmbeddingProvider;
import com.devpilot.ai.EmbeddingFailure;
import com.devpilot.common.Crypto;
import com.devpilot.github.*;
import java.util.*;
import java.io.PrintStream;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named = "RUN_REPOSITORY_INDEXING_SMOKE", matches = "true")
class RepositoryIndexingSmokeIT {
    @Test
    void realGithubRevisionIsEmbeddedAndPersisted() {
        var previousContext = SecurityContextHolder.getContext();
        String stage = "configuration";
        String shortSha = "unavailable";
        int files = 0;
        int chunks = 0;
        try {
            UUID owner = UUID.fromString(required("REPOSITORY_INDEXING_USER_ID"));
            UUID repository = UUID.fromString(required("REPOSITORY_INDEXING_REPOSITORY_ID"));
            String commit = required("REPOSITORY_INDEXING_COMMIT_SHA").toLowerCase(Locale.ROOT);
            require(commit.matches("[a-f0-9]{40}"));
            shortSha = commit.substring(0, 12);
            require(required("DATABASE_URL").startsWith("jdbc:postgresql:"));
            required("DATABASE_USER");
            required("DATABASE_PASSWORD");
            required("ENCRYPTION_KEY");
            require("google".equals(required("EMBEDDING_MODE")));
            required("EMBEDDING_API_KEY");
            var authentication = SecurityContextHolder.createEmptyContext();
            authentication.setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
            SecurityContextHolder.setContext(authentication);

            stage = "startup";
            try (var application = new SpringApplicationBuilder(SmokeConfiguration.class)
                    .web(WebApplicationType.NONE).logStartupInfo(false).registerShutdownHook(false)
                    .run("--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--spring.profiles.active=repository-indexing-smoke",
                            "--spring.datasource.url=${DATABASE_URL}",
                            "--spring.datasource.username=${DATABASE_USER}",
                            "--spring.datasource.password=${DATABASE_PASSWORD}",
                            "--app.encryption-key=${ENCRYPTION_KEY}",
                            "--app.embedding.mode=google", "--app.embedding.api-key=${EMBEDDING_API_KEY}",
                            "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.show-sql=false",
                            "--spring.jpa.properties.hibernate.hbm2ddl.auto=none",
                            "--spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
                            "--spring.flyway.enabled=false", "--spring.sql.init.mode=never")) {
                stage = "indexing";
                var result = application.getBean(RepositoryIndexingService.class).index(repository, commit);
                files = result.filesProcessed();
                chunks = result.chunksIndexed();
                stage = "persisted row validation";
                require(repository.equals(result.repositoryId()) && commit.equals(result.commitSha())
                        && files > 0 && chunks > 0);
                long[] counts = application.getBean(JdbcTemplate.class).queryForObject("""
                        select count(*) as total,
                               count(distinct (repository_id, commit_sha, source_path, chunk_index)) as positions,
                               count(*) filter (where
                                   coalesce(source_path ~ '[^[:space:]]', false)
                                   and coalesce(chunk_index >= 0, false)
                                   and coalesce(content ~ '[^[:space:]]', false)
                                   and coalesce(content_hash ~ '^[0-9a-f]{64}$', false)
                                   and coalesce(embedding_model ~ '[^[:space:]]', false)
                                   and coalesce(vector_dims(embedding) = 768, false)) as valid
                        from repository_chunks where repository_id = ? and commit_sha = ?
                        """, (row, rowNumber) -> new long[] {
                            row.getLong("total"), row.getLong("positions"), row.getLong("valid")
                        }, repository, commit);
                require(counts != null && counts[0] == chunks && counts[1] == chunks && counts[2] == chunks);
                stage = "shutdown";
            }
        } catch (RuntimeException failure) {
            System.out.printf("success=false commit=%s files=%d chunks=%d%n", shortSha, files, chunks);
            throw new SafeSmokeFailure(stage, failure);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
        System.out.printf("success=true commit=%s files=%d chunks=%d%n", shortSha, files, chunks);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException();
    }

    static final class SafeSmokeFailure extends AssertionError {
        SafeSmokeFailure(String stage, RuntimeException original) {
            super("Repository indexing smoke unsuccessful during " + stage + ": "
                    + original.getClass().getName() + ": " + safeMessage(original), original);
        }

        @Override public void printStackTrace() { printStackTrace(System.err); }
        @Override public void printStackTrace(PrintStream stream) { safeTrace().printStackTrace(stream); }
        @Override public void printStackTrace(PrintWriter writer) { safeTrace().printStackTrace(writer); }

        private Throwable safeTrace() {
            AssertionError safe = new AssertionError(getMessage());
            safe.setStackTrace(getStackTrace());
            safe.initCause(safeCopy(getCause(), Collections.newSetFromMap(new IdentityHashMap<>())));
            return safe;
        }

        private static Throwable safeCopy(Throwable original, Set<Throwable> seen) {
            if (original == null || !seen.add(original)) return null;
            RuntimeException safe = new RuntimeException(original.getClass().getName() + ": " + safeMessage(original));
            safe.setStackTrace(original.getStackTrace());
            safe.initCause(safeCopy(original.getCause(), seen));
            for (Throwable suppressed : original.getSuppressed()) {
                Throwable copy = safeCopy(suppressed, seen);
                if (copy != null) safe.addSuppressed(copy);
            }
            return safe;
        }
    }

    private static String safeMessage(Throwable exception) {
        if (exception instanceof EmbeddingFailure embedding)
            return "Embedding request failed: " + embedding.kind().name();
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "No exception message";
        for (String diagnostic : List.of(
                "Stored credential cannot be decrypted", "ENCRYPTION_KEY must be base64 of 32 random bytes",
                "Connect GitHub in Settings first", "Resource not found",
                "GitHub request failed; check integration settings", "GitHub commit mismatch",
                "GitHub incomplete tree", "GitHub invalid blob", "GitHub invalid tree path",
                "GitHub duplicate tree path", "GitHub source size mismatch",
                "Invalid repository chunk metadata or dimensions", "Repository chunk embedding must be finite",
                "No qualifying bean", "Unsatisfied dependency", "Error creating bean",
                "Could not resolve placeholder", "Not a managed type", "Connection refused",
                "connect timed out", "Connection timed out", "Read timed out",
                "PKIX path building failed", "unable to find valid certification path",
                "password authentication failed", "permission denied", "duplicate key value",
                "value too long", "could not execute statement", "does not exist")) {
            if (message.contains(diagnostic)) return diagnostic + " [additional details withheld]";
        }
        return "Message withheld (may contain credentials, configuration, source data, or embeddings)";
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = {RepositoryStore.class, IntegrationStore.class})
    @Import({RepositoryAccess.class, IntegrationService.class, Crypto.class, GitHubSourceControlAdapter.class,
            SourceFilePolicy.class, RepositorySourceAcquisitionService.class, RepositorySourceChunker.class,
            GoogleEmbeddingProvider.class, com.devpilot.ai.EmbeddingPacing.class, RepositoryIndexJobs.class, RepositoryIndexingService.class})
    static class SmokeConfiguration {
        @Bean
        PersistenceManagedTypes smokeEntities() {
            return PersistenceManagedTypes.of(TrackedRepository.class.getName(), RepositoryChunk.class.getName(),
                    GitHubIntegration.class.getName());
        }
    }
}
