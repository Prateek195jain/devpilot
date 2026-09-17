package com.devpilot.ai;

import com.devpilot.common.Problem;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class GeminiAIProviderTest {
    private static final String DIFF="FILE: src/run.js\n@@ -1 +17,2 @@\n+eval(input);\n+return input;\n";
    private final ObjectMapper mapper=new ObjectMapper();
    private ValidatorFactory validation;
    private HttpServer server;
    private String baseUrl;
    private String response;
    private final AtomicReference<Captured> captured=new AtomicReference<>();
    private record Captured(String path,String method,String authorization,JsonNode body) {}

    @BeforeEach void start()throws Exception {
        validation=Validation.buildDefaultValidatorFactory();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange-> {
            captured.set(new Captured(exchange.getRequestURI().getPath(),exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    mapper.readTree(exchange.getRequestBody())));
            byte[] bytes=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",response)))));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1beta/openai";
    }

    @AfterEach void stop() {
        server.stop(0);
        validation.close();
    }

    private GeminiAIProvider provider(String suffix,String model) {
        return new GeminiAIProvider("test-only-key",baseUrl+suffix,model,mapper,validation.getValidator());
    }

    private AIProvider.Issue issue(String title,String file,Integer line) {
        return new AIProvider.Issue("SECURITY","HIGH",title,"Evidence-backed risk",file,line,"Validate input",0.9);
    }

    private void respond(List<AIProvider.Issue> issues)throws Exception {
        response=mapper.writeValueAsString(new AIProvider.Review("Gemini review",issues));
    }

    @ParameterizedTest @ValueSource(strings={"","/","///"})
    void endpointAuthenticationParsingRedactionAndMerging(String suffix)throws Exception {
        respond(List.of(
                issue(" Review dynamic code or command execution ","src/run.js",17),
                issue("Input requires validation","src/run.js",18),
                issue("INPUT REQUIRES VALIDATION","src/run.js",18)));
        var provider=provider(suffix,"custom-gemini-model");
        var review=provider.analyze(DIFF,"Token: ghp_abcdefghijklmnopqrstuvwxyz");
        var request=captured.get();
        assertEquals("/v1beta/openai/chat/completions",request.path());
        assertEquals("POST",request.method());
        assertEquals("Bearer test-only-key",request.authorization());
        assertEquals("custom-gemini-model",request.body().path("model").asText());
        assertEquals("json_object",request.body().path("response_format").path("type").asText());
        assertTrue(request.body().toString().contains("[REDACTED_TOKEN]"));
        assertFalse(request.body().toString().contains("ghp_abcdefghijklmnopqrstuvwxyz"));
        assertEquals("Gemini review",review.summary());
        assertEquals(3,review.findings().size());
        assertEquals(0.9,review.findings().get(0).confidence());
        assertTrue(review.findings().stream().anyMatch(i->i.title().equals("Input requires validation")&&i.line()==18));
        assertTrue(review.findings().stream().anyMatch(i->i.type().equals("TESTING")&&i.file().equals("change-set")&&i.line()==null));
        assertEquals("GEMINI_AND_LOCAL_RULES",provider.source());
    }

    @Test void defaultsModelAndCapsMergedFindings()throws Exception {
        List<AIProvider.Issue> issues=new ArrayList<>();
        for(int i=0;i<49;i++)issues.add(issue("Risk "+i,"src/run.js",17));
        respond(issues);
        var review=provider("","").analyze(DIFF,"");
        assertEquals("gemini-3.8-flash",captured.get().body().path("model").asText());
        assertEquals(50,review.findings().size());
        assertTrue(review.findings().stream().anyMatch(i->i.title().equals("Review dynamic code or command execution")));
    }

    @Test void rejectsInvalidLocationsAndInvalidStructuredResponses()throws Exception {
        var provider=provider("","model");
        respond(List.of(issue("Risk","invented.js",17)));
        assertThrows(Problem.class,()->provider.analyze(DIFF,""));
        respond(List.of(issue("Risk","src/run.js",999)));
        assertThrows(Problem.class,()->provider.analyze(DIFF,""));
        respond(List.of(issue("","src/run.js",17)));
        assertThrows(Problem.class,()->provider.analyze(DIFF,""));
        response="not JSON";
        assertThrows(Problem.class,()->provider.analyze(DIFF,""));
    }

    @Test void openAiRetainsItsDefaultModelAndDoesNotMergeLocalRules()throws Exception {
        respond(List.of());
        var provider=new OpenAIProvider("test-only-key",baseUrl,"",mapper,validation.getValidator());
        assertTrue(provider.analyze(DIFF,"").findings().isEmpty());
        assertEquals("gpt-4.1-mini",captured.get().body().path("model").asText());
        assertEquals("OPENAI",provider.source());
    }

    @Test void inheritedConversationAndDocumentCallsUseSharedEndpoint() {
        response="Evidence response";
        var provider=provider("/","model");
        assertEquals("Evidence response",provider.answer("context",List.of(),"question"));
        assertFalse(captured.get().body().has("response_format"));
        assertEquals("Evidence response",provider.document("README","context"));
        assertEquals("/v1beta/openai/chat/completions",captured.get().path());
    }

    @Test void providerSelectionDefaultsToLocalAndHonorsEachMode() {
        var runner=new ApplicationContextRunner()
                .withBean(ObjectMapper.class,()->mapper)
                .withBean(Validator.class,()->validation.getValidator())
                .withUserConfiguration(LocalAIProvider.class,OpenAIProvider.class,GeminiAIProvider.class);
        runner.run(ctx->assertInstanceOf(LocalAIProvider.class,ctx.getBean(AIProvider.class)));
        for(String mode:List.of("local","openai","gemini")) {
            runner.withPropertyValues("app.ai.mode="+mode,"app.ai.api-key=test-only-key").run(ctx-> {
                assertEquals(1,ctx.getBeansOfType(AIProvider.class).size());
                assertEquals(switch(mode) {
                    case "openai"->"OPENAI";
                    case "gemini"->"GEMINI_AND_LOCAL_RULES";
                    default->"LOCAL_RULES";
                },ctx.getBean(AIProvider.class).source());
            });
        }
    }

    @Test void rejectsMissingKeyAndAmbiguousBaseUrls() {
        assertThrows(IllegalStateException.class,()->new GeminiAIProvider("",baseUrl,"model",mapper,validation.getValidator()));
        for(String url:List.of(baseUrl+"?key=value",baseUrl+"#fragment","file:///tmp/ai")) {
            assertThrows(IllegalArgumentException.class,()->new GeminiAIProvider("test-only-key",url,"model",mapper,validation.getValidator()));
        }
    }
}
