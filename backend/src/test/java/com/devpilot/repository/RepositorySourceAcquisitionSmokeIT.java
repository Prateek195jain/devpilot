package com.devpilot.repository;

import com.devpilot.common.Crypto;
import com.devpilot.github.*;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Manual live GitHub check. Maven's default test discovery excludes *IT classes.
 * Keep environment values in a local/unshared IntelliJ JUnit configuration.
 * Required: RUN_GITHUB_ACQUISITION_SMOKE=true, GITHUB_ACQUISITION_USER_ID,
 * GITHUB_ACQUISITION_REPOSITORY_ID, GITHUB_ACQUISITION_COMMIT_SHA,
 * DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD, ENCRYPTION_KEY.
 * Optional: SOURCE_ACQUISITION_MAX_FILE_BYTES (same value as the backend).
 * Uses the existing database integration token; no separate GitHub token is accepted.
 */
@EnabledIfEnvironmentVariable(named = "RUN_GITHUB_ACQUISITION_SMOKE", matches = "true")
class RepositorySourceAcquisitionSmokeIT {
    @Test
    void actualGithubAcquiresOwnedRepositoryAtImmutableCommit() {
        boolean success = false;
        String shortSha = "unavailable";
        int count = 0;
        RuntimeException failure = null;
        String stage = "configuration";
        var previousContext = SecurityContextHolder.getContext();
        try {
            UUID userId = UUID.fromString(required("GITHUB_ACQUISITION_USER_ID"));
            UUID repositoryId = UUID.fromString(required("GITHUB_ACQUISITION_REPOSITORY_ID"));
            String commit = required("GITHUB_ACQUISITION_COMMIT_SHA");
            if (!commit.matches("[a-fA-F0-9]{40}")) throw new IllegalArgumentException();
            commit = commit.toLowerCase(Locale.ROOT);
            shortSha = commit.substring(0, 12);
            // Require explicit existing-database configuration; never fall back to embedded H2.
            required("DATABASE_URL");
            required("DATABASE_USER");
            required("DATABASE_PASSWORD");
            required("ENCRYPTION_KEY");

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
            SecurityContextHolder.setContext(context);

            // Only import the real acquisition stack and JPA. No workers, AI providers,
            // controllers, migrations, SQL initialization, or full application startup.
            stage = "application startup";
            try (var application = new SpringApplicationBuilder(SmokeConfiguration.class)
                    .web(WebApplicationType.NONE).logStartupInfo(false).registerShutdownHook(false)
                    .run("--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--spring.profiles.active=github-acquisition-smoke",
                            "--spring.datasource.url=${DATABASE_URL}",
                            "--spring.datasource.username=${DATABASE_USER}",
                            "--spring.datasource.password=${DATABASE_PASSWORD}",
                            "--app.encryption-key=${ENCRYPTION_KEY}",
                            "--spring.datasource.hikari.read-only=true",
                            "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.show-sql=false",
                            "--spring.jpa.properties.hibernate.hbm2ddl.auto=none",
                            "--spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
                            "--spring.flyway.enabled=false", "--spring.sql.init.mode=never")) {
                stage = "source acquisition";
                var result = application.getBean(RepositorySourceAcquisitionService.class).acquire(repositoryId, commit);
                stage = "structural validation";
                var policy = application.getBean(SourceFilePolicy.class);
                count = result.files().size();
                boolean valid = commit.equals(result.commitSha()) && count > 0;
                String previous = null;
                for (var file : result.files()) {
                    valid &= file.path() != null && !file.path().isBlank()
                            && file.content() != null && policy.sizeAllowed(file.byteSize())
                            && file.sha256() != null && file.sha256().matches("[0-9a-f]{64}");
                    if (file.path() == null) { valid = false; break; }
                    valid &= previous == null || previous.compareTo(file.path()) < 0;
                    previous = file.path();
                }
                success = valid;
                stage = "application shutdown";
            }
        } catch (RuntimeException exception) {
            failure = exception;
            success = false;
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
        System.out.printf("success=%s commit=%s files=%d%n", success, shortSha, count);
        if (!success) {
            if (failure != null) throw new SafeSmokeFailure(stage, failure);
            throw new AssertionError("GitHub acquisition smoke structural validation unsuccessful (including non-empty result)");
        }
    }

    /**
     * Retain the original cause for local debugger inspection, but never render its raw
     * messages. Redacting only the assertion message would leave nested/suppressed
     * exception messages exposed in IntelliJ/JUnit stack-trace output.
     */
    static final class SafeSmokeFailure extends AssertionError {
        SafeSmokeFailure(String stage, RuntimeException original) {
            super("GitHub acquisition smoke unsuccessful during " + stage + ": "
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
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "No exception message";
        // Emit only fixed diagnostic phrases, never arbitrary values from an exception.
        // Even environment-value redaction alone cannot protect decrypted GitHub tokens
        // or source contents, neither of which is necessarily present in the environment.
        for (String diagnostic : List.of(
                "No default constructor found", "No qualifying bean", "Unsatisfied dependency",
                "Error creating bean", "Not a managed type", "Could not resolve placeholder",
                "Failed to determine a suitable driver class", "Unable to determine Dialect",
                "Connection refused", "connect timed out", "Connection timed out",
                "password authentication failed", "permission denied", "does not exist",
                "Stored credential cannot be decrypted", "ENCRYPTION_KEY must be base64 of 32 random bytes",
                "Connect GitHub in Settings first", "Resource not found",
                "GitHub request failed; check integration settings",
                "GitHub commit mismatch", "GitHub incomplete tree", "GitHub invalid blob",
                "GitHub invalid tree path", "GitHub duplicate tree path", "GitHub source size mismatch",
                "Source file limit must be between 1 and 1048576 bytes")) {
            if (message.contains(diagnostic)) return diagnostic + " [additional details withheld]";
        }
        return "Message withheld (may contain credentials, configuration, or source data)";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = {RepositoryStore.class, IntegrationStore.class},
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RepositoryChunkStore.class))
    @Import({RepositoryAccess.class, IntegrationService.class, Crypto.class,
            GitHubSourceControlAdapter.class, SourceFilePolicy.class, RepositorySourceAcquisitionService.class})
    static class SmokeConfiguration {
        @Bean
        PersistenceManagedTypes smokeEntities() {
            return PersistenceManagedTypes.of(TrackedRepository.class.getName(), GitHubIntegration.class.getName());
        }
    }
}
