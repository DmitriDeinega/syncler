package app.syncler.android.pluginhost.capabilities

import app.syncler.android.pluginhost.JsonEscaping
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

internal object JsonBridgeCodec {
    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    fun objectFrom(json: String): Map<String, Any?> = mapAdapter.fromJson(json).orEmpty()

    fun toJson(value: Map<String, Any?>): String = mapAdapter.toJson(value)

    fun error(reason: String): String = """{"error":${JsonEscaping.quote(reason)}}"""
}
