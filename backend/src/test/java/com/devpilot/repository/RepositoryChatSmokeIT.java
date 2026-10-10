package com.devpilot.repository;

import com.devpilot.ai.*;
import com.devpilot.analysis.*;
import com.devpilot.conversation.*;
import com.devpilot.memory.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named = "RUN_REPOSITORY_CHAT_SMOKE", matches = "true")
class RepositoryChatSmokeIT {
    @Test
    void realIndexedRepositoryAnswersThroughChat() {
        var previousContext = SecurityContextHolder.getContext();
        int questionNumber = 0;
        String stage = "configuration";
        try {
            UUID owner = UUID.fromString(required("REPOSITORY_CHAT_USER_ID"));
            UUID repository = UUID.fromString(required("REPOSITORY_CHAT_REPOSITORY_ID"));
            UUID conversation = UUID.fromString(required("REPOSITORY_CHAT_CONVERSATION_ID"));
            String commit = required("REPOSITORY_CHAT_COMMIT_SHA").toLowerCase(Locale.ROOT);
            require(commit.matches("[a-f0-9]{40}"));
            require(required("DATABASE_URL").startsWith("jdbc:postgresql:"));
            required("DATABASE_USER");
            required("DATABASE_PASSWORD");
            require("google".equals(required("EMBEDDING_MODE")));
            required("EMBEDDING_API_KEY");
            require(Set.of("openai", "gemini").contains(required("AI_MODE")));
            required("AI_API_KEY");
            var authentication = SecurityContextHolder.createEmptyContext();
            authentication.setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
            SecurityContextHolder.setContext(authentication);

            stage = "startup";
            try (var application = new SpringApplicationBuilder(SmokeConfiguration.class)
                    .web(WebApplicationType.NONE).logStartupInfo(false).registerShutdownHook(false)
                    .run("--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--spring.profiles.active=repository-chat-smoke",
                            "--spring.datasource.url=${DATABASE_URL}",
                            "--spring.datasource.username=${DATABASE_USER}",
                            "--spring.datasource.password=${DATABASE_PASSWORD}",
                            "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.show-sql=false",
                            "--spring.jpa.properties.hibernate.hbm2ddl.auto=none",
                            "--spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
                            "--spring.flyway.enabled=false", "--spring.sql.init.mode=never")) {
                stage = "ownership and conversation validation";
                application.getBean(RepositoryAccess.class).require(repository);
                require(application.getBean(ConversationStore.class)
                        .findByIdAndRepositoryId(conversation, repository).isPresent());
                var builder = application.getBean(RecordingContextBuilder.class);
                var chat = application.getBean(ConversationService.class);
                for (String question : List.of("What is the main purpose of this repository?",
                        "Where is authentication handled?",
                        "Which files are most relevant to the backend architecture?",
                        "Where is database access implemented?")) {
                    questionNumber++;
                    builder.result = null;
                    stage = "answer generation";
                    var answers = chat.send(repository, conversation, question, commit);
                    stage = "structural validation";
                    RepositoryContext context = builder.result;
                    require(context != null && repository.equals(context.repositoryId())
                            && commit.equals(context.commitSha()) && context.chunksIncluded() > 0
                            && context.matchesConsidered() >= context.chunksIncluded() && !context.text().isBlank());
                    long sources = context.text().lines().filter(line -> line.startsWith("[Source: ")
                            && line.matches("\\[Source: .+ \\| chunk=\\d+\\]")).distinct().count();
                    require(sources > 0 && answers != null && answers.size() == 2);
                    ChatMessageView answer = answers.get(1);
                    require("assistant".equals(answer.role()) && conversation.equals(answer.conversationId())
                            && answer.content() != null && !answer.content().isBlank());
                    System.out.printf("question=%d success=true sources=%d answerChars=%d%n",
                            questionNumber, sources, answer.content().length());
                }
                stage = "shutdown";
            }
        } catch (RuntimeException failure) {
            System.out.printf("question=%d success=false%n", questionNumber);
            throw new AssertionError("Repository chat smoke unsuccessful during " + stage);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException();
    }

    static class RecordingContextBuilder extends RepositoryContextBuilder {
        private RepositoryContext result;

        RecordingContextBuilder(RepositoryRetrievalService retrieval, int maxChars) {
            super(retrieval, maxChars);
        }

        @Override public RepositoryContext build(UUID repositoryId, String commitSha, String question) {
            result = super.build(repositoryId, commitSha, question);
            require(result.chunksIncluded() > 0 && !result.text().isBlank());
            return result;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            TransactionAutoConfiguration.class, JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
    @EnableJpaRepositories(basePackageClasses = {RepositoryStore.class, ConversationStore.class,
            AnalysisStore.class, MemoryStore.class})
    @Import({RepositoryAccess.class, RepositoryRetrievalService.class, ConversationService.class,
            ContextService.class, GoogleEmbeddingProvider.class, OpenAIProvider.class, GeminiAIProvider.class})
    static class SmokeConfiguration {
        @Bean
        RecordingContextBuilder repositoryContextBuilder(RepositoryRetrievalService retrieval,
                @Value("${app.repository-context.max-chars:24000}") int maxChars) {
            return new RecordingContextBuilder(retrieval, maxChars);
        }

        @Bean
        PersistenceManagedTypes smokeEntities() {
            return PersistenceManagedTypes.of(TrackedRepository.class.getName(), RepositoryChunk.class.getName(),
                    Conversation.class.getName(), ChatMessage.class.getName(), Analysis.class.getName(),
                    Finding.class.getName(), EngineeringMemory.class.getName());
        }
    }
}
