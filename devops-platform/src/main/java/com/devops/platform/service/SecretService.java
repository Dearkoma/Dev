package com.devops.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 密钥服务
 * - AES-256-GCM 加密/解密
 * - ${secrets.KEY} 变量解析
 * - 日志脱敏
 */
@Slf4j
@Service
public class SecretService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** ${secrets.XXX} 或 ${vars.XXX} 引用模式 */
    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{(secrets|vars)\\.([^}]+)\\}");

    private final SecretKey secretKey;

    public SecretService(@Value("${devops.security.encryption-key:}") String configuredKey) {
        try {
            byte[] keyBytes;
            if (configuredKey != null && !configuredKey.isBlank()) {
                // 使用配置的密钥（需 base64 编码的 32 字节）
                keyBytes = Base64.getDecoder().decode(configuredKey);
            } else {
                // 降级：用 JWT secret 的 SHA-256 派生 32 字节密钥
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                keyBytes = sha.digest("devops-platform-default-encryption".getBytes(StandardCharsets.UTF_8));
            }
            this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("初始化加密密钥失败", e);
        }
    }

    /** 加密明文 → Base64(IV + ciphertext) */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /** 解密 Base64(IV + ciphertext) → 明文 */
    public String decrypt(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败", e);
            throw new RuntimeException("解密失败", e);
        }
    }

    /**
     * 解析字符串中的 ${secrets.KEY} 和 ${vars.KEY} 引用
     * @param text 包含引用的原始字符串
     * @param resolver 变量解析器 (key → value)
     * @return 替换后的字符串
     */
    public String resolveReferences(String text, java.util.function.Function<String, String> resolver) {
        if (text == null || !text.contains("${")) return text;
        Matcher m = SECRET_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(2);
            String value = resolver.apply(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 对日志内容中的敏感值进行脱敏（用 *** 替换）
     * @param logLine 日志行
     * @param sensitiveValues 需要脱敏的值列表
     * @return 脱敏后的日志行
     */
    public String maskSensitive(String logLine, java.util.List<String> sensitiveValues) {
        if (logLine == null || sensitiveValues == null || sensitiveValues.isEmpty()) return logLine;
        String result = logLine;
        for (String sv : sensitiveValues) {
            if (sv != null && sv.length() > 2) {
                // 保留首尾各1个字符，中间替换为 ***
                String masked = sv.charAt(0) + "***" + sv.charAt(sv.length() - 1);
                result = result.replace(sv, masked);
            }
        }
        return result;
    }
}
