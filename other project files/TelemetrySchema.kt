package com.navigator.app.ble

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the bike's telemetry datapoint schema (bundled from the official
 * KTM Connect app's assets/CUKT_bCCU.json - "Datapoint master schema
 * ktm-telemetry service in bCCU"). Confirmed byte-exact against
 * com.ktm.mobsdk.prpc / ContractJsonManager / ByteConverterHelperKt (all
 * multi-byte values are little-endian).
 */
data class Datapoint(
    val id: Int,
    val name: String,
    val type: String,
    val unit: String?,
    val sampleRateDefaultMs: Int,
    val sampleRateMinMs: Int,
    val sampleRateMaxMs: Int,
    val telemetryable: Boolean
)

object TelemetrySchema {

    private val typeLengths = mapOf(
        "T_UINT8" to 1, "T_INT8" to 1, "T_CHAR" to 1,
        "T_UINT16" to 2, "T_INT16" to 2,
        "T_UINT32" to 4, "T_INT32" to 4,
        "T_UINT64" to 8, "T_INT64" to 8,
        "T_FLOAT" to 4
    )

    fun typeLength(type: String): Int = typeLengths[type] ?: 0

    fun load(context: Context): List<Datapoint> {
        val json = context.assets.open("bike_signals_contract.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val subjects = root.getJSONArray("subjects")
        val result = mutableListOf<Datapoint>()
        for (i in 0 until subjects.length()) {
            val subject = subjects.getJSONObject(i)
            val datapoints = subject.getJSONArray("datapoints")
            for (j in 0 until datapoints.length()) {
                val dp = datapoints.getJSONObject(j)
                val attributes = dp.optJSONArray("attributes")
                var telemetryable = false
                if (attributes != null) {
                    for (k in 0 until attributes.length()) {
                        if (attributes.getString(k) == "TELEMETRYABLE") telemetryable = true
                    }
                }
                val sampleRates = dp.optJSONObject("sample_rates")
                result.add(
                    Datapoint(
                        id = dp.getInt("id"),
                        name = dp.getString("name"),
                        type = dp.getString("type"),
                        unit = if (dp.has("unit")) dp.getString("unit") else null,
                        sampleRateDefaultMs = sampleRates?.optInt("sample_rate_default", 5000) ?: 5000,
                        sampleRateMinMs = sampleRates?.optInt("sample_rate_min", 200) ?: 200,
                        sampleRateMaxMs = sampleRates?.optInt("sample_rate_max", 60000) ?: 60000,
                        telemetryable = telemetryable
                    )
                )
            }
        }
        return result
    }

    /** Decode a raw little-endian value byte array according to its datapoint type. */
    fun decodeValue(type: String, bytes: ByteArray): String {
        if (bytes.isEmpty()) return "(empty)"
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (type) {
                "T_UINT8" -> (bytes[0].toInt() and 0xFF).toString()
                "T_INT8" -> bytes[0].toInt().toString()
                "T_CHAR" -> bytes[0].toInt().toChar().toString()
                "T_UINT16" -> (buf.short.toInt() and 0xFFFF).toString()
                "T_INT16" -> buf.short.toString()
                "T_UINT32" -> (buf.int.toLong() and 0xFFFFFFFFL).toString()
                "T_INT32" -> buf.int.toString()
                "T_UINT64" -> java.lang.Long.toUnsignedString(buf.long)
                "T_INT64" -> buf.long.toString()
                "T_FLOAT" -> buf.float.toString()
                else -> BccuCrypto.hex(bytes)
            }
        } catch (e: Exception) {
            "decode error (${e.message})"
        }
    }
}
