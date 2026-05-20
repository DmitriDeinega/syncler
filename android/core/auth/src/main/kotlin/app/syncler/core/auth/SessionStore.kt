package app.syncler.core.auth

import app.syncler.core.storage.SecurePrefs
import javax.inject.Inject

interface TokenStore {
    fun readToken(): String?
    fun writeToken(token: String)
    fun clearToken()
}

class SessionStore @Inject constructor(
    private val securePrefs: SecurePrefs,
) : TokenStore {
    override fun readToken(): String? = securePrefs.getString(KEY_SESSION_TOKEN)

    override fun writeToken(token: String) {
        securePrefs.putString(KEY_SESSION_TOKEN, token)
    }

    override fun clearToken() {
        securePrefs.remove(KEY_SESSION_TOKEN)
    }

    private companion object {
        const val KEY_SESSION_TOKEN = "session_token"
    }
}
