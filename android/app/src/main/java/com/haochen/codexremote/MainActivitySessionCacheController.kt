package com.haochen.codexremote

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val SESSION_CACHE_FILE = "session_cache_index.json"
private const val SESSION_CACHE_CONTENT_DIR = "session_cache"
private const val SESSION_CACHE_VERSION = 2

internal fun MainActivity.loadLocalSessionCache() {
    val cacheKey = sessionCacheKeyForCurrentConnection()
    activeSessionCacheKey = cacheKey
    loadLocalSessionCacheForKey(cacheKey)
}

internal fun MainActivity.clearAllSessionCaches() {
    val reloadCacheKey = sessionCacheReloadKey()
    clearVisibleSessionStateAfterCacheReset()
    activeSessionCacheKey = if (connected) reloadCacheKey else sessionCacheKeyForCurrentConnection()
    deleteAllSessionCacheFiles()
    if (connected) {
        showNotice("Весь кэш очищен, перезагружаю")
        requestSessionList()
    } else {
        showNotice("Весь кэш очищен")
    }
}

internal fun MainActivity.clearSessionCacheForConnection(entry: BridgeHistoryEntry) {
    val cacheKey = sessionCacheKeyForConnection(entry)
    deleteSessionCacheEntry(cacheKey)
    if (activeSessionCacheKey == cacheKey) {
        clearVisibleSessionStateAfterCacheReset()
        activeSessionCacheKey = if (connected) sessionCacheReloadKey(cacheKey) else sessionCacheKeyForCurrentConnection()
        if (connected) {
            showNotice("Локальный кэш удалён, перезагружаю")
            requestSessionList()
        } else {
            showNotice("Локальный кэш удалён")
        }
        return
    }
    showNotice("Локальный кэш удалён")
}

internal fun MainActivity.hasLocalSessionCacheForConnection(entry: BridgeHistoryEntry): Boolean {
    val cacheKey = sessionCacheKeyForConnection(entry)
    val cacheDir = File(File(filesDir, SESSION_CACHE_CONTENT_DIR), cacheKey)
    if (cacheDir.exists()) return true
    return readConnectionCache(cacheKey) != null
}

internal fun MainActivity.hasOtherUrlsForBridge(entry: BridgeHistoryEntry): Boolean {
    val bridgeId = entry.bridgeId?.takeIf { it.isNotBlank() } ?: return false
    return connectionHistory.any { it.id != entry.id && it.bridgeId == bridgeId }
}

internal fun MainActivity.switchLocalSessionCache(entry: BridgeHistoryEntry?) {
    val targetCacheKey = sessionCacheKeyForConnection(entry)
    if (activeSessionCacheKey == targetCacheKey) {
        return
    }
    activeSessionCacheKey = targetCacheKey
    stopSessionSyncLoop()
    clearComposerImageAttachments()
    clearConversation()
    sessions.clear()
    sessionContentCache.clear()
    sessionContentCacheSignatures.clear()
    selectedWorkspace = null
    bootSyncRequested = false
    activeSessionId = null
    val cachedSessionId = loadLocalSessionCacheForKey(activeSessionCacheKey, applyContent = false)
    if (cachedSessionId == null) {
        prefs.edit().remove(KEY_SESSION).apply()
    }
}

internal fun MainActivity.persistLocalSessionList() {
    writeSessionCacheRoot(buildUpdatedSessionListCacheRoot())
}

internal fun MainActivity.persistLocalSessionContent(sessionId: String, payload: JSONObject?) {
    if (sessionId.isBlank() || payload == null) return
    val signature = buildSessionContentCacheSignature(payload)
    if (sessionContentCacheSignatures[sessionId] == signature) return
    sessionContentCacheSignatures[sessionId] = signature
    sessionContentCache[sessionId] = payload
    writeSessionContentCache(sessionId, payload)
}

internal fun MainActivity.applyCachedSessionContent(sessionId: String): Boolean {
    val payload = sessionContentCache[sessionId] ?: readSessionContentCache(sessionId)
        ?.also { sessionContentCache[sessionId] = it }
        ?: return false
    return applyCachedSessionPayload(sessionId, payload)
}

/**
 * Shows cached history for a session the user just opened. The payload is read
 * and parsed off the main thread unless it is already in memory, and the result
 * is dropped when a fresher snapshot or another session selection won the race.
 */
internal fun MainActivity.scheduleCachedSessionContentApply(
    sessionId: String,
    onApplied: (Boolean) -> Unit = {},
) {
    sessionContentCache[sessionId]?.let { payload ->
        onApplied(applyCachedSessionPayload(sessionId, payload))
        return
    }
    val cacheKey = activeSessionCacheKey
    val expectedVersion = conversationSnapshotVersion
    val expectedSeq = lastSyncedSeq
    cacheReadExecutor.execute {
        val payload = readSessionContentCacheAt(cacheKey, sessionId)
        mainHandler.post {
            val stillCurrent = payload != null &&
                activeSessionId == sessionId &&
                activeSessionCacheKey == cacheKey &&
                conversationSnapshotVersion == expectedVersion &&
                lastSyncedSeq == expectedSeq
            if (!stillCurrent) {
                onApplied(false)
                return@post
            }
            sessionContentCache[sessionId] = payload!!
            onApplied(applyCachedSessionPayload(sessionId, payload))
        }
    }
}

private fun MainActivity.applyCachedSessionPayload(sessionId: String, payload: JSONObject): Boolean {
    payload.optJSONObject("session")?.let(SessionInfo::fromSession)?.let(::upsertSessionFromCache)
    sessionContentCacheSignatures[sessionId] = buildSessionContentCacheSignature(payload)
    applySessionContentSnapshot(JSONObject(payload.toString()))
    updateSessionSyncCursor(payload)
    updateLiveTurnStatus(null)
    return true
}

internal fun MainActivity.showCachedHistoryAfterConnectionFailure() {
    if (sessions.isEmpty()) return
    val sessionId = activeSessionId?.takeIf { current -> sessions.any { it.sessionId == current } }
        ?: sessions.firstOrNull()?.sessionId
    if (!sessionId.isNullOrBlank()) {
        activeSessionId = sessionId
        prefs.edit().putString(KEY_SESSION, sessionId).apply()
        if (conversationItems.isEmpty()) {
            applyCachedSessionContent(sessionId)
        }
    }
    if (currentPage == AppPage.Connection) {
        currentPage = AppPage.Chat
    }
}

private fun MainActivity.clearVisibleSessionStateAfterCacheReset() {
    stopSessionSyncLoop()
    clearConversation()
    sessions.clear()
    sessionContentCache.clear()
    sessionContentCacheSignatures.clear()
    codeBrowserFileCache.clear()
    codeBrowserRenderCache.clear()
    projectGroupExpanded.clear()
    projectGroupSessionListExpanded.clear()
    sessionInfoSheetState = null
    selectedWorkspace = null
    bootSyncRequested = false
    activeSessionId = null
    prefs.edit().remove(KEY_SESSION).apply()
}

private fun MainActivity.loadLocalSessionCacheForKey(cacheKey: String?): String? {
    return loadLocalSessionCacheForKey(cacheKey, applyContent = true)
}

private fun MainActivity.loadLocalSessionCacheForKey(
    cacheKey: String?,
    applyContent: Boolean,
): String? {
    val connectionCache = readConnectionCache(cacheKey) ?: return null
    replaceSessions(parseCachedSessions(connectionCache.optJSONArray("sessions")))
    sessionContentCache.clear()
    sessionContentCacheSignatures.clear()
    val selectedSessionId = connectionCache.optString("selectedSessionId", "").trim()
    val cachedSessionId = selectedSessionId.takeIf { sessionId -> sessionId.isNotBlank() && sessions.any { it.sessionId == sessionId } }
        ?: activeSessionId?.takeIf { sessionId -> sessions.any { it.sessionId == sessionId } }
        ?: sessions.firstOrNull()?.sessionId
    if (!cachedSessionId.isNullOrBlank()) {
        activeSessionId = cachedSessionId
        prefs.edit().putString(KEY_SESSION, cachedSessionId).apply()
        if (applyContent) {
            applyCachedSessionContent(cachedSessionId)
        }
    }
    return cachedSessionId
}

private fun MainActivity.upsertSessionFromCache(info: SessionInfo) {
    val index = sessions.indexOfFirst { it.sessionId == info.sessionId }
    val merged = info.mergedWith(sessions.firstOrNull { it.sessionId == info.sessionId })
    if (index >= 0) {
        sessions[index] = merged
    } else {
        sessions.add(merged)
    }
}

private fun MainActivity.parseCachedSessions(array: JSONArray?): List<SessionInfo> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(SessionInfo::fromSession)?.let(::add)
        }
    }
}

private fun MainActivity.buildUpdatedSessionListCacheRoot(): JSONObject {
    val root = readSessionCacheRoot() ?: JSONObject()
    val connections = root.optJSONObject("connections") ?: JSONObject()
    val cacheKey = activeSessionCacheKey ?: sessionCacheKeyForCurrentConnection()
    connections.put(cacheKey, buildConnectionSessionListCache())
    return JSONObject().apply {
        put("version", SESSION_CACHE_VERSION)
        put("updatedAt", System.currentTimeMillis())
        put("connections", connections)
    }
}

private fun MainActivity.buildConnectionSessionListCache(): JSONObject {
    return JSONObject().apply {
        put("updatedAt", System.currentTimeMillis())
        activeSessionId?.takeIf { it.isNotBlank() }?.let { put("selectedSessionId", it) }
        put(
            "sessions",
            JSONArray().also { array ->
                sessions.forEach { session -> array.put(session.toJson()) }
            },
        )
    }
}

private fun buildSessionContentCacheSignature(payload: JSONObject): String {
    val session = payload.optJSONObject("session")
    val entries = payload.optJSONArray("entries")
    val approvals = payload.optJSONArray("pending_approvals")
    val tail = buildList {
        if (entries != null) {
            for (index in (entries.length() - 4).coerceAtLeast(0) until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val item = entry.optJSONObject("item")
                add(
                    listOf(
                        entry.optString("type", ""),
                        entry.optString("turn_id", ""),
                        item?.optString("id", "").orEmpty(),
                        item?.optString("type", "").orEmpty(),
                        item?.optString("status", "").orEmpty(),
                        entry.optString("status", ""),
                    ).joinToString(":"),
                )
            }
        }
    }
    return listOf(
        session?.optString("updatedAt", "").orEmpty(),
        entries?.length()?.toString().orEmpty(),
        approvals?.length()?.toString().orEmpty(),
        tail.joinToString("|"),
    ).joinToString("#")
}

private fun MainActivity.readConnectionCache(cacheKey: String?): JSONObject? {
    val root = readSessionCacheRoot() ?: return null
    val key = cacheKey ?: sessionCacheKeyForCurrentConnection()
    return root.optJSONObject("connections")?.optJSONObject(key)
}

private fun MainActivity.readSessionCacheRoot(): JSONObject? {
    sessionCacheRootMemory?.let { return it }
    val file = sessionCacheFile()
    if (!file.exists()) return null
    val loaded = try {
        JSONObject(file.readText()).takeIf { root ->
            root.optInt("version", 0) == SESSION_CACHE_VERSION
        }
    } catch (_: JSONException) {
        null
    } catch (_: Exception) {
        null
    }
    sessionCacheRootMemory = loaded
    return loaded
}

private fun MainActivity.writeSessionCacheRoot(root: JSONObject) {
    sessionCacheRootMemory = root
    sessionCacheWriter.submit(sessionCacheFile()) { root.toString() }
}

private fun MainActivity.deleteAllSessionCacheFiles() {
    sessionCacheRootMemory = null
    sessionCacheWriter.clearPending()
    filesDir.listFiles()
        ?.filter { file ->
            file.name == SESSION_CACHE_FILE ||
                file.name.startsWith(SESSION_CACHE_CONTENT_DIR)
        }
        ?.forEach(::deleteSessionCacheFile)
}

private fun MainActivity.deleteSessionCacheEntry(cacheKey: String) {
    sessionCacheWriter.clearPending()
    val root = readSessionCacheRoot()
    val connections = root?.optJSONObject("connections")
    connections?.remove(cacheKey)
    val updatedRoot =
        if (root != null && connections != null && connections.length() > 0) {
            JSONObject(root.toString()).apply {
                put("connections", connections)
                put("updatedAt", System.currentTimeMillis())
            }
        } else {
            null
        }
    if (updatedRoot == null) {
        deleteSessionCacheFile(sessionCacheFile())
    } else {
        writeSessionCacheRoot(updatedRoot)
    }
    deleteSessionCacheFile(File(File(filesDir, SESSION_CACHE_CONTENT_DIR), cacheKey))
}

private fun MainActivity.deleteSessionCacheFile(file: File) {
    if (!file.exists()) return
    runCatching {
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }
}

private fun MainActivity.sessionCacheReloadKey(fallback: String? = null): String {
    return activeSessionCacheKey?.takeIf { it.isNotBlank() }
        ?: fallback?.takeIf { it.isNotBlank() }
        ?: sessionCacheKeyForCurrentConnection()
}

private fun MainActivity.sessionCacheFile(): File = File(filesDir, SESSION_CACHE_FILE)

private fun MainActivity.readSessionContentCache(sessionId: String): JSONObject? =
    readSessionContentCacheAt(activeSessionCacheKey, sessionId)

private fun MainActivity.readSessionContentCacheAt(cacheKey: String?, sessionId: String): JSONObject? {
    val file = sessionContentCacheFile(cacheKey, sessionId)
    if (!file.exists()) return null
    return try {
        JSONObject(file.readText())
    } catch (_: JSONException) {
        null
    } catch (_: Exception) {
        null
    }
}

private fun MainActivity.writeSessionContentCache(sessionId: String, payload: JSONObject) {
    val file = sessionContentCacheFile(activeSessionCacheKey, sessionId)
    // Serialization runs on the writer thread; payload is not mutated afterwards.
    sessionCacheWriter.submit(file) { payload.toString() }
}

private fun MainActivity.sessionContentCacheFile(cacheKey: String?, sessionId: String): File {
    val resolvedCacheKey = cacheKey ?: sessionCacheKeyForCurrentConnection()
    return File(
        File(File(filesDir, SESSION_CACHE_CONTENT_DIR), resolvedCacheKey),
        "${stableCacheDigest(sessionId)}.json",
    )
}

private fun MainActivity.sessionCacheKeyForCurrentConnection(): String {
    return sessionCacheKeyForConnection(findConnectionHistoryById(currentConnectionId))
}

private fun sessionCacheKeyForConnection(entry: BridgeHistoryEntry?): String {
    val stableId = entry?.bridgeId?.takeIf { it.isNotBlank() }
        ?: entry?.id?.takeIf { it.isNotBlank() }
    if (stableId.isNullOrBlank()) return "bridge_unconfigured"
    return "bridge_${stableCacheDigest(stableId)}"
}

private fun stableCacheDigest(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
