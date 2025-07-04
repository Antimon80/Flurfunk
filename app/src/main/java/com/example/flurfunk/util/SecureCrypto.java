package com.example.flurfunk.util;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Utility class for AES encryption and decryption of strings using the device's mesh ID
 * as a shared secret. Uses AES in CTR mode with no padding.
 * <p>
 * The encryption key is derived from the mesh ID using SHA-256 and truncated to 128 bits (16 bytes).
 * A new random IV (initialization vector) is generated for each encryption and must be stored or
 * transmitted alongside the ciphertext for decryption.
 */
public class SecureCrypto {

    private static final int IV_LENGTH = 16;

    /**
     * Derives a 128-bit AES key from the given mesh ID by hashing it with SHA-256
     * and truncating the result to the first 16 bytes.
     *
     * @param meshId the mesh ID to derive the key from
     * @return a 16-byte AES key
     * @throws RuntimeException if key derivation fails
     */
    public static byte[] deriveKeyFromMeshId(String meshId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(meshId.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, 16);
        } catch (Exception e) {
            throw new RuntimeException("Unable to derive AES key from mesh ID", e);
        }
    }

    /**
     * Encrypts a plaintext string using AES in CTR mode with a random IV and a key
     * derived from the given mesh ID.
     *
     * @param plainText the plaintext string to encrypt
     * @param meshId    the mesh ID used to derive the encryption key
     * @return an {@link EncryptedPayload} containing the Base64-encoded ciphertext and IV
     * @throws RuntimeException if encryption fails
     */
    public static EncryptedPayload encrypt(String plainText, String meshId) {
        try {
            byte[] key = deriveKeyFromMeshId(meshId);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));

            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return new EncryptedPayload(
                    Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    Base64.encodeToString(iv, Base64.NO_WRAP)
            );
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded AES ciphertext using the corresponding Base64-encoded IV
     * and a key derived from the given mesh ID.
     *
     * @param base64Ciphertext the Base64-encoded ciphertext
     * @param base64Iv         the Base64-encoded initialization vector
     * @param meshId           the mesh ID used to derive the decryption key
     * @return the decrypted plaintext string
     * @throws RuntimeException if decryption fails
     */
    public static String decrypt(String base64Ciphertext, String base64Iv, String meshId) {
        try {
            byte[] key = deriveKeyFromMeshId(meshId);
            byte[] iv = Base64.decode(base64Iv, Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(base64Ciphertext, Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Simple container for an AES-encrypted message consisting of:
     * <ul>
     *     <li>{@code ciphertext}: the Base64-encoded encrypted payload</li>
     *     <li>{@code iv}: the Base64-encoded initialization vector used in encryption</li>
     * </ul>
     */
    public static class EncryptedPayload {
        public final String ciphertext;
        public final String iv;

        public EncryptedPayload(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }
}
