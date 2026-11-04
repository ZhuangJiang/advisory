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
 * AES-256-GCM encryption for provider API keys ({@code model_providers.api_key_cipher}).
 *
 * <p><b>Key derivation:</b> the configured {@code app.encryption-key} string is hashed with
 * SHA-256, so any configured value (including the short development default) yields a full 32-byte
 * AES-256 key instead of being silently truncated or rejected as an invalid key size.</p>
 *
 * <p><b>Ciphertext format:</b> {@code Base64(iv ‖ ciphertext+tag)} with a fresh random 12-byte IV
 * per encryption (encrypting the same plaintext twice therefore yields different ciphertexts) and
 * the standard 128-bit GCM authentication tag appended to the ciphertext.</p>
 *
 * <p><b>Failure contract:</b> {@code null} or blank input passes through as {@code null};
 * {@code decrypt} never returns garbage — malformed input (bad Base64, too short) raises
 * {@link IllegalArgumentException} and an authentication failure (tampered ciphertext or a
 * different key) raises {@link IllegalStateException}. Error messages never echo key material.</p>
 */
@Service
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${app.encryption-key}") String encryptionKey) {
        Objects.requireNonNull(encryptionKey, "app.encryption-key must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            if (digest.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException("SHA-256 must produce a 32-byte key");
            }
            this.key = new SecretKeySpec(digest, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    /** Returns {@code Base64(iv ‖ ciphertext+tag)}, or {@code null} for null/blank input. */
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
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Reverses {@link #encrypt}, or returns {@code null} for null/blank input. Never degrades to
     * returning the input: anything that is not a valid, authentic ciphertext is rejected.
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Stored secret is not valid Base64", e);
        }
        if (payload.length <= IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            // must hold at least iv + tag on top of any plaintext bytes
            throw new IllegalArgumentException("Stored secret is too short to be an AES-GCM payload");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH_BYTES));
            byte[] plaintext = cipher.doFinal(payload, IV_LENGTH_BYTES,
                    payload.length - IV_LENGTH_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored secret failed AES-GCM authentication", e);
        }
    }
}
