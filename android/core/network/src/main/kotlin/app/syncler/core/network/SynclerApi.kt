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
    ): InboxFeedResponseDto

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

    /**
     * Phase 8 master-key rotation (docs/crypto-spec.md §10.6).
     *
     * Issues a fresh single-use 32-byte challenge bound to the calling
     * device. The /rotate-master-key call below must consume it within
     * ~5 minutes.
     */
    @POST("/v1/account/rotate-master-key/challenge")
    suspend fun rotateMasterKeyChallenge(): RotationChallengeResponseDto

    /**
     * Phase 8 master-key rotation. The server runs the 14-step
     * transaction per §10.8 — returns 200 with the new generation +
     * row versions on success, or one of:
     *   - 401 invalid_password_proof / rotation_challenge_invalid
     *   - 426 account_upgraded_requires_newer_client (mixed-client gate)
     *   - 409 key_generation_mismatch / pairing_set_changed /
     *         pairing_state_changed / state_version_mismatch
     *   - 429 rotation_success_rate_limited / rotate_proof_fail_rate_limited
     */
    @POST("/v1/account/rotate-master-key")
    suspend fun rotateMasterKey(
        @Body body: RotateMasterKeyRequestDto,
    ): Response<RotateMasterKeyResponseDto>

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
    /**
     * Phase 3a. "script" (legacy WebView bundle) or "template" (native
     * Compose renderer). Defaults to "script" when the server returns
     * null (pre-Phase-3a clients hitting an upgraded server keep using
     * the script path).
     */
    val renderer: String = "script",
    /**
     * Phase 3a. The template manifest block when renderer == "template".
     * Null otherwise — the publish-time server validator enforces the
     * pairing so the client doesn't need to defend against malformed
     * combinations here.
     */
    val template: TemplateBlockDto? = null,
    /**
     * Phase 3b: card_type ("event" or "live") and optional card_key_path.
     */
    @Json(name = "card_type") val cardType: String = "event",
    @Json(name = "card_key_path") val cardKeyPath: String? = null,
)

/** Phase 3a: native-renderer manifest. Mirrors `TemplateObject` in `server/app/schemas.py`. */
@JsonClass(generateAdapter = true)
data class TemplateBlockDto(
    val layout: String,
    val fields: Map<String, TemplateFieldDto>,
    val actions: List<TemplateActionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TemplateFieldDto(
    val path: String,
)

@JsonClass(generateAdapter = true)
data class TemplateActionDto(
    val id: String,
    val label: String,
    val endpoint: String,
)

@JsonClass(generateAdapter = true)
data class StateGetResponseDto(
    @Json(name = "state_version") val stateVersion: Int,
    @Json(name = "encrypted_blob") val encryptedBlob: String,
    @Json(name = "updated_at") val updatedAt: String?,
    /**
     * Phase 8 (docs/crypto-spec.md §10.4): the generation the row is
     * encrypted under. Defaults to 1 for pre-Phase-8 server responses
     * and for rows that pre-date the migration's server_default.
     */
    @Json(name = "key_generation") val keyGeneration: Int = 1,
)

@JsonClass(generateAdapter = true)
data class StatePutRequestDto(
    @Json(name = "expected_state_version") val expectedStateVersion: Int,
    @Json(name = "new_encrypted_blob") val newEncryptedBlob: String,
    /**
     * Phase 8 (§10.5 MUST clause): the AAD-bound generation the client
     * encrypted under. Server 409s if it doesn't match the locked
     * users.key_generation. Null is acceptable for legacy callers
     * pre-Phase-8; Phase-8-aware clients always populate.
     */
    @Json(name = "key_generation_observed") val keyGenerationObserved: Int? = null,
)

@JsonClass(generateAdapter = true)
data class StatePutResponseDto(
    @Json(name = "new_state_version") val newStateVersion: Int,
    /** Phase 8: row's stamped generation post-write. */
    @Json(name = "key_generation") val keyGeneration: Int = 1,
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
    // V1.5 automated pairing — the sender registered an X25519
    // bootstrap key with syncler at adoption time. When all four
    // fields are present + bootstrapProtocolVersion == 1, the
    // device can POST an encrypted envelope directly to
    // [senderBrokerUrl] instead of asking the user to copy values
    // by hand. See `docs/integration-guide.md §8.5` + `docs/crypto-spec.md §9`.
    @Json(name = "sender_broker_url") val senderBrokerUrl: String? = null,
    @Json(name = "bootstrap_key") val bootstrapKey: String? = null,
    @Json(name = "bootstrap_key_signature") val bootstrapKeySignature: String? = null,
    @Json(name = "bootstrap_protocol_version") val bootstrapProtocolVersion: Int? = null,
)

@JsonClass(generateAdapter = true)
data class PairingCompleteRequestDto(
    @Json(name = "pairing_token") val pairingToken: String,
    @Json(name = "encrypted_initial_state") val encryptedInitialState: String,
    /**
     * Phase 8 (§10.5): the encrypted_initial_state AAD binds this
     * generation; mismatch → 409 so the client refetches.
     */
    @Json(name = "key_generation_observed") val keyGenerationObserved: Int? = null,
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
data class LiveCardItemDto(
    val id: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "plugin_id") val pluginId: String,
    @Json(name = "plugin_identifier") val pluginIdentifier: String,
    @Json(name = "card_key") val cardKey: String,
    @Json(name = "encrypted_payload") val encryptedPayload: String,
    val nonce: String,
    @Json(name = "sequence_number") val sequenceNumber: Long,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = true)
data class InboxFeedItemDto(
    val type: String, // "event" or "live"
    // Message fields
    val id: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "plugin_id") val pluginId: String,
    @Json(name = "plugin_identifier") val pluginIdentifier: String,
    @Json(name = "min_plugin_version") val minPluginVersion: String? = null,
    @Json(name = "encrypted_body") val encryptedBody: String? = null,
    @Json(name = "envelope_signature") val envelopeSignature: String? = null,
    @Json(name = "sent_at") val sentAt: String? = null,
    // Live card fields
    @Json(name = "card_key") val cardKey: String? = null,
    @Json(name = "encrypted_payload") val encryptedPayload: String? = null,
    @Json(name = "sequence_number") val sequenceNumber: Long? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    // Shared
    val nonce: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = true)
data class InboxFeedResponseDto(
    val items: List<InboxFeedItemDto>,
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
    /**
     * Phase 8 (docs/crypto-spec.md §10.10): the generation the wrapped
     * MK + user-state blob are encrypted under. The client compares
     * this to its locally-persisted high-water mark BEFORE unwrapping
     * the MK (downgrade defense).
     */
    @Json(name = "key_generation") val keyGeneration: Int = 1,
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

// ---------------------------------------------------------------------------
// Phase 8 master-key rotation DTOs (docs/crypto-spec.md §10.6).
// ---------------------------------------------------------------------------

@JsonClass(generateAdapter = true)
data class RotationChallengeResponseDto(
    @Json(name = "rotation_challenge") val rotationChallenge: String,
    @Json(name = "expires_at") val expiresAt: String,
)

/**
 * Rotation request — per-mode field combinations are enforced by the
 * server's pydantic model_validator (see crypto-spec §10.1 reason×field
 * matrix). The client builds the right shape based on the chosen
 * [reason]:
 *   - ``password_rewrap``: send new_auth_salt + new_auth_key_proof;
 *     omit new_encrypted_user_state + pairings.
 *   - ``root_hygiene_rotation``: omit new_auth_salt + new_auth_key_proof;
 *     send new_encrypted_user_state + pairings.
 *   - ``root_compromise_rotation``: send all four.
 */
@JsonClass(generateAdapter = true)
data class RotateMasterKeyRequestDto(
    val reason: String,
    @Json(name = "key_generation_observed") val keyGenerationObserved: Int,
    @Json(name = "rotation_challenge") val rotationChallenge: String,
    @Json(name = "current_password_proof") val currentPasswordProof: String,
    @Json(name = "new_encrypted_master_key") val newEncryptedMasterKey: String,
    @Json(name = "new_auth_salt") val newAuthSalt: String? = null,
    @Json(name = "new_auth_key_proof") val newAuthKeyProof: String? = null,
    @Json(name = "new_encrypted_user_state") val newEncryptedUserState: RotationUserStateBodyDto? = null,
    val pairings: List<RotationPairingEntryDto>? = null,
)

@JsonClass(generateAdapter = true)
data class RotationUserStateBodyDto(
    @Json(name = "encrypted_blob") val encryptedBlob: String,
    @Json(name = "state_version_observed") val stateVersionObserved: Int,
)

@JsonClass(generateAdapter = true)
data class RotationPairingEntryDto(
    @Json(name = "pairing_id") val pairingId: String,
    @Json(name = "state_version_observed") val stateVersionObserved: Int,
    @Json(name = "new_encrypted_state") val newEncryptedState: String,
)

@JsonClass(generateAdapter = true)
data class RotateMasterKeyResponseDto(
    @Json(name = "key_generation") val keyGeneration: Int,
    @Json(name = "encrypted_user_state") val encryptedUserState: RotationUserStateResultDto? = null,
    val pairings: List<RotationPairingResultDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class RotationUserStateResultDto(
    @Json(name = "state_version") val stateVersion: Int,
    @Json(name = "key_generation") val keyGeneration: Int,
)

@JsonClass(generateAdapter = true)
data class RotationPairingResultDto(
    @Json(name = "pairing_id") val pairingId: String,
    @Json(name = "state_version") val stateVersion: Int,
    @Json(name = "key_generation") val keyGeneration: Int,
)

/**
 * 426 / 409 / 429 detail bodies the server emits per spec §10.5 + §10.8.
 * Retrofit error-body parsing routes through these envelope types.
 */
@JsonClass(generateAdapter = true)
data class KeyGenerationMismatchDetailDto(
    val error: String,
    @Json(name = "current_key_generation") val currentKeyGeneration: Int,
    @Json(name = "client_action") val clientAction: String,
)

@JsonClass(generateAdapter = true)
data class KeyGenerationMismatchResponseDto(
    val detail: KeyGenerationMismatchDetailDto,
)

@JsonClass(generateAdapter = true)
data class UpgradeRequiredDetailDto(
    val error: String,
    @Json(name = "minimum_supported_phase") val minimumSupportedPhase: Int,
)

@JsonClass(generateAdapter = true)
data class UpgradeRequiredResponseDto(
    val detail: UpgradeRequiredDetailDto,
)
