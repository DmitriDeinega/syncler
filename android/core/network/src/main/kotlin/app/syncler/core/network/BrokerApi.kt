package app.syncler.core.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Retrofit interface for the V1.5 automated-pairing broker POST.
 *
 * Uses [Url] so the call site supplies the full broker URL (which is
 * sender-controlled, not part of the syncler server). The injected
 * OkHttp client (qualified with [BrokerOkHttp]) has NO auth or logging
 * interceptors so the user's bearer token + URL log lines never leak
 * to a third-party host.
 *
 * Wire shape: `docs/crypto-spec.md §9` step 7. JSON keys are snake_case
 * on the wire to match the Python-side server reference implementation
 * byte-for-byte.
 */
interface BrokerApi {
    @POST
    suspend fun postBootstrapEnvelope(
        @Url brokerUrl: String,
        @Body envelope: BootstrapEnvelopeDto,
    ): Response<Unit>
}

/**
 * Wire-format DTO for the bootstrap envelope POST. Property names are
 * `camelCase` for idiomatic Kotlin; `@Json(name = ...)` enforces
 * `snake_case` keys on the JSON wire so the Python-side
 * [sdk-python/syncler/broker/app.py](../../../../../../../../sdk-python/syncler/broker/app.py)
 * sees exactly the keys it expects.
 *
 * `aadJson` is intentionally NOT on the wire — the broker reconstructs
 * AAD from its trusted state (sender_broker_url) plus envelope fields
 * (consultation 87).
 */
@JsonClass(generateAdapter = true)
data class BootstrapEnvelopeDto(
    @Json(name = "protocol_version") val protocolVersion: Int,
    @Json(name = "pairing_id") val pairingId: String,
    @Json(name = "sender_id") val senderId: String,
    @Json(name = "bootstrap_key_id") val bootstrapKeyId: String,
    @Json(name = "exp") val exp: String,
    @Json(name = "ephemeral_pubkey") val ephemeralPubkey: String,
    @Json(name = "nonce") val nonce: String,
    @Json(name = "ciphertext") val ciphertext: String,
)
