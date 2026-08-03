package com.haochen.codexremote

import android.content.Intent
import android.net.Uri

private const val PROJECT_GITHUB_URL = "https://github.com/chenhaoc/codex-remote-control"
private const val SUPPORT_ALIPAY_ACCOUNT = "1635273709@qq.com"

internal fun MainActivity.openSettingsPage() {
    currentPage = AppPage.Settings
}

internal fun projectGithubUrl(): String {
    return PROJECT_GITHUB_URL
}

internal fun supportAlipayAccount(): String {
    return SUPPORT_ALIPAY_ACCOUNT
}

internal fun MainActivity.openExternalUrl(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        startActivity(intent)
    }.onFailure {
        showNotice("无法打开链接")
    }
}

internal fun MainActivity.copySupportAccountToClipboard() {
    if (copyTextToClipboard("支付宝账户", supportAlipayAccount())) {
        showNotice("账号已复制")
    } else {
        showNotice("复制失败")
    }
}

internal fun MainActivity.hasNavigableChatTarget(): Boolean {
    return connected && !activeSessionId.isNullOrBlank()
}

internal fun MainActivity.handleTopLevelBack(): Boolean {
    return when (currentPage) {
        AppPage.Settings -> {
            currentPage = if (hasNavigableChatTarget()) AppPage.Chat else AppPage.Connection
            true
        }
        AppPage.Connection -> {
            if (hasNavigableChatTarget()) {
                currentPage = AppPage.Chat
                true
            } else {
                false
            }
        }
        AppPage.Chat -> false
    }
}

internal fun MainActivity.loadSessionHistoryMonths(): Int {
    return prefs.getInt(KEY_SESSION_HISTORY_MONTHS, DEFAULT_SESSION_HISTORY_MONTHS).coerceIn(0, 120)
}

internal fun MainActivity.updateSessionHistoryMonths(months: Int) {
    val normalized = months.coerceIn(0, 120)
    if (sessionHistoryMonths == normalized) return
    sessionHistoryMonths = normalized
    prefs.edit().putInt(KEY_SESSION_HISTORY_MONTHS, normalized).apply()
    if (connected) {
        bootSyncRequested = false
        requestSessionList()
    }
}

internal fun MainActivity.sessionHistoryMonthMenuOptions(): List<SelectionMenuOption> {
    return listOf(1, 3, 6, 12, 0).map { months ->
        SelectionMenuOption(
            value = months.toString(),
            label = if (months == 0) "全部历史" else "最近 $months 个月",
            supporting = if (months == 0) "拉取所有会话的摘要。" else "只拉取最近更新过的会话摘要。",
        )
    }
}

internal fun MainActivity.sessionHistoryMonthLabel(): String {
    return if (sessionHistoryMonths == 0) "全部历史" else "最近 $sessionHistoryMonths 个月"
}

internal fun MainActivity.sessionHistoryMonthDescription(): String {
    return "只限制侧边栏会话列表，不会删除 Bridge 上的旧对话。"
}

internal fun MainActivity.loadRefreshSessionsOnDrawerOpen(): Boolean {
    return prefs.getBoolean(KEY_REFRESH_SESSIONS_ON_DRAWER_OPEN, true)
}

internal fun MainActivity.updateRefreshSessionsOnDrawerOpen(enabled: Boolean) {
    refreshSessionsOnDrawerOpen = enabled
    prefs.edit().putBoolean(KEY_REFRESH_SESSIONS_ON_DRAWER_OPEN, enabled).apply()
}

internal fun MainActivity.loadSessionSyncIntervalSeconds(): Int {
    val value = prefs.getInt(KEY_SESSION_SYNC_INTERVAL_SECONDS, DEFAULT_SESSION_SYNC_INTERVAL_SECONDS)
    return value.coerceIn(1, 60)
}

internal fun MainActivity.updateSessionSyncIntervalSeconds(seconds: Int) {
    val normalized = seconds.coerceIn(1, 60)
    sessionSyncIntervalSeconds = normalized
    prefs.edit().putInt(KEY_SESSION_SYNC_INTERVAL_SECONDS, normalized).apply()
    if (connected && !activeSessionId.isNullOrBlank()) {
        startSessionSyncLoop()
    }
}

internal fun MainActivity.sessionSyncIntervalMs(): Long {
    return sessionSyncIntervalSeconds * 1000L
}

internal fun MainActivity.sessionSyncIntervalMenuOptions(): List<SelectionMenuOption> {
    return listOf(5, 15, 30, 60).map { seconds ->
        SelectionMenuOption(
            value = seconds.toString(),
            label = "${seconds} 秒",
            supporting = if (seconds == 5) "默认频率，响应更及时。" else "降低请求频率，适合稳定查看历史内容。",
        )
    }
}

internal fun MainActivity.sessionSyncIntervalLabel(): String {
    return "${sessionSyncIntervalSeconds} 秒"
}

internal fun MainActivity.sessionSyncIntervalDescription(): String {
    return "当前打开会话的内容轮询间隔。只影响 active session，不影响项目和历史列表刷新。"
}

internal fun MainActivity.loadBridgeConnectTimeoutSeconds(): Int {
    val value = prefs.getInt(KEY_BRIDGE_CONNECT_TIMEOUT_SECONDS, DEFAULT_BRIDGE_CONNECT_TIMEOUT_SECONDS)
    return value.coerceIn(1, 30)
}

internal fun MainActivity.updateBridgeConnectTimeoutSeconds(seconds: Int) {
    val normalized = seconds.coerceIn(1, 30)
    bridgeConnectTimeoutSeconds = normalized
    prefs.edit().putInt(KEY_BRIDGE_CONNECT_TIMEOUT_SECONDS, normalized).apply()
}

internal fun MainActivity.bridgeConnectTimeoutMenuOptions(): List<SelectionMenuOption> {
    return listOf(3, 5, 10, 15).map { seconds ->
        SelectionMenuOption(
            value = seconds.toString(),
            label = "${seconds} 秒",
            supporting =
                if (seconds == DEFAULT_BRIDGE_CONNECT_TIMEOUT_SECONDS) {
                    "默认值，弱网下更快暴露失败。"
                } else {
                    "适合网络更慢或跨网段连接。"
                },
        )
    }
}

internal fun MainActivity.bridgeConnectTimeoutLabel(): String {
    return "${bridgeConnectTimeoutSeconds} 秒"
}

internal fun MainActivity.bridgeConnectTimeoutDescription(): String {
    return "WebSocket 建连超时。超时后会按当前自动重连策略处理。"
}

internal fun MainActivity.loadBridgePingIntervalSeconds(): Int {
    val value = prefs.getInt(KEY_BRIDGE_PING_INTERVAL_SECONDS, DEFAULT_BRIDGE_PING_INTERVAL_SECONDS)
    return value.coerceIn(5, 120)
}

internal fun MainActivity.updateBridgePingIntervalSeconds(seconds: Int) {
    val normalized = seconds.coerceIn(5, 120)
    bridgePingIntervalSeconds = normalized
    prefs.edit().putInt(KEY_BRIDGE_PING_INTERVAL_SECONDS, normalized).apply()
}

internal fun MainActivity.bridgePingIntervalMenuOptions(): List<SelectionMenuOption> {
    return listOf(10, 20, 30, 60).map { seconds ->
        SelectionMenuOption(
            value = seconds.toString(),
            label = "${seconds} 秒",
            supporting =
                if (seconds == DEFAULT_BRIDGE_PING_INTERVAL_SECONDS) {
                    "默认值，兼顾断线发现速度和功耗。"
                } else {
                    "更快发现断线，或更少心跳流量。"
                },
        )
    }
}

internal fun MainActivity.bridgePingIntervalLabel(): String {
    return "${bridgePingIntervalSeconds} 秒"
}

internal fun MainActivity.bridgePingIntervalDescription(): String {
    return "WebSocket 心跳间隔。越短越快发现断线，但会增加心跳流量。"
}

internal fun MainActivity.loadSessionIncrementalSyncEnabled(): Boolean {
    return prefs.getBoolean(KEY_SESSION_INCREMENTAL_SYNC, true)
}

internal fun MainActivity.updateSessionIncrementalSyncEnabled(enabled: Boolean) {
    sessionIncrementalSyncEnabled = enabled
    prefs.edit().putBoolean(KEY_SESSION_INCREMENTAL_SYNC, enabled).apply()
    if (!enabled) {
        sessionSyncCursorReady = false
    }
}

internal fun MainActivity.loadShowMessageTimestamps(): Boolean {
    return prefs.getBoolean(KEY_SHOW_MESSAGE_TIMESTAMPS, false)
}

internal fun MainActivity.updateShowMessageTimestamps(enabled: Boolean) {
    showMessageTimestamps = enabled
    prefs.edit().putBoolean(KEY_SHOW_MESSAGE_TIMESTAMPS, enabled).apply()
}

internal fun MainActivity.loadStartupPagePreference(): AppPage {
    return appPageFromPreference(prefs.getString(KEY_STARTUP_PAGE, null))
}

internal fun MainActivity.updateStartupPagePreference(page: AppPage) {
    startupPagePreference = page
    prefs.edit().putString(KEY_STARTUP_PAGE, page.preferenceValue()).apply()
}

internal fun MainActivity.appVersionLabel(): String {
    return "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_COMMIT_SHORT}"
}

internal fun MainActivity.appBuildTypeLabel(): String {
    return if (BuildConfig.DEBUG) "Debug" else "Release"
}

internal fun MainActivity.appPackageLabel(): String {
    return BuildConfig.APPLICATION_ID
}

internal fun appFeatureSummaries(): List<String> {
    return listOf(
        "连接私有网络中的 Codex Bridge",
        "查看、切换和恢复 Codex 会话",
        "发送消息并流式查看回复",
        "处理审批请求和文件变更",
        "浏览代码文件、diff 和会话变更",
        "备份和恢复连接信息与本地设置",
    )
}

internal fun MainActivity.startupPageMenuOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption(
            value = AppPage.Chat.preferenceValue(),
            label = "聊天",
            supporting = "启动后直接进入当前会话页面。",
        ),
        SelectionMenuOption(
            value = AppPage.Connection.preferenceValue(),
            label = "连接设置",
            supporting = "先看到 bridge 地址和连接状态。",
        ),
        SelectionMenuOption(
            value = AppPage.Settings.preferenceValue(),
            label = "设置",
            supporting = "先进入本地偏好设置页面。",
        ),
    )
}

internal fun MainActivity.autoReconnectAttemptMenuOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption(
            value = "0",
            label = "不限制",
            supporting = "断线后持续重试，间隔逐步拉长。",
        ),
        SelectionMenuOption(
            value = "3",
            label = "3 次",
            supporting = "最多自动重连 3 次。",
        ),
        SelectionMenuOption(
            value = "5",
            label = "5 次",
            supporting = "最多自动重连 5 次。",
        ),
        SelectionMenuOption(
            value = "10",
            label = "10 次",
            supporting = "最多自动重连 10 次。",
        ),
    )
}

internal fun MainActivity.autoReconnectMaxAttemptsLabel(): String {
    return if (autoReconnectMaxAttempts <= 0) "不限制" else "${autoReconnectMaxAttempts} 次"
}

internal fun MainActivity.autoReconnectMaxAttemptsDescription(): String {
    return if (autoReconnectMaxAttempts <= 0) {
        "断线后会一直自动重试，重连间隔会逐步增加。"
    } else {
        "每次断线后最多自动重试 ${autoReconnectMaxAttempts} 次。"
    }
}

internal fun AppPage.preferenceValue(): String {
    return name.lowercase()
}

internal fun appPageFromPreference(value: String?): AppPage {
    return when (value?.trim()?.lowercase()) {
        AppPage.Connection.preferenceValue() -> AppPage.Connection
        AppPage.Settings.preferenceValue() -> AppPage.Settings
        else -> AppPage.Chat
    }
}
