package app.syncler.android.pluginhost

import com.squareup.moshi.Json

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val senderId: String? = null,
    val bundleHash: String,
    val signature: String,
    val declaredCapabilities: List<String> = emptyList(),
    val declaredEndpoints: List<String> = emptyList(),
    val dismissBehavior: String? = null,
    val minPlatformVersion: String? = null,
    @Json(name = "signed_bundle_url")
    val signedBundleUrl: String? = null,
)

fun PluginManifest.bundleUrlOrNull(rawManifest: Map<String, Any?>): String? =
    signedBundleUrl
        ?: rawManifest["signedBundleUrl"] as? String
        ?: rawManifest["signed_bundle_url"] as? String
