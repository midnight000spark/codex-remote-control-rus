package com.haochen.codexremote

import android.content.ClipData
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

internal fun ClipData.firstText(): String? {
    for (i in 0 until itemCount) {
        val text = getItemAt(i).text?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) {
            val line = text.lineSequence().firstOrNull { it.startsWith("ws://") || it.startsWith("wss://") }
            return line ?: text.lineSequence().firstOrNull()?.trim()
        }
    }
    return null
}

internal fun cloneJsonValue(value: Any?): Any? {
    return when (value) {
        null -> null
        is JSONObject -> JSONObject(value.toString())
        is JSONArray -> JSONArray(value.toString())
        else -> value
    }
}

internal fun encodeToken(token: String): String = URLEncoder.encode(token, "UTF-8")

internal fun formatBeijingDateTimeLabel(
    epochMillis: Long,
    fallback: String = "刚刚",
    pattern: String = "MM-dd HH:mm",
): String {
    if (epochMillis <= 0L) return fallback
    return runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern(pattern))
    }.getOrDefault(fallback)
}

internal fun JSONObject.optTimestampMillis(key: String): Long? {
    val value = opt(key)
    if (value == null || value == JSONObject.NULL) return null
    val numeric = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
    if (numeric != null && numeric.isFinite() && numeric > 0) {
        return if (numeric < 100_000_000_000L) {
            (numeric * 1000).toLong()
        } else {
            numeric.toLong()
        }
    }
    return (value as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}
