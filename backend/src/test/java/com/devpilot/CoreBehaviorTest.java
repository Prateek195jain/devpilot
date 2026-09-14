package com.devpilot;
import com.devpilot.ai.LocalRules;
import com.devpilot.webhook.WebhookSignature;
import com.devpilot.common.Crypto;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
class CoreBehaviorTest {
    @Test void catchesRiskyAddedCodeAndUsesNewLineNumbers() {
        var hits=LocalRules.scan("FILE: src/run.js\n@@ -1,1 +17,2 @@\n+eval(input);\n+console.log('ok');\n");
        assertTrue(hits.stream().anyMatch(h->h.type().equals("SECURITY")&&h.line()==17));
        assertTrue(hits.stream().anyMatch(h->h.type().equals("TESTING")));
    }
    @Test void ignoresRemovedRiskyCode() {
        assertTrue(LocalRules.scan("FILE: src/run.js\n@@ -1 +1 @@\n-eval(input);\n").isEmpty());
    }
    @Test void testChangeSuppressesMissingTestsFinding() {
        assertFalse(LocalRules.scan("FILE: src/run.js\n+return 2;\nFILE: src/run.test.js\n+assertEquals(2,result);\n").stream().anyMatch(h->h.type().equals("TESTING")));
    }
    @Test void catchesEmptyCatchAndPrintStackTrace() {
        assertTrue(LocalRules.scan("FILE: App.java\n+catch (Exception e) {}\n+e.printStackTrace();").size()>=3);
    }
    @Test void encryptionUsesRandomNoncesAndAuthenticatesCiphertext() {
        Crypto crypto=new Crypto(Base64.getEncoder().encodeToString(new byte[32]));
        String a=crypto.encrypt("private-github-token"),b=crypto.encrypt("private-github-token");
        assertNotEquals(a,b);
        assertEquals("private-github-token",crypto.decrypt(a));
        byte[] tampered=Base64.getDecoder().decode(a);
        tampered[20]^=1;
        assertThrows(IllegalStateException.class,()->crypto.decrypt(Base64.getEncoder().encodeToString(tampered)));
    }
    @Test void acceptsOnlyCorrectWebhookPayloadAndSignature()throws Exception {
        String secret="test-webhook-secret-with-at-least-32-bytes";
        byte[] body="{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature="sha256="+HexFormat.of().formatHex(mac.doFinal(body));
        assertTrue(WebhookSignature.valid(body,signature,secret));
        assertFalse(WebhookSignature.valid("{}".getBytes(StandardCharsets.UTF_8),signature,secret));
        assertFalse(WebhookSignature.valid(body,null,secret));
        assertFalse(WebhookSignature.valid(body,"sha256=xyz",secret));
    }
}
