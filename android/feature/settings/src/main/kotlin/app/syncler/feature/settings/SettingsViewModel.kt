package app.syncler.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.syncler.core.auth.RotationRepository
import app.syncler.core.auth.WrongCurrentPasswordError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber

/**
 * Phase 8c — Settings flow.
 *
 * The "Change password" entry triggers ``password_rewrap`` via
 * [RotationRepository.rewrapPassword]. The UI displays the
 * backup-or-lose-access warning (spec §10.2 MUST) before
 * accepting the new password.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val rotationRepository: RotationRepository,
) : ViewModel() {

    private val _changePasswordUiState = MutableStateFlow<ChangePasswordUiState>(
        ChangePasswordUiState.Idle,
    )
    val changePasswordUiState: StateFlow<ChangePasswordUiState> =
        _changePasswordUiState.asStateFlow()

    fun changePassword(currentPassword: CharArray, newPassword: CharArray) {
        // Snapshot current state — if a previous call is in-flight,
        // refuse the new one (UI should disable the button while
        // InFlight, but defense in depth).
        if (_changePasswordUiState.value is ChangePasswordUiState.InFlight) {
            Timber.tag(TAG).w("changePassword called while a previous call is in-flight; ignoring")
            currentPassword.fill('\u0000')
            newPassword.fill('\u0000')
            return
        }
        _changePasswordUiState.value = ChangePasswordUiState.InFlight
        viewModelScope.launch {
            val result = try {
                rotationRepository.rewrapPassword(
                    currentPassword = currentPassword,
                    newPassword = newPassword,
                )
            } finally {
                currentPassword.fill('\u0000')
                newPassword.fill('\u0000')
            }
            _changePasswordUiState.value = when {
                result.isSuccess -> ChangePasswordUiState.Success
                else -> {
                    val cause = result.exceptionOrNull()
                    when (cause) {
                        is WrongCurrentPasswordError ->
                            ChangePasswordUiState.Failure(
                                kind = FailureKind.WRONG_CURRENT_PASSWORD,
                                message = "Current password is incorrect.",
                            )
                        is HttpException -> when (cause.code()) {
                            401 -> ChangePasswordUiState.Failure(
                                kind = FailureKind.WRONG_CURRENT_PASSWORD,
                                message = "The server rejected the current password.",
                            )
                            409 -> ChangePasswordUiState.Failure(
                                kind = FailureKind.RETRY,
                                message = "Server state changed (the account was rotated " +
                                    "elsewhere). Log out and back in, then try again.",
                            )
                            426 -> ChangePasswordUiState.Failure(
                                kind = FailureKind.UPGRADE_REQUIRED,
                                message = "This account requires a newer client. " +
                                    "Update Syncler and try again.",
                            )
                            429 -> ChangePasswordUiState.Failure(
                                kind = FailureKind.RATE_LIMITED,
                                message = "Too many recent password changes. Try again later.",
                            )
                            else -> {
                                Timber.tag(TAG).e(cause, "rewrap HTTP ${cause.code()}")
                                ChangePasswordUiState.Failure(
                                    kind = FailureKind.UNKNOWN,
                                    message = "Server returned HTTP ${cause.code()}.",
                                )
                            }
                        }
                        else -> {
                            Timber.tag(TAG).e(cause, "rewrap failed (non-HTTP)")
                            ChangePasswordUiState.Failure(
                                kind = FailureKind.UNKNOWN,
                                message = cause?.message ?: "Unknown error.",
                            )
                        }
                    }
                }
            }
        }
    }

    /** Called after the UI consumed Success or Failure to return to Idle. */
    fun acknowledgeChangePasswordResult() {
        _changePasswordUiState.value = ChangePasswordUiState.Idle
    }

    private companion object {
        const val TAG = "Settings"
    }
}

sealed interface ChangePasswordUiState {
    data object Idle : ChangePasswordUiState
    data object InFlight : ChangePasswordUiState
    data object Success : ChangePasswordUiState
    data class Failure(val kind: FailureKind, val message: String) : ChangePasswordUiState
}

enum class FailureKind {
    WRONG_CURRENT_PASSWORD,
    RATE_LIMITED,
    UPGRADE_REQUIRED,
    RETRY,
    UNKNOWN,
}
