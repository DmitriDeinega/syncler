package app.syncler.core.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

data class DerivedUserKeys(
    val authKey: ByteArray,
    val masterKeyWrapKey: ByteArray,
) {
    init {
        require(authKey.size == KEY_SIZE_BYTES)
        require(masterKeyWrapKey.size == KEY_SIZE_BYTES)
    }
}

object KeyDerivation {
    const val PARAMS_VERSION = 1
    const val MEMORY_COST_KIB = 19_456
    const val TIME_COST = 2
    const val PARALLELISM = 1
    const val HASH_LENGTH_BYTES = 64
    const val SALT_LENGTH_BYTES = 16

    fun derive(password: CharArray, salt: ByteArray): DerivedUserKeys {
        require(salt.size == SALT_LENGTH_BYTES) { "Argon2 salt must be 16 bytes" }
        val passwordBytes = password.concatToString().encodeToByteArray()
        return try {
            deriveWithArgon2Kt(passwordBytes, salt)
        } catch (_: Throwable) {
            deriveWithBouncyCastle(passwordBytes, salt)
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun deriveWithArgon2Kt(passwordBytes: ByteArray, salt: ByteArray): DerivedUserKeys {
        val output = Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passwordBytes,
            salt = salt,
            tCostInIterations = TIME_COST,
            mCostInKibibyte = MEMORY_COST_KIB,
            parallelism = PARALLELISM,
            hashLengthInBytes = HASH_LENGTH_BYTES,
        ).rawHashAsByteArray()
        return split(output)
    }

    private fun deriveWithBouncyCastle(passwordBytes: ByteArray, salt: ByteArray): DerivedUserKeys {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(MEMORY_COST_KIB)
            .withIterations(TIME_COST)
            .withParallelism(PARALLELISM)
            .build()
        val output = ByteArray(HASH_LENGTH_BYTES)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passwordBytes, output)
        return split(output)
    }

    private fun split(output: ByteArray): DerivedUserKeys {
        require(output.size == HASH_LENGTH_BYTES)
        return DerivedUserKeys(
            authKey = output.copyOfRange(0, KEY_SIZE_BYTES),
            masterKeyWrapKey = output.copyOfRange(KEY_SIZE_BYTES, HASH_LENGTH_BYTES),
        )
    }
}
