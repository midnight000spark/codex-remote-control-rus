package com.haochen.codexremote

import java.time.Instant
import java.util.Locale
import org.json.JSONObject

internal data class ReasoningEffortInfo(
    val effort: String,
    val description: String,
)

internal fun toSandboxModeValue(value: String): String {
    return when (value.trim()) {
        "readOnly" -> "read-only"
        "workspaceWrite" -> "workspace-write"
        "dangerFullAccess" -> "danger-full-access"
        else -> value
    }
}

internal data class ModelInfo(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val isDefault: Boolean,
    val supportedReasoningEfforts: List<ReasoningEffortInfo>,
    val defaultReasoningEffort: String,
) {
    companion object {
        fun fromJson(objectValue: JSONObject): ModelInfo? {
            val id = objectValue.optString("id", "").trim()
            if (id.isBlank()) return null
            val reasoningOptions = buildList {
                val array = objectValue.optJSONArray("supportedReasoningEfforts") ?: return@buildList
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val effort = item.optString("reasoningEffort", "").trim()
                    if (effort.isBlank()) continue
                    add(
                        ReasoningEffortInfo(
                            effort = effort,
                            description = item.optString("description", "").trim(),
                        ),
                    )
                }
            }
            return ModelInfo(
                id = id,
                model = objectValue.optString("model", "").trim().ifBlank { id },
                displayName = objectValue.optString("displayName", "").trim().ifBlank { id },
                description = objectValue.optString("description", "").trim(),
                hidden = objectValue.optBoolean("hidden", false),
                isDefault = objectValue.optBoolean("isDefault", false),
                supportedReasoningEfforts = reasoningOptions,
                defaultReasoningEffort = objectValue.optString("defaultReasoningEffort", "").trim(),
            )
        }
    }
}

internal data class SessionInfo(
    val sessionId: String,
    val title: String,
    val preview: String,
    val model: String,
    val reasoningEffort: String,
    val backend: String,
    val cwd: String,
    val updatedAt: String,
    val approvalPolicy: String,
    val sandbox: Any?,
    val permissions: JSONObject?,
    val contextWindow: Int?,
    val lastTokenUsage: TokenUsageSnapshot?,
    val totalTokenUsage: TokenUsageSnapshot?,
) {
    companion object {
        private fun firstNonEmpty(vararg values: String): String {
            values.forEach { if (it.trim().isNotEmpty()) return it }
            return ""
        }

        private fun copyJsonObject(value: JSONObject?): JSONObject? = value?.let { JSONObject(it.toString()) }

        private fun normalizeTimestamp(raw: Long): String {
            if (raw <= 0L) return ""
            return if (raw < 1_000_000_000_000L) {
                (raw * 1000L).toString()
            } else {
                raw.toString()
            }
        }

        private fun extractUpdatedAt(envelope: JSONObject, threadObject: JSONObject): String {
            return firstNonEmpty(
                envelope.optString("updatedAt", ""),
                threadObject.optString("updatedAt", ""),
                normalizeTimestamp(envelope.optLong("updatedAt", 0L)),
                normalizeTimestamp(threadObject.optLong("updatedAt", 0L)),
            )
        }

        private fun extractPermissions(envelope: JSONObject, threadObject: JSONObject): JSONObject? {
            return copyJsonObject(
                envelope.optJSONObject("permissions")
                    ?: envelope.optJSONObject("activePermissionProfile")
                    ?: envelope.optJSONObject("permissionProfile")
                    ?: threadObject.optJSONObject("permissions")
                    ?: threadObject.optJSONObject("activePermissionProfile")
                    ?: threadObject.optJSONObject("permissionProfile"),
            )
        }

        private fun extractSandbox(envelope: JSONObject, threadObject: JSONObject): Any? {
            return cloneJsonValue(
                envelope.opt("sandbox")
                    ?: envelope.opt("sandboxPolicy")
                    ?: threadObject.opt("sandbox")
                    ?: threadObject.opt("sandboxPolicy"),
            )
        }

        private fun extractContextWindow(envelope: JSONObject, threadObject: JSONObject): Int? {
            return envelope.optInt("contextWindow").takeIf { it > 0 }
                ?: threadObject.optInt("contextWindow").takeIf { it > 0 }
                ?: envelope.optJSONObject("tokenUsage")?.optInt("modelContextWindow")?.takeIf { it > 0 }
                ?: threadObject.optJSONObject("tokenUsage")?.optInt("modelContextWindow")?.takeIf { it > 0 }
        }

        private fun extractTokenUsageSnapshot(
            envelope: JSONObject,
            threadObject: JSONObject,
            directKey: String,
            nestedKey: String,
        ): TokenUsageSnapshot? {
            return envelope.optJSONObject(directKey)?.let { TokenUsageSnapshot.fromJson(it) }
                ?: threadObject.optJSONObject(directKey)?.let { TokenUsageSnapshot.fromJson(it) }
                ?: envelope.optJSONObject("tokenUsage")?.optJSONObject(nestedKey)?.let { TokenUsageSnapshot.fromJson(it) }
                ?: threadObject.optJSONObject("tokenUsage")?.optJSONObject(nestedKey)?.let { TokenUsageSnapshot.fromJson(it) }
        }

        fun fromSession(objectValue: JSONObject): SessionInfo? {
            val threadObject = objectValue.optJSONObject("thread") ?: objectValue
            val sessionId = firstNonEmpty(
                objectValue.optString("session_id", ""),
                objectValue.optString("thread_id", ""),
                objectValue.optString("sessionId", ""),
                threadObject.optString("sessionId", ""),
                objectValue.optString("id", ""),
                threadObject.optString("id", ""),
            )
            if (sessionId.isBlank()) return null
            val title = firstNonEmpty(
                objectValue.optString("title", ""),
                objectValue.optString("name", ""),
                objectValue.optString("preview", ""),
                threadObject.optString("name", ""),
                threadObject.optString("title", ""),
                threadObject.optString("preview", ""),
            )
            return SessionInfo(
                sessionId = sessionId,
                title = title,
                preview = firstNonEmpty(objectValue.optString("preview", ""), threadObject.optString("preview", "")),
                model = firstNonEmpty(objectValue.optString("model", ""), threadObject.optString("model", "")),
                reasoningEffort = firstNonEmpty(objectValue.optString("reasoningEffort", ""), threadObject.optString("reasoningEffort", "")),
                backend = firstNonEmpty(
                    objectValue.optString("backend", ""),
                    objectValue.optString("modelProvider", ""),
                    threadObject.optString("modelProvider", ""),
                ),
                cwd = firstNonEmpty(
                    objectValue.optString("cwd", ""),
                    threadObject.optString("cwd", ""),
                    threadObject.optString("path", ""),
                ),
                updatedAt = extractUpdatedAt(objectValue, threadObject),
                approvalPolicy = firstNonEmpty(
                    objectValue.optString("approvalPolicy", ""),
                    threadObject.optString("approvalPolicy", ""),
                ),
                sandbox = extractSandbox(objectValue, threadObject),
                permissions = extractPermissions(objectValue, threadObject),
                contextWindow = extractContextWindow(objectValue, threadObject),
                lastTokenUsage = extractTokenUsageSnapshot(objectValue, threadObject, "lastTokenUsage", "last"),
                totalTokenUsage = extractTokenUsageSnapshot(objectValue, threadObject, "totalTokenUsage", "total"),
            )
        }

        fun fromThread(objectValue: JSONObject): SessionInfo? {
            val threadObject = objectValue.optJSONObject("thread") ?: objectValue
            val sessionId = firstNonEmpty(
                objectValue.optString("session_id", ""),
                objectValue.optString("thread_id", ""),
                objectValue.optString("sessionId", ""),
                threadObject.optString("sessionId", ""),
                objectValue.optString("id", ""),
                threadObject.optString("id", ""),
            )
            if (sessionId.isBlank()) return null
            val title = firstNonEmpty(
                objectValue.optString("title", ""),
                threadObject.optString("name", ""),
                objectValue.optString("name", ""),
                objectValue.optString("preview", ""),
                threadObject.optString("preview", ""),
                threadObject.optString("title", ""),
            )
            return SessionInfo(
                sessionId = sessionId,
                title = title,
                preview = firstNonEmpty(objectValue.optString("preview", ""), threadObject.optString("preview", "")),
                model = firstNonEmpty(objectValue.optString("model", ""), threadObject.optString("model", "")),
                reasoningEffort = firstNonEmpty(objectValue.optString("reasoningEffort", ""), threadObject.optString("reasoningEffort", "")),
                backend = firstNonEmpty(objectValue.optString("backend", ""), objectValue.optString("modelProvider", ""), threadObject.optString("modelProvider", "")),
                cwd = firstNonEmpty(
                    objectValue.optString("cwd", ""),
                    threadObject.optString("cwd", ""),
                    threadObject.optString("path", ""),
                ),
                updatedAt = extractUpdatedAt(objectValue, threadObject),
                approvalPolicy = firstNonEmpty(
                    objectValue.optString("approvalPolicy", ""),
                    threadObject.optString("approvalPolicy", ""),
                ),
                sandbox = extractSandbox(objectValue, threadObject),
                permissions = extractPermissions(objectValue, threadObject),
                contextWindow = extractContextWindow(objectValue, threadObject),
                lastTokenUsage = extractTokenUsageSnapshot(objectValue, threadObject, "lastTokenUsage", "last"),
                totalTokenUsage = extractTokenUsageSnapshot(objectValue, threadObject, "totalTokenUsage", "total"),
            )
        }
    }

    fun mergedWith(previous: SessionInfo?): SessionInfo {
        if (previous == null) return this
        return copy(
            title = title.ifBlank { previous.title },
            preview = preview.ifBlank { previous.preview },
            model = model.ifBlank { previous.model },
            reasoningEffort = reasoningEffort.ifBlank { previous.reasoningEffort },
            backend = backend.ifBlank { previous.backend },
            cwd = cwd.ifBlank { previous.cwd },
            updatedAt = updatedAt.ifBlank { previous.updatedAt },
            approvalPolicy = approvalPolicy.ifBlank { previous.approvalPolicy },
            sandbox = sandbox ?: cloneJsonValue(previous.sandbox),
            permissions = permissions?.let { JSONObject(it.toString()) } ?: previous.permissions?.let { JSONObject(it.toString()) },
            contextWindow = contextWindow ?: previous.contextWindow,
            lastTokenUsage = lastTokenUsage ?: previous.lastTokenUsage,
            totalTokenUsage = totalTokenUsage ?: previous.totalTokenUsage,
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("session_id", sessionId)
            put("title", title)
            put("preview", preview)
            put("model", model)
            put("reasoningEffort", reasoningEffort)
            put("backend", backend)
            put("cwd", cwd)
            put("updatedAt", updatedAt)
            put("approvalPolicy", approvalPolicy)
            sandbox?.let { put("sandbox", cloneJsonValue(it)) }
            permissions?.let { put("permissions", JSONObject(it.toString())) }
            contextWindow?.let { put("contextWindow", it) }
            lastTokenUsage?.let { put("lastTokenUsage", it.toJson()) }
            totalTokenUsage?.let { put("totalTokenUsage", it.toJson()) }
        }
    }

    fun titleLine(): String {
        if (title.isNotBlank()) return title
        if (preview.isNotBlank()) return preview
        return sessionId.take(8)
    }

    fun previewLine(): String {
        if (preview.isNotBlank() && preview != title) return preview
        if (cwd.isNotBlank()) return cwd
        return "Нет содержимого для предпросмотра"
    }

    fun updatedAtLabel(): String {
        val raw = updatedAt.trim()
        val timestamp =
            raw.toLongOrNull()
                ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                ?: return raw.ifBlank { "Только что" }
        return formatBeijingDateTimeLabel(timestamp, fallback = "Только что")
    }

    fun metaLine(): String {
        val parts = mutableListOf<String>()
        if (model.isNotBlank()) parts.add(model)
        if (backend.isNotBlank()) parts.add(backend)
        if (approvalPolicy.isNotBlank()) parts.add("Подтверждение ${approvalPolicy.trim()}")
        parts.add(sessionId.take(8))
        if (updatedAt.isNotBlank()) parts.add(updatedAtLabel())
        return parts.joinToString(" · ")
    }

    fun permissionsSummary(): String {
        val id = firstNonEmpty(
            permissions?.optString("id", "") ?: "",
            permissions?.optString("type", "") ?: "",
        ).trim()
        return id.ifBlank { "По умолчанию" }
    }

    fun sandboxSummary(): String {
        val raw = sandbox
        val type =
            when (raw) {
                is String -> raw
                is JSONObject -> raw.optString("type", "")
                else -> raw?.toString().orEmpty()
            }.trim()
        return when (type) {
            "dangerFullAccess", "danger-full-access" -> "dangerFullAccess"
            "workspaceWrite", "workspace-write" -> "workspaceWrite"
            "readOnly", "read-only" -> "readOnly"
            "externalSandbox" -> "external sandbox"
            else -> "Не указано"
        }
    }

    fun isDangerFullAccess(): Boolean {
        val raw = sandbox
        val type =
            when (raw) {
                is String -> raw
                is JSONObject -> raw.optString("type", "")
                else -> raw?.toString().orEmpty()
            }.trim()
        return type == "dangerFullAccess" || type == "danger-full-access"
    }

    fun reasoningEffortSummary(): String {
        val effort = reasoningEffort.trim()
        return if (effort.isBlank()) {
            "Не указано"
        } else {
            labelReasoningEffort(effort)
        }
    }

    fun modelSummary(): String {
        val modelValue = model.trim().ifBlank { "Не указано" }
        val effortValue = reasoningEffort.trim()
        return if (effortValue.isBlank()) {
            modelValue
        } else {
            "$modelValue · ${labelReasoningEffort(effortValue)}"
        }
    }

    fun contextWindowSummary(): String = contextWindow?.let(::formatCountLabel) ?: "Не указано"

    fun lastTokenUsageSummary(): String = lastTokenUsage?.summary() ?: "Не указано"

    fun totalTokenUsageSummary(): String = totalTokenUsage?.summary() ?: "Не указано"
}

internal data class TokenUsageSnapshot(
    val totalTokens: Int,
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
    val reasoningOutputTokens: Int,
) {
    companion object {
        fun fromJson(objectValue: JSONObject): TokenUsageSnapshot {
            return TokenUsageSnapshot(
                totalTokens = objectValue.optInt("totalTokens", 0),
                inputTokens = objectValue.optInt("inputTokens", 0),
                cachedInputTokens = objectValue.optInt("cachedInputTokens", 0),
                outputTokens = objectValue.optInt("outputTokens", 0),
                reasoningOutputTokens = objectValue.optInt("reasoningOutputTokens", 0),
            )
        }
    }

    fun summary(): String {
        return buildList {
            add("Всего ${formatCountLabel(totalTokens)}")
            if (inputTokens > 0) add("Вход ${formatCountLabel(inputTokens)}")
            if (cachedInputTokens > 0) add("Кэш ${formatCountLabel(cachedInputTokens)}")
            if (outputTokens > 0) add("Выход ${formatCountLabel(outputTokens)}")
            if (reasoningOutputTokens > 0) add("Рассуждения ${formatCountLabel(reasoningOutputTokens)}")
        }.joinToString(" · ").ifBlank { "0" }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("totalTokens", totalTokens)
            put("inputTokens", inputTokens)
            put("cachedInputTokens", cachedInputTokens)
            put("outputTokens", outputTokens)
            put("reasoningOutputTokens", reasoningOutputTokens)
        }
    }
}

internal fun formatCountLabel(value: Int): String {
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
        value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000f)
        else -> value.toString()
    }
}

internal fun labelReasoningEffort(effort: String): String {
    return when (effort.trim()) {
        "none" -> "Без рассуждений (none)"
        "minimal" -> "Очень низкая (minimal)"
        "low" -> "Низкая (low)"
        "medium" -> "Средняя (medium)"
        "high" -> "Высокая (high)"
        "xhigh" -> "Очень высокая (xhigh)"
        else -> effort.ifBlank { "Не указано" }
    }
}
