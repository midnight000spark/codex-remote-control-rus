package com.haochen.codexremote

import java.util.Locale
import org.json.JSONObject

internal fun MainActivity.selectChatMode(mode: ChatMode) {
    selectedChatMode = mode
    prefs.edit().putString(KEY_CHAT_MODE, mode.preferenceValue()).apply()
}

internal fun MainActivity.selectReasoningEffort(effort: String) {
    selectedReasoningEffort = effort.trim()
    prefs.edit().putString(KEY_CHAT_REASONING_EFFORT, selectedReasoningEffort).apply()
}

internal fun MainActivity.selectReasoningSummary(summary: String) {
    selectedReasoningSummary = normalizeReasoningSummary(summary)
    prefs.edit().putString(KEY_CHAT_REASONING_SUMMARY, selectedReasoningSummary).apply()
}

internal fun MainActivity.selectSandbox(sandbox: String) {
    selectedSandbox = normalizeSandboxPreference(sandbox)
    persistSelectedSandbox(selectedSandbox)
}

internal fun MainActivity.persistSelectedSandbox(sandbox: String) {
    prefs.edit().putString(KEY_CHAT_SANDBOX, normalizeSandboxPreference(sandbox)).apply()
}

internal fun MainActivity.chatReasoningEffortOptions(modelId: String): List<SelectionMenuOption> {
    return listOf(SelectionMenuOption("", "По умолчанию", "Используется интенсивность рассуждений модели по умолчанию.")) + reasoningEffortOptionsForModel(modelId)
}

internal fun MainActivity.reasoningEffortShortLabel(effort: String): String {
    return effort.trim().ifBlank { "По умолчанию" }
}

internal fun MainActivity.reasoningSummaryOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption("none", "Закрыть", "Не запрашивать сводку рассуждений."),
        SelectionMenuOption("auto", "Авто", "Степень детализации сводки Codex выбирает сам."),
        SelectionMenuOption("concise", "Кратко", "Показывать краткую сводку рассуждений."),
        SelectionMenuOption("detailed", "Подробно", "Показывать более подробную сводку рассуждений."),
    )
}

internal fun normalizeReasoningSummary(value: String?): String {
    return when (value?.trim()) {
        "none", "auto", "concise", "detailed" -> value.trim()
        else -> "auto"
    }
}

internal fun MainActivity.reasoningSummaryLabel(summary: String): String {
    return reasoningSummaryOptions().firstOrNull { it.value == normalizeReasoningSummary(summary) }?.label ?: "Авто"
}

internal fun MainActivity.chatModeOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption(ChatMode.Default.preferenceValue(), "Обычный", "Выполнить текущий запрос сразу."),
        SelectionMenuOption(ChatMode.Plan.preferenceValue(), "Plan", "Сначала выдаёт план, выполняет после подтверждения."),
        SelectionMenuOption(ChatMode.Goal.preferenceValue(), "Goal", "Последовательно продвигаться к одной цели."),
    )
}

internal fun ChatMode.preferenceValue(): String {
    return when (this) {
        ChatMode.Default -> "default"
        ChatMode.Plan -> "plan"
        ChatMode.Goal -> "goal"
    }
}

internal fun chatModeFromPreference(value: String?): ChatMode {
    return when (value?.trim()?.lowercase(Locale.getDefault())) {
        "plan" -> ChatMode.Plan
        "goal" -> ChatMode.Goal
        else -> ChatMode.Default
    }
}

internal fun MainActivity.chatModeLabel(mode: ChatMode = selectedChatMode): String {
    return when (mode) {
        ChatMode.Default -> "Обычный"
        ChatMode.Plan -> "Plan"
        ChatMode.Goal -> "Goal"
    }
}

internal fun normalizeSandboxPreference(value: String?): String {
    return when (value?.trim()) {
        "readOnly", "read-only" -> "readOnly"
        "dangerFullAccess", "danger-full-access" -> "dangerFullAccess"
        else -> "workspaceWrite"
    }
}

internal fun MainActivity.sandboxShortLabel(sandbox: String = selectedSandbox): String {
    return when (normalizeSandboxPreference(sandbox)) {
        "readOnly" -> "Только чтение"
        "dangerFullAccess" -> "Полный доступ"
        else -> "Рабочая область"
    }
}

internal fun MainActivity.sandboxPreferenceFromSession(session: SessionInfo?): String {
    val summary = session?.sandboxSummary().orEmpty()
    return when (summary) {
        "readOnly" -> "readOnly"
        "dangerFullAccess" -> "dangerFullAccess"
        "workspaceWrite" -> "workspaceWrite"
        else -> normalizeSandboxPreference(prefs.getString(KEY_CHAT_SANDBOX, "workspaceWrite"))
    }
}

internal fun MainActivity.resolveReasoningEffortForSend(modelId: String): String {
    val normalized = selectedReasoningEffort.trim()
    if (normalized.isBlank()) return ""
    return normalizeReasoningEffortForModel(modelId, normalized)
}

internal fun MainActivity.syncChatSettingsFromSession(session: SessionInfo?) {
    session ?: return
    session.model.trim().takeIf { it.isNotBlank() }?.let { selectModel(it) }
    val normalizedEffort = session.reasoningEffort.trim()
    selectReasoningEffort(
        if (normalizedEffort.isBlank()) {
            ""
        } else {
            normalizeReasoningEffortForModel(session.model.trim(), normalizedEffort)
        },
    )
    selectedSandbox = sandboxPreferenceFromSession(session)
    persistSelectedSandbox(selectedSandbox)
}

internal fun MainActivity.buildCollaborationModePayload(modelId: String, effort: String): JSONObject? {
    val modeValue = when (selectedChatMode) {
        ChatMode.Plan -> "plan"
        else -> return null
    }
    if (modelId.isBlank()) return null
    return JSONObject().apply {
        put("mode", modeValue)
        put(
            "settings",
            JSONObject().apply {
                put("model", modelId)
                put("reasoning_effort", effort.ifBlank { JSONObject.NULL })
                put("developer_instructions", JSONObject.NULL)
            },
        )
    }
}

internal fun MainActivity.modelToolbarLabel(): String {
    val model = resolveModelForSend()
    val modelLabel = modelInfoFor(model)?.displayName ?: model.ifBlank { "Модель" }
    val effort = reasoningEffortShortLabel(selectedReasoningEffort)
    return "$modelLabel · $effort"
}

internal fun MainActivity.currentSessionModelLabelSource(): String {
    return activeSession()?.model?.trim().orEmpty()
}

internal fun MainActivity.currentSessionReasoningEffortLabelSource(): String {
    return activeSession()?.reasoningEffort?.trim().orEmpty()
}

internal fun MainActivity.tokenUsageToolbarLabel(): String? {
    val session = activeSession() ?: return null
    val used = session.lastTokenUsage?.totalTokens ?: return null
    val window = session.contextWindow ?: return null
    if (used <= 0 || window <= 0) return null
    return "${formatCountLabel(used)}/${formatCountLabel(window)}"
}

internal fun MainActivity.requestActiveGoal(sessionId: String? = activeSessionId) {
    val targetSessionId = sessionId?.takeIf { it.isNotBlank() } ?: return
    if (!connected) return
    val payload = JSONObject().apply { put("session_id", targetSessionId) }
    sendRequest("goal.get", payload, object : ResponseHandler {
        override fun onResponse(response: JSONObject) {
            if (activeSessionId != targetSessionId) return
            activeThreadGoal = ThreadGoalState.fromJson(response.optJSONObject("payload")?.optJSONObject("goal"))
        }

        override fun suppressDefaultErrorUi(): Boolean = true
    })
}

internal fun MainActivity.setActiveGoal(objective: String, tokenBudgetText: String) {
    val sessionId = activeSessionId?.takeIf { it.isNotBlank() } ?: return
    val normalizedObjective = objective.trim()
    if (normalizedObjective.isBlank()) {
        showNotice("Сначала заполните цель")
        return
    }
    val budget = tokenBudgetText.trim().toIntOrNull()?.takeIf { it > 0 }
    val payload = JSONObject().apply {
        put("session_id", sessionId)
        put("objective", normalizedObjective)
        put("status", "active")
        budget?.let { put("tokenBudget", it) }
    }
    if (!sendRequest("goal.set", payload, object : ResponseHandler {
            override fun onResponse(response: JSONObject) {
                activeThreadGoal = ThreadGoalState.fromJson(response.optJSONObject("payload")?.optJSONObject("goal"))
                selectChatMode(ChatMode.Goal)
                showNotice("Цель задана")
            }
        })) {
        return
    }
}

internal fun MainActivity.clearActiveGoal() {
    val sessionId = activeSessionId?.takeIf { it.isNotBlank() } ?: return
    val payload = JSONObject().apply { put("session_id", sessionId) }
    if (!sendRequest("goal.clear", payload, object : ResponseHandler {
            override fun onResponse(response: JSONObject) {
                activeThreadGoal = null
                if (selectedChatMode == ChatMode.Goal) {
                    selectChatMode(ChatMode.Default)
                }
                showNotice("Цель очищена")
            }
        })) {
        return
    }
}
