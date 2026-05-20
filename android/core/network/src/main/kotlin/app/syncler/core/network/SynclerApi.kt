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

    @POST("/v1/auth/devices/enroll")
    suspend fun enrollDevice(@Body body: DeviceEnrollRequest): DeviceEnrollResponse

    @GET("/v1/auth/devices")
    suspend fun listDevices(): List<DeviceItem>

    @POST("/v1/auth/devices/{id}/revoke")
    suspend fun revokeDevice(@Path("id") id: String): Response<Unit>

    @DELETE("/v1/account")
    suspend fun deleteAccount(): Response<Unit>

    @GET("/v1/messages/{id}")
    suspend fun getMessage(@Path("id") id: String): MessageInboxItemDto

    @GET("/v1/messages/inbox")
    suspend fun inbox(@retrofit2.http.Query("since") since: String? = null): MessageInboxResponseDto

    @POST("/v1/messages/{id}/dismiss")
    suspend fun dismissMessage(
        @Path("id") id: String,
        @retrofit2.http.Query("device_id") deviceId: String,
    ): Response<Unit>

    @GET("/v1/pairing/preview")
    suspend fun previewPairing(@retrofit2.http.Query("token") token: String): PairingPreviewResponseDto

    @POST("/v1/pairing/complete")
    suspend fun completePairing(@Body body: PairingCompleteRequestDto): PairingCompleteResponseDto

    @POST("/v1/pairing/{id}/revoke")
    suspend fun revokePairing(@Path("id") id: String): Response<Unit>

    @GET("/v1/pairing")
    suspend fun listPairings(): List<PairingItemDto>
}

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
)

@JsonClass(generateAdapter = true)
data class DeviceItem(
    val id: String,
    @Json(name = "created_at") val createdAt: String?,
    @Json(name = "last_seen") val lastSeen: String?,
    @Json(name = "revoked_at") val revokedAt: String?,
    @Json(name = "has_fcm_token") val hasFcmToken: Boolean,
)
