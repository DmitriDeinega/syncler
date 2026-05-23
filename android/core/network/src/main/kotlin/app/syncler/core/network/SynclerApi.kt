package app.syncler.core.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface SynclerApi {
    @POST("/v1/auth/signup")
    suspend fun signup(@Body body: SignupRequest): SignupResponse

    @POST("/v1/auth/pre-login")
    suspend fun preLogin(@Body body: PreLoginRequest): PreLoginResponse

    @POST("/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /**
     * Enroll a device. The Authorization header is set per-call from the
     * caller's bootstrap token rather than from the [AuthTokenProvider]
     * interceptor — the AuthRepository runs enroll BEFORE authenticating
     * the Session so observers don't see a momentarily-unlocked session
     * carrying a user-only bootstrap token (Codex consultation 51 YELLOW
     * #4). The auth interceptor in [NetworkModule] preserves a pre-set
     * Authorization header instead of overriding it.
     */
    @POST("/v1/auth/devices/enroll")
    suspend fun enrollDevice(
        @retrofit2.http.Header("Authorization") authHeader: String,
        @Body body: DeviceEnrollRequest,
    ): DeviceEnrollResponse

    @GET("/v1/auth/devices")
    suspend fun listDevices(): List<DeviceItem>

    @POST("/v1/auth/devices/{id}/revoke")
    suspend fun revokeDevice(@Path("id") id: String): Response<Unit>

    @DELETE("/v1/account")
    suspend fun deleteAccount(): Response<Unit>

    @GET("/v1/messages/{id}")
    suspend fun getMessage(@Path("id") id: String): MessageInboxItemDto

    @GET("/v1/messages/inbox")
    suspend fun inbox(
        @retrofit2.http.Query("since") since: String? = null,
    ): MessageInboxResponseDto

    @POST("/v1/messages/{id}/dismiss")
    suspend fun dismissMessage(
        @Path("id") id: String,
    ): Response<Unit>

    @GET("/v1/pairing/preview")
    suspend fun previewPairing(@retrofit2.http.Query("token") token: String): PairingPreviewResponseDto

    @POST("/v1/pairing/complete")
    suspend fun completePairing(@Body body: PairingCompleteRequestDto): PairingCompleteResponseDto

    @POST("/v1/pairing/{id}/revoke")
    suspend fun revokePairing(@Path("id") id: String): Response<Unit>

    @GET("/v1/pairing")
    suspend fun listPairings(): List<PairingItemDto>

    @GET("/v1/state")
    suspend fun getUserState(): StateGetResponseDto

    @retrofit2.http.PUT("/v1/state")
    suspend fun putUserState(@Body body: StatePutRequestDto): Response<StatePutResponseDto>

    @GET("/v1/plugins/{sender_id}/{plugin_identifier}/latest")
    suspend fun getPluginLatest(
        @Path("sender_id") senderId: String,
        @Path("plugin_identifier") pluginIdentifier: String,
    ): PluginLatestDto

    /**
     * Historical lookup by exact plugin row UUID. Returns active OR
     * revoked rows so the device can render an old message against the
     * exact bundle it was validated against, with the revocation reason
     * if it's been pulled since. Use this — not `getPluginLatest` —
     * when resolving the manifest for an inbox message.
     */
    @GET("/v1/plugins/by-id/{plugin_row_id}")
    suspend fun getPluginById(
        @Path("plugin_row_id") pluginRowId: String,
    ): PluginLatestDto
}

@JsonClass(generateAdapter = true)
data class PluginLatestDto(
    @Json(name = "plugin_row_id") val pluginRowId: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "plugin_identifier") val pluginIdentifier: String,
    val version: String,
    @Json(name = "signed_bundle_url") val signedBundleUrl: String,
    @Json(name = "manifest_hash") val manifestHash: String,
    @Json(name = "bundle_hash") val bundleHash: String,
    val signature: String,
    val capabilities: List<String>,
    val endpoints: List<String>,
    @Json(name = "created_at") val createdAt: String,
    /** Non-null when the row has been revoked (server omits these on /latest). */
    @Json(name = "revoked_at") val revokedAt: String? = null,
    /**
     * One of: ``superseded``, ``compromised``, ``sender_disabled``,
     * ``unspecified``. Drives client UX (refuse-to-execute, neutral
     * banner, etc.). Null on active rows or pre-M11.4 revoked rows.
     */
    @Json(name = "revocation_reason") val revocationReason: String? = null,
)

@JsonClass(generateAdapter = true)
data class StateGetResponseDto(
    @Json(name = "state_version") val stateVersion: Int,
    @Json(name = "encrypted_blob") val encryptedBlob: String,
    @Json(name = "updated_at") val updatedAt: String?,
)

@JsonClass(generateAdapter = true)
data class StatePutRequestDto(
    @Json(name = "expected_state_version") val expectedStateVersion: Int,
    @Json(name = "new_encrypted_blob") val newEncryptedBlob: String,
)

@JsonClass(generateAdapter = true)
data class StatePutResponseDto(
    @Json(name = "new_state_version") val newStateVersion: Int,
)

@JsonClass(generateAdapter = true)
data class StateConflictBodyDto(
    @Json(name = "current_state_version") val currentStateVersion: Int,
    @Json(name = "current_encrypted_blob") val currentEncryptedBlob: String,
)

/**
 * FastAPI nests HTTPException(detail=...) under a top-level ``detail`` key,
 * so the actual 409 body shape is ``{"detail": {…}}``. Retrofit error-body
 * parsing must go through this envelope to reach the conflict fields.
 */
@JsonClass(generateAdapter = true)
data class StateConflictResponseDto(
    val detail: StateConflictBodyDto,
)

@JsonClass(generateAdapter = true)
data class PairingPreviewResponseDto(
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "sender_name") val senderName: String,
    @Json(name = "sender_public_key") val senderPublicKey: String,
    @Json(name = "sender_public_key_fingerprint") val senderPublicKeyFingerprint: String,
    @Json(name = "sender_name_hash") val senderNameHash: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = true)
data class PairingCompleteRequestDto(
    @Json(name = "pairing_token") val pairingToken: String,
    @Json(name = "encrypted_initial_state") val encryptedInitialState: String,
)

@JsonClass(generateAdapter = true)
data class PairingCompleteResponseDto(
    @Json(name = "pairing_id") val pairingId: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "sender_name") val senderName: String,
    @Json(name = "sender_public_key") val senderPublicKey: String,
    @Json(name = "sender_public_key_fingerprint") val senderPublicKeyFingerprint: String,
    @Json(name = "sender_name_hash") val senderNameHash: String,
    @Json(name = "paired_at") val pairedAt: String,
)

@JsonClass(generateAdapter = true)
data class PairingItemDto(
    val id: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "created_at") val createdAt: String?,
    @Json(name = "revoked_at") val revokedAt: String?,
)

@JsonClass(generateAdapter = true)
data class MessageInboxItemDto(
    val id: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "plugin_id") val pluginId: String,
    @Json(name = "plugin_identifier") val pluginIdentifier: String,
    @Json(name = "min_plugin_version") val minPluginVersion: String?,
    @Json(name = "encrypted_body") val encryptedBody: String,
    val nonce: String,
    @Json(name = "envelope_signature") val envelopeSignature: String,
    @Json(name = "sent_at") val sentAt: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = true)
data class MessageInboxResponseDto(
    val messages: List<MessageInboxItemDto>,
    @Json(name = "next_since") val nextSince: String?,
)

@JsonClass(generateAdapter = true)
data class SignupRequest(
    val email: String,
    @Json(name = "auth_key_hash") val authKeyHash: String,
    @Json(name = "encrypted_master_key") val encryptedMasterKey: String,
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "argon2_params_version") val argon2ParamsVersion: Int,
)

@JsonClass(generateAdapter = true)
data class SignupResponse(
    @Json(name = "user_id") val userId: String,
    @Json(name = "created_at") val createdAt: String,
)

@JsonClass(generateAdapter = true)
data class PreLoginRequest(
    val email: String,
)

@JsonClass(generateAdapter = true)
data class PreLoginResponse(
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "argon2_params_version") val argon2ParamsVersion: Int,
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val email: String,
    @Json(name = "auth_key_hash") val authKeyHash: String,
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    @Json(name = "user_id") val userId: String,
    @Json(name = "session_token") val sessionToken: String,
    @Json(name = "encrypted_master_key") val encryptedMasterKey: String,
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "argon2_params_version") val argon2ParamsVersion: Int,
)

@JsonClass(generateAdapter = true)
data class DeviceEnrollRequest(
    @Json(name = "public_key") val publicKey: String,
    @Json(name = "fcm_token") val fcmToken: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeviceEnrollResponse(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "created_at") val createdAt: String,
    // Device-bound JWT. Replaces the bootstrap token from /v1/auth/login so
    // subsequent requests to sensitive endpoints carry the `did` claim.
    @Json(name = "session_token") val sessionToken: String,
)

@JsonClass(generateAdapter = true)
data class DeviceItem(
    val id: String,
    @Json(name = "created_at") val createdAt: String?,
    @Json(name = "last_seen") val lastSeen: String?,
    @Json(name = "revoked_at") val revokedAt: String?,
    @Json(name = "has_fcm_token") val hasFcmToken: Boolean,
)
