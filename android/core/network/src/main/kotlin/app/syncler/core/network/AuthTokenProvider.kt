package app.syncler.core.network

interface AuthTokenProvider {
    fun currentToken(): String?
}
