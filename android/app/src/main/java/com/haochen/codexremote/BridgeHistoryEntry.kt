package com.haochen.codexremote

import java.net.URI
import java.util.UUID

internal data class BridgeHistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    val pathLabel: String,
    val maskedToken: String?,
    val maskedUrl: String,
    val lastUsedAt: Long,
    val name: String? = null,
    val bridgeId: String? = null,
) {
    fun displayName(): String = name?.trim()?.takeIf { it.isNotBlank() } ?: title

    fun subtitleLine(): String {
        val parts = mutableListOf<String>()
        if (displayName() != title) parts.add(title)
        parts.add(pathLabel)
        return parts.distinct().joinToString(" · ")
    }

    fun lastUsedLabel(): String {
        return formatBeijingDateTimeLabel(lastUsedAt, fallback = "Только что", pattern = "yyyy-MM-dd HH:mm")
    }

    companion object {
        fun fromUrl(
            url: String,
            lastUsedAt: Long = System.currentTimeMillis(),
            name: String? = null,
            id: String = createId(),
            bridgeId: String? = null,
        ): BridgeHistoryEntry? {
            return try {
                val uri = URI.create(url)
                val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "ws"
                val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
                val port = if (uri.port >= 0) uri.port else 8787
                val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
                val token = (uri.rawQuery ?: "")
                    .split('&')
                    .mapNotNull { segment ->
                        val parts = segment.split('=', limit = 2)
                        if (parts.size == 2 && parts[0] == "token") parts[1] else null
                    }
                    .firstOrNull()
                val maskedToken = token?.let(::maskToken)
                val title = buildString {
                    append(host)
                    append(':')
                    append(port)
                    if (path != "/") {
                        append(path)
                    }
                }
                val maskedUrl = if (token.isNullOrBlank()) {
                    url
                } else {
                    url.replace(token, maskedToken ?: "****")
                }
                BridgeHistoryEntry(
                    id = id.trim().takeIf { it.isNotBlank() } ?: createId(),
                    url = url,
                    title = title,
                    pathLabel = if (path == "/") scheme else "$scheme $path",
                    maskedToken = maskedToken,
                    maskedUrl = maskedUrl,
                    lastUsedAt = lastUsedAt,
                    name = name?.trim()?.takeIf { it.isNotBlank() },
                    bridgeId = bridgeId?.trim()?.takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                null
            }
        }

        fun createId(): String = "conn_${UUID.randomUUID().toString().replace("-", "")}"

        private fun maskToken(token: String): String {
            if (token.isBlank()) return "****"
            if (token.length <= 6) return "****"
            return "${token.take(3)}...${token.takeLast(2)}"
        }
    }
}
