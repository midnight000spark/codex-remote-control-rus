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
        showNotice("Не удалось открыть ссылку")
    }
}

internal fun MainActivity.copySupportAccountToClipboard() {
    if (copyTextToClipboard("Счёт Alipay", supportAlipayAccount())) {
        showNotice("Аккаунт скопирован")
    } else {
        showNotice("Не удалось скопировать")
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
            label = if (months == 0) "Вся история" else "Последние $months мес.",
            supporting = if (months == 0) "Загружать сводки всех сессий." else "Загружать только сводки недавно обновлённых сессий.",
        )
    }
}

internal fun MainActivity.sessionHistoryMonthLabel(): String {
    return if (sessionHistoryMonths == 0) "Вся история" else "Последние $sessionHistoryMonths мес."
}

internal fun MainActivity.sessionHistoryMonthDescription(): String {
    return "Ограничивает только список сессий в боковой панели и не удаляет старые диалоги на Bridge."
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
            label = "${seconds} с",
            supporting = if (seconds == 5) "Частота по умолчанию — ответы приходят быстрее." else "Снижает частоту запросов — подходит для спокойного просмотра истории.",
        )
    }
}

internal fun MainActivity.sessionSyncIntervalLabel(): String {
    return "${sessionSyncIntervalSeconds} с"
}

internal fun MainActivity.sessionSyncIntervalDescription(): String {
    return "Интервал опроса содержимого открытой сессии. Влияет только на active session, не на обновление проектов и списка истории."
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
            label = "${seconds} с",
            supporting =
                if (seconds == DEFAULT_BRIDGE_CONNECT_TIMEOUT_SECONDS) {
                    "Значение по умолчанию — при слабой сети сбой выявляется быстрее."
                } else {
                    "Подходит для более медленной сети или соединений между подсетями."
                },
        )
    }
}

internal fun MainActivity.bridgeConnectTimeoutLabel(): String {
    return "${bridgeConnectTimeoutSeconds} с"
}

internal fun MainActivity.bridgeConnectTimeoutDescription(): String {
    return "Тайм-аут установки соединения WebSocket. После истечения применяется текущая политика автопереподключения."
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
            label = "${seconds} с",
            supporting =
                if (seconds == DEFAULT_BRIDGE_PING_INTERVAL_SECONDS) {
                    "Значение по умолчанию — баланс скорости обнаружения обрыва и энергопотребления."
                } else {
                    "Быстрее обнаруживать обрыв или меньше трафика пульса."
                },
        )
    }
}

internal fun MainActivity.bridgePingIntervalLabel(): String {
    return "${bridgePingIntervalSeconds} с"
}

internal fun MainActivity.bridgePingIntervalDescription(): String {
    return "Интервал пульса WebSocket. Чем короче, тем быстрее обнаруживается обрыв, но растёт трафик пульса."
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
        "Подключение к Codex Bridge в частной сети",
        "Просмотр, переключение и восстановление сессий Codex",
        "Отправить сообщение и смотреть ответ потоком",
        "Обработка запросов на подтверждение и изменений файлов",
        "Просмотр файлов кода, diff и изменений сессии",
        "Резервное копирование и восстановление данных подключения и локальных настроек",
    )
}

internal fun MainActivity.startupPageMenuOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption(
            value = AppPage.Chat.preferenceValue(),
            label = "Чат",
            supporting = "После запуска сразу открывать страницу текущей сессии.",
        ),
        SelectionMenuOption(
            value = AppPage.Connection.preferenceValue(),
            label = "Настройки подключения",
            supporting = "Сначала видно адрес bridge и состояние подключения.",
        ),
        SelectionMenuOption(
            value = AppPage.Settings.preferenceValue(),
            label = "Настройки",
            supporting = "Сначала откройте страницу локальных настроек.",
        ),
    )
}

internal fun MainActivity.autoReconnectAttemptMenuOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption(
            value = "0",
            label = "Без ограничений",
            supporting = "После обрыва попытки продолжаются, интервал постепенно увеличивается.",
        ),
        SelectionMenuOption(
            value = "3",
            label = "3 раза",
            supporting = "Не более 3 автопереподключений.",
        ),
        SelectionMenuOption(
            value = "5",
            label = "5 раз",
            supporting = "Не более 5 автопереподключений.",
        ),
        SelectionMenuOption(
            value = "10",
            label = "10 раз",
            supporting = "Не более 10 автопереподключений.",
        ),
    )
}

internal fun MainActivity.autoReconnectMaxAttemptsLabel(): String {
    return if (autoReconnectMaxAttempts <= 0) "Без ограничений" else "${autoReconnectMaxAttempts} раз"
}

internal fun MainActivity.autoReconnectMaxAttemptsDescription(): String {
    return if (autoReconnectMaxAttempts <= 0) {
        "После обрыва автоматические попытки продолжаются, интервал между ними постепенно растёт."
    } else {
        "Максимум автоматических попыток после каждого обрыва: ${autoReconnectMaxAttempts} раз."
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
