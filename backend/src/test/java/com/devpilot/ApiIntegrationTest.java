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
