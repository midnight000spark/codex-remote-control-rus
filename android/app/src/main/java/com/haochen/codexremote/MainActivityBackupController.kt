package com.haochen.codexremote

import android.net.Uri
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val BACKUP_VERSION = 1
private const val BACKUP_APP = "codex-remote-control"

private val STRING_BACKUP_KEYS = listOf(
    KEY_URL,
    KEY_CONNECTION_ID,
    KEY_HOST,
    KEY_PORT,
    KEY_TOKEN,
    KEY_CONNECTION_HISTORY,
    KEY_MODEL,
    KEY_CHAT_MODE,
    KEY_CHAT_REASONING_EFFORT,
    KEY_CHAT_REASONING_SUMMARY,
    KEY_CHAT_SANDBOX,
    KEY_STARTUP_PAGE,
    KEY_NOTIFICATION_MODE,
)

private val BOOLEAN_BACKUP_KEYS = listOf(
    KEY_AUTO_RECONNECT,
    KEY_SESSION_INCREMENTAL_SYNC,
)

private val INT_BACKUP_KEYS = listOf(
    KEY_AUTO_RECONNECT_MAX_ATTEMPTS,
    KEY_SESSION_SYNC_INTERVAL_SECONDS,
    KEY_BRIDGE_CONNECT_TIMEOUT_SECONDS,
    KEY_BRIDGE_PING_INTERVAL_SECONDS,
)

internal fun MainActivity.defaultBackupFileName(): String {
    val stamp = formatBeijingDateTimeLabel(
        System.currentTimeMillis(),
        fallback = "backup",
        pattern = "yyyyMMdd-HHmmss",
    )
    return "codex-remote-control-settings-$stamp.json"
}

internal fun MainActivity.exportSettingsBackup(uri: Uri) {
    val content = buildSettingsBackupDocument().toString(2)
    try {
        contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(content.toByteArray(StandardCharsets.UTF_8))
        } ?: throw IllegalStateException("无法打开备份文件")
        showNotice("备份已导出")
    } catch (error: Exception) {
        showNotice("导出失败: ${error.message ?: "无法写入文件"}")
    }
}

internal fun MainActivity.prepareSettingsBackupImport(uri: Uri) {
    try {
        val document = contentResolver.openInputStream(uri)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("无法读取备份文件")
        val backup = parseSettingsBackupDocument(document)
        backupImportPreview = BackupImportPreview(
            document = backup.toString(),
            connectionCount = backup.optJSONArray("connections")?.length() ?: 0,
            hasCurrentConnection = !backup.optJSONObject("currentConnection")
                ?.optString("url", "")
                .isNullOrBlank(),
        )
    } catch (error: Exception) {
        backupImportPreview = null
        showNotice("导入失败: ${error.message ?: "备份文件无效"}")
    }
}

internal fun MainActivity.confirmSettingsBackupImport(preview: BackupImportPreview) {
    try {
        val backup = parseSettingsBackupDocument(preview.document)
        applySettingsBackupDocument(backup)
        backupImportPreview = null
        showNotice("备份已导入")
    } catch (error: Exception) {
        showNotice("导入失败: ${error.message ?: "备份文件无效"}")
    }
}

internal fun MainActivity.cancelSettingsBackupImport() {
    backupImportPreview = null
}

private fun MainActivity.buildSettingsBackupDocument(): JSONObject {
    val currentConnection = JSONObject().apply {
        putStringIfPresent("url", prefs.getString(KEY_URL, null))
        putStringIfPresent("connectionId", prefs.getString(KEY_CONNECTION_ID, null))
        putStringIfPresent("host", prefs.getString(KEY_HOST, null))
        putStringIfPresent("port", prefs.getString(KEY_PORT, null))
        putStringIfPresent("token", prefs.getString(KEY_TOKEN, null))
    }
    val settings = JSONObject().apply {
        putStringIfPresent("model", prefs.getString(KEY_MODEL, null))
        putStringIfPresent("chatMode", prefs.getString(KEY_CHAT_MODE, null))
        putStringIfPresent("chatReasoningEffort", prefs.getString(KEY_CHAT_REASONING_EFFORT, null))
        putStringIfPresent("chatReasoningSummary", prefs.getString(KEY_CHAT_REASONING_SUMMARY, null))
        putStringIfPresent("chatSandbox", prefs.getString(KEY_CHAT_SANDBOX, null))
        put("autoReconnect", prefs.getBoolean(KEY_AUTO_RECONNECT, false))
        put("autoReconnectMaxAttempts", prefs.getInt(KEY_AUTO_RECONNECT_MAX_ATTEMPTS, 0).coerceAtLeast(0))
        putStringIfPresent("startupPage", prefs.getString(KEY_STARTUP_PAGE, null))
        putStringIfPresent("notificationMode", prefs.getString(KEY_NOTIFICATION_MODE, null))
        put(
            "sessionSyncIntervalSeconds",
            prefs.getInt(KEY_SESSION_SYNC_INTERVAL_SECONDS, DEFAULT_SESSION_SYNC_INTERVAL_SECONDS).coerceIn(1, 60),
        )
        put("sessionIncrementalSync", prefs.getBoolean(KEY_SESSION_INCREMENTAL_SYNC, true))
        put(
            "bridgeConnectTimeoutSeconds",
            prefs.getInt(KEY_BRIDGE_CONNECT_TIMEOUT_SECONDS, DEFAULT_BRIDGE_CONNECT_TIMEOUT_SECONDS).coerceIn(1, 30),
        )
        put(
            "bridgePingIntervalSeconds",
            prefs.getInt(KEY_BRIDGE_PING_INTERVAL_SECONDS, DEFAULT_BRIDGE_PING_INTERVAL_SECONDS).coerceIn(5, 120),
        )
    }
    return JSONObject().apply {
        put("app", BACKUP_APP)
        put("version", BACKUP_VERSION)
        put("exportedAt", System.currentTimeMillis())
        put("currentConnection", currentConnection)
        put("connections", buildConnectionHistoryBackupArray())
        put("settings", settings)
    }
}

private fun MainActivity.buildConnectionHistoryBackupArray(): JSONArray {
    val array = JSONArray()
    connectionHistory.take(MAX_CONNECTION_HISTORY).forEach { entry ->
        array.put(
            JSONObject().apply {
                put("id", entry.id)
                put("url", entry.url)
                put("lastUsedAt", entry.lastUsedAt)
                put("name", entry.name.orEmpty())
                put("bridgeId", entry.bridgeId.orEmpty())
            },
        )
    }
    return array
}

private fun parseSettingsBackupDocument(document: String): JSONObject {
    val root = try {
        JSONObject(document)
    } catch (_: JSONException) {
        throw IllegalArgumentException("不是有效的 JSON 文件")
    }
    if (root.optString("app") != BACKUP_APP) {
        throw IllegalArgumentException("不是 CodexRemote 备份")
    }
    if (root.optInt("version", 0) != BACKUP_VERSION) {
        throw IllegalArgumentException("不支持的备份版本")
    }
    root.optJSONObject("settings") ?: throw IllegalArgumentException("备份缺少设置")
    return root
}

private fun MainActivity.applySettingsBackupDocument(root: JSONObject) {
    val currentConnection = root.optJSONObject("currentConnection")
    val settings = root.optJSONObject("settings") ?: JSONObject()
    val connections = root.optJSONArray("connections") ?: JSONArray()
    val editor = prefs.edit()

    clearBackupKeys(editor)
    currentConnection?.let {
        editor.putStringIfPresent(KEY_URL, it.optStringOrNull("url"))
        editor.putStringIfPresent(KEY_CONNECTION_ID, it.optStringOrNull("connectionId"))
        editor.putStringIfPresent(KEY_HOST, it.optStringOrNull("host"))
        editor.putStringIfPresent(KEY_PORT, it.optStringOrNull("port"))
        editor.putStringIfPresent(KEY_TOKEN, it.optStringOrNull("token"))
    }
    editor.putString(KEY_CONNECTION_HISTORY, normalizeBackupConnections(connections, currentConnection).toString())
    editor.putStringIfPresent(KEY_MODEL, settings.optStringOrNull("model"))
    editor.putStringIfPresent(KEY_CHAT_MODE, settings.optStringOrNull("chatMode"))
    editor.putStringIfPresent(KEY_CHAT_REASONING_EFFORT, settings.optStringOrNull("chatReasoningEffort"))
    editor.putStringIfPresent(KEY_CHAT_REASONING_SUMMARY, settings.optStringOrNull("chatReasoningSummary"))
    editor.putStringIfPresent(KEY_CHAT_SANDBOX, settings.optStringOrNull("chatSandbox"))
    editor.putBoolean(KEY_AUTO_RECONNECT, settings.optBoolean("autoReconnect", false))
    editor.putInt(KEY_AUTO_RECONNECT_MAX_ATTEMPTS, settings.optInt("autoReconnectMaxAttempts", 0).coerceAtLeast(0))
    editor.putStringIfPresent(KEY_STARTUP_PAGE, settings.optStringOrNull("startupPage"))
    editor.putStringIfPresent(KEY_NOTIFICATION_MODE, settings.optStringOrNull("notificationMode"))
    editor.putInt(
        KEY_SESSION_SYNC_INTERVAL_SECONDS,
        settings.optInt("sessionSyncIntervalSeconds", DEFAULT_SESSION_SYNC_INTERVAL_SECONDS).coerceIn(1, 60),
    )
    editor.putBoolean(KEY_SESSION_INCREMENTAL_SYNC, settings.optBoolean("sessionIncrementalSync", true))
    editor.putInt(
        KEY_BRIDGE_CONNECT_TIMEOUT_SECONDS,
        settings.optInt("bridgeConnectTimeoutSeconds", DEFAULT_BRIDGE_CONNECT_TIMEOUT_SECONDS).coerceIn(1, 30),
    )
    editor.putInt(
        KEY_BRIDGE_PING_INTERVAL_SECONDS,
        settings.optInt("bridgePingIntervalSeconds", DEFAULT_BRIDGE_PING_INTERVAL_SECONDS).coerceIn(5, 120),
    )
    editor.apply()

    applyBackedUpStateFromPreferences()
}

private fun clearBackupKeys(editor: android.content.SharedPreferences.Editor) {
    STRING_BACKUP_KEYS.forEach(editor::remove)
    BOOLEAN_BACKUP_KEYS.forEach(editor::remove)
    INT_BACKUP_KEYS.forEach(editor::remove)
}

private fun MainActivity.normalizeBackupConnections(
    connections: JSONArray,
    currentConnection: JSONObject?,
): JSONArray {
    val entries = mutableListOf<BridgeHistoryEntry>()
    for (i in 0 until connections.length()) {
        val item = connections.optJSONObject(i) ?: continue
        val url = item.optString("url", "").trim()
        if (url.isBlank()) continue
        BridgeHistoryEntry.fromUrl(
            url = url,
            lastUsedAt = item.optLong("lastUsedAt", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
            name = item.optString("name", "").trim(),
            id = item.optString("id", "").trim().takeIf { it.isNotBlank() } ?: BridgeHistoryEntry.createId(),
            bridgeId = item.optString("bridgeId", "").trim(),
        )?.let(entries::add)
    }
    currentConnection?.let { current ->
        val url = current.optString("url", "").trim()
        if (url.isNotBlank() && entries.none { it.url == url }) {
            BridgeHistoryEntry.fromUrl(
                url = url,
                lastUsedAt = System.currentTimeMillis(),
                id = current.optString("connectionId", "").trim().takeIf { it.isNotBlank() }
                    ?: BridgeHistoryEntry.createId(),
            )?.let(entries::add)
        }
    }
    val normalized = entries
        .sortedByDescending { it.lastUsedAt }
        .distinctBy { it.url }
        .take(MAX_CONNECTION_HISTORY)
    val array = JSONArray()
    normalized.forEach { entry ->
        array.put(
            JSONObject().apply {
                put("id", entry.id)
                put("url", entry.url)
                put("lastUsedAt", entry.lastUsedAt)
                put("name", entry.name.orEmpty())
                put("bridgeId", entry.bridgeId.orEmpty())
            },
        )
    }
    return array
}

private fun MainActivity.applyBackedUpStateFromPreferences() {
    cancelReconnectSchedule(resetAttempt = true)
    if (bridgeClient != null) {
        disconnectBridge(detail = "已导入备份，连接已断开", switchToConnectionPage = false)
    }
    bridgeUrl = loadBridgeUrl()
    replaceConnectionHistory(loadConnectionHistory(bridgeUrl))
    currentConnectionId = loadCurrentConnectionId()
    prefs.edit().apply {
        if (currentConnectionId == null) {
            remove(KEY_CONNECTION_ID)
        } else {
            putString(KEY_CONNECTION_ID, currentConnectionId)
        }
    }.apply()
    persistConnectionHistory()
    selectedModel = prefs.getString(KEY_MODEL, "")?.trim().orEmpty()
    selectedChatMode = chatModeFromPreference(prefs.getString(KEY_CHAT_MODE, null))
    selectedReasoningEffort = prefs.getString(KEY_CHAT_REASONING_EFFORT, "")?.trim().orEmpty()
    selectedReasoningSummary = normalizeReasoningSummary(prefs.getString(KEY_CHAT_REASONING_SUMMARY, "auto"))
    selectedSandbox = normalizeSandboxPreference(prefs.getString(KEY_CHAT_SANDBOX, "workspaceWrite"))
    autoReconnectEnabled = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
    autoReconnectMaxAttempts = loadAutoReconnectMaxAttempts()
    startupPagePreference = loadStartupPagePreference()
    sessionSyncIntervalSeconds = loadSessionSyncIntervalSeconds()
    sessionIncrementalSyncEnabled = loadSessionIncrementalSyncEnabled()
    bridgeConnectTimeoutSeconds = loadBridgeConnectTimeoutSeconds()
    bridgePingIntervalSeconds = loadBridgePingIntervalSeconds()
    notificationMode = loadNotificationMode()
    switchLocalSessionCache(findConnectionHistoryById(currentConnectionId))
    currentBridgeUrl = null
    connectionDetail = if (bridgeUrl.isBlank()) "已导入备份，请配置 Bridge URL" else "已导入备份，点击连接即可"
    currentPage = AppPage.Connection
}

private fun JSONObject.putStringIfPresent(key: String, value: String?) {
    value?.trim()?.takeIf { it.isNotBlank() }?.let { put(key, it) }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    return optString(key, "").trim().takeIf { it.isNotBlank() }
}

private fun android.content.SharedPreferences.Editor.putStringIfPresent(key: String, value: String?) {
    value?.trim()?.takeIf { it.isNotBlank() }?.let { putString(key, it) }
}
