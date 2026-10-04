package com.smartgn.management.integration;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AEAD protects retryable credentials and binds ciphertext to its device. */
public final class CredentialVault {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialVault(String base64Key) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(base64Key); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid credential encryption key"); }
        if (decoded.length != 32) throw new IllegalArgumentException("Credential encryption key must contain 32 bytes");
        key = new SecretKeySpec(decoded, "AES");
    }

    public String generate() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String protect(UUID deviceId, String value) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        return encode(nonce) + "." + encode(crypt(Cipher.ENCRYPT_MODE, deviceId, nonce,
                value.getBytes(StandardCharsets.UTF_8)));
    }

    public String reveal(UUID deviceId, String protectedValue) {
        String[] parts = protectedValue.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid protected credential");
        return new String(crypt(Cipher.DECRYPT_MODE, deviceId,
                Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, UUID deviceId, byte[] nonce, byte[] value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(deviceId.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Credential encryption or authentication failed");
        }
    }

    private static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
}
