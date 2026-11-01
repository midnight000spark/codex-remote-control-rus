package com.haochen.codexremote

import org.json.JSONObject

// Tokens are kept in one place keyed by the token-less connection URL, so the
// persisted url and the connection history never carry a secret. A token typed
// into the url field is stored here as soon as the connection is used.
private val TOKEN_QUERY_REGEX = Regex("([?&])token=[^&]*")

internal fun stripTokenFromUrl(url: String): String {
    val trimmed = url.trim()
    if (!trimmed.contains("token=")) return trimmed
    var stripped = trimmed.replace(TOKEN_QUERY_REGEX, "$1")
    stripped = stripped.replace("?&", "?").replace("&&", "&")
    stripped = stripped.replace(Regex("[?&]+$"), "")
    return stripped
}

internal fun appendTokenToUrl(url: String, token: String): String {
    val stripped = stripTokenFromUrl(url)
    val separator = if (stripped.contains("?")) "&" else "?"
    return "$stripped$separator" + "token=${encodeToken(token)}"
}

internal fun MainActivity.loadConnectionTokens() {
    connectionTokens.clear()
    val raw = prefs.getString(KEY_CONNECTION_TOKENS, "")?.trim().orEmpty()
    if (raw.isNotBlank()) {
        try {
            val stored = JSONObject(raw)
            for (key in stored.keys()) {
                val token = stored.optString(key, "").trim()
                val normalizedKey = stripTokenFromUrl(key)
                if (normalizedKey.isNotBlank() && token.isNotBlank()) {
                    connectionTokens[normalizedKey] = token
                }
            }
        } catch (_: Exception) {
            connectionTokens.clear()
        }
    }
    migrateStoredUrlToken()
}

// Older builds kept the token inside the persisted url.
private fun MainActivity.migrateStoredUrlToken() {
    val storedUrl = prefs.getString(KEY_URL, null)?.trim().orEmpty()
    if (storedUrl.isBlank()) return
    val token = extractTokenFromUrl(storedUrl)?.takeIf { it.isNotBlank() }
    val stripped = stripTokenFromUrl(storedUrl)
    if (token != null) {
        connectionTokens[stripped] = token
        persistConnectionTokens()
    }
    if (stripped != storedUrl) {
        prefs.edit().putString(KEY_URL, stripped).apply()
    }
}

internal fun MainActivity.persistConnectionTokens() {
    val encoded = JSONObject()
    connectionTokens.forEach { (url, token) ->
        if (url.isNotBlank() && token.isNotBlank()) {
            try {
                encoded.put(url, token)
            } catch (_: Exception) {
                // A malformed key must not drop the rest of the map.
            }
        }
    }
    prefs.edit().putString(KEY_CONNECTION_TOKENS, encoded.toString()).apply()
}

internal fun MainActivity.rememberConnectionToken(url: String, token: String?) {
    val normalizedToken = token?.trim()?.takeIf { it.isNotBlank() } ?: return
    val key = stripTokenFromUrl(url)
    if (key.isBlank()) return
    if (connectionTokens[key] == normalizedToken) return
    connectionTokens[key] = normalizedToken
    persistConnectionTokens()
}

internal fun MainActivity.storedTokenForUrl(url: String): String? {
    val key = stripTokenFromUrl(url)
    if (key.isBlank()) return null
    connectionTokens[key]?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}

internal fun MainActivity.attachStoredToken(url: String): String {
    if (!extractTokenFromUrl(url).isNullOrBlank()) return url
    val token = storedTokenForUrl(url) ?: lastUsedTokenForUrl(url) ?: return url
    return appendTokenToUrl(url, token)
}

// Fallback for connections that predate the token store: reuse the last used
// token, but only for the same host and port it was saved from.
private fun MainActivity.lastUsedTokenForUrl(url: String): String? {
    val token = prefs.getString(KEY_TOKEN, "")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val lastHost = prefs.getString(KEY_HOST, "")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val lastPort = prefs.getString(KEY_PORT, "8787")?.trim()?.takeIf { it.isNotBlank() } ?: "8787"
    val uri = try {
        java.net.URI.create(url)
    } catch (_: Exception) {
        return null
    }
    val host = uri.host?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val port = if (uri.port >= 0) uri.port.toString() else "8787"
    return token.takeIf { host.equals(lastHost, ignoreCase = true) && port == lastPort }
}

// User-facing text must never print the token.
internal fun displayUrlWithoutToken(url: String): String = stripTokenFromUrl(url)