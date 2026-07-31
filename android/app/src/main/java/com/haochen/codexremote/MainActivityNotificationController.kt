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
        "Codex 消息",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "Codex 回复和任务完成提醒"
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

internal fun MainActivity.maybeNotifyAssistantMessage(item: JSONObject) {
    if (notificationMode != NotificationMode.AnyMessage) return
    val sessionId = activeSessionId?.takeIf { it.isNotBlank() } ?: return
    val preview = extractThreadItemText(item)
        .replace('\n', ' ')
        .trim()
        .take(240)
        .ifBlank { "收到新的 Codex 回复" }
    postCodexNotification(
        sessionId = sessionId,
        title = notificationSessionTitle(sessionId),
        content = preview,
        category = Notification.CATEGORY_MESSAGE,
    )
}

internal fun MainActivity.maybeNotifyTurnCompleted(payload: JSONObject) {
    if (notificationMode != NotificationMode.CompletionOnly) return
    val sessionId = activeSessionId?.takeIf { it.isNotBlank() } ?: return
    val status = firstNonEmpty(
        payload.optString("status", ""),
        payload.optJSONObject("turn")?.optString("status", "").orEmpty(),
    )
    val title = when (status) {
        "failed" -> "任务失败"
        "interrupted", "cancelled", "canceled" -> "任务已中断"
        else -> "任务已完成"
    }
    postCodexNotification(
        sessionId = sessionId,
        title = title,
        content = notificationSessionTitle(sessionId),
        category = Notification.CATEGORY_STATUS,
    )
}

private fun MainActivity.postCodexNotification(
    sessionId: String,
    title: String,
    content: String,
    category: String,
) {
    if (!hasNotificationPermission()) return
    if (activityInForeground && currentPage == AppPage.Chat) return
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

private fun MainActivity.notificationSessionTitle(sessionId: String): String {
    return sessions.firstOrNull { it.sessionId == sessionId }
        ?.titleLine()
        ?.take(80)
        ?.ifBlank { null }
        ?: "CodexRemote"
}
