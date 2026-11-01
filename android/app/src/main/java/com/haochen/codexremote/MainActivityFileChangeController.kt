package com.haochen.codexremote

import org.json.JSONArray
import org.json.JSONObject

internal fun MainActivity.handleFileChangeItem(turnKey: String, item: JSONObject) {
        val itemId = item.optString("id", "").trim()
        if (itemId.isBlank()) return
        val changes = item.optJSONArray("changes").toConversationDiffEntries()
        val status = item.optString("status", "").trim()
        upsertFileChangeConversationItem(
            itemId = itemId,
            turnKey = turnKey,
            status = status,
            diffEntries = changes,
            fallbackDiff = turnDiffs[turnKey],
        )
    }

internal fun MainActivity.handleFileChangePatchUpdated(message: JSONObject, payload: JSONObject) {
        val itemId = extractAssistantItemKey(message = message, payload = payload)
        if (itemId.isBlank()) return
        val turnKey = extractTurnKey(message, payload)
        val changes = payload.optJSONArray("changes").toConversationDiffEntries()
        upsertFileChangeConversationItem(
            itemId = itemId,
            turnKey = turnKey,
            status = "inProgress",
            diffEntries = changes,
            fallbackDiff = turnDiffs[turnKey],
        )
    }

internal fun MainActivity.handleTurnDiffUpdated(message: JSONObject, payload: JSONObject) {
        val turnKey = extractTurnKey(message, payload)
        val diff = payload.optString("diff", "").trim()
        if (turnKey.isBlank() || diff.isBlank()) return
        turnDiffs[turnKey] = diff

        val existingFileChangeItemId = fileChangeTurnIds.entries.firstOrNull { it.value == turnKey }?.key
        if (existingFileChangeItemId != null) {
            val conversationItemId = fileChangeItemIds[existingFileChangeItemId]
            if (conversationItemId != null) {
                replaceConversationItem(conversationItemId) { current ->
                    if (current is ConversationItem.FileChange) {
                        current.copy(
                            summary = buildFileChangeSummary(
                                status = current.status,
                                diffEntries = current.diffEntries,
                                fallbackDiff = diff,
                            ),
                            fallbackDiff = diff,
                        )
                    } else {
                        current
                    }
                }
                return
            }
        }

        upsertTurnDiffConversationItem(turnKey, diff)
    }

internal fun MainActivity.upsertFileChangeConversationItem(
        itemId: String,
        turnKey: String,
        status: String,
        diffEntries: List<ConversationDiffEntry>,
        fallbackDiff: String?,
    ) {
        if (itemId.isBlank() || turnKey.isBlank()) return
        fileChangeTurnIds[itemId] = turnKey
        turnDiffItemIds.remove(turnKey)?.let(::removeConversationItem)

        val summary = buildFileChangeSummary(status, diffEntries, fallbackDiff)
        val existingId = fileChangeItemIds[itemId]
        if (existingId == null) {
            val created = ConversationItem.FileChange(
                id = buildConversationItemId("file_change", itemId, turnKey),
                title = "Изменение файла",
                summary = summary,
                status = status,
                diffEntries = diffEntries,
                fallbackDiff = fallbackDiff,
                sourceItemId = itemId,
                turnId = turnKey,
            )
            fileChangeItemIds[itemId] = created.id
            conversationItems.add(created)
            return
        }

        replaceConversationItem(existingId) { current ->
            if (current is ConversationItem.FileChange) {
                current.copy(
                    title = "Изменение файла",
                    summary = summary,
                    status = status,
                    diffEntries = diffEntries,
                    fallbackDiff = fallbackDiff,
                    sourceItemId = itemId,
                    turnId = turnKey,
                )
            } else {
                current
            }
        }
    }

internal fun MainActivity.upsertTurnDiffConversationItem(turnKey: String, diff: String) {
        val summary = buildFileChangeSummary(status = "inProgress", diffEntries = emptyList(), fallbackDiff = diff)
        val existingId = turnDiffItemIds[turnKey]
        if (existingId == null) {
            val created = ConversationItem.FileChange(
                id = buildConversationItemId("turn_diff", turnKey),
                title = "Изменение файлов",
                summary = summary,
                status = "inProgress",
                diffEntries = emptyList(),
                fallbackDiff = diff,
                turnId = turnKey,
            )
            turnDiffItemIds[turnKey] = created.id
            conversationItems.add(created)
            return
        }

        replaceConversationItem(existingId) { current ->
            if (current is ConversationItem.FileChange) {
                current.copy(
                    title = "Изменение файлов",
                    summary = summary,
                    status = "inProgress",
                    fallbackDiff = diff,
                    turnId = turnKey,
                )
            } else {
                current
            }
        }
    }

internal fun MainActivity.buildFileChangeSummary(
        status: String,
        diffEntries: List<ConversationDiffEntry>,
        fallbackDiff: String?,
    ): String {
        val lines = mutableListOf<String>()
        labelPatchStatus(status).takeIf { it.isNotBlank() }?.let(lines::add)

        if (diffEntries.isNotEmpty()) {
            lines.add("${diffEntries.size} файлов")
            buildDiffStatsLine(diffEntries = diffEntries, fallbackDiff = fallbackDiff)?.let(lines::add)
        } else if (!fallbackDiff.isNullOrBlank()) {
            buildDiffStatsLine(diffEntries = emptyList(), fallbackDiff = fallbackDiff)?.let(lines::add)
        }

        return if (lines.isEmpty()) "Нажмите, чтобы увидеть подробности" else lines.joinToString("\n")
    }

internal fun MainActivity.buildDiffStatsLine(
        diffEntries: List<ConversationDiffEntry>,
        fallbackDiff: String?,
    ): String? {
        if (diffEntries.size == 1) {
            return diffEntries.first().changeLabel()
        }
        val source =
            if (diffEntries.isNotEmpty()) {
                diffEntries.joinToString("\n") { it.diff }
            } else {
                fallbackDiff.orEmpty()
            }
        return parseDiffStats(source)?.toLabel()
    }

internal fun MainActivity.labelPatchStatus(status: String): String {
        return when (status.trim()) {
            "inProgress" -> "Выполняется"
            "completed" -> "Завершено"
            "failed" -> "Не удалось"
            "declined" -> "Отклонено"
            else -> status.trim()
        }
    }

internal fun JSONArray?.toConversationDiffEntries(): List<ConversationDiffEntry> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optJSONObject(i)?.toConversationDiffEntry()?.let(::add)
            }
        }
    }

internal fun JSONObject.toConversationDiffEntry(pathOverride: String? = null): ConversationDiffEntry? {
        val path = normalizeNullablePath(pathOverride ?: opt("path")).orEmpty()
        if (path.isBlank()) return null
        val kindObject = optJSONObject("kind")
        val kindType = firstNonEmpty(
            kindObject?.optString("type", "") ?: "",
            optString("type", ""),
            "update",
        )
        val movePath = firstNonEmpty(
            normalizeNullablePath(kindObject?.opt("move_path")).orEmpty(),
            normalizeNullablePath(opt("move_path")).orEmpty(),
        ).takeIf { it.isNotBlank() }
        return ConversationDiffEntry(
            path = path,
            kind = kindType,
            diff = buildConversationChangeDiffText(kindType, this),
            movePath = movePath,
        )
    }

internal fun JSONObject?.toApprovalDiffEntries(): List<ConversationDiffEntry> {
        if (this == null) return emptyList()
        val changes = mutableListOf<ConversationDiffEntry>()
        val keys = keys()
        while (keys.hasNext()) {
            val path = normalizeNullablePath(keys.next()).orEmpty()
            if (path.isBlank()) continue
            val change = optJSONObject(path) ?: continue
            change.toConversationDiffEntry(pathOverride = path)?.let(changes::add)
        }
        return changes
    }

internal fun buildConversationChangeDiffText(kind: String, change: JSONObject): String {
        val text = firstConversationChangeText(
            change.opt("diff"),
            change.opt("unified_diff"),
            change.opt("content"),
        )
        if (text.isBlank()) return ""
        if (looksLikeUnifiedDiff(text)) return text
        return when (kind.trim()) {
            "add" -> prefixConversationChangeLines(text, "+")
            "delete" -> prefixConversationChangeLines(text, "-")
            else -> text
        }
    }

internal fun firstConversationChangeText(vararg values: Any?): String {
        for (value in values) {
            val text =
                when (value) {
                    null, JSONObject.NULL -> ""
                    is String -> value
                    else -> value.toString()
                }.replace("\r\n", "\n").trimEnd()
            if (text.isNotBlank()) return text
        }
        return ""
    }

internal fun looksLikeUnifiedDiff(text: String): Boolean {
        return text.lineSequence().any { line ->
            line.startsWith("diff --git") ||
                line.startsWith("@@") ||
                line.startsWith("+++ ") ||
                line.startsWith("--- ") ||
                line.startsWith("new file mode ") ||
                line.startsWith("deleted file mode ") ||
                line.startsWith("rename from ") ||
                line.startsWith("rename to ")
        }
    }

internal fun prefixConversationChangeLines(text: String, prefix: String): String =
        text.split('\n').joinToString("\n") { line -> "$prefix$line" }
