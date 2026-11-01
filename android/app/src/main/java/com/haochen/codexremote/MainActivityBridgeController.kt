package com.haochen.codexremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun MainActivity.toggleConnection() {
        if (bridgeClient?.isOpen() == true) {
            disconnectBridge(detail = "Соединение закрыто", switchToConnectionPage = true)
            return
        }

        val url = try {
            buildBridgeUrl()
        } catch (error: Exception) {
            connectionDetail = "Недействительный адрес Bridge: ${error.message}"
            showNotice(connectionDetail)
            return
        }

        connectToBridge(url)
    }

internal fun MainActivity.openConnectionPage() {
        currentPage = AppPage.Connection
    }

internal fun MainActivity.handleConnectionStatusClick() {
        if (connected) {
            openConnectionPage()
            return
        }

        retryCurrentConnection()
    }

internal fun MainActivity.retryCurrentConnection() {
        val entry = findConnectionHistoryById(currentConnectionId)
        val url = entry?.url?.takeIf { it.isNotBlank() }
            ?: currentBridgeUrl?.takeIf { it.isNotBlank() }
            ?: bridgeUrl.trim().takeIf { it.isNotBlank() }
        if (url == null) {
            openConnectionPage()
            connectionDetail = "Сначала заполните Bridge URL"
            showNotice(connectionDetail)
            return
        }
        if (bridgeClient != null && bridgeClient?.isOpen() != true) {
            disconnectRequested = true
            bridgeClient?.close()
            bridgeClient = null
            connected = false
        }
        connectToBridge(url, connectionId = entry?.id ?: currentConnectionId)
    }

internal fun MainActivity.disconnectBridge(
        detail: String,
        switchToConnectionPage: Boolean,
    ) {
        stopSessionSyncLoop()
        cancelReconnectSchedule(resetAttempt = true)
        mainHandler.removeCallbacks(bridgeHelloTimeoutRunnable)
        disconnectRequested = true
        bridgeClient?.close()
        bridgeClient = null
        connected = false
        activeTurnId = null
        interruptingTurnId = null
        activeThreadGoal = null
        sessionContentDirty = false
        assistantDeltaBuffers.clear()
        pendingApprovals.clear()
        clearPendingRequests()
        currentBridgeUrl = null
        selectedWorkspace = null
        projectDirectoryPickerState = null
        if (switchToConnectionPage) {
            currentPage = AppPage.Connection
        }
        connectionDetail = detail
    }

internal fun MainActivity.connectToBridge(
        rawUrl: String,
        isAutoReconnect: Boolean = false,
        connectionId: String? = null,
    ) {
        // Persisted urls and history entries are token-less; re-attach the
        // token that belongs to this connection.
        val url = attachStoredToken(rawUrl)
        // A URL restored from prefs or history can be malformed; never let that
        // reach URI.create and crash the activity.
        val bridgeUri = try {
            URI.create(url)
        } catch (error: IllegalArgumentException) {
            bridgeClient = null
            currentBridgeUrl = null
            connected = false
            connectionDetail = "Некорректный адрес моста: ${error.message.orEmpty()}"
            showNotice(connectionDetail)
            return
        }
        val targetEntry = resolveConnectionHistory(url = url, connectionId = connectionId)
        val targetConnectionId = targetEntry?.id ?: connectionId?.trim()?.takeIf { it.isNotBlank() }
        if (bridgeClient?.isOpen() == true && currentBridgeUrl == url) {
            currentConnectionId = targetConnectionId ?: currentConnectionId
            currentPage = AppPage.Chat
            connectionDetail = "Подключено к ${connectionDisplayName(url, currentConnectionId)}"
            return
        }

        if (bridgeClient != null && currentBridgeUrl == url) {
            currentConnectionId = targetConnectionId ?: currentConnectionId
            connectionDetail =
                if (isAutoReconnect || reconnectAttempt > 0) {
                    "Автопереподключение: ${displayUrlWithoutToken(url)}"
                } else {
                    "Подключение: ${displayUrlWithoutToken(url)}"
                }
            return
        }

        if (bridgeClient != null) {
            disconnectBridge(detail = "Переключаю подключение…", switchToConnectionPage = false)
        }

        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(bridgeHelloTimeoutRunnable)
        reconnectScheduled = false
        if (!isAutoReconnect) {
            reconnectAttempt = 0
        }
        saveBridgeSettings(url, targetConnectionId)
        // The field and prefs keep the token-less form; the token is re-attached
        // on every connect.
        bridgeUrl = stripTokenFromUrl(url)
        currentConnectionId = targetConnectionId
        switchLocalSessionCache(targetEntry)
        disconnectRequested = false
        bootSyncRequested = false
        connected = false
        currentBridgeUrl = url
        connectionDetail =
            if (isAutoReconnect || reconnectAttempt > 0) {
                "Автопереподключение${reconnectAttempt.takeIf { it > 0 }?.let { "($it)" }.orEmpty()}: ${displayUrlWithoutToken(url)}"
            } else {
                "Подключение: ${displayUrlWithoutToken(url)}"
            }

        // Bumped on every connect and disconnect so frames parsed for a dead
        // connection are dropped instead of mutating live state.
        bridgeConnectionGeneration += 1
        val connectionGeneration = bridgeConnectionGeneration
        bridgeClient = BridgeClient(
            bridgeUri,
            object : BridgeClient.Listener {
                override fun onOpen() {
                    mainHandler.post {
                        connected = false
                        connectionDetail = "Соединение установлено, ожидание рукопожатия Bridge"
                        mainHandler.removeCallbacks(bridgeHelloTimeoutRunnable)
                        mainHandler.postDelayed(
                            bridgeHelloTimeoutRunnable,
                            bridgeConnectTimeoutSeconds.coerceAtLeast(1) * 1000L,
                        )
                    }
                }

                override fun onText(text: String) {
                    try {
                        protocolParseExecutor.execute {
                            val message = parseBridgeMessage(text)
                            mainHandler.post {
                                if (connectionGeneration != bridgeConnectionGeneration) return@post
                                if (message == null) {
                                    appendSystemNote(
                                        "Получено сообщение, не являющееся JSON: " +
                                            text.take(INVALID_FRAME_PREVIEW_CHARS),
                                    )
                                } else {
                                    handleIncomingMessage(message)
                                }
                            }
                        }
                    } catch (error: java.util.concurrent.RejectedExecutionException) {
                        // The activity is shutting down; drop the frame.
                    }
                }

                override fun onDisconnected(code: Int, reason: String, error: Throwable?) {
                    mainHandler.post {
                        bridgeConnectionGeneration += 1
                        connected = false
                        mainHandler.removeCallbacks(bridgeHelloTimeoutRunnable)
                        activeTurnId = null
                        interruptingTurnId = null
                        activeThreadGoal = null
                        sessionContentDirty = false
                        assistantDeltaBuffers.clear()
                        pendingApprovals.clear()
                        clearPendingRequests()
                        stopSessionSyncLoop()
                        bridgeClient = null
                        val normalizedReason = normalizeBridgeDisconnectReason(code, reason, error)
                        val entry = findConnectionHistoryById(currentConnectionId)
                        val targetUrl = entry?.url?.takeIf { it.isNotBlank() }
                            ?: currentBridgeUrl?.takeIf { it.isNotBlank() }
                            ?: bridgeUrl.trim().takeIf { it.isNotBlank() }
                        if (normalizedReason == "token неверный или истёк") {
                            currentBridgeUrl = null
                            connectionDetail = normalizedReason
                            currentPage = AppPage.Connection
                            showNotice(connectionDetail)
                        } else if (shouldScheduleReconnect(targetUrl)) {
                            scheduleReconnect(
                                url = targetUrl.orEmpty(),
                                reason = normalizedReason,
                                error = error,
                            )
                        } else {
                            currentBridgeUrl = null
                            if (sessions.isEmpty()) {
                                selectedWorkspace = null
                                currentPage = AppPage.Connection
                            } else {
                                showCachedHistoryAfterConnectionFailure()
                            }
                            connectionDetail = buildReconnectExhaustedDetail(normalizedReason)
                            // This is the one-shot event of the outage: retries
                            // are over (or were never scheduled), so say it once.
                            showNotice(connectionDetail)
                        }
                        disconnectRequested = false
                    }
                }
            },
            bridgeConnectTimeoutSeconds,
            bridgePingIntervalSeconds,
        )

        try {
            bridgeClient?.connect()
        } catch (error: RuntimeException) {
            bridgeClient = null
            val entry = findConnectionHistoryById(currentConnectionId)
            val targetUrl = entry?.url?.takeIf { it.isNotBlank() } ?: url
            if (shouldScheduleReconnect(targetUrl)) {
                scheduleReconnect(url = targetUrl, reason = "Не удалось подключиться", error = error)
            } else {
                currentBridgeUrl = null
                showCachedHistoryAfterConnectionFailure()
                connectionDetail = buildReconnectExhaustedDetail(normalizeBridgeDisconnectReason(-1, "Не удалось подключиться", error))
                showNotice(connectionDetail)
            }
        }
    }

internal fun MainActivity.applyHistoryConnection(entry: BridgeHistoryEntry) {
        cancelReconnectSchedule(resetAttempt = true)
        mainHandler.removeCallbacks(bridgeHelloTimeoutRunnable)
        currentBridgeUrl = null
        currentConnectionId = entry.id
        bridgeUrl = entry.url
        saveBridgeSettings(entry.url, entry.id)
        switchLocalSessionCache(entry)
        connectionDetail = "Заполнено ${entry.displayName()}, достаточно нажать «Подключиться»"
    }

internal fun MainActivity.connectToHistory(entry: BridgeHistoryEntry) {
        currentConnectionId = entry.id
        bridgeUrl = entry.url
        switchLocalSessionCache(entry)
        connectToBridge(entry.url, connectionId = entry.id)
    }

internal fun MainActivity.openEditConnectionDialog(entry: BridgeHistoryEntry) {
        connectionEditState =
            ConnectionEditDialogState(
                connectionId = entry.id,
                currentName = entry.name.orEmpty(),
                currentUrl = entry.url,
            )
    }

internal fun MainActivity.requestRemoveConnection(entry: BridgeHistoryEntry) {
        if (hasOtherUrlsForBridge(entry) || !hasLocalSessionCacheForConnection(entry)) {
            removeConnectionHistory(entry.id)
            showNotice("Подключение удалено")
            return
        }
        connectionRemovalState =
            ConnectionRemovalDialogState(
                connectionId = entry.id,
                displayName = entry.displayName(),
                maskedUrl = entry.maskedUrl,
            )
    }

internal fun MainActivity.confirmRemoveConnection(
        connectionId: String,
        deleteCache: Boolean,
    ) {
        val entry = findConnectionHistoryById(connectionId)
        removeConnectionHistory(connectionId)
        if (deleteCache && entry != null) {
            clearSessionCacheForConnection(entry)
        } else {
            showNotice("Подключение удалено")
        }
        connectionRemovalState = null
    }


internal fun MainActivity.buildBridgeUrl(): String {
        val raw = bridgeUrl.trim()
        if (raw.isEmpty()) {
            throw IllegalArgumentException("Bridge URL не может быть пустым")
        }

        val normalized = normalizeBridgeUrl(raw, requireToken = true)
        val uri = URI.create(normalized)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("URL host не может быть пустым")
        val port = if (uri.port >= 0) uri.port else 8787
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery
        val tokenFromUrl = query?.let { extractTokenFromQuery(it) }
        val tokenFromConnection = storedTokenForUrl("${uri.scheme}://$host:$port$path")
        val tokenFromPrefs = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()
        val token = tokenFromUrl ?: tokenFromConnection ?: tokenFromPrefs
        if (token.isBlank()) {
            throw IllegalArgumentException("В URL нужен token, например ?token=...")
        }

        val base = "${uri.scheme}://$host:$port$path"
        val finalQuery = when {
            query.isNullOrBlank() -> "token=${encodeToken(token)}"
            query.contains("token=") -> query
            else -> "$query&token=${encodeToken(token)}"
        }
        return "$base?$finalQuery"
    }

internal fun MainActivity.saveBridgeSettings(url: String, connectionId: String? = currentConnectionId) {
        val token = extractTokenFromUrl(url)
        val uri = URI.create(url)
        rememberConnectionToken(url, token)
        val editor = prefs.edit()
            .putString(KEY_URL, stripTokenFromUrl(url))
            .putString(KEY_SESSION, activeSessionId)
        val normalizedConnectionId = connectionId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedConnectionId == null) {
            editor.remove(KEY_CONNECTION_ID)
        } else {
            editor.putString(KEY_CONNECTION_ID, normalizedConnectionId)
        }
        editor.apply()
        if (!token.isNullOrBlank()) {
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
        if (uri.host != null) {
            prefs.edit().putString(KEY_HOST, uri.host).apply()
        }
        if (uri.port >= 0) {
            prefs.edit().putString(KEY_PORT, uri.port.toString()).apply()
        }
    }

internal fun MainActivity.rememberConnectionHistory(url: String, bridgeId: String? = null): BridgeHistoryEntry? {
        val normalizedBridgeId = bridgeId?.trim()?.takeIf { it.isNotBlank() }
        // History keeps the token-less url; the token lives in the token store.
        rememberConnectionToken(url, extractTokenFromUrl(url))
        val historyUrl = stripTokenFromUrl(url)
        val existing = resolveConnectionHistory(url = historyUrl, connectionId = currentConnectionId, bridgeId = normalizedBridgeId)
        val currentEntry = findConnectionHistoryById(currentConnectionId)
        val reusedConnectionId =
            currentConnectionId?.trim()?.takeIf { it.isNotBlank() && (currentEntry == null || currentEntry.url == historyUrl) }
        val inheritedName =
            existing?.name
                ?: normalizedBridgeId?.let { id ->
                    connectionHistory.firstOrNull { it.bridgeId == id && !it.name.isNullOrBlank() }?.name
                }
        val entry = BridgeHistoryEntry.fromUrl(
            url = historyUrl,
            lastUsedAt = System.currentTimeMillis(),
            name = inheritedName,
            id = existing?.id ?: reusedConnectionId ?: BridgeHistoryEntry.createId(),
            bridgeId = normalizedBridgeId ?: existing?.bridgeId ?: currentEntry?.bridgeId,
        ) ?: return null
        replaceConnectionHistory(
            listOf(entry) + connectionHistory.filterNot { it.conflictsWith(entry) },
        )
        currentConnectionId = entry.id
        saveBridgeSettings(url, entry.id)
        persistConnectionHistory()
        return entry
    }

internal fun MainActivity.removeConnectionHistory(connectionId: String) {
        replaceConnectionHistory(connectionHistory.filterNot { it.id == connectionId })
        if (currentConnectionId == connectionId) {
            val fallbackUrl = currentBridgeUrl?.takeIf { it.isNotBlank() } ?: bridgeUrl.trim().takeIf { it.isNotBlank() }
            currentConnectionId = fallbackUrl?.let(::resolveConnectionHistory)?.id
            val editor = prefs.edit()
            if (currentConnectionId == null) {
                editor.remove(KEY_CONNECTION_ID)
            } else {
                editor.putString(KEY_CONNECTION_ID, currentConnectionId)
            }
            editor.apply()
        }
        persistConnectionHistory()
    }

internal fun MainActivity.updateConnectionHistory(
        connectionId: String,
        name: String,
        url: String,
    ): BridgeHistoryEntry? {
        val existing = findConnectionHistoryById(connectionId) ?: return null
        // Normalize first so a pasted token is stored under the canonical
        // token-less key of this connection.
        val normalizedWithToken = normalizeBridgeUrl(url, requireToken = false)
        rememberConnectionToken(normalizedWithToken, extractTokenFromUrl(normalizedWithToken))
        val normalizedUrl = stripTokenFromUrl(normalizedWithToken)
        val updated = BridgeHistoryEntry.fromUrl(
            url = normalizedUrl,
            lastUsedAt = existing.lastUsedAt,
            name = name,
            id = existing.id,
            bridgeId = existing.bridgeId,
        ) ?: return null
        replaceConnectionHistory(
            listOf(updated) + connectionHistory.filterNot { it.conflictsWith(updated) },
        )
        if (currentConnectionId == connectionId || bridgeUrl.trim() == existing.url.trim()) {
            currentConnectionId = updated.id
            bridgeUrl = updated.url
            saveBridgeSettings(updated.url, updated.id)
            switchLocalSessionCache(updated)
        }
        persistConnectionHistory()
        return updated
    }

internal fun MainActivity.replaceConnectionHistory(entries: List<BridgeHistoryEntry>) {
        val normalized = entries
            .sortedByDescending { it.lastUsedAt }
            .distinctBy { entry -> entry.url }
            .take(MAX_CONNECTION_HISTORY)
        connectionHistory.clear()
        connectionHistory.addAll(normalized)
    }

internal fun MainActivity.loadConnectionHistory(fallbackUrl: String): List<BridgeHistoryEntry> {
        val raw = prefs.getString(KEY_CONNECTION_HISTORY, "")?.trim().orEmpty()
        val parsed = mutableListOf<BridgeHistoryEntry>()
        if (raw.isNotBlank()) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url", "").trim()
                    if (url.isBlank()) continue
                    // Older builds stored the token inside the entry url.
                    rememberConnectionToken(url, extractTokenFromUrl(url))
                    BridgeHistoryEntry.fromUrl(
                        url = stripTokenFromUrl(url),
                        lastUsedAt = item.optLong("lastUsedAt", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
                        name = item.optString("name", "").trim(),
                        id = item.optString("id", "").trim().takeIf { it.isNotBlank() } ?: BridgeHistoryEntry.createId(),
                        bridgeId = item.optString("bridgeId", "").trim(),
                    )?.let(parsed::add)
                }
            } catch (_: JSONException) {
                parsed.clear()
            }
        }
        if (parsed.isNotEmpty()) {
            return parsed
        }
        return fallbackUrl.takeIf { it.isNotBlank() }
            ?.let { BridgeHistoryEntry.fromUrl(stripTokenFromUrl(it), System.currentTimeMillis()) }
            ?.let(::listOf)
            .orEmpty()
    }

internal fun MainActivity.persistConnectionHistory() {
        val array = JSONArray()
        connectionHistory.take(MAX_CONNECTION_HISTORY).forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("url", stripTokenFromUrl(entry.url))
                    put("lastUsedAt", entry.lastUsedAt)
                    put("name", entry.name.orEmpty())
                    put("bridgeId", entry.bridgeId.orEmpty())
                },
            )
        }
        prefs.edit().putString(KEY_CONNECTION_HISTORY, array.toString()).apply()
    }

private fun BridgeHistoryEntry.conflictsWith(other: BridgeHistoryEntry): Boolean {
        return id == other.id ||
            url == other.url
    }

internal fun MainActivity.loadCurrentConnectionId(): String? {
        val saved = prefs.getString(KEY_CONNECTION_ID, null)?.trim()?.takeIf { it.isNotBlank() }
        saved
            ?.let { id -> connectionHistory.firstOrNull { it.id == id } }
            ?.takeIf { entry -> bridgeUrl.isNotBlank() && entry.url == bridgeUrl }
            ?.let { return it.id }
        return connectionHistory.firstOrNull { it.url == bridgeUrl }?.id
    }

internal fun MainActivity.findConnectionHistoryById(connectionId: String?): BridgeHistoryEntry? {
        val normalized = connectionId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return connectionHistory.firstOrNull { it.id == normalized }
    }

internal fun MainActivity.resolveConnectionHistory(
        url: String,
        connectionId: String? = null,
        bridgeId: String? = null,
    ): BridgeHistoryEntry? {
        val normalizedUrl = stripTokenFromUrl(url)
        val normalizedBridgeId = bridgeId?.trim()?.takeIf { it.isNotBlank() }
        findConnectionHistoryById(connectionId)
            ?.takeIf { entry -> normalizedUrl.isBlank() || entry.url == normalizedUrl }
            ?.let { return it }
        if (normalizedUrl.isNotBlank()) {
            connectionHistory.firstOrNull { it.url == normalizedUrl }?.let { return it }
        }
        if (normalizedUrl.isBlank() && normalizedBridgeId != null) {
            connectionHistory.firstOrNull { it.bridgeId == normalizedBridgeId }?.let { return it }
        }
        return null
    }

internal fun MainActivity.connectionDisplayName(url: String, connectionId: String?): String {
        return findConnectionHistoryById(connectionId)?.displayName()
            ?: connectionHistory.firstOrNull { it.url == stripTokenFromUrl(url) }?.displayName()
            ?: BridgeHistoryEntry.fromUrl(url)?.displayName()
            ?: url
    }

internal fun MainActivity.extractTokenFromUrl(url: String): String? {
        return try {
            extractTokenFromQuery(URI.create(url).rawQuery ?: "")
        } catch (_: Exception) {
            null
    }
}

internal fun MainActivity.normalizeBridgeDisconnectReason(
        code: Int,
        reason: String,
        error: Throwable? = null,
    ): String {
        val rawReason = reason.trim()
        val rawError = error?.message?.trim().orEmpty()
        val combined = listOf(rawReason, rawError)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()
        return when {
            code == 1008 || rawReason.equals("unauthorized", ignoreCase = true) -> "token неверный или истёк"
            combined.contains("ping") || combined.contains("pong") -> "Тайм-аут пульса, соединение разорвано"
            combined.contains("timeout") || combined.contains("timed out") -> "Время ожидания подключения истекло"
            rawReason.equals("Не удалось подключиться", ignoreCase = true) && rawError.isNotBlank() -> "Не удалось подключиться: $rawError"
            rawReason.isNotBlank() && rawError.isNotBlank() && !rawReason.contains(rawError) -> "$rawReason: $rawError"
            rawReason.isNotBlank() -> rawReason
            rawError.isNotBlank() -> "Не удалось подключиться: $rawError"
            else -> "Соединение разорвано"
        }
    }

internal fun MainActivity.extractTokenFromQuery(query: String): String? {
        if (query.isBlank()) return null
        return query.split('&')
            .mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2 && parts[0] == "token") parts[1] else null
            }
            .firstOrNull()
    }

internal fun MainActivity.pasteBridgeUrlFromClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.firstText()?.trim()?.takeIf { it.isNotBlank() }
    }

internal fun MainActivity.copyTextToClipboard(label: String, text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }

internal fun MainActivity.pasteBridgeUrl() {
        cancelReconnectSchedule(resetAttempt = true)
        mainHandler.removeCallbacks(bridgeHelloTimeoutRunnable)
        currentBridgeUrl = null
        val pasted = pasteBridgeUrlFromClipboard()
        if (pasted.isNullOrBlank()) {
            connectionDetail = "В буфере обмена нет адреса для вставки"
            return
        }
        bridgeUrl = try {
            normalizeBridgeUrl(pasted, requireToken = false)
        } catch (_: Exception) {
            pasted
        }
        currentConnectionId = resolveConnectionHistory(bridgeUrl)?.id
        connectionDetail = "Bridge URL вставлен, нажмите «Подключиться»"
    }

internal fun MainActivity.ensureConnected(): Boolean {
        if (bridgeClient?.isOpen() != true) {
            connectionDetail =
                if (autoReconnectEnabled && currentBridgeUrl != null) {
                    "Соединение разорвано, выполняется автопереподключение"
                } else {
                    "Сначала подключитесь к Linux Bridge"
                }
            showNotice(connectionDetail)
            return false
        }
        return true
    }

internal fun MainActivity.sendRequest(type: String, payload: JSONObject, handler: ResponseHandler? = null): Boolean {
        if (!ensureConnected()) return false

        val id = "req_${UUID.randomUUID().toString().replace("-", "")}"
        val request = JSONObject()
        try {
            request.put("id", id)
            request.put("type", type)
            request.put("payload", payload)
        } catch (error: JSONException) {
            appendSystemNote("Не удалось составить запрос: ${error.message}")
            return false
        }

        if (handler != null) {
            registerPendingRequest(id, handler)
        }
        return try {
            bridgeClient?.sendText(request.toString())
            true
        } catch (error: IOException) {
            takePendingRequest(id)
            appendSystemNote("Не удалось отправить: ${error.message}")
            showNotice("Не удалось отправить: ${error.message}")
            false
        }
    }

// A response that never arrives used to keep its handler (and anything it
// captured) alive for the rest of the session, so every request is armed with
// a timeout that drops it.
internal fun MainActivity.registerPendingRequest(id: String, handler: ResponseHandler) {
        pendingRequests[id] = handler
        val timeout = Runnable {
            pendingRequestTimeouts.remove(id)
            if (pendingRequests.remove(id) != null) {
                syncDebugLog { "request timeout id=$id" }
            }
        }
        pendingRequestTimeouts[id] = timeout
        mainHandler.postDelayed(timeout, PENDING_REQUEST_TIMEOUT_MS)
    }

internal fun MainActivity.takePendingRequest(id: String): ResponseHandler? {
        pendingRequestTimeouts.remove(id)?.let(mainHandler::removeCallbacks)
        return pendingRequests.remove(id)
    }

internal fun MainActivity.clearPendingRequests() {
        pendingRequestTimeouts.values.forEach(mainHandler::removeCallbacks)
        pendingRequestTimeouts.clear()
        pendingRequests.clear()
    }

internal fun MainActivity.loadBridgeUrl(): String {
        prefs.getString(KEY_URL, "")?.trim()?.takeIf { it.isNotBlank() }?.let {
            // An unparsable persisted URL must not be handed to the connector,
            // and the token stays out of the field: it is re-attached on connect.
            return try {
                stripTokenFromUrl(normalizeBridgeUrl(it, requireToken = false))
            } catch (_: Exception) {
                ""
            }
        }

        val host = prefs.getString(KEY_HOST, "")?.trim().orEmpty()
        val port = prefs.getString(KEY_PORT, "8787")?.trim().orEmpty()
        if (host.isBlank()) return ""
        return try {
            stripTokenFromUrl(
                normalizeBridgeUrl("ws://$host:${if (port.isBlank()) "8787" else port}/", requireToken = false),
            )
        } catch (_: Exception) {
            ""
        }
    }

internal fun MainActivity.updateAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
        if (!enabled) {
            cancelReconnectSchedule(resetAttempt = true)
        }
    }

internal fun MainActivity.loadAutoReconnectMaxAttempts(): Int {
        return prefs.getInt(KEY_AUTO_RECONNECT_MAX_ATTEMPTS, 0).coerceAtLeast(0)
    }

internal fun MainActivity.updateAutoReconnectMaxAttempts(maxAttempts: Int) {
        autoReconnectMaxAttempts = maxAttempts.coerceAtLeast(0)
        prefs.edit().putInt(KEY_AUTO_RECONNECT_MAX_ATTEMPTS, autoReconnectMaxAttempts).apply()
        if (reconnectScheduled && !canScheduleReconnectAttempt(reconnectAttempt)) {
            cancelReconnectSchedule(resetAttempt = true)
            currentBridgeUrl = null
            selectedWorkspace = null
            currentPage = AppPage.Connection
            connectionDetail = "Попытки переподключения исчерпаны"
            showNotice(connectionDetail)
        }
    }

internal fun MainActivity.resumeBridgeConnectionIfNeeded() {
        if (!autoReconnectEnabled || connected || bridgeClient != null) return
        val entry = findConnectionHistoryById(currentConnectionId)
        val targetUrl = entry?.url?.takeIf { it.isNotBlank() }
            ?: bridgeUrl.trim().takeIf { it.isNotBlank() }
            ?: return
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        mainHandler.post {
            if (!connected && bridgeClient == null && autoReconnectEnabled) {
                connectToBridge(targetUrl, isAutoReconnect = true, connectionId = entry?.id ?: currentConnectionId)
            }
        }
    }

internal fun MainActivity.scheduleReconnect(
        url: String,
        reason: String,
        error: Throwable?,
    ) {
        if (!autoReconnectEnabled) return
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return
        if (connected || bridgeClient != null || reconnectScheduled) return
        resolveConnectionHistory(normalizedUrl, currentConnectionId)?.let { currentConnectionId = it.id }
        reconnectAttempt += 1
        val delayMs = reconnectDelayMillis(reconnectAttempt)
        reconnectScheduled = true
        currentBridgeUrl = normalizedUrl
        val errorSuffix = describeThrowable(error)
        val detailReason =
            if (errorSuffix.isNotBlank() && reason.contains(error?.message.orEmpty(), ignoreCase = true)) {
                reason
            } else {
                reason + errorSuffix
            }
        connectionDetail = buildString {
            append(detailReason.ifBlank { "Соединение разорвано" })
            append(",")
            append((delayMs / 1000L).coerceAtLeast(1L))
            append(" сек до автопереподключения")
        }
        // One toast per outage: retrying silently keeps the status dot and the
        // connection page text as the persistent indicator, instead of a toast
        // every 1-30 seconds while the bridge is down.
        if (reconnectAttempt <= 1) {
            showNotice(connectionDetail)
        }
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

internal fun MainActivity.cancelReconnectSchedule(resetAttempt: Boolean) {
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        if (resetAttempt) {
            reconnectAttempt = 0
        }
    }

internal fun MainActivity.reconnectDelayMillis(attempt: Int): Long {
        // Exponential backoff, 1s doubling to a 30s cap, with +/-30% jitter so
        // several clients do not reconnect in lockstep after a bridge restart.
        val exponent = (attempt - 1).coerceIn(0, 5)
        val base = (1_000L shl exponent).coerceAtMost(RECONNECT_DELAY_CAP_MS)
        val jittered = base * (1.0 + Random.nextDouble(-RECONNECT_JITTER_RATIO, RECONNECT_JITTER_RATIO))
        return jittered.toLong().coerceIn(500L, RECONNECT_DELAY_CAP_MS)
    }

internal fun MainActivity.shouldScheduleReconnect(targetUrl: String?): Boolean {
        if (!autoReconnectEnabled || targetUrl.isNullOrBlank()) return false
        return canScheduleReconnectAttempt(reconnectAttempt + 1)
    }

internal fun MainActivity.canScheduleReconnectAttempt(attempt: Int): Boolean {
        return autoReconnectMaxAttempts <= 0 || attempt <= autoReconnectMaxAttempts
    }

internal fun MainActivity.buildReconnectExhaustedDetail(baseDetail: String): String {
        if (autoReconnectEnabled && autoReconnectMaxAttempts > 0 && reconnectAttempt >= autoReconnectMaxAttempts) {
            return "Попытки переподключения исчерпаны"
        }
        return baseDetail
    }

internal fun MainActivity.normalizeBridgeUrl(input: String, requireToken: Boolean): String {
        val raw = input.trim()
        if (raw.isBlank()) {
            if (requireToken) throw IllegalArgumentException("Bridge URL не может быть пустым")
            return ""
        }

        val scheme = if (raw.contains("wss://")) "wss" else "ws"
        val withoutScheme = raw.replace(Regex("^(?:wss?://)+"), "")
        val beforeQuery = withoutScheme.substringBefore("?")
        val query = withoutScheme.substringAfter("?", "")
        val slashIndex = beforeQuery.indexOf('/')
        val authority = if (slashIndex >= 0) beforeQuery.take(slashIndex) else beforeQuery
        val path = if (slashIndex >= 0) beforeQuery.substring(slashIndex).ifBlank { "/" } else "/"
        val authorityParts = authority.split(':').filter { it.isNotBlank() }
        val host = authorityParts.firstOrNull()?.trim()
            ?: throw IllegalArgumentException("URL host не может быть пустым")
        val port = authorityParts.drop(1).firstOrNull { part -> part.all { it.isDigit() } }
            ?: prefs.getString(KEY_PORT, "8787")?.trim()?.takeIf { it.isNotBlank() }
            ?: "8787"
        val token = extractTokenFromQuery(query).orEmpty()
            .ifBlank { storedTokenForUrl("$scheme://$host:$port$path").orEmpty() }
            .ifBlank { prefs.getString(KEY_TOKEN, "")?.trim().orEmpty() }

        if (requireToken && token.isBlank()) {
            throw IllegalArgumentException("В URL нужен token, например ?token=...")
        }

        val queryWithoutToken = query
            .split('&')
            .filter { it.isNotBlank() && !it.startsWith("token=") }
            .joinToString("&")
        val finalQuery = listOfNotNull(
            queryWithoutToken.takeIf { it.isNotBlank() },
            token.takeIf { it.isNotBlank() }?.let { "token=${encodeToken(it)}" },
        ).joinToString("&")

        return buildString {
            append("$scheme://$host:$port")
            append(path.ifBlank { "/" })
            if (finalQuery.isNotBlank()) {
                append('?')
                append(finalQuery)
            }
        }
    }
