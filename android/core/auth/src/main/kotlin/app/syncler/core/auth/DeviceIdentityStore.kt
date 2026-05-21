package app.syncler.core.auth

import app.syncler.core.storage.SecurePrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the server-issued `device_id` (UUID) returned by /v1/auth/devices/enroll.
 *
 * Stored so the inbox poll can send `?device_id=…`, which lets the server
 * bump the row's `last_seen` and surface this device as "current" in the
 * Settings list. Cleared on logout alongside the session token.
 */
interface DeviceIdentityStore {
    fun read(): String?
    fun write(deviceId: String)
    fun clear()
}

@Singleton
class SecureDeviceIdentityStore @Inject constructor(
    private val securePrefs: SecurePrefs,
) : DeviceIdentityStore {
    override fun read(): String? = securePrefs.getString(KEY_DEVICE_ID)

    override fun write(deviceId: String) {
        securePrefs.putString(KEY_DEVICE_ID, deviceId)
    }

    override fun clear() {
        securePrefs.remove(KEY_DEVICE_ID)
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
