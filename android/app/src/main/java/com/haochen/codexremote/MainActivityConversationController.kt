package com.haochen.codexremote

import android.graphics.Color as AndroidColor
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun MainActivity.appendSystemNote(text: String) {
        conversationItems.add(
            ConversationItem.SystemNote(
                id = "sys_${UUID.randomUUID()}",
                text = text,
            ),
        )
    }

internal fun MainActivity.appendBubble(
        text: String,
        right: Boolean,
        backgroundColor: Int,
        textColor: Int,
        turnKey: String? = null,
    ) {
        val normalizedTurnKey = normalizeProtocolString(turnKey).takeIf { it.isNotBlank() }
        if (right) {
            val latestUserBubble = conversationItems.asReversed().firstOrNull { item ->
                (item as? ConversationItem.Bubble)?.right == true
            } as? ConversationItem.Bubble
            if (latestUserBubble != null && normalizeAssistantText(latestUserBubble.text) == normalizeAssistantText(text)) {
                val existingTurnKey = latestUserBubble.turnKey.orEmpty()
                val incomingTurnKey = normalizedTurnKey.orEmpty()
                if (existingTurnKey == incomingTurnKey || existingTurnKey.isBlank() || incomingTurnKey.isBlank()) {
                    replaceConversationItem(latestUserBubble.id) { item ->
                        if (item is ConversationItem.Bubble) {
                            item.copy(turnKey = normalizedTurnKey ?: item.turnKey)
                        } else {
                            item
                        }
                    }
                    return
                }
            }
        }
        conversationItems.add(
            ConversationItem.Bubble(
                id = "msg_${UUID.randomUUID()}",
                right = right,
                text = text,
                backgroundColor = backgroundColor,
                textColor = textColor,
                turnKey = normalizedTurnKey,
            ),
        )
    }

internal fun MainActivity.replaceConversationItem(itemId: String, transform: (ConversationItem) -> ConversationItem) {
        val index = conversationItems.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            conversationItems[index] = transform(conversationItems[index])
        }
    }

internal fun MainActivity.removeConversationItem(itemId: String) {
        val index = conversationItems.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            conversationItems.removeAt(index)
        }
        if (codeBrowserState?.conversationItemId == itemId) {
            codeBrowserState = null
        }
    }

internal fun MainActivity.removeCompletedTurnApprovalItems(turnId: String) {
        val normalizedTurnId = normalizeProtocolString(turnId)
        if (pendingApprovals.isEmpty()) return

        if (normalizedTurnId.isBlank()) {
            pendingApprovals.clear()
            return
        }

        pendingApprovals.removeAll { approval -> approval.turnId == normalizedTurnId }
    }


internal fun MainActivity.bufferAssistantDelta(turnKey: String, itemKey: String, delta: String) {
        if (delta.isBlank()) return
        val bubbleKey = buildAssistantBubbleKey(turnKey, itemKey)
        assistantDeltaBuffers[bubbleKey] = assistantDeltaBuffers[bubbleKey].orEmpty() + delta
    }

internal fun MainActivity.discardBufferedAssistantText(turnKey: String, itemKey: String) {
        val key = buildAssistantBubbleKey(turnKey, itemKey)
        assistantDeltaBuffers.remove(key)
        if (itemKey != "stream") {
            assistantDeltaBuffers.remove(buildAssistantBubbleKey(turnKey, "stream"))
        }
    }

internal fun MainActivity.appendStandaloneAssistantBubble(turnKey: String, text: String) {
        if (text.isBlank()) return
        if (hasEquivalentAssistantBubble(turnKey, text)) return
        conversationItems.add(createAssistantBubble(turnKey, text))
    }

internal fun MainActivity.appendUserInputBubble(
        turnKey: String,
        itemKey: String,
        text: String,
        images: List<ConversationImage> = emptyList(),
        timestampMillis: Long? = null,
    ) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank() && images.isEmpty()) return
        val normalizedItemKey = normalizeProtocolString(itemKey)
        val normalizedTurnKey = normalizeProtocolString(turnKey)
        val sourceItemId = normalizedItemKey.takeIf { it.isNotBlank() }
        val existing = conversationItems.asReversed().firstOrNull { item ->
            val bubble = item as? ConversationItem.Bubble ?: return@firstOrNull false
            if (!bubble.right) return@firstOrNull false
            val sameSourceItem = sourceItemId != null && bubble.sourceItemId == sourceItemId
            val sameTurnContent =
                bubble.turnKey == normalizedTurnKey &&
                    normalizeAssistantText(bubble.text) == normalizeAssistantText(normalizedText) &&
                    bubble.images == images
            sameSourceItem || sameTurnContent
        } as? ConversationItem.Bubble
        if (existing != null) {
            if (existing.turnKey.isNullOrBlank() && normalizedTurnKey.isNotBlank()) {
                replaceConversationItem(existing.id) { item ->
                    if (item is ConversationItem.Bubble) {
                        item.copy(
                            turnKey = normalizedTurnKey,
                            sourceItemId = sourceItemId ?: item.sourceItemId,
                            timestampMillis = timestampMillis ?: item.timestampMillis,
                        )
                    } else {
                        item
                    }
                }
            }
            return
        }
        conversationItems.add(
            ConversationItem.Bubble(
                id = buildUserConversationItemId(normalizedItemKey, normalizedTurnKey),
                right = true,
                text = normalizedText,
                images = images,
                backgroundColor = 0xFF1A8F55.toInt(),
                textColor = AndroidColor.WHITE,
                turnKey = normalizedTurnKey,
                sourceItemId = sourceItemId,
                timestampMillis = timestampMillis,
            ),
        )
    }

internal fun MainActivity.createAssistantBubble(turnKey: String, text: String, assistantKey: String? = null): ConversationItem.Bubble {
        return ConversationItem.Bubble(
            id = buildConversationItemId("assistant", assistantKey, turnKey),
            right = false,
            text = stripClientDirectivesFromAssistantText(text),
            backgroundColor = 0xFFF1F8F2.toInt(),
            textColor = 0xFF183326.toInt(),
            turnKey = turnKey,
            assistantKey = assistantKey,
        )
    }

internal fun MainActivity.buildConversationItemId(
        prefix: String,
        primaryKey: String?,
        secondaryKey: String? = null,
    ): String {
        val parts = buildList {
            add(prefix.trim().ifBlank { "item" })
            primaryKey?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            secondaryKey?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        }
        return parts.joinToString("_") { sanitizeConversationItemIdPart(it) }
    }

internal fun MainActivity.buildUserConversationItemId(sourceItemId: String?, turnKey: String?): String {
        val normalizedSourceItemId = normalizeProtocolString(sourceItemId)
        val normalizedTurnKey = normalizeProtocolString(turnKey)
        return if (normalizedSourceItemId.isNotBlank()) {
            buildConversationItemId("user", normalizedSourceItemId)
        } else {
            buildConversationItemId("user", normalizedTurnKey, normalizedTurnKey)
        }
    }

internal fun sanitizeConversationItemIdPart(value: String): String {
        val normalized = value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return normalized.trim('_').ifBlank { "item" }
    }

internal fun MainActivity.extractTurnKey(message: JSONObject? = null, payload: JSONObject? = null): String {
        return firstNonEmpty(
            message?.optProtocolString("turn_id") ?: "",
            message?.optProtocolString("turnId") ?: "",
            payload?.optProtocolString("turn_id") ?: "",
            payload?.optProtocolString("turnId") ?: "",
            "assistant",
        )
    }

internal fun MainActivity.extractExplicitTurnKey(message: JSONObject? = null, payload: JSONObject? = null): String {
        return firstNonEmpty(
            message?.optProtocolString("turn_id") ?: "",
            message?.optProtocolString("turnId") ?: "",
            payload?.optProtocolString("turn_id") ?: "",
            payload?.optProtocolString("turnId") ?: "",
        )
    }

internal fun MainActivity.extractAssistantItemKey(message: JSONObject? = null, payload: JSONObject? = null, item: JSONObject? = null): String {
        return firstNonEmpty(
            message?.optProtocolString("item_id") ?: "",
            message?.optProtocolString("itemId") ?: "",
            payload?.optProtocolString("item_id") ?: "",
            payload?.optProtocolString("itemId") ?: "",
            item?.optProtocolString("id") ?: "",
        )
    }

internal fun MainActivity.buildAssistantBubbleKey(turnKey: String, itemKey: String): String {
        return "$turnKey::$itemKey"
    }

internal fun MainActivity.hasEquivalentAssistantBubble(turnKey: String, text: String): Boolean {
        val normalized = normalizeAssistantText(text)
        if (normalized.isBlank()) return false
        return conversationItems.asReversed().any { item ->
            val bubble = item as? ConversationItem.Bubble ?: return@any false
            !bubble.right && bubble.turnKey == turnKey && assistantTextsOverlap(bubble.text, normalized)
        }
    }

internal fun MainActivity.assistantTextsOverlap(left: String, right: String): Boolean {
        val leftNormalized = normalizeAssistantText(left)
        val rightNormalized = normalizeAssistantText(right)
        if (leftNormalized.isBlank() || rightNormalized.isBlank()) return false
        return leftNormalized == rightNormalized ||
            leftNormalized.contains(rightNormalized) ||
            rightNormalized.contains(leftNormalized)
    }

internal fun MainActivity.preferredAssistantText(current: String, incoming: String): String {
        val currentNormalized = normalizeAssistantText(current)
        val incomingNormalized = normalizeAssistantText(incoming)
        return when {
            currentNormalized.isBlank() -> incoming
            incomingNormalized.isBlank() -> current
            currentNormalized == incomingNormalized -> incoming
            incomingNormalized.contains(currentNormalized) -> incoming
            else -> current
        }
    }

internal fun MainActivity.normalizeAssistantText(text: String): String {
        return stripClientDirectivesFromAssistantText(text).replace("\r\n", "\n").trim()
    }

internal fun MainActivity.stripClientDirectivesFromAssistantText(text: String): String {
        if (text.isBlank()) return text
        val filteredLines =
            text.replace("\r\n", "\n")
                .lines()
                .filterNot { line ->
                    isClientDirectiveLine(line.trim())
                }
        return filteredLines.joinToString("\n").trim()
    }

internal fun MainActivity.isClientDirectiveLine(line: String): Boolean {
        if (!line.startsWith("::")) return false
        val braceIndex = line.indexOf('{')
        if (braceIndex <= 2 || !line.endsWith("}")) return false
        val name = line.substring(2, braceIndex)
        if (name.isBlank()) return false
        if (!name.first().isLowerCase()) return false
        return name.all { it.isLowerCase() || it.isDigit() || it == '-' }
    }

internal fun MainActivity.updateLiveTurnStatusFromItem(eventName: String, item: JSONObject?) {
        if (item == null) return
        val started = eventName == "item/started"
        val status = buildItemLiveStatus(item, started)
        if (status != null) {
            updateLiveTurnStatus(status)
        }
    }

internal fun MainActivity.buildItemLiveStatus(item: JSONObject, started: Boolean): String? {
        return when (item.optString("type", "")) {
            "contextCompaction" -> if (started) "Привожу в порядок контекст…" else "Продолжаю ответ…"
            "reasoning" -> if (started) buildReasoningLiveStatus(item) else "Продолжаю ответ…"
            "plan" -> if (started) "Составляю план…" else "Продолжаю ответ…"
            "commandExecution" -> if (started) buildCommandExecutionLiveStatus(item) else buildCompletedItemLiveStatus(item, "Команда выполнена")
            "fileChange" -> if (started) buildFileChangeItemLiveStatus(item) else buildCompletedItemLiveStatus(item, "Изменение файла завершено")
            "mcpToolCall" -> if (started) buildMcpToolLiveStatus(item) else buildCompletedItemLiveStatus(item, "Вызов инструмента завершён")
            "dynamicToolCall" -> if (started) buildDynamicToolLiveStatus(item) else buildCompletedItemLiveStatus(item, "Работа инструмента завершена")
            "collabAgentToolCall" -> if (started) buildCollabAgentLiveStatus(item) else buildCompletedItemLiveStatus(item, "Подзадача завершена")
            "agentMessage" -> if (started) "Готовлю ответ…" else "Ответ завершён, синхронизирую…"
            else -> null
        }
    }

internal fun MainActivity.buildAssistantOutputLiveStatus(turnKey: String, itemKey: String): String {
        val bufferedLength = assistantDeltaBuffers[buildAssistantBubbleKey(turnKey, itemKey)]?.length ?: 0
        return if (bufferedLength > 0) {
            "Вывод: около $bufferedLength символов"
        } else {
            "Вывод…"
        }
    }

internal fun MainActivity.buildApprovalLiveStatus(eventName: String, payload: JSONObject): String {
        val detail = when (eventName) {
            "item/fileChange/requestApproval", "applyPatchApproval" -> describeApprovalFileChanges(payload)
            "item/permissions/requestApproval" -> describePermissionApproval(payload)
            "execCommandApproval", "item/commandExecution/requestApproval" -> describeApprovalCommand(payload)
            else -> ""
        }
        val label = when (eventName) {
            "item/fileChange/requestApproval", "applyPatchApproval" -> "Ожидание подтверждения изменения файла"
            "item/permissions/requestApproval" -> "Ожидание подтверждения прав"
            "execCommandApproval", "item/commandExecution/requestApproval" -> "Ожидание подтверждения команды"
            else -> "Ожидание подтверждения"
        }
        return joinLiveStatus(label, detail)
    }

internal fun MainActivity.buildFileChangePatchLiveStatus(payload: JSONObject): String {
        val diffEntries = payload.optJSONArray("changes").toConversationDiffEntries()
        return joinLiveStatus("Изменяю файлы", describeDiffEntries(diffEntries))
    }

internal fun MainActivity.buildTurnDiffLiveStatus(payload: JSONObject): String {
        val stats = buildDiffStatsLine(diffEntries = emptyList(), fallbackDiff = payload.optString("diff", ""))
        return joinLiveStatus("Изменяю файлы", stats.orEmpty())
    }

internal fun MainActivity.buildReasoningLiveStatus(item: JSONObject): String {
        val summaryCount = item.optJSONArray("summary")?.length() ?: 0
        val contentCount = item.optJSONArray("content")?.length() ?: 0
        val detail = when {
            summaryCount > 0 && contentCount > 0 -> "$summaryCount сводок / $contentCount фрагментов рассуждений"
            summaryCount > 0 -> "$summaryCount сводок"
            contentCount > 0 -> "$contentCount фрагментов рассуждений"
            else -> ""
        }
        return joinLiveStatus("Размышляю", detail)
    }

internal fun MainActivity.buildCommandExecutionLiveStatus(item: JSONObject): String {
        return joinLiveStatus("Выполняю команду", item.optString("command", "").trim())
    }

internal fun MainActivity.buildFileChangeItemLiveStatus(item: JSONObject): String {
        val diffEntries = item.optJSONArray("changes").toConversationDiffEntries()
        return joinLiveStatus("Изменяю файлы", describeDiffEntries(diffEntries))
    }

internal fun MainActivity.buildMcpToolLiveStatus(item: JSONObject): String {
        return joinLiveStatus("Вызываю инструмент", firstNonEmpty(item.optString("server", ""), item.optString("tool", "")))
    }

internal fun MainActivity.buildDynamicToolLiveStatus(item: JSONObject): String {
        val toolName = listOf(item.optString("namespace", ""), item.optString("tool", ""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(".")
        return joinLiveStatus("Запускаю инструмент", toolName)
    }

internal fun MainActivity.buildCollabAgentLiveStatus(item: JSONObject): String {
        val prompt = item.optString("prompt", "").trim()
        return joinLiveStatus("Обрабатываю подзадачу", item.optString("tool", "").ifBlank { prompt })
    }

internal fun MainActivity.buildCompletedItemLiveStatus(item: JSONObject, fallback: String): String {
        val status = item.optString("status", "").trim()
        return when (status) {
            "failed" -> "${fallback.removeSuffix("Завершено")}Не удалось"
            "declined" -> "${fallback.removeSuffix("Завершено")}Отклонено"
            else -> "$fallback, продолжаю ответ…"
        }
    }

internal fun MainActivity.describeApprovalCommand(payload: JSONObject): String {
        return firstNonEmpty(
            payload.optString("command", ""),
            payload.optJSONObject("item")?.optString("command", "") ?: "",
            payload.optString("reason", ""),
        )
    }

internal fun MainActivity.describeApprovalFileChanges(payload: JSONObject): String {
        val diffEntries = payload.optJSONObject("fileChanges").toApprovalDiffEntries()
        return describeDiffEntries(diffEntries).ifBlank { payload.optString("reason", "") }
    }

internal fun MainActivity.describePermissionApproval(payload: JSONObject): String {
        return firstNonEmpty(
            payload.optString("grantRoot", ""),
            payload.optString("cwd", ""),
            payload.optString("reason", ""),
        )
    }

internal fun MainActivity.describeDiffEntries(diffEntries: List<ConversationDiffEntry>): String {
        if (diffEntries.isEmpty()) return ""
        val stats = buildDiffStatsLine(diffEntries = diffEntries, fallbackDiff = null)
        val fileLabel = when (diffEntries.size) {
            1 -> diffEntries.first().filenameLabel()
            else -> "${diffEntries.size} файлов"
        }
        return listOf(fileLabel, stats).filter { !it.isNullOrBlank() }.joinToString(" ")
    }

internal fun joinLiveStatus(label: String, detail: String): String {
        val cleanLabel = label.trim()
        val cleanDetail = compactLiveStatusDetail(detail)
        return if (cleanDetail.isBlank()) "$cleanLabel…" else "$cleanLabel: $cleanDetail"
    }

internal fun compactLiveStatusDetail(value: String, maxLength: Int = 42): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxLength) return normalized
        return normalized.take(maxLength - 1).trimEnd() + "…"
    }

internal fun MainActivity.updateLiveTurnStatusFromWarning(payload: JSONObject) {
        val message = extractWarningText(payload)
        if (message.contains("Long threads", ignoreCase = true)) {
            updateLiveTurnStatus("Сжимаю длинный контекст…")
        }
    }

internal fun MainActivity.updateLiveTurnStatusFromError(payload: JSONObject) {
        val error = payload.optJSONObject("error") ?: return
        val message = cleanDisplayText(error.opt("message"))
        when {
            message.startsWith("Reconnecting...", ignoreCase = true) -> updateLiveTurnStatus("Нестабильное соединение Codex, повторяю…")
            payload.optBoolean("willRetry", false) -> updateLiveTurnStatus("Ошибка запроса Codex, повторяю…")
            message.isNotBlank() -> updateLiveTurnStatus("Аномальный ответ Codex")
        }
    }

internal fun MainActivity.updateLiveTurnStatusFromThreadStatus(status: JSONObject?) {
        when (status?.optString("type", "")?.trim()) {
            "active" -> updateLiveTurnStatus("Идёт ход…")
            "idle" -> if (activeTurnId != null) updateLiveTurnStatus("Идёт ход…")
            "systemError" -> updateLiveTurnStatus("Аномальный ответ Codex")
        }
    }

internal fun MainActivity.updateLiveTurnStatus(status: String?) {
        liveTurnStatus = status?.trim()?.takeIf { it.isNotBlank() }
    }

internal fun MainActivity.labelRawResponseRole(role: String): String {
        return when (role) {
            "assistant" -> "Ассистент"
            "developer" -> "Разработчик"
            "user" -> "Пользователь"
            else -> role.ifBlank { "Сообщение" }
        }
    }

internal fun MainActivity.labelThreadItem(item: JSONObject): String {
        return when (item.optString("type", "")) {
            "plan" -> "План"
            "reasoning" -> "Рассуждения"
            "commandExecution" -> "Выполнение команды"
            "fileChange" -> "Изменение файла"
            "imageView" -> "Просмотр изображений"
            "imageGeneration" -> "Генерация изображений"
            "contextCompaction" -> "Сжатие контекста"
            else -> item.optString("type", "Проект")
        }
    }

internal fun MainActivity.extractWarningText(payload: JSONObject): String {
        val message = payload.optString("message", "").trim()
        return if (message.isNotBlank()) message else formatJson(payload)
    }

internal fun MainActivity.extractErrorText(error: JSONObject?): String {
        if (error == null) return ""
        val nestedMessage = cleanDisplayText(error.opt("message"))
        val parsedNested = nestedMessage.takeIf { it.startsWith("{") && it.endsWith("}") }?.let { raw ->
            try {
                JSONObject(raw)
            } catch (_: JSONException) {
                null
            }
        }
        val parts = mutableListOf<String>()
        cleanDisplayText(error.opt("code")).takeIf { it.isNotBlank() }?.let { parts.add(it) }
        if (parsedNested != null) {
            val nestedText = extractErrorText(parsedNested)
            if (nestedText.isNotBlank()) parts.add(nestedText)
        } else if (nestedMessage.isNotBlank()) {
            parts.add(nestedMessage)
        }
        cleanDisplayText(error.opt("additionalDetails")).takeIf { it.isNotBlank() }?.let { details ->
            if (details != nestedMessage) parts.add(details)
        }
        error.optJSONObject("codexErrorInfo")?.let { nested ->
            val nestedText = extractErrorText(nested)
            if (nestedText.isNotBlank()) parts.add(nestedText)
        }
        return if (parts.isNotEmpty()) parts.joinToString("\n") else error.keys().asSequence().joinToString("\n") { key ->
            val value = cleanDisplayText(error.opt(key))
            if (value.isNotBlank()) "$key: $value" else "$key"
        }
    }

internal fun MainActivity.describeThreadStatus(status: JSONObject?): String {
        if (status == null) return "Неизвестно"
        val type = status.optString("type", "").trim()
        return when (type) {
            "active" -> "Активна"
            "idle" -> "Свободно"
            "systemError" -> "Системная ошибка"
            "notLoaded" -> "Не загружено"
            else -> if (type.isNotBlank()) type else formatJson(status)
        }
    }

internal fun MainActivity.cleanDisplayText(value: Any?): String {
        val text = when (value) {
            null -> ""
            is String -> value
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value.toString()
        }.trim()
        return if (text.isBlank() || text.equals("null", ignoreCase = true)) "" else text
    }
