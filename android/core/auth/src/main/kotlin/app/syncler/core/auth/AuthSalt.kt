package app.syncler.core.auth

import app.syncler.core.crypto.KeyDerivation
import java.security.MessageDigest
import java.util.Locale

object AuthSalt {
    fun normalizedEmail(email: String): String = email.trim().lowercase(Locale.US)

    fun forEmail(email: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("syncler-v1-auth-salt:${normalizedEmail(email)}".encodeToByteArray())
        return digest.copyOf(KeyDerivation.SALT_LENGTH_BYTES)
    }
}
