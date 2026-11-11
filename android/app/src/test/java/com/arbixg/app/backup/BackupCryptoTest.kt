package com.arbixg.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun encryptDecryptRoundTrip() {
        val password = "correct-horse-battery".toCharArray()
        val plaintext = """{"schemaVersion":1,"tradeRecords":[]}"""
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        assertEquals(plaintext, BackupCrypto.decrypt(encrypted, password))
    }

    @Test
    fun eachEncryptionUsesFreshSaltAndNonce() {
        val password = "correct-horse-battery".toCharArray()
        val plaintext = """{"schemaVersion":1}"""
        val first = BackupCrypto.encrypt(plaintext, password)
        val second = BackupCrypto.encrypt(plaintext, password)
        assert(!first.contentEquals(second))
        assertEquals(plaintext, BackupCrypto.decrypt(first, password))
        assertEquals(plaintext, BackupCrypto.decrypt(second, password))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val encrypted = BackupCrypto.encrypt("private backup".toCharArray().concatToString(), "correct-password".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(encrypted, "incorrect-password".toCharArray())
        }
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = BackupCrypto.encrypt("backup payload", "correct-password".toCharArray())
        val modified = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(modified, "correct-password".toCharArray())
        }
    }

    @Test
    fun shortPasswordIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt("backup", "short".toCharArray())
        }
    }
}
