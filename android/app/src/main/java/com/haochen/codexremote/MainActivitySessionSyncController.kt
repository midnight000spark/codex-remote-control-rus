package com.haochen.codexremote

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal const val SYNC_LOG_TAG = "CodexRemoteSync"

internal inline fun syncDebugLog(message: () -> String) {
    if (isSyncDebugLoggable()) {
        Log.d(SYNC_LOG_TAG, message())
    }
}

internal fun isSyncDebugLoggable(): Boolean {
    return Log.isLoggable(SYNC_LOG_TAG, Log.DEBUG)
}

internal fun MainActivity.requestSessionRefresh(sessionId: String) {
        if (sessionIncrementalSyncEnabled) {
            requestSessionSync(sessionId)
        } else {
            requestSessionContent(sessionId)
        }
    }

internal fun MainActivity.requestSessionSync(sessionId: String) {
        if (sessionId.isBlank()) {
            syncDebugLog { "session.sync skip blank session" }
            return
        }
        if (!ensureConnected()) {
            syncDebugLog { "session.sync skip disconnected sessionId=$sessionId" }
            return
        }
        if (!sessionSyncCursorReady) {
            syncDebugLog { "session.sync fallback content cursor_not_ready sessionId=$sessionId lastSyncedSeq=$lastSyncedSeq conversationItems=${conversationItems.size}" }
            requestSessionContent(sessionId)
            return
        }
        if (syncInFlight) {
            sessionContentDirty = true
            syncDebugLog { "session.sync defer in_flight sessionId=$sessionId lastSyncedSeq=$lastSyncedSeq dirty=$sessionContentDirty" }
            return
        }

        syncInFlight = true
        val requestedSessionId = sessionId
        val sinceSeq = lastSyncedSeq
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("since_seq", sinceSeq)
            put("incremental", true)
        }
        syncDebugLog { "session.sync request sessionId=$requestedSessionId since=$sinceSeq conversationItems=${conversationItems.size} pendingApprovals=${pendingApprovals.size}" }

        if (!sendRequest("session.sync", payload, object : ResponseHandler {
            override fun onResponse(response: JSONObject) {
                syncInFlight = false
                if (activeSessionId != requestedSessionId) {
                    sessionContentDirty = false
                    syncDebugLog { "session.sync ignore inactive requested=$requestedSessionId active=$activeSessionId" }
                    return
                }

                val responsePayload = response.optJSONObject("payload")
                if (responsePayload == null) {
                    syncDebugLog { "session.sync fallback content missing_payload sessionId=$requestedSessionId" }
                    requestSessionContent(requestedSessionId)
                    return
                }

                val changedTurnIds = responsePayload.optJSONArray("changed_turn_ids").toStringList()
                val needsFullSync = responsePayload.optBoolean("needs_full_sync", false)
                syncDebugLog { "sessionId=$requestedSessionId since=$sinceSeq ready=$sessionSyncCursorReady last=${responsePayload.optInt("last_seq", lastSyncedSeq)} entries=${responsePayload.optJSONArray("entries")?.length() ?: 0} turns=$changedTurnIds needsFull=$needsFullSync reason=${responsePayload.optString("fallback_reason", "")}" }
                logSessionSyncEntries("session.sync response entries", responsePayload)

                if (needsFullSync) {
                    syncDebugLog { "session.sync fallback content needs_full_sync sessionId=$requestedSessionId reason=${responsePayload.optString("fallback_reason", "")}" }
                    requestSessionContent(requestedSessionId)
                    return
                }

                val result = applySessionSyncSnapshot(responsePayload, changedTurnIds)
                if (!result) {
                    syncDebugLog { "session.sync fallback content apply_failed sessionId=$requestedSessionId" }
                    requestSessionContent(requestedSessionId)
                    return
                }

                updateSessionSyncCursor(responsePayload)
                val mergedPayload = buildCurrentSessionCachePayload(requestedSessionId, responsePayload)
                persistLocalSessionContent(requestedSessionId, mergedPayload)
                updateLiveTurnStatusFromSnapshot(
                    mergedPayload,
                    hasIncrementalChanges = changedTurnIds.isNotEmpty(),
                )
                syncDebugLog { "session.sync applied sessionId=$requestedSessionId conversationItems=${conversationItems.size} cacheEntries=${mergedPayload.optJSONArray("entries")?.length() ?: 0}" }
                flushPendingSessionRefresh(requestedSessionId)
            }

            override fun onError(errorText: String) {
                syncInFlight = false
                if (activeSessionId != requestedSessionId) {
                    sessionContentDirty = false
                    syncDebugLog { "session.sync error ignored inactive requested=$requestedSessionId active=$activeSessionId error=$errorText" }
                    return
                }
                syncDebugLog { "session.sync fallback content error sessionId=$requestedSessionId error=$errorText" }
                requestSessionContent(requestedSessionId)
            }

            override fun suppressDefaultErrorUi(): Boolean = true
        })) {
            syncInFlight = false
            syncDebugLog { "session.sync send_failed sessionId=$requestedSessionId" }
        }
    }

internal fun MainActivity.applySessionSyncSnapshot(payload: JSONObject, changedTurnIds: List<String>): Boolean {
        val entries = payload.optJSONArray("entries") ?: JSONArray()
        val snapshotApprovals = buildPendingApprovalsFromSnapshot(payload)
        if (changedTurnIds.isEmpty()) {
            replacePendingApprovals(snapshotApprovals)
            syncDebugLog { "session.sync apply approvals_only entries=${entries.length()} approvals=${snapshotApprovals.size}" }
            return true
        }

        if (changedTurnIds.any { it.isBlank() }) {
            syncDebugLog { "session.sync apply reject blank_changed_turn turns=$changedTurnIds" }
            return false
        }
        if (!entriesMatchChangedTurns(entries, changedTurnIds)) {
            syncDebugLog { "session.sync apply reject entries_mismatch turns=$changedTurnIds entryTurns=${entries.turnIdSummary()}" }
            return false
        }

        val incrementalItems = buildConversationItemsFromSnapshot(payload)
        syncDebugLog { "session.sync apply parsed entries=${entries.length()} incrementalItems=${incrementalItems.size} items=${incrementalItems.itemSummary()}" }
        if (incrementalItems.isEmpty() && entries.length() > 0 && !entriesContainOnlyTurnStatus(entries)) {
            syncDebugLog { "session.sync apply reject empty_items_with_entries" }
            return false
        }
        if (incrementalItems.any { it.snapshotTurnKey().isNullOrBlank() }) {
            syncDebugLog { "session.sync apply reject blank_item_turn items=${incrementalItems.itemSummary()}" }
            return false
        }

        val changedTurnSet = changedTurnIds.toSet()
        if (!canReplaceConversationTurns(changedTurnSet, incrementalItems)) {
            syncDebugLog { "session.sync apply reject id_conflict changedTurns=$changedTurnIds incoming=${incrementalItems.itemSummary()} existing=${conversationItems.itemSummary()}" }
            return false
        }

        val incomingUserSourceItemIds = incrementalItems.userSourceItemIds()
        val nextItems = conversationItems.filterNot { item ->
            item.snapshotTurnKey()?.let(changedTurnSet::contains) == true
                || item.isUnboundUserInputShadowOf(incomingUserSourceItemIds)
        } + incrementalItems
        if (nextItems.distinctBy { it.id }.size != nextItems.size) {
            syncDebugLog { "session.sync apply reject duplicate_next_ids next=${nextItems.itemSummary()}" }
            return false
        }

        assistantItemIds.clear()
        toolItemIds.clear()
        fileChangeItemIds.clear()
        fileChangeTurnIds.clear()
        turnDiffItemIds.clear()
        turnDiffs.clear()

        conversationItems.clear()
        conversationItems.addAll(nextItems)
        conversationSnapshotVersion += 1
        replacePendingApprovals(snapshotApprovals)
        rebuildConversationIndexes(nextItems)

        codeBrowserState?.let { state ->
            if (!state.conversationItemId.startsWith("approval_") && nextItems.none { it.id == state.conversationItemId }) {
                codeBrowserState = null
            }
        }
        syncDebugLog { "session.sync apply success changedTurns=$changedTurnIds conversationItems=${conversationItems.size} approvals=${pendingApprovals.size}" }
        return true
    }

internal fun MainActivity.updateSessionSyncCursor(payload: JSONObject?) {
        val lastSeq = payload?.optInt("last_seq", -1) ?: -1
        if (lastSeq >= 0) {
            val previousSeq = lastSyncedSeq
            lastSyncedSeq = lastSeq
            sessionSyncCursorReady = true
            syncDebugLog { "cursor advanced previous=$previousSeq next=$lastSyncedSeq" }
        } else {
            syncDebugLog { "cursor not advanced missing_last_seq current=$lastSyncedSeq" }
        }
    }

internal fun MainActivity.buildCurrentSessionCachePayload(sessionId: String, syncPayload: JSONObject): JSONObject {
        val changedTurnIds = syncPayload.optJSONArray("changed_turn_ids").toStringList().toSet()
        val previousPayload = sessionContentCache[sessionId]?.let { JSONObject(it.toString()) } ?: JSONObject()
        val previousEntries = previousPayload.optJSONArray("entries") ?: JSONArray()
        val syncEntries = syncPayload.optJSONArray("entries") ?: JSONArray()
        val mergedEntries = JSONArray()

        if (changedTurnIds.isEmpty()) {
            for (index in 0 until previousEntries.length()) {
                previousEntries.optJSONObject(index)?.let { mergedEntries.put(JSONObject(it.toString())) }
            }
        } else {
            for (index in 0 until previousEntries.length()) {
                val entry = previousEntries.optJSONObject(index) ?: continue
                val turnId = entry.optProtocolString("turn_id")
                if ((turnId.isBlank() || !changedTurnIds.contains(turnId)) && !entry.isUnboundUserInputShadowOf(syncEntries)) {
                    mergedEntries.put(JSONObject(entry.toString()))
                }
            }
            for (index in 0 until syncEntries.length()) {
                syncEntries.optJSONObject(index)?.let { mergedEntries.put(JSONObject(it.toString())) }
            }
        }

        return JSONObject(previousPayload.toString()).apply {
            put("session_id", syncPayload.optString("session_id", optString("session_id", sessionId)))
            put("thread_id", syncPayload.optString("thread_id", optString("thread_id", sessionId)))
            put("last_seq", syncPayload.optInt("last_seq", optInt("last_seq", lastSyncedSeq)))
            put("entries", mergedEntries)
            syncPayload.optJSONArray("active_turns")?.let { put("active_turns", JSONArray(it.toString())) }
            syncPayload.optJSONArray("pending_approvals")?.let { put("pending_approvals", JSONArray(it.toString())) }
            if (!has("session")) {
                activeSession()?.let { put("session", it.toJson()) }
            }
        }
    }

internal fun MainActivity.logSessionSyncEntries(prefix: String, payload: JSONObject?) {
    if (!isSyncDebugLoggable()) return
    val entries = payload?.optJSONArray("entries") ?: JSONArray()
    val summary = buildList {
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val item = entry.optJSONObject("item")
            add(
                listOf(
                    entry.optProtocolString("turn_id"),
                    item?.optString("type", "").orEmpty(),
                    item?.optProtocolString("id").orEmpty(),
                    (item?.optString("text", "")?.length ?: item?.optJSONArray("content")?.optJSONObject(0)?.optString("text", "")?.length ?: 0).toString(),
                    entry.optString("status", ""),
                ).joinToString(":"),
            )
        }
    }
    syncDebugLog { "$prefix count=${entries.length()} tail=${summary.takeLast(8)}" }
}

private fun MainActivity.entriesMatchChangedTurns(entries: JSONArray, changedTurnIds: List<String>): Boolean {
    val changedTurnSet = changedTurnIds.toSet()
    for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: return false
        val turnId = entry.optProtocolString("turn_id")
        if (turnId.isBlank() || !changedTurnSet.contains(turnId)) return false
    }
    return true
}

private fun entriesContainOnlyTurnStatus(entries: JSONArray): Boolean {
    if (entries.length() == 0) return false
    for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: return false
        if (entry.optString("type", "") != "turn_status") return false
    }
    return true
}

private fun MainActivity.canReplaceConversationTurns(
    changedTurnIds: Set<String>,
    incrementalItems: List<ConversationItem>,
): Boolean {
    val incomingIds = incrementalItems.map { it.id }
    if (incomingIds.distinct().size != incomingIds.size) return false

    val incomingUserSourceItemIds = incrementalItems.userSourceItemIds()
    val changedIds = conversationItems
        .filter { item -> item.snapshotTurnKey()?.let(changedTurnIds::contains) == true }
        .map { it.id }
        .toSet()
    val unchangedIds = conversationItems
        .filterNot { item ->
            item.snapshotTurnKey()?.let(changedTurnIds::contains) == true
                || item.isUnboundUserInputShadowOf(incomingUserSourceItemIds)
        }
        .map { it.id }
        .toSet()

    return incomingIds.none { id -> unchangedIds.contains(id) && !changedIds.contains(id) }
}

private fun List<ConversationItem>.userSourceItemIds(): Set<String> {
    return mapNotNull { item ->
        val bubble = item as? ConversationItem.Bubble ?: return@mapNotNull null
        if (!bubble.right) return@mapNotNull null
        bubble.sourceItemId?.trim()?.takeIf { it.isNotBlank() }
    }.toSet()
}

private fun ConversationItem.isUnboundUserInputShadowOf(sourceItemIds: Set<String>): Boolean {
    if (sourceItemIds.isEmpty()) return false
    val bubble = this as? ConversationItem.Bubble ?: return false
    return bubble.right
        && bubble.turnKey.isNullOrBlank()
        && bubble.sourceItemId?.trim()?.let(sourceItemIds::contains) == true
}

private fun JSONObject.isUnboundUserInputShadowOf(syncEntries: JSONArray): Boolean {
    if (optProtocolString("turn_id").isNotEmpty()) return false
    val item = optJSONObject("item") ?: return false
    if (item.optString("type", "") != "userMessage") return false
    val itemId = item.optProtocolString("id")
    if (itemId.isBlank()) return false
    for (index in 0 until syncEntries.length()) {
        val syncEntry = syncEntries.optJSONObject(index) ?: continue
        if (syncEntry.optProtocolString("turn_id").isBlank()) continue
        val syncItem = syncEntry.optJSONObject("item") ?: continue
        if (syncItem.optString("type", "") == "userMessage" && syncItem.optProtocolString("id") == itemId) {
            return true
        }
    }
    return false
}

private fun MainActivity.rebuildConversationIndexes(items: List<ConversationItem>) {
    items.forEach { item ->
        when (item) {
            is ConversationItem.Bubble -> {
                item.assistantKey?.takeIf { it.isNotBlank() }?.let { assistantKey ->
                    val bubbleKey = buildAssistantBubbleKey(item.turnKey.orEmpty(), assistantKey)
                    assistantItemIds[bubbleKey] = item.id
                    assistantDeltaBuffers.remove(bubbleKey)
                }
            }
            is ConversationItem.FileChange -> {
                item.sourceItemId?.takeIf { it.isNotBlank() }?.let { sourceId ->
                    fileChangeItemIds[sourceId] = item.id
                }
                item.turnId?.takeIf { it.isNotBlank() }?.let { turnId ->
                    item.sourceItemId?.takeIf { it.isNotBlank() }?.let { sourceId -> fileChangeTurnIds[sourceId] = turnId }
                }
            }
            else -> Unit
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optProtocolString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray.turnIdSummary(): List<String> {
    return buildList {
        for (index in 0 until length()) {
            val entry = optJSONObject(index) ?: continue
            add("${entry.optProtocolString("turn_id")}:${entry.optJSONObject("item")?.optString("type", "").orEmpty()}:${entry.optJSONObject("item")?.optProtocolString("id").orEmpty()}")
        }
    }
}

private fun List<ConversationItem>.itemSummary(): List<String> {
    return takeLast(12).map { item ->
        when (item) {
            is ConversationItem.Bubble -> "${item.id}:${if (item.right) "user" else "assistant"}:${item.turnKey.orEmpty()}:${item.assistantKey.orEmpty()}:${item.sourceItemId.orEmpty()}:${item.text.length}"
            is ConversationItem.FileChange -> "${item.id}:fileChange:${item.turnId.orEmpty()}:${item.sourceItemId.orEmpty()}"
            is ConversationItem.SystemNote -> "${item.id}:system:${item.itemKey.orEmpty()}:${item.text.length}"
        }
    }
}

private fun ConversationItem.snapshotTurnKey(): String? {
    return when (this) {
        is ConversationItem.Bubble -> turnKey?.trim()?.takeIf { it.isNotBlank() }
        is ConversationItem.FileChange -> turnId?.trim()?.takeIf { it.isNotBlank() }
        is ConversationItem.SystemNote -> itemKey?.trim()?.takeIf { it.isNotBlank() }
    }
}
