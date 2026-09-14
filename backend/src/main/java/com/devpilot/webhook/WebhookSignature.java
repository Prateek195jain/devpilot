package com.devpilot.webhook;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.util.HexFormat;
public final class WebhookSignature {
    private WebhookSignature() {
    }
    public static boolean valid(byte[] payload,String signature,String secret) {
        if(signature==null||!signature.matches("sha256=[a-fA-F0-9]{64}"))return false;
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload),HexFormat.of().parseHex(signature.substring(7)));
        }
        catch(GeneralSecurityException|IllegalArgumentException e) {
            return false;
        }
    }
}
