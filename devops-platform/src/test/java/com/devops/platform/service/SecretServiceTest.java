package com.devops.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecretService 单元测试 — AES-256-GCM 加密/解密/脱敏
 */
class SecretServiceTest {

    private SecretService secretService;

    @BeforeEach
    void setUp() {
        // 32 字节密钥 → base64 (AES-256-GCM 需要 256-bit key)
        secretService = new SecretService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @Test
    void testEncryptDecrypt() {
        String plaintext = "my-secret-password-123";
        String encrypted = secretService.encrypt(plaintext);
        assertNotNull(encrypted);
        assertNotEquals(plaintext, encrypted);

        String decrypted = secretService.decrypt(encrypted);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void testEncryptEmptyString() {
        String encrypted = secretService.encrypt("");
        String decrypted = secretService.decrypt(encrypted);
        assertEquals("", decrypted);
    }

    @Test
    void testMaskSensitive() {
        String log = "password=super123 token=abc123";
        String masked = secretService.maskSensitive(log, List.of("super123", "abc123"));
        assertFalse(masked.contains("super123"));
        assertFalse(masked.contains("abc123"));
        assertTrue(masked.contains("***"));
    }

    @Test
    void testMaskSensitiveNoMatch() {
        String log = "Build completed successfully";
        String masked = secretService.maskSensitive(log, List.of("secret123"));
        assertEquals(log, masked);
    }

    @Test
    void testMaskSensitiveEmptyList() {
        String log = "sensitive data here";
        String masked = secretService.maskSensitive(log, List.of());
        assertEquals(log, masked);
    }

    @Test
    void testDecryptTamperedData() {
        assertThrows(RuntimeException.class, () -> {
            secretService.decrypt("tampered-data-not-encrypted");
        });
    }

    @Test
    void testMultipleEncryptionsProduceDifferentCiphertexts() {
        String plaintext = "test-password";
        String enc1 = secretService.encrypt(plaintext);
        String enc2 = secretService.encrypt(plaintext);
        // AES-GCM 使用随机 IV，每次加密结果应不同
        assertNotEquals(enc1, enc2);
        // 但都能正确解密
        assertEquals(plaintext, secretService.decrypt(enc1));
        assertEquals(plaintext, secretService.decrypt(enc2));
    }

    @Test
    void testDefaultKeyConstruction() {
        // 测试空密钥构造（应使用默认派生密钥）
        SecretService defaultService = new SecretService("");
        String encrypted = defaultService.encrypt("test");
        assertNotNull(encrypted);
        assertEquals("test", defaultService.decrypt(encrypted));
    }

    @Test
    void testResolveReferences() {
        String text = "db.password=${secrets.DB_PASSWORD} app.name=${vars.APP_NAME}";
        String resolved = secretService.resolveReferences(text, key -> {
            if ("DB_PASSWORD".equals(key)) return "mysecret";
            if ("APP_NAME".equals(key)) return "myapp";
            return null;
        });
        assertEquals("db.password=mysecret app.name=myapp", resolved);
    }

    @Test
    void testResolveReferencesNoMatch() {
        String text = "no references here";
        String resolved = secretService.resolveReferences(text, key -> "value");
        assertEquals(text, resolved);
    }

    @Test
    void testResolveReferencesNull() {
        assertNull(secretService.resolveReferences(null, key -> "value"));
    }

    @Test
    void testNullEncrypt() {
        assertThrows(RuntimeException.class, () -> secretService.encrypt(null));
    }
}
