package com.guardian.shield.service.detection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREF_FILE = "guardian_pin_secure"
        private const val KEY_PIN_HASH = "pin_hash_v2"
        private const val KEY_PIN_SALT = "pin_salt_v2"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH = 256
        private const val SALT_LENGTH = 32
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isPinSet(): Boolean {
        return prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)
    }

    fun setPin(pin: String): Boolean {
        if (pin.length < 4) return false
        return try {
            val salt = generateSalt()
            val hash = hashPin(pin, salt)
            prefs.edit()
                .putString(KEY_PIN_HASH, hash)
                .putString(KEY_PIN_SALT, salt.toBase64())
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun verifyPin(pin: String): VerifyResult {
        // Check lockout
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (System.currentTimeMillis() < lockoutUntil) {
            val remainingSec = (lockoutUntil - System.currentTimeMillis()) / 1000
            return VerifyResult.LockedOut(remainingSec)
        }

        val storedHash = prefs.getString(KEY_PIN_HASH, null)
            ?: return VerifyResult.NotSet
        val saltStr = prefs.getString(KEY_PIN_SALT, null)
            ?: return VerifyResult.NotSet

        return try {
            val salt = saltStr.fromBase64()
            val computedHash = hashPin(pin, salt)
            
            if (constantTimeEquals(computedHash, storedHash)) {
                // Reset failed attempts
                prefs.edit()
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, 0L)
                    .apply()
                VerifyResult.Success
            } else {
                val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
                if (attempts >= MAX_ATTEMPTS) {
                    val lockUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                    prefs.edit()
                        .putInt(KEY_FAILED_ATTEMPTS, 0)
                        .putLong(KEY_LOCKOUT_UNTIL, lockUntil)
                        .apply()
                    VerifyResult.LockedOut(LOCKOUT_DURATION_MS / 1000)
                } else {
                    prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply()
                    VerifyResult.Failed(MAX_ATTEMPTS - attempts)
                }
            }
        } catch (e: Exception) {
            VerifyResult.Failed(0)
        }
    }

    fun changePin(oldPin: String, newPin: String): Boolean {
        return when (verifyPin(oldPin)) {
            is VerifyResult.Success -> setPin(newPin)
            else -> false
        }
    }

    fun clearPin() {
        prefs.edit().clear().apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return hash.toBase64()
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    sealed class VerifyResult {
        object Success : VerifyResult()
        object NotSet : VerifyResult()
        data class Failed(val remainingAttempts: Int) : VerifyResult()
        data class LockedOut(val remainingSeconds: Long) : VerifyResult()
    }
}