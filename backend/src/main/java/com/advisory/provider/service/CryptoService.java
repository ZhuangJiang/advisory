package com.advisory.provider.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 提供商 API Key 的 AES-256-GCM 加解密（{@code model_providers.api_key_cipher}）。
 *
 * <p><b>密钥派生：</b>配置项 {@code app.encryption-key} 先做一次 SHA-256 再作为密钥，这样任意长度的配置值
 * （包括开发环境那个很短的默认值）都能得到完整的 32 字节 AES-256 密钥，而不会被悄悄截断或因长度非法报错。</p>
 *
 * <p><b>密文格式：</b>{@code Base64(iv ‖ ciphertext+tag)}。每次加密都用新的 12 字节随机 IV，因此同一明文
 * 两次加密得到的密文不同；末尾附带标准的 128 位 GCM 认证标签，解密时自动校验完整性。</p>
 *
 * <p><b>失败约定：</b>{@code null} 或空白输入原样返回 {@code null}；{@code decrypt} 绝不返回疑似明文的垃圾数据——
 * 格式错误（Base64 非法、长度不足）抛 {@link IllegalArgumentException}，认证失败（密文被改或密钥不符）
 * 抛 {@link IllegalStateException}。异常信息中不含任何密钥材料。</p>
 */
@Service
public class CryptoService {

    /** 算法/模式/填充；GCM 自带认证，故无需额外填充。 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 推荐的 IV 长度，固定 12 字节。 */
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    /** 认证标签字节数，用于校验密文最短长度。 */
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${app.encryption-key}") String encryptionKey) {
        Objects.requireNonNull(encryptionKey, "app.encryption-key must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            if (digest.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException("SHA-256 必须产出 32 字节密钥");
            }
            this.key = new SecretKeySpec(digest, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不提供 SHA-256 算法", e);
        }
    }

    /**
     * 加密。
     *
     * @param plaintext 明文，{@code null} 或空白时直接返回 {@code null}
     * @return {@code Base64(iv ‖ ciphertext+tag)}
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    /**
     * 解密，{@link #encrypt} 的逆操作；{@code null} 或空白输入返回 {@code null}。
     *
     * <p>绝不降级为「解不开就把入参原样返回」：任何不是合法且通过认证的密文都会被拒绝，
     * 否则一段误配置就能把明文当成密文在系统里流转。</p>
     *
     * @param ciphertext 密文
     * @return 明文
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("保存的密文不是合法的 Base64", e);
        }
        if (payload.length <= IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            // 长度至少要有 iv + tag，再谈明文
            throw new IllegalArgumentException("保存的密文长度不足，不是有效的 AES-GCM 载荷");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH_BYTES));
            byte[] plaintext = cipher.doFinal(payload, IV_LENGTH_BYTES,
                    payload.length - IV_LENGTH_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("保存的密文未通过 AES-GCM 认证", e);
        }
    }
}
