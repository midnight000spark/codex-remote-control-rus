package com.haochen.codexremote

import android.graphics.Color as AndroidColor
import org.json.JSONArray
import org.json.JSONObject

internal fun MainActivity.buildConversationItemsFromSnapshot(payload: JSONObject?): List<ConversationItem> {
        if (payload == null) return emptyList()
        val items = mutableListOf<ConversationItem>()
        payload.optJSONArray("entries")?.let { entries ->
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                buildConversationItemFromSnapshotEntry(entry)?.let(items::add)
            }
        }
        return items
    }

internal fun MainActivity.buildPendingApprovalsFromSnapshot(payload: JSONObject?): List<ApprovalDialogState> {
        if (payload == null) return emptyList()
        val approvals = mutableListOf<ApprovalDialogState>()
        payload.optJSONArray("pending_approvals")?.let { array ->
            for (i in 0 until array.length()) {
                val approval = array.optJSONObject(i) ?: continue
                buildConversationApprovalFromSnapshot(approval)?.let(approvals::add)
            }
        }
        return approvals
    }

internal fun MainActivity.buildConversationItemFromSnapshotEntry(entry: JSONObject): ConversationItem? {
        return when (entry.optString("type", "")) {
            "item" -> buildConversationSnapshotThreadItem(entry)
            "turn_status" -> buildConversationSnapshotTurnStatus(entry)
            else -> null
        }
    }

internal fun MainActivity.buildConversationSnapshotThreadItem(entry: JSONObject): ConversationItem? {
        val item = entry.optJSONObject("item") ?: return null
        val itemType = item.optString("type", "").trim()
        val turnId = entry.optProtocolString("turn_id")
        val itemId = item.optProtocolString("id")
        val timestampMillis = entry.optTimestampMillis("at")
        return when (itemType) {
            "userMessage" -> {
                val text = extractThreadItemText(item)
                val images = extractThreadItemImages(item)
                if (text.isBlank() && images.isEmpty()) return null
                ConversationItem.Bubble(
                    id = buildUserConversationItemId(itemId, turnId),
                    right = true,
                    text = text,
                    images = images,
                    backgroundColor = 0xFF1A8F55.toInt(),
                    textColor = AndroidColor.WHITE,
                    turnKey = turnId.takeIf { it.isNotBlank() },
                    sourceItemId = itemId.takeIf { it.isNotBlank() },
                    timestampMillis = timestampMillis,
                )
            }
            "agentMessage" -> {
                val text = extractThreadItemText(item)
                if (text.isBlank()) return null
                ConversationItem.Bubble(
                    id = buildConversationItemId("assistant", itemId.ifBlank { turnId }, turnId),
                    right = false,
                    text = text,
                    backgroundColor = 0xFFF1F8F2.toInt(),
                    textColor = 0xFF183326.toInt(),
                    turnKey = turnId.ifBlank { "assistant" },
                    assistantKey = itemId.takeIf { it.isNotBlank() },
                    timestampMillis = timestampMillis,
                )
            }
            "imageView", "imageGeneration" -> {
                val images = extractThreadItemImages(item)
                if (images.isEmpty()) return null
                ConversationItem.Bubble(
                    id = buildConversationItemId("image", itemId.ifBlank { turnId }, turnId),
                    right = false,
                    text = "",
                    images = images,
                    backgroundColor = 0xFFF1F8F2.toInt(),
                    textColor = 0xFF183326.toInt(),
                    turnKey = turnId.takeIf { it.isNotBlank() },
                    sourceItemId = itemId.takeIf { it.isNotBlank() },
                    timestampMillis = timestampMillis,
                )
            }
            "fileChange" -> {
                val diffEntries = item.optJSONArray("changes").toConversationDiffEntries()
                val status = item.optString("status", "").trim()
                ConversationItem.FileChange(
                    id = buildConversationItemId("file_change", itemId.ifBlank { turnId }, turnId),
                    title = if (status == "inProgress") "文件修改中" else "文件修改",
                    summary = buildFileChangeSummary(status, diffEntries, fallbackDiff = null),
                    status = status,
                    diffEntries = diffEntries,
                    fallbackDiff = null,
                    sourceItemId = itemId.takeIf { it.isNotBlank() },
                    turnId = turnId.takeIf { it.isNotBlank() },
                )
            }
            else -> null
        }
    }

internal fun MainActivity.buildConversationSnapshotTurnStatus(entry: JSONObject): ConversationItem? {
        val turnId = entry.optProtocolString("turn_id")
        val status = entry.optString("status", "").trim()
        val errorText = extractErrorText(entry.optJSONObject("error"))
        if (status == "interrupted" && errorText.isBlank()) {
            return null
        }
        if (status != "failed" && status != "interrupted" && errorText.isBlank()) {
            return null
        }
        return ConversationItem.SystemNote(
            id = buildConversationItemId("turn_status", turnId.ifBlank { "unknown" }),
            text = buildString {
                append("当前回合结束: ${if (status.isNotBlank()) status else "unknown"}")
                if (errorText.isNotBlank()) {
                    append('\n')
                    append(errorText)
                }
            },
            itemKey = turnId.takeIf { it.isNotBlank() },
        )
    }

internal fun MainActivity.buildConversationApprovalFromSnapshot(approval: JSONObject): ApprovalDialogState? {
        val requestId = approval.optString("request_id", "").trim()
        if (requestId.isBlank()) return null
        val eventName = approval.optString("request_method", "").trim().ifBlank { "approval" }
        val payload = approval.optJSONObject("payload") ?: JSONObject()
        val presentation = buildApprovalPresentation(eventName, payload)
        return ApprovalDialogState(
            requestId = requestId,
            title = presentation.title,
            detail = presentation.detail,
            actions = buildApprovalActions(eventName, payload),
            diffEntries = presentation.diffEntries,
            turnId = approval.optProtocolString("turn_id").takeIf { it.isNotBlank() },
        )
    }

internal fun MainActivity.updateLiveTurnStatusFromSnapshot(
        payload: JSONObject?,
        hasIncrementalChanges: Boolean = false,
) {
        val entries = payload?.optJSONArray("entries")
        val snapshotSignature = entries?.snapshotSignature().orEmpty()
        val snapshotItemCount = entries?.length() ?: 0
        val hasPreviousSnapshot = lastSnapshotSignature != null
        val snapshotGrew = hasPreviousSnapshot && snapshotItemCount > lastSnapshotItemCount
        val snapshotChanged = hasPreviousSnapshot && snapshotSignature.isNotBlank() && snapshotSignature != lastSnapshotSignature
        reconcileActiveTurnFromSnapshot(payload)
        val status = inferLiveTurnStatusFromSnapshot(payload)
        when {
            status != null -> updateLiveTurnStatus(status)
            hasIncrementalChanges || snapshotGrew || snapshotChanged -> updateLiveTurnStatus("正在同步…")
            activeTurnId == null -> updateLiveTurnStatus(null)
            else -> Unit
        }
        if (snapshotSignature.isNotBlank()) {
            lastSnapshotSignature = snapshotSignature
            lastSnapshotItemCount = snapshotItemCount
        }
    }

internal fun MainActivity.reconcileActiveTurnFromSnapshot(payload: JSONObject?) {
        val activeTurns = payload?.optJSONArray("active_turns") ?: return
        if (activeTurns.length() == 0) {
            val turnId = activeTurnId
            if (turnId != null && payload.snapshotHasTerminalTurnStatus(turnId)) {
                activeTurnId = null
                if (interruptingTurnId == turnId) {
                    interruptingTurnId = null
                }
            }
            return
        }
        val turnId = activeTurns.optJSONObject(activeTurns.length() - 1)?.optProtocolString("turn_id").orEmpty()
        if (turnId.isNotBlank()) {
            activeTurnId = turnId
            if (interruptingTurnId == turnId) {
                interruptingTurnId = null
            }
        }
    }

internal fun MainActivity.inferLiveTurnStatusFromSnapshot(payload: JSONObject?): String? {
        val activeTurns = payload?.optJSONArray("active_turns")
        if (activeTurns != null) {
            inferLiveTurnStatusFromActiveTurns(activeTurns)?.let { return it }
            payload.optJSONArray("entries")?.let { entries ->
                inferLiveTurnStatusFromSnapshotEntries(entries)?.let { return it }
            }
            return null
        }
        val session = payload?.optJSONObject("session")
        inferLiveTurnStatusFromSession(session)?.let { return it }
        val entries = payload?.optJSONArray("entries") ?: return null
        return inferLiveTurnStatusFromSnapshotEntries(entries)
    }

internal fun MainActivity.inferLiveTurnStatusFromActiveTurns(activeTurns: JSONArray?): String? {
        if (activeTurns == null || activeTurns.length() == 0) return null
        val activeTurn = activeTurns.optJSONObject(activeTurns.length() - 1) ?: return "回合进行中…"
        val eventStatus = when (activeTurn.optString("last_event", "")) {
            "item/agentMessage/delta" -> buildActiveTurnOutputStatus(activeTurn)
            "item/fileChange/patchUpdated", "turn/diff/updated" -> buildActiveTurnFileStatus(activeTurn)
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
            "applyPatchApproval",
            "execCommandApproval" -> buildActiveTurnApprovalStatus(activeTurn)
            "item/completed" -> null
            "item/started" -> null
            "turn/input" -> "已开始，等待输出…"
            else -> null
        }
        return eventStatus ?: when (activeTurn.optString("last_item_type", "")) {
            "agentMessage" -> buildActiveTurnOutputStatus(activeTurn)
            "fileChange" -> buildActiveTurnFileStatus(activeTurn)
            "commandExecution" -> joinLiveStatus("正在执行命令", activeTurn.optString("detail", ""))
            "reasoning" -> "正在思考…"
            "plan" -> "正在整理计划…"
            "mcpToolCall" -> joinLiveStatus("正在调用工具", activeTurn.optString("detail", ""))
            "dynamicToolCall" -> joinLiveStatus("正在运行工具", activeTurn.optString("detail", ""))
            "collabAgentToolCall" -> joinLiveStatus("正在处理子任务", activeTurn.optString("detail", ""))
            "userMessage" -> "已开始，等待输出…"
            else -> "回合进行中…"
        }
    }

internal fun MainActivity.buildActiveTurnOutputStatus(activeTurn: JSONObject): String {
        val outputChars = activeTurn.optInt("output_chars", 0)
        return if (outputChars > 0) joinLiveStatus("正在输出", "约 $outputChars 字") else "正在输出…"
    }

internal fun MainActivity.buildActiveTurnFileStatus(activeTurn: JSONObject): String {
        val detail = firstNonEmpty(
            activeTurn.optString("detail", ""),
            activeTurn.optString("diff_stats", ""),
            activeTurn.optInt("file_count", 0).takeIf { it > 0 }?.let { "$it 个文件" } ?: "",
        )
        return joinLiveStatus("正在改文件", detail)
    }

internal fun MainActivity.buildActiveTurnApprovalStatus(activeTurn: JSONObject): String {
        val label = when (activeTurn.optString("approval_type", "")) {
            "command" -> "等待命令审批"
            "fileChange" -> "等待文件审批"
            "permission" -> "等待权限审批"
            else -> "等待审批"
        }
        return joinLiveStatus(label, activeTurn.optString("detail", ""))
    }

internal fun MainActivity.inferLiveTurnStatusFromActiveTurnItem(item: JSONObject): String? {
        return when (item.optString("type", "")) {
            "agentMessage" -> "正在输出…"
            "fileChange" -> "正在改文件…"
            "commandExecution" -> "正在执行命令…"
            "reasoning" -> "正在思考…"
            "plan" -> "正在整理计划…"
            "mcpToolCall" -> "正在调用工具…"
            "dynamicToolCall" -> "正在运行工具…"
            "collabAgentToolCall" -> "正在处理子任务…"
            "userMessage" -> "已开始，等待输出…"
            else -> null
        }
    }

internal fun MainActivity.inferLiveTurnStatusFromSession(session: JSONObject?): String? {
        if (session == null) return null
        val thread = session.optJSONObject("thread") ?: session
        inferLiveTurnStatusFromThreadTurns(thread)?.let { return it }
        return when (threadStatusType(thread.opt("status"))) {
            "active", "running" -> "回合进行中…"
            "systemError" -> "Codex 响应异常"
            else -> null
        }
    }

internal fun MainActivity.inferLiveTurnStatusFromThreadTurns(thread: JSONObject): String? {
        val turns = thread.optJSONArray("turns") ?: return null
        for (index in turns.length() - 1 downTo 0) {
            val turn = turns.optJSONObject(index) ?: continue
            val status = turn.optString("status", "").trim()
            val errorText = extractErrorText(turn.optJSONObject("error"))
            when {
                errorText.isNotBlank() -> return "Codex 响应异常"
                status == "inProgress" -> return inferLiveTurnStatusFromThreadTurn(turn) ?: "回合进行中…"
                status == "failed" -> return "Codex 响应异常"
                status == "completed" -> Unit
                status == "interrupted" -> Unit
                status.isNotBlank() -> return "回合进行中…"
            }
        }
        return null
    }

internal fun MainActivity.inferLiveTurnStatusFromThreadTurn(turn: JSONObject): String? {
        val items = turn.optJSONArray("items") ?: return null
        for (index in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(index) ?: continue
            inferLiveTurnStatusFromActiveTurnItem(item)?.let { return it }
        }
        return null
    }

internal fun threadStatusType(value: Any?): String {
        return when (value) {
            is JSONObject -> value.optString("type", "").trim()
            is String -> value.trim()
            else -> ""
        }
    }

internal fun MainActivity.inferLiveTurnStatusFromSnapshotEntries(entries: JSONArray): String? {
        inferOpenTurnLiveStatusFromSnapshotEntries(entries)?.let { return it }
        for (index in entries.length() - 1 downTo 0) {
            val entry = entries.optJSONObject(index) ?: continue
            when (entry.optString("type", "")) {
                "turn_status" -> {
                    val status = entry.optString("status", "").trim()
                    val errorText = extractErrorText(entry.optJSONObject("error"))
                    return when {
                        errorText.isNotBlank() -> "Codex 响应异常"
                        status == "failed" -> "Codex 响应异常"
                        status == "interrupted" -> null
                        else -> null
                    }
                }
                "item" -> {
                    val item = entry.optJSONObject("item") ?: continue
                    val itemStatus = item.optString("status", "").trim()
                    if (itemStatus == "inProgress") {
                        return when (item.optString("type", "")) {
                            "fileChange" -> "正在改文件…"
                            "commandExecution" -> "正在执行命令…"
                            "reasoning" -> "正在思考…"
                            "plan" -> "正在整理计划…"
                            else -> "回合进行中…"
                        }
                    }
                }
            }
        }
        return null
    }

internal fun MainActivity.inferOpenTurnLiveStatusFromSnapshotEntries(entries: JSONArray): String? {
        val startedTurns = linkedSetOf<String>()
        val completedTurns = mutableSetOf<String>()
        val lastItemsByTurn = linkedMapOf<String, JSONObject>()

        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val turnId = entry.optProtocolString("turn_id")
            if (turnId.isBlank()) continue
            when (entry.optString("type", "")) {
                "turn_started" -> {
                    startedTurns.add(turnId)
                }
                "turn_status" -> {
                    val status = entry.optString("status", "").trim()
                    if (status != "inProgress" && status.isNotBlank()) {
                        completedTurns.add(turnId)
                    }
                }
                "item" -> {
                    entry.optJSONObject("item")?.let { item ->
                        lastItemsByTurn[turnId] = item
                    }
                }
            }
        }

        val openTurnId = startedTurns.lastOrNull { turnId -> !completedTurns.contains(turnId) } ?: return null
        val item = lastItemsByTurn[openTurnId]
        return item?.let(::inferLiveTurnStatusFromActiveTurnItem) ?: "回合进行中…"
    }

private fun JSONArray.snapshotSignature(): String {
    val tail = buildList {
        for (index in (length() - 4).coerceAtLeast(0) until length()) {
            val entry = optJSONObject(index) ?: continue
            val item = entry.optJSONObject("item")
            add(
                listOf(
                    entry.optString("type", ""),
                    entry.optProtocolString("turn_id"),
                    item?.optProtocolString("id").orEmpty(),
                    item?.optString("type", "").orEmpty(),
                    item?.optString("status", "").orEmpty(),
                    item?.snapshotTextLength()?.toString().orEmpty(),
                    entry.optString("status", ""),
                ).joinToString(":"),
            )
        }
    }
    return "${length()}|${tail.joinToString("|")}"
}

private fun JSONObject.snapshotHasTerminalTurnStatus(turnId: String): Boolean {
    val entries = optJSONArray("entries") ?: return false
    for (index in entries.length() - 1 downTo 0) {
        val entry = entries.optJSONObject(index) ?: continue
        if (entry.optProtocolString("turn_id") != turnId) continue
        if (entry.optString("type", "") != "turn_status") continue
        val status = entry.optString("status", "").trim()
        if (status.isNotBlank() && status != "inProgress") {
            return true
        }
    }
    return false
}

private fun JSONObject.snapshotTextLength(): Int {
    val text = optString("text", "")
    if (text.isNotEmpty()) return text.length
    val content = optJSONArray("content") ?: return 0
    var length = 0
    for (index in 0 until content.length()) {
        length += content.optJSONObject(index)?.optString("text", "")?.length ?: 0
    }
    return length
}
