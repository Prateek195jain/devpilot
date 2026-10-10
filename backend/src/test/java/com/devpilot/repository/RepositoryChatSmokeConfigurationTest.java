package com.devpilot.repository;

import com.devpilot.ai.*;
import com.devpilot.conversation.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryChatSmokeConfigurationTest {
    @Test void restrictedSmokeContextWiresRealServicesWithoutProviderCalls() {
        new ApplicationContextRunner()
                .withUserConfiguration(RepositoryChatSmokeIT.SmokeConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:chat-smoke-wiring;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.jpa.database-platform=com.devpilot.repository.H2VectorDialect",
                        "spring.jpa.hibernate.ddl-auto=create-drop", "app.embedding.mode=google",
                        "app.embedding.api-key=test-only", "app.ai.mode=openai", "app.ai.api-key=test-only")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(ConversationService.class));
                    assertInstanceOf(RepositoryChatSmokeIT.RecordingContextBuilder.class,
                            context.getBean(RepositoryContextBuilder.class));
                    assertInstanceOf(GoogleEmbeddingProvider.class, context.getBean(EmbeddingProvider.class));
                    assertInstanceOf(OpenAIProvider.class, context.getBean(AIProvider.class));
                });
    }
}
