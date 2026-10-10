package com.devpilot.repository;

import com.devpilot.analysis.*;
import com.devpilot.auth.RefreshToken;
import com.devpilot.common.*;
import com.devpilot.conversation.*;
import com.devpilot.documentation.GeneratedDocument;
import com.devpilot.github.*;
import com.devpilot.memory.EngineeringMemory;
import com.devpilot.technicaldebt.TechnicalDebt;
import com.devpilot.user.UserAccount;
import com.devpilot.webhook.WebhookEvent;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RepositoryRemovalService.class, RepositoryAccess.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositoryRemovalServiceTest {
    @Autowired RepositoryRemovalService removal;
    @Autowired RepositoryAccess access;
    @Autowired RepositoryStore repositories;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate transaction;
    @MockitoSpyBean JdbcTemplate jdbc;
    @MockitoBean SourceControlProvider github;
    @MockitoBean IntegrationService integrations;
    final List<BaseEntity> created = new ArrayList<>();
    Map<String, UUID> target, sameOwner, otherOwner;
    UUID owner;
    MockMvc mvc;

    @BeforeEach void setup() {
        transaction.executeWithoutResult(status -> {
            owner = user().id;
            UUID anotherOwner = user().id;
            target = repository(owner, 1);
            sameOwner = repository(owner, 2);
            otherOwner = repository(anotherOwner, 3);
            var integration = new GitHubIntegration();
            integration.userId = owner; integration.login = "test"; integration.encryptedToken = "synthetic";
            persist(integration);
            var refresh = new RefreshToken();
            refresh.userId = owner; refresh.tokenHash = "a".repeat(64); refresh.expiresAt = Instant.now().plusSeconds(600);
            persist(refresh);
            var webhook = new WebhookEvent();
            webhook.deliveryId = UUID.randomUUID().toString(); webhook.eventType = "push"; webhook.payloadHash = "a".repeat(64);
            persist(webhook);
        });
        authenticate(owner);
        var controller = new RepositoryController(repositories, access, integrations, github, null, removal);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new Errors()).build();
    }

    private <T extends BaseEntity> T persist(T entity) {
        em.persist(entity); created.add(entity); return entity;
    }

    private UserAccount user() {
        var user = new UserAccount();
        user.email = UUID.randomUUID() + "@example.com"; user.name = "Removal test";
        user.passwordHash = "synthetic"; user.role = "USER";
        return persist(user);
    }

    private Map<String, UUID> repository(UUID user, long githubId) {
        Map<String, UUID> rows = new LinkedHashMap<>();
        var repo = new TrackedRepository();
        repo.ownerId = user; repo.githubId = githubId; repo.fullName = "test/repo" + githubId;
        repo.defaultBranch = "main"; repo.latestIndexedCommitSha = "a".repeat(40);
        rows.put("repositories", persist(repo).id);
        var analysis = new Analysis();
        analysis.repositoryId = repo.id; analysis.kind = "COMMIT"; analysis.ref = "a".repeat(40);
        analysis.status = "RUNNING"; analysis.source = "LOCAL_RULES";
        rows.put("analyses", persist(analysis).id);
        var finding = new Finding();
        finding.repositoryId = repo.id; finding.analysisId = analysis.id; finding.type = "TEST_COVERAGE";
        finding.severity = "HIGH"; finding.title = "Finding"; finding.file = "A.java";
        finding.recommendation = "Add tests"; finding.confidence = 0.9; finding.source = "LOCAL_RULES";
        finding.fingerprint = "b".repeat(64);
        rows.put("findings", persist(finding).id);
        var debt = new TechnicalDebt();
        debt.repositoryId = repo.id; debt.sourceFindingId = finding.id; debt.title = "Debt";
        debt.category = "TEST_COVERAGE"; debt.severity = "HIGH"; debt.status = "OPEN";
        debt.estimatedEffort = "1h"; debt.updatedAt = Instant.now();
        rows.put("technical_debt", persist(debt).id);
        var memory = new EngineeringMemory();
        memory.repositoryId = repo.id; memory.fingerprint = "c".repeat(64); memory.type = "TEST_COVERAGE";
        memory.occurrences = 1; memory.lastSeen = Instant.now(); memory.status = "ACTIVE";
        rows.put("engineering_memories", persist(memory).id);
        var conversation = new Conversation();
        conversation.repositoryId = repo.id; conversation.title = "Chat";
        rows.put("conversations", persist(conversation).id);
        var message = new ChatMessage();
        message.conversationId = conversation.id; message.role = "assistant"; message.content = "Answer";
        message.sources = List.of(new RepositorySourceReference("A.java", 0, "a".repeat(40)));
        rows.put("messages", persist(message).id);
        var document = new GeneratedDocument();
        document.repositoryId = repo.id; document.kind = "README"; document.documentVersion = 1;
        document.content = "Documentation"; document.source = "LOCAL_RULES";
        rows.put("generated_documents", persist(document).id);
        var chunk = new RepositoryChunk();
        chunk.repositoryId = repo.id; chunk.commitSha = "a".repeat(40); chunk.sourcePath = "A.java";
        chunk.content = "class A {}"; chunk.contentHash = "d".repeat(64);
        chunk.embeddingModel = "test-model"; chunk.embedding = new float[768];
        rows.put("repository_chunks", persist(chunk).id);
        return rows;
    }

    private void authenticate(UUID user) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private void assertRows(Map<String, UUID> rows, int expected) {
        rows.forEach((table, id) -> assertEquals(expected,
                jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id), table));
    }

    private void assertUnrelatedData() {
        assertRows(sameOwner, 1); assertRows(otherOwner, 1);
        assertEquals(1, jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, owner));
        assertEquals(1, jdbc.queryForObject("select count(*) from github_integrations where user_id = ?", Integer.class, owner));
        assertEquals(1, jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, owner));
        assertEquals(1, jdbc.queryForObject("select count(*) from webhook_events", Integer.class));
    }

    @Test void ownerDeletesEveryOwnedRowThroughEndpointWithoutGitHubCalls() throws Exception {
        mvc.perform(delete("/api/v1/repositories/" + target.get("repositories")))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertRows(target, 0); assertUnrelatedData();
        verifyNoInteractions(github, integrations);
    }

    @Test void nonOwnerCannotDeleteAnyRows() throws Exception {
        authenticate(UUID.randomUUID());
        mvc.perform(delete("/api/v1/repositories/" + target.get("repositories"))).andExpect(status().isNotFound());
        assertRows(target, 1); assertUnrelatedData();
        verifyNoInteractions(github, integrations);
    }

    @Test void failureAfterDeletingSeveralTablesRollsBackEverythingAndReturnsClearError() throws Exception {
        doThrow(new IllegalStateException("Synthetic deletion failure"))
                .when(jdbc).update(eq("delete from engineering_memories where repository_id = ?"), eq(target.get("repositories")));
        mvc.perform(delete("/api/v1/repositories/" + target.get("repositories")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No data was removed")));
        assertRows(target, 1); assertUnrelatedData();
        verifyNoInteractions(github, integrations);
    }

    @Test void missingRepositoryReturnsNotFoundWithoutDeletingAnything() throws Exception {
        mvc.perform(delete("/api/v1/repositories/" + UUID.randomUUID())).andExpect(status().isNotFound());
        assertRows(target, 1); assertUnrelatedData();
        verifyNoInteractions(github, integrations);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        reset(jdbc);
        transaction.executeWithoutResult(status -> {
            // Reverse fixture dependency order, flushing each deletion for PostgreSQL FKs.
            var reverse = new ArrayList<>(created);
            Collections.reverse(reverse);
            for (BaseEntity row : reverse) {
                var remaining = em.find(row.getClass(), row.id);
                if (remaining != null) { em.remove(remaining); em.flush(); }
            }
        });
        created.clear();
    }
}
