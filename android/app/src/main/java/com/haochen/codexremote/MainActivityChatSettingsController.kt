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
    return listOf(SelectionMenuOption("", "默认", "使用模型默认思考强度。")) + reasoningEffortOptionsForModel(modelId)
}

internal fun MainActivity.reasoningEffortShortLabel(effort: String): String {
    return when (effort.trim()) {
        "" -> "默认"
        "none" -> "无"
        "minimal" -> "极简"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "xhigh" -> "极高"
        else -> effort.ifBlank { "默认" }
    }
}

internal fun MainActivity.reasoningSummaryOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption("none", "关闭", "不请求推理摘要。"),
        SelectionMenuOption("auto", "自动", "由 Codex 自动决定摘要粒度。"),
        SelectionMenuOption("concise", "简短", "显示较短的推理摘要。"),
        SelectionMenuOption("detailed", "详细", "显示更详细的推理摘要。"),
    )
}

internal fun normalizeReasoningSummary(value: String?): String {
    return when (value?.trim()) {
        "none", "auto", "concise", "detailed" -> value.trim()
        else -> "auto"
    }
}

internal fun MainActivity.reasoningSummaryLabel(summary: String): String {
    return reasoningSummaryOptions().firstOrNull { it.value == normalizeReasoningSummary(summary) }?.label ?: "自动"
}

internal fun MainActivity.chatModeOptions(): List<SelectionMenuOption> {
    return listOf(
        SelectionMenuOption(ChatMode.Default.preferenceValue(), "普通", "直接执行当前请求。"),
        SelectionMenuOption(ChatMode.Plan.preferenceValue(), "Plan", "先产出方案，等待确认后再执行。"),
        SelectionMenuOption(ChatMode.Goal.preferenceValue(), "Goal", "围绕一个目标持续推进。"),
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
        ChatMode.Default -> "普通"
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
        "readOnly" -> "只读"
        "dangerFullAccess" -> "完整访问"
        else -> "工作区"
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
    val modelLabel = modelInfoFor(model)?.displayName ?: model.ifBlank { "模型" }
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
        showNotice("请先填写目标")
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
                showNotice("目标已设置")
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
                showNotice("目标已清除")
            }
        })) {
        return
    }
}
