package app.syncler.core.pluginaidl

import android.os.Parcel
import android.os.Parcelable

/**
 * Host-allocated session-keyed plugin load payload (Phase 10a v4).
 *
 * The host's `PluginRegistry` allocates [sandboxToken] BEFORE staging
 * the bundle. The token flows verbatim into the sandbox via this
 * parcel and is adopted as the AIDL routing key for the lifecycle of
 * the load. Successive loads of the same [pluginId] get DIFFERENT
 * tokens — Phase 10's generation fence.
 *
 * Grant authority lives in [declaredCapabilities] + [declaredEndpoints]
 * — the host parses the signed manifest once with the existing
 * [app.syncler.android.pluginhost.PluginManifest] validator, then
 * ships the normalized fields here. [diagnosticManifestJson] is
 * carried for telemetry only and MUST NOT be consulted for grant
 * decisions (see docs/plugin-host-multi-process.md "Manifest grant
 * source"). The sandbox-side load path enforces a 64 KB cap on it
 * via [DIAGNOSTIC_MANIFEST_BYTES_CAP] and surfaces
 * `diagnostic_field_oversize` if exceeded.
 *
 * Stable-shape Parcelable: any field additions go at the end with
 * versioned read/write so old apk/sandbox combinations don't crash
 * (V0.1 doesn't actually need this, but it's free to set up).
 */
data class PluginLoadParcel(
    val sandboxToken: Int,
    val pluginId: String,
    val pluginIdentifier: String,
    val version: String,
    val renderer: String,
    val bundleFilePath: String,
    val bundleHashHex: String,
    val declaredCapabilities: List<String>,
    val declaredEndpoints: List<String>,
    val dismissBehavior: String,
    val timeoutMillis: Long,
    val diagnosticManifestJson: String,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(WIRE_VERSION)
        dest.writeInt(sandboxToken)
        dest.writeString(pluginId)
        dest.writeString(pluginIdentifier)
        dest.writeString(version)
        dest.writeString(renderer)
        dest.writeString(bundleFilePath)
        dest.writeString(bundleHashHex)
        dest.writeStringList(declaredCapabilities)
        dest.writeStringList(declaredEndpoints)
        dest.writeString(dismissBehavior)
        dest.writeLong(timeoutMillis)
        // Enforce the 64 KB cap on the wire too. Senders SHOULD have
        // already truncated; we double-check so a buggy host can't
        // blow the Binder transaction.
        val safe = if (diagnosticManifestJson.length > DIAGNOSTIC_MANIFEST_BYTES_CAP) {
            diagnosticManifestJson.substring(0, DIAGNOSTIC_MANIFEST_BYTES_CAP)
        } else {
            diagnosticManifestJson
        }
        dest.writeString(safe)
    }

    override fun describeContents(): Int = 0

    companion object {
        /**
         * Wire format version. Bump only when reordering / removing
         * fields; new fields go at the end with a length-prefixed
         * appendix so old readers can ignore them.
         */
        const val WIRE_VERSION = 1

        /**
         * 64 KB cap per Phase 10a v3 IPC payload-caps decision. Binder
         * transaction limit is ~1 MB; this leaves room for the other
         * fields + the AIDL framing.
         */
        const val DIAGNOSTIC_MANIFEST_BYTES_CAP = 64 * 1024

        @JvmField
        val CREATOR: Parcelable.Creator<PluginLoadParcel> =
            object : Parcelable.Creator<PluginLoadParcel> {
                override fun createFromParcel(source: Parcel): PluginLoadParcel {
                    val version = source.readInt()
                    require(version == WIRE_VERSION) {
                        "PluginLoadParcel wire version $version not supported by reader $WIRE_VERSION"
                    }
                    return PluginLoadParcel(
                        sandboxToken = source.readInt(),
                        pluginId = source.readString().orEmpty(),
                        pluginIdentifier = source.readString().orEmpty(),
                        version = source.readString().orEmpty(),
                        renderer = source.readString().orEmpty(),
                        bundleFilePath = source.readString().orEmpty(),
                        bundleHashHex = source.readString().orEmpty(),
                        declaredCapabilities = mutableListOf<String>().also(source::readStringList).toList(),
                        declaredEndpoints = mutableListOf<String>().also(source::readStringList).toList(),
                        dismissBehavior = source.readString().orEmpty(),
                        timeoutMillis = source.readLong(),
                        diagnosticManifestJson = source.readString().orEmpty(),
                    )
                }

                override fun newArray(size: Int): Array<PluginLoadParcel?> = arrayOfNulls(size)
            }
    }
}
