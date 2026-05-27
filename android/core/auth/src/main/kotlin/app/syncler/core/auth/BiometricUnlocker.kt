package app.syncler.core.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * V4 #20 — host for the biometric / device-credential authentication
 * prompt that gates [SensitiveActionGate]. Triad 166 agreed shape:
 *
 * - Allowed authenticators: BIOMETRIC_STRONG OR DEVICE_CREDENTIAL.
 *   Setting both lets the OS fall back to the device PIN / pattern /
 *   password if the user has no biometric enrolled or biometrics fail.
 *   That eliminates the need for us to build a custom PIN flow.
 * - The prompt runs over a [FragmentActivity]; Compose call sites pass
 *   `LocalContext.current as FragmentActivity`.
 * - Outcome maps to a [Result] sealed type: Success unlocks the gate;
 *   Failed / Cancelled / Unavailable leave the gate locked and surface
 *   a message the caller can show to the user.
 *
 * What this does NOT do:
 * - Fall back to the master-key password screen on its own. If the
 *   biometric prompt is Unavailable, the caller decides whether to
 *   route to the existing AuthScreen (which is the master-password
 *   path) or to abort the sensitive action.
 */
@Singleton
class BiometricUnlocker @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val gate: SensitiveActionGate,
) {
    /**
     * Quick capability check the UI can use to decide whether to even
     * offer the prompt. Returns [Availability.Available] when the OS
     * can show a prompt that satisfies the [Authenticators.BIOMETRIC_STRONG]
     * OR [Authenticators.DEVICE_CREDENTIAL] set; otherwise an
     * [Availability] variant explaining why.
     */
    fun availability(): Availability {
        val manager = BiometricManager.from(appContext)
        return when (
            manager.canAuthenticate(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            )
        ) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                Availability.SecurityUpdateRequired
            else -> Availability.Unknown
        }
    }

    /**
     * Show the prompt. Suspends until the user authenticates or the
     * prompt closes. Unlocks [SensitiveActionGate] via
     * [SensitiveActionGate.unlockFromUserAuth] on success; the caller
     * does NOT need to do that.
     *
     * Authenticators allowed: BIOMETRIC_STRONG OR DEVICE_CREDENTIAL.
     * The device-credential fallback is what gives us the PIN/PIN/
     * password path automatically on every device with a screen lock.
     *
     * @param activity the host. Must be a FragmentActivity (Compose
     *   call sites do `LocalContext.current as FragmentActivity`).
     * @param title shown above the prompt. Defaults to a neutral
     *   "Confirm it's you" copy.
     * @param subtitle optional clarifier shown under the title. Useful
     *   for explaining what sensitive surface the user is unlocking.
     */
    suspend fun promptForUnlock(
        activity: FragmentActivity,
        title: String = DEFAULT_TITLE,
        subtitle: String? = null,
    ): Result = suspendCancellableCoroutine { continuation ->
        val executor = androidx.core.content.ContextCompat.getMainExecutor(appContext)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                gate.unlockFromUserAuth()
                if (continuation.isActive) continuation.resume(Result.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val outcome = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED,
                    -> Result.Cancelled
                    else -> Result.Failed(errorCode, errString.toString())
                }
                if (continuation.isActive) continuation.resume(outcome)
            }

            override fun onAuthenticationFailed() {
                // Single attempt failed (e.g. wrong finger). The
                // system stays on the prompt and lets the user retry;
                // we don't resume here — only the terminal callbacks
                // (Succeeded / Error) end the suspension.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            )
            .build()

        try {
            prompt.authenticate(info)
        } catch (t: Throwable) {
            // Rare — e.g. activity destroyed mid-call. Treat as
            // Failed so the caller surfaces a generic error.
            if (continuation.isActive) {
                continuation.resume(Result.Failed(-1, t.message ?: "prompt failed"))
            }
        }

        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    enum class Availability {
        Available,
        NoHardware,
        HardwareUnavailable,
        NoneEnrolled,
        SecurityUpdateRequired,
        Unknown,
    }

    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Failed(val errorCode: Int, val message: String) : Result()
    }

    private companion object {
        const val DEFAULT_TITLE = "Confirm it's you"
    }
}
