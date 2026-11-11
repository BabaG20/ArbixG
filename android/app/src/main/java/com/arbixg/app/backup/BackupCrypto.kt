package com.arbixg.app.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected, device-independent backup envelope.
 *
 * The caller must only pass non-secret app data. OKX API credentials and Android
 * Keystore material must never be included in the plaintext payload.
 */
object BackupCrypto {
    private val random = SecureRandom()
    private val magic = "ARBIXGB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 310_000
    private const val MIN_CIPHERTEXT_BYTES = 16

    /**
     * Encrypt UTF-8 backup JSON. The returned bytes can be written to a user-selected
     * document (SAF) and copied to another phone. The password is never persisted.
     */
    fun encrypt(plaintext: String, password: CharArray): ByteArray {
        require(password.size >= 12) { "Backup password must contain at least 12 characters." }
        require(plaintext.isNotBlank()) { "Backup data is empty." }

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(magic)
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            ByteBuffer.allocate(magic.size + SALT_BYTES + NONCE_BYTES + encrypted.size)
                .put(magic).put(salt).put(nonce).put(encrypted).array()
        } finally {
            key.encoded?.fill(0)
        }
    }

    /** Decrypts a backup envelope. Wrong passwords or modified files fail authentication. */
    fun decrypt(envelope: ByteArray, password: CharArray): String {
        require(password.size >= 12) { "Backup password must contain at least 12 characters." }
        require(envelope.size >= magic.size + SALT_BYTES + NONCE_BYTES + MIN_CIPHERTEXT_BYTES) {
            "Backup file is incomplete or invalid."
        }

        val buffer = ByteBuffer.wrap(envelope)
        val header = ByteArray(magic.size).also(buffer::get)
        require(header.contentEquals(magic)) { "This is not a supported ArbixG backup file." }
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(magic)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Password is incorrect or the backup file was modified.")
        } finally {
            key.encoded?.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
