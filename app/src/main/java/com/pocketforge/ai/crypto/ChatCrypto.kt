package com.pocketforge.ai.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption for chat-derived data (conversations, personas).
 *
 * Threat model (as stated in Settings):
 *  - Protects chat data at rest if the phone is lost, imaged, or the app data is
 *    extracted while the app is closed.
 *  - Does NOT protect against root malware while the app is running, or someone using
 *    the unlocked phone with the app open.
 *
 * Design: a random 32-byte data key (SecureRandom) encrypts files with AES-256-GCM
 * (fresh 12-byte IV per file). The data key is wrapped with an AES-256-GCM key that
 * lives in AndroidKeyStore (StrongBox requested on P+, TEE fallback). Only the wrapped
 * blob + IV is persisted (noBackupFilesDir). Raw DEK bytes exist in memory only for the
 * duration of an open/encrypt call and are zeroed afterwards.
 *
 * File format: magic "PFC1" [4] + IV [12] + GCM ciphertext+tag [16].
 */
object ChatCrypto {

    private const val TAG = "PFLlamaJNI"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "pf_chat_key_v1"
    private val MAGIC = "PFC1".toByteArray(Charsets.US_ASCII)
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val DEK_LENGTH = 32

    /** Where the wrapped DEK + IV live (excluded from cloud/device backups). */
    fun wrappedKeyFile(context: Context): File =
        File(context.noBackupFilesDir, "chatkey.bin")

    sealed class KeyStatus {
        object Ready : KeyStatus()
        /** Keystore entry and/or wrapped blob unusable; old ciphertext cannot be opened. */
        object Unrecoverable : KeyStatus()
        data class Failed(val reason: String) : KeyStatus()
    }

    data class SecurityLevelInfo(val label: String, val insideSecureHardware: Boolean)

    /**
     * Ensures both the Keystore key and the wrapped DEK exist and match.
     * Returns Ready when new files can be opened/written; Unrecoverable when existing
     * ciphertext would be lost (never overwrites wrapped key silently).
     */
    fun ensure(context: Context): KeyStatus {
        return try {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val hasAlias = keystore.containsAlias(KEY_ALIAS)
            val blob = wrappedKeyFile(context)
            when {
                hasAlias && blob.isFile -> {
                    // verify we can unwrap; wraps are self-checking via GCM tag
                    unwrapOrThrow(context)
                    KeyStatus.Ready
                }
                !hasAlias && blob.isFile -> KeyStatus.Unrecoverable // blob orphaned
                hasAlias && !blob.isFile -> KeyStatus.Unrecoverable // keystore orphaned
                else -> {
                    createMasterAndWrap(context)
                    KeyStatus.Ready
                }
            }
        } catch (unrec: RecoveryException) {
            KeyStatus.Unrecoverable
        } catch (t: Throwable) {
            Log.e(TAG, "ChatCrypto.ensure failed: $t")
            KeyStatus.Failed(t.message ?: "unknown")
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= 28) {
            specBuilder.setIsStrongBoxBacked(true)
        }
        return try {
            kg.init(specBuilder.build())
            kg.generateKey()
        } catch (e: Exception) {
            // StrongBox hw not available -> TEE fallback
            kg.init(specBuilder.setIsStrongBoxBacked(false).build())
            kg.generateKey()
        }
    }

    private fun createMasterAndWrap(context: Context) {
        val master = getOrCreateMasterKey()
        val dek = ByteArray(DEK_LENGTH).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, master) // fresh randomized IV
            val wrapped = cipher.doFinal(dek)
            val iv = cipher.iv
            require(iv.size == IV_LENGTH) { "unexpected IV length ${iv.size}" }
            val bytes = MAGIC + iv + wrapped
            val f = wrappedKeyFile(context)
            f.parentFile?.mkdirs()
            f.writeText(java.util.Base64.getEncoder().encodeToString(bytes), Charsets.US_ASCII)
        } finally {
            dek.fill(0)
        }
    }

    /** Loads master key, unwraps DEK, zeroes after use-in-place. Returns keys as GCM Cipher inputs. */
    private fun unwrapOrThrow(context: Context): ByteArray {
        val f = wrappedKeyFile(context)
        val raw = try {
            java.util.Base64.getDecoder().decode(f.readText(Charsets.US_ASCII))
        } catch (t: Throwable) {
            throw RecoveryException("wrapped key blob unreadable")
        }
        if (raw.size < MAGIC.size + IV_LENGTH + 1 || !raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw RecoveryException("wrapped key blob has wrong format")
        }
        val master = when (val e = getOrCreateMasterKeyEntrySecret(ANDROID_KEYSTORE)) {
            null -> throw RecoveryException("keystore key missing")
            else -> e
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            master,
            GCMParameterSpec(TAG_BITS, raw, MAGIC.size, IV_LENGTH),
        )
        val dek = try {
            cipher.doFinal(raw, MAGIC.size + IV_LENGTH, raw.size - MAGIC.size - IV_LENGTH)
        } catch (t: Throwable) {
            throw RecoveryException("wrapped key failed to unwrap (GCM tag)")
        }
        if (dek.size != DEK_LENGTH) throw RecoveryException("unwrapped key has wrong length")
        return dek
    }

    private fun getOrCreateMasterKeyEntrySecret(keystoreName: String): SecretKey? {
        val keystore = KeyStore.getInstance(keystoreName).apply { load(null) }
        return (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    class RecoveryException(message: String) : Exception(message)

    /** AES-256-GCM encrypt bytes with the wrapped DEK; fresh IV per call. */
    fun encrypt(context: Context, plaintext: ByteArray): ByteArray {
        val dek = unwrapOrThrow(context)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"))
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext)
            return MAGIC + iv + body
        } finally {
            dek.fill(0)
        }
    }

    /** Decrypt bytes produced by [encrypt]; throws on wrong/corrupted data (GCM tag). */
    fun decrypt(context: Context, data: ByteArray): ByteArray {
        val dek = unwrapOrThrow(context)
        try {
            if (data.size < MAGIC.size + IV_LENGTH + 1 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw RecoveryException("encrypted blob has wrong format")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dek, "AES"),
                GCMParameterSpec(TAG_BITS, data, MAGIC.size, IV_LENGTH),
            )
            return cipher.doFinal(data, MAGIC.size + IV_LENGTH, data.size - MAGIC.size - IV_LENGTH)
        } finally {
            dek.fill(0)
        }
    }

    /** Status of the Keystore key for the settings UI. */
    fun securityLevelInfo(context: Context): SecurityLevelInfo {
        return try {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keystore.containsAlias(KEY_ALIAS)) {
                SecurityLevelInfo("not initialized", false)
            } else {
                val secret = getOrCreateMasterKeyEntrySecret(ANDROID_KEYSTORE) ?: return SecurityLevelInfo("unknown", false)
                val factory = SecretKeyFactory.getInstance(secret.algorithm, ANDROID_KEYSTORE)
                val info = factory.getKeySpec(secret, KeyInfo::class.java)
                val level: String = when (val sec = runCatching {
                    KeyInfo::class.java.getMethod("getSecurityLevel").invoke(info)
                }.getOrNull()) {
                    is Int -> when (sec) {
                        4 -> "StrongBox"
                        3 -> "TEE"
                        2 -> "software"
                        else -> "unknown"
                    }
                    is Long -> when (sec.toInt()) {
                        4 -> "StrongBox"
                        3 -> "TEE"
                        2 -> "software"
                        else -> "unknown"
                    }
                    else -> {
                        val inside = runCatching {
                            KeyInfo::class.java.getMethod("isInsideSecureHardware").invoke(info) as Boolean
                        }.getOrDefault(false)
                        if (inside) "secure hardware" else "software"
                    }
                }
                SecurityLevelInfo(level, level != "software")
            }
        } catch (t: Throwable) {
            SecurityLevelInfo("unknown", false)
        }
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(size + other.size)
        out.write(this); out.write(other)
        return out.toByteArray()
    }
}
