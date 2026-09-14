package com.devpilot.common;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component public class Crypto {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public Crypto(@Value("${app.encryption-key}")String encoded) {
        key=Base64.getDecoder().decode(encoded);
        if(key.length!=32)throw new IllegalStateException("ENCRYPTION_KEY must be base64 of 32 random bytes");
    }
    public String encrypt(String text) {
        try {
            byte[] iv=new byte[12];
            random.nextBytes(iv);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            byte[] encrypted=c.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] out=new byte[iv.length+encrypted.length];
            System.arraycopy(iv,0,out,0,12);
            System.arraycopy(encrypted,0,out,12,encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        }
        catch(GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed");
        }
    }
    public String decrypt(String data) {
        try {
            byte[] bytes=Base64.getDecoder().decode(data);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            return new String(c.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8);
        }
        catch(GeneralSecurityException|IllegalArgumentException e) {
            throw new IllegalStateException("Stored credential cannot be decrypted");
        }
    }
    public static String randomToken() {
        byte[] b=new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
