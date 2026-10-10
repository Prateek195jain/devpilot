package com.devpilot;
import com.devpilot.github.*;
import com.devpilot.analysis.*;
import com.devpilot.ai.*;
import com.devpilot.repository.*;
import com.devpilot.memory.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.Duration;
import com.devpilot.auth.RefreshStore;
import com.devpilot.common.Crypto;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AnalysisService analyses;
    @Autowired AnalysisStore analysisStore;
    @Autowired FindingStore findings;
    @Autowired MemoryStore memories;
    @Autowired RepositoryStore repositories;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired RefreshStore refreshTokens;
    @MockitoBean SourceControlProvider github;
    @MockitoBean AnalysisWorker worker;
    record Session(String access,String cookie,String user) {
    }
    Session register(String suffix)throws Exception {
        var r=mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(json.writeValueAsString(Map.of("name","Developer","email",suffix+UUID.randomUUID()+"@example.com","password","a-strong-test-password")))).andExpect(status().isCreated()).andReturn().getResponse();
        JsonNode n=json.readTree(r.getContentAsString());
        return new Session(n.path("accessToken").asText(),r.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1],n.path("user").path("id").asText());
    }
    String repo(Session s)throws Exception {
        when(github.login(anyString())).thenReturn("engineer");
        when(github.repository(anyString(),eq("acme/service"))).thenReturn(new SourceControlProvider.Repo(42,"acme/service","Example","main","Java"));
        when(github.readme(anyString(),anyString())).thenReturn("# Service\nSpring Boot REST API.");
        mvc.perform(post("/api/v1/github").header("Authorization","Bearer "+s.access).contentType("application/json").content("{\"token\":\"private-test-token\"}")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-test-token"))));
        return json.readTree(mvc.perform(post("/api/v1/repositories").header("Authorization","Bearer "+s.access).contentType("application/json").content("{\"fullName\":\"acme/service\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
    }
    @Test void unauthenticatedAndInvalidJwtRequestsFail()throws Exception {
        mvc.perform(get("/api/v1/repositories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/repositories").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }
    @Test void refreshRotatesAndRejectsReplayedToken()throws Exception {
        var s=register("refresh");
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173").cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",s.cookie))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173").cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",s.cookie))).andExpect(status().isUnauthorized());
    }
    @Test void refreshRejectsForeignOriginAndLogoutRevokes()throws Exception {
        var s=register("logout");
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","https://evil.example").cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",s.cookie))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").header("Origin","http://localhost:5173").cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",s.cookie))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173").cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",s.cookie))).andExpect(status().isUnauthorized());
    }
    @Test void userCannotAccessAnotherOwnersResources()throws Exception {
        var a=register("owner");
        var b=register("intruder");
        String r=repo(a);
        for(String endpoint:List.of("","/analyses","/findings","/memories","/technical-debt","/conversations","/documents","/stats"))mvc.perform(get("/api/v1/repositories/"+r+endpoint).header("Authorization","Bearer "+b.access)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+a.access)).andExpect(status().isOk()).andExpect(jsonPath("passwordHash").doesNotExist());
    }

    String expiredAccess(Session session) {
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer("devpilot").subject(session.user)
                .issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(600))
                .claim("role","USER").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }

    @Test void expiredBearerBlocksRefreshButCookieOnlyRefreshRestoresTheSameSession()throws Exception {
        var session=register("expired-access");
        String expired=expiredAccess(session);
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
        // Reproduce the old SPA behavior: the JWT filter rejects before cookie rotation.
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173")
                .header("Authorization","Bearer "+expired)
                .cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",session.cookie)))
                .andExpect(status().isUnauthorized());
        var token=refreshTokens.findAll().stream().filter(t->t.tokenHash.equals(Crypto.hash(session.cookie))).findFirst().orElseThrow();
        assertNull(token.revokedAt);
        var response=mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173")
                .cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",session.cookie)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(session.user))
                .andReturn().getResponse();
        String renewed=json.readTree(response.getContentAsString()).path("accessToken").asText();
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+renewed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(session.user));
        assertNotNull(refreshTokens.findById(token.id).orElseThrow().revokedAt);
        assertNotEquals(session.cookie,response.getCookie("devpilot_refresh").getValue());
    }

    @Test void expiredRefreshSessionIsRejectedAndLogoutRevokesWithoutAnAccessBearer()throws Exception {
        var expired=register("expired-refresh");
        var token=refreshTokens.findAll().stream().filter(t->t.tokenHash.equals(Crypto.hash(expired.cookie))).findFirst().orElseThrow();
        token.expiresAt=Instant.now().minusSeconds(1);
        refreshTokens.saveAndFlush(token);
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173")
                .cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",expired.cookie)))
                .andExpect(status().isUnauthorized());
        var valid=register("cookie-logout");
        mvc.perform(post("/api/v1/auth/logout").header("Origin","http://localhost:5173")
                .cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",valid.cookie)))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("devpilot_refresh",0));
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173")
                .cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",valid.cookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test void accessAndRefreshLifetimesAndCookieProtectionRemainUnchanged()throws Exception {
        var session=register("lifetimes");
        var jwt=jwtDecoder.decode(session.access);
        assertEquals(600,Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt()).toSeconds());
        var token=refreshTokens.findAll().stream().filter(t->t.tokenHash.equals(Crypto.hash(session.cookie))).findFirst().orElseThrow();
        assertTrue(Duration.between(jwt.getIssuedAt().plus(Duration.ofDays(7)),token.expiresAt).abs().toMillis()<1000);
        mvc.perform(post("/api/v1/auth/refresh").header("Origin","http://localhost:5173")
                .cookie(new jakarta.servlet.http.Cookie("devpilot_refresh",session.cookie)))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("devpilot_refresh",true))
                .andExpect(cookie().path("devpilot_refresh","/api/v1/auth"))
                .andExpect(cookie().maxAge("devpilot_refresh",604800))
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("SameSite=Strict")));
    }

    @Test void repositoryResponsesExposeOnlyTheirOwnIndexedCommitMetadata()throws Exception {
        var session=register("indexed-metadata");
        UUID first=UUID.fromString(repo(session));
        var unindexed=new TrackedRepository();
        unindexed.ownerId=UUID.fromString(session.user); unindexed.githubId=43L;
        unindexed.fullName="acme/unindexed"; unindexed.defaultBranch="main";
        UUID second=repositories.saveAndFlush(unindexed).id;
        String commit="a".repeat(40);
        var indexed=repositories.findById(first).orElseThrow();
        indexed.latestIndexedCommitSha=commit;
        repositories.saveAndFlush(indexed);
        var firstJson=json.readTree(mvc.perform(get("/api/v1/repositories/"+first)
                .header("Authorization","Bearer "+session.access)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(commit,firstJson.path("latestIndexedCommitSha").asText());
        assertFalse(firstJson.has("embedding"));
        assertFalse(firstJson.has("chunks"));
        mvc.perform(get("/api/v1/repositories/"+second).header("Authorization","Bearer "+session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latestIndexedCommitSha").isEmpty());
        var list=json.readTree(mvc.perform(get("/api/v1/repositories?size=100")
                .header("Authorization","Bearer "+session.access)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("content");
        assertEquals(2,list.size());
        for(var repository:list) {
            if(repository.path("id").asText().equals(first.toString()))
                assertEquals(commit,repository.path("latestIndexedCommitSha").asText());
            else assertTrue(repository.path("latestIndexedCommitSha").isNull());
        }
    }

    EngineeringMemory memory(UUID repository,String status,String description,String createdAt) {
        var memory=new EngineeringMemory();
        memory.repositoryId=repository; memory.status=status; memory.description=description;
        memory.type="TEST_COVERAGE"; memory.fingerprint=Crypto.hash(UUID.randomUUID().toString());
        memory.occurrences=5; memory.createdAt=Instant.parse(createdAt);
        memory.lastSeen=Instant.parse("2026-01-05T00:00:00Z");
        return memories.saveAndFlush(memory);
    }

    @Test void overviewUsesActiveMemoryBeforePaginationAndArchiveReactivatePreserveInventory()throws Exception {
        var session=register("memory-status");
        UUID repository=UUID.fromString(repo(session));
        var active=memory(repository,"ACTIVE","Active pattern","2026-01-01T00:00:00Z");
        memory(repository,"ARCHIVED","Newest archived pattern","2026-01-03T00:00:00Z");
        memory(repository,"ARCHIVED","Another archived pattern","2026-01-02T00:00:00Z");
        String url="/api/v1/repositories/"+repository+"/memories";
        mvc.perform(get(url+"?size=1&status=ACTIVE").header("Authorization","Bearer "+session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(active.id.toString()))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
        mvc.perform(patch(url+"/"+active.id).header("Authorization","Bearer "+session.access)
                .contentType("application/json").content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(get(url+"?size=3&status=ACTIVE").header("Authorization","Bearer "+session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        var inventory=json.readTree(mvc.perform(get(url).header("Authorization","Bearer "+session.access))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(3,inventory.path("totalElements").asInt());
        JsonNode archived=null;
        for(var item:inventory.path("content"))if(item.path("id").asText().equals(active.id.toString()))archived=item;
        assertNotNull(archived);
        assertEquals("ARCHIVED",archived.path("status").asText());
        assertEquals(active.occurrences,archived.path("occurrences").asInt());
        assertEquals(active.createdAt,Instant.parse(archived.path("createdAt").asText()));
        assertEquals(active.lastSeen,Instant.parse(archived.path("lastSeen").asText()));
        mvc.perform(patch(url+"/"+active.id).header("Authorization","Bearer "+session.access)
                .contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(url+"?size=3&status=ACTIVE").header("Authorization","Bearer "+session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(active.id.toString()));
        var unchanged=memories.findById(active.id).orElseThrow();
        assertEquals(active.occurrences,unchanged.occurrences);
        assertEquals(active.createdAt,unchanged.createdAt);
        assertEquals(active.lastSeen,unchanged.lastSeen);
        assertEquals(active.fingerprint,unchanged.fingerprint);
    }

    @Test void filteredMemoryRemainsRepositoryScopedAndValidatesStatus()throws Exception {
        var session=register("scoped-memory");
        UUID first=UUID.fromString(repo(session));
        var secondRepository=new TrackedRepository();
        secondRepository.ownerId=UUID.fromString(session.user); secondRepository.githubId=43L;
        secondRepository.fullName="acme/second"; secondRepository.defaultBranch="main";
        UUID second=repositories.saveAndFlush(secondRepository).id;
        var firstMemory=memory(first,"ACTIVE","First repo pattern","2026-01-01T00:00:00Z");
        var secondMemory=memory(second,"ACTIVE","Second repo pattern","2026-01-02T00:00:00Z");
        memory(second,"ARCHIVED","Second repo archived pattern","2026-01-03T00:00:00Z");
        String url="/api/v1/repositories/"+second+"/memories";
        mvc.perform(get(url+"?status=ACTIVE").header("Authorization","Bearer "+session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(secondMemory.id.toString()));
        mvc.perform(get(url+"?status=ARCHIVED").header("Authorization","Bearer "+session.access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("ARCHIVED"));
        mvc.perform(get(url+"?status=UNKNOWN").header("Authorization","Bearer "+session.access)).andExpect(status().isBadRequest());
        mvc.perform(patch(url+"/"+firstMemory.id).header("Authorization","Bearer "+session.access)
                .contentType("application/json").content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isNotFound());
        assertEquals("ACTIVE",memories.findById(firstMemory.id).orElseThrow().status);
        var otherOwner=register("memory-intruder");
        mvc.perform(get(url+"?status=ACTIVE").header("Authorization","Bearer "+otherOwner.access)).andExpect(status().isNotFound());
    }
    @Test void registrationNeverAcceptsAdminRole()throws Exception {
        var s=register("role");
        mvc.perform(get("/api/v1/admin/stats").header("Authorization","Bearer "+s.access)).andExpect(status().isForbidden());
    }
    @Test void localAnalysisCompletionIsIdempotentAndBuildsMemory()throws Exception {
        var s=register("analysis");
        UUID r=UUID.fromString(repo(s));
        var queued=analyses.enqueue(r,"PR","7");
        var again=analyses.enqueue(r,"PR","7");
        assertEquals(queued.id(),again.id());
        var job=analyses.claim().orElseThrow();
        var review=new AIProvider.Review("One risk",List.of(new AIProvider.Issue("SECURITY","HIGH","Dynamic execution","Untrusted input may execute","src/a.js",1,"Remove eval",0.9)));
        analyses.complete(job,review,"FILE: src/a.js\n+eval(input);","LOCAL_RULES",false);
        analyses.complete(job,review,"duplicate","LOCAL_RULES",false);
        assertEquals(1,findings.findByRepositoryId(r,org.springframework.data.domain.PageRequest.of(0,100)).getTotalElements());
        assertEquals(1,memories.findByRepositoryId(r,org.springframework.data.domain.PageRequest.of(0,100)).getContent().get(0).occurrences);
    }
    @Test void debtTransitionsRecordAndClearResolution()throws Exception {
        var s=register("debt");
        String r=repo(s);
        String body="{\"title\":\"Add validation\",\"description\":\"Validate DTOs\",\"category\":\"API\",\"severity\":\"HIGH\",\"estimatedEffort\":\"2h\"}";
        String id=json.readTree(mvc.perform(post("/api/v1/repositories/"+r+"/technical-debt").header("Authorization","Bearer "+s.access).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(patch("/api/v1/repositories/"+r+"/technical-debt/"+id).header("Authorization","Bearer "+s.access).contentType("application/json").content("{\"status\":\"RESOLVED\",\"estimatedEffort\":\"2h\"}")).andExpect(status().isOk()).andExpect(jsonPath("resolvedAt").isNotEmpty());
        mvc.perform(patch("/api/v1/repositories/"+r+"/technical-debt/"+id).header("Authorization","Bearer "+s.access).contentType("application/json").content("{\"status\":\"OPEN\",\"estimatedEffort\":\"2h\"}")).andExpect(status().isOk()).andExpect(jsonPath("resolvedAt").isEmpty());
    }
    @Test void webhookRejectsBadSignatureAndDeduplicatesValidDelivery()throws Exception {
        String body="{\"zen\":\"keep it simple\"}";
        String delivery=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/webhooks/github").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-webhook-secret-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String sig="sha256="+HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        for(boolean duplicate:List.of(false,true))mvc.perform(post("/api/v1/webhooks/github").header("X-Hub-Signature-256",sig).header("X-GitHub-Delivery",delivery).header("X-GitHub-Event","ping").contentType("application/json").content(body)).andExpect(status().isAccepted()).andExpect(jsonPath("duplicate").value(duplicate));
    }
    @Test void conversationPersistsBothSidesAndDocsVersion()throws Exception {
        var s=register("chat");
        String r=repo(s);
        String c=json.readTree(mvc.perform(post("/api/v1/repositories/"+r+"/conversations").header("Authorization","Bearer "+s.access).contentType("application/json").content("{\"title\":\"Architecture\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/repositories/"+r+"/conversations/"+c+"/messages").header("Authorization","Bearer "+s.access).contentType("application/json").content("{\"content\":\"Explain the service\"}")).andExpect(status().isOk()).andExpect(jsonPath("$[1].role").value("assistant"));
        for(int version=1;version<=2;version++)mvc.perform(post("/api/v1/repositories/"+r+"/documents").header("Authorization","Bearer "+s.access).contentType("application/json").content("{\"kind\":\"README\"}")).andExpect(status().isCreated()).andExpect(jsonPath("documentVersion").value(version));
    }
}
