package com.devops.agent.infrastructure.llm;

/**
 * API Key 加解密工具（阶段A-P0 模型配置可视化）。
 *
 * <h3>为什么需要</h3>
 * 模型渠道配置落库后，API Key 不能明文存 DB——泄露面太大。本工具
 * 用 AES-GCM 加密 key 后存入 {@code sys_ai_channel.api_key_enc}。
 *
 * <h3>密钥来源</h3>
 * 加密密钥由环境变量 {@code MODEL_KEY_CRYPT_SECRET} 经 SHA-256 派生
 * （32 字节，AES-256）。未配置该变量时（本地开发/未部署），
 * <b>不加密存储</b>并打告警日志——宁可降级明文让功能可用，也不把 key
 * 加密到不可逆后退化失败；但生产部署必须显式配置该密钥。
 *
 * <h3>格式</h3>
 * 密文统一前缀 {@code enc:v1:}；未加密的明文 key 原样返回（前缀为空）。
 * 解密时认前缀：无前缀按明文处理，避免旧数据解密报错。
 */
public final class ApiKeyCrypt {

    private static final String PREFIX = "enc:v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private ApiKeyCrypt() {}

    /**
     * 是否已配置加密密钥。
     * @param secret MODEL_KEY_CRYPT_SECRET 环境变量值
     */
    public static boolean enabled(String secret) {
        return secret != null && !secret.isBlank();
    }

    /**
     * 加密明文 key。secret 未配置时返回原值（不加密，降级）。
     */
    public static String encrypt(String rawKey, String secret) {
        if (rawKey == null || rawKey.isBlank()) return null;
        if (!enabled(secret)) return rawKey; // 降级明文
        try {
            byte[] iv = new byte[12]; // GCM 标准 12 字节 IV
            java.security.SecureRandom.getInstanceStrong().nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(ALGORITHM);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, deriveKey(secret), new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(iv);
            out.write(ciphertext);
            return PREFIX + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("[ApiKeyCrypt] 加密失败（检查 MODEL_KEY_CRYPT_SECRET 配置）", e);
        }
    }

    /**
     * 解密密文。无 {@code enc:v1:} 前缀按明文返回（兼容降级/旧数据）。
     */
    public static String decrypt(String stored, String secret) {
        if (stored == null || stored.isBlank()) return null;
        if (!stored.startsWith(PREFIX)) return stored; // 明文
        try {
            byte[] full = java.util.Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = java.util.Arrays.copyOfRange(full, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(full, 12, full.length);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(ALGORITHM);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, deriveKey(secret), new javax.crypto.spec.GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败：密钥变了或数据损坏。不抛业务异常——视作明文返回并打日志，
            // 由上层决定处理（多数场景是「无法还原 key，需重新配置」）。
            throw new IllegalStateException("[ApiKeyCrypt] 解密失败（可能 MODEL_KEY_CRYPT_SECRET 已变更）", e);
        }
    }

    /**
     * 生成脱敏展示串。{@code sk-ws-abcd1234} → {@code sk-ws-****1234}。
     * 保留前 6 位 + 后 4 位；过短则整串打码。
     */
    public static String mask(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        if (rawKey.length() <= 10) return "****";
        return rawKey.substring(0, Math.min(6, rawKey.length()))
                + "****" + rawKey.substring(rawKey.length() - 4);
    }

    /** 从 MODEL_KEY_CRYPT_SECRET 派生 AES-256 密钥。 */
    private static javax.crypto.SecretKey deriveKey(String secret) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new javax.crypto.spec.SecretKeySpec(digest, "AES");
    }
}
