package com.haochen.codexremote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

private const val NOTIFICATION_CHANNEL_ID = "codex_updates"
private const val ACTION_OPEN_NOTIFICATION = "com.haochen.codexremote.OPEN_NOTIFICATION"
private const val EXTRA_NOTIFICATION_SESSION_ID = "notification_session_id"

internal enum class NotificationMode(val preferenceValue: String) {
    Off("off"),
    AnyMessage("any_message"),
    CompletionOnly("completion_only"),
}

internal fun MainActivity.loadNotificationMode(): NotificationMode {
    val value = prefs.getString(KEY_NOTIFICATION_MODE, NotificationMode.Off.preferenceValue)
    return NotificationMode.entries.firstOrNull { it.preferenceValue == value } ?: NotificationMode.Off
}

internal fun MainActivity.updateNotificationMode(mode: NotificationMode) {
    notificationMode = mode
    prefs.edit().putString(KEY_NOTIFICATION_MODE, mode.preferenceValue).apply()
}

internal fun MainActivity.hasNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

internal fun MainActivity.createNotificationChannel() {
    val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        "Сообщение Codex",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "Уведомления об ответах Codex, подтверждениях и завершении задач"
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

internal fun MainActivity.handleNotificationIntent(intent: Intent?) {
    if (intent?.action != ACTION_OPEN_NOTIFICATION) return
    val sessionId = intent.getStringExtra(EXTRA_NOTIFICATION_SESSION_ID)?.trim().orEmpty()
    intent.action = null
    intent.removeExtra(EXTRA_NOTIFICATION_SESSION_ID)
    currentPage = AppPage.Chat
    if (sessionId.isNotBlank()) {
        selectSession(sessionId, syncHistory = connected)
    }
}

internal fun MainActivity.maybeNotifyIncomingEvent(
    eventName: String,
    sessionId: String,
    payload: JSONObject,
) {
    if (sessionId.isBlank()) return
    when (eventName) {
        "item/completed" -> {
            val item = payload.optJSONObject("item") ?: return
            if (item.optString("type", "") == "agentMessage") {
                maybeNotifyAssistantMessage(sessionId, item)
            }
        }
        "turn/completed" -> maybeNotifyTurnCompleted(sessionId, payload)
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "item/permissions/requestApproval",
        "applyPatchApproval",
        "execCommandApproval" -> maybeNotifyApprovalRequest(sessionId, eventName, payload)
    }
}

private fun MainActivity.maybeNotifyAssistantMessage(sessionId: String, item: JSONObject) {
    if (notificationMode != NotificationMode.AnyMessage) return
    val preview = extractThreadItemText(item)
        .replace('\n', ' ')
        .trim()
        .take(240)
        .ifBlank { "Получен новый ответ Codex" }
    postCodexNotification(
        sessionId = sessionId,
        title = notificationSessionTitle(sessionId),
        content = preview,
        category = Notification.CATEGORY_MESSAGE,
    )
}

private fun MainActivity.maybeNotifyTurnCompleted(sessionId: String, payload: JSONObject) {
    if (notificationMode == NotificationMode.Off) return
    val status = firstNonEmpty(
        payload.optString("status", ""),
        payload.optJSONObject("turn")?.optString("status", "").orEmpty(),
    )
    if (notificationMode == NotificationMode.AnyMessage && status !in TURN_FAILURE_STATUSES) return
    val error = payload.optJSONObject("error")
        ?: payload.optJSONObject("turn")?.optJSONObject("error")
    val backendConnectionFailure = isBackendConnectionFailure(error)
    val title = when {
        backendConnectionFailure -> "Не удалось подключиться к Codex"
        status == "failed" -> "Сбой задачи"
        status in TURN_FAILURE_STATUSES -> "Задача прервана"
        else -> "Задача завершена"
    }
    postCodexNotification(
        sessionId = sessionId,
        title = title,
        content = if (backendConnectionFailure) turnErrorDisplayText(error) else notificationSessionTitle(sessionId),
        category = Notification.CATEGORY_STATUS,
    )
}

private fun MainActivity.maybeNotifyApprovalRequest(
    sessionId: String,
    eventName: String,
    payload: JSONObject,
) {
    if (notificationMode != NotificationMode.AnyMessage) return
    val presentation = buildApprovalPresentation(eventName, payload)
    val detail = presentation.detail
        .replace('\n', ' ')
        .trim()
        .take(180)
    val content = listOf(notificationSessionTitle(sessionId), detail)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    postCodexNotification(
        sessionId = sessionId,
        title = presentation.title,
        content = content,
        category = Notification.CATEGORY_REMINDER,
    )
}

private fun MainActivity.postCodexNotification(
    sessionId: String,
    title: String,
    content: String,
    category: String,
) {
    if (!hasNotificationPermission()) return
    val viewingThisChat = activityInForeground &&
        currentPage == AppPage.Chat &&
        activeSessionId == sessionId &&
        codeBrowserState == null &&
        conversationImageViewerState == null
    if (viewingThisChat) return
    val manager = getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return

    val requestCode = sessionId.hashCode() and Int.MAX_VALUE
    val openChatIntent = Intent(this, MainActivity::class.java).apply {
        action = ACTION_OPEN_NOTIFICATION
        putExtra(EXTRA_NOTIFICATION_SESSION_ID, sessionId)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val contentIntent = PendingIntent.getActivity(
        this,
        requestCode,
        openChatIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(title)
        .setContentText(content)
        .setStyle(Notification.BigTextStyle().bigText(content))
        .setCategory(category)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .setColor(0xFF1A8F55.toInt())
        .build()
    manager.notify(requestCode, notification)
}

private val TURN_FAILURE_STATUSES = setOf("failed", "interrupted", "cancelled", "canceled")

private fun MainActivity.notificationSessionTitle(sessionId: String): String {
    return sessions.firstOrNull { it.sessionId == sessionId }
        ?.titleLine()
        ?.take(80)
        ?.ifBlank { null }
        ?: "CodexRemote"
}
