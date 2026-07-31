package com.haochen.codexremote

import org.json.JSONException
import org.json.JSONObject

internal fun MainActivity.handleIncoming(text: String) {
        val message = try {
            JSONObject(text)
        } catch (error: JSONException) {
            appendSystemNote("收到非 JSON 消息: $text")
            return
        }

        when (message.optString("type", "")) {
            "hello" -> {
                val payload = message.optJSONObject("payload") ?: JSONObject()
                val bridgeId = payload.optProtocolString("bridge_id")
                val url = currentBridgeUrl?.takeIf { it.isNotBlank() } ?: bridgeUrl.trim()
                mainHandler.removeCallbacks(bridgeHelloTimeoutRunnable)
                connected = true
                reconnectAttempt = 0
                cancelReconnectSchedule(resetAttempt = false)
                val entry = if (url.isNotBlank()) {
                    rememberConnectionHistory(url, bridgeId)
                } else {
                    null
                }
                if (entry != null) {
                    switchLocalSessionCache(entry)
                }
                connectionDetail = "Bridge 已就绪，正在拉取会话列表"
                currentPage = AppPage.Chat
                requestSessionList()
                requestModelList()
            }

            "event" -> handleEvent(message)
            "response", "pong", "error" -> handleResponse(message)
        }
    }

internal fun MainActivity.handleResponse(message: JSONObject) {
        val id = message.optString("id", "")
        val callback = pendingRequests.remove(id)
        val ok = message.optBoolean("ok", true)
        if (callback != null) {
            if (!ok) {
                val errorText = extractErrorText(message.optJSONObject("error")).ifBlank { formatJson(message.optJSONObject("error")) }
                callback.onError(errorText)
                if (!callback.suppressDefaultErrorUi()) {
                    appendSystemNote("请求失败: $errorText")
                    showNotice("请求失败: $errorText")
                }
                return
            }
            try {
                callback.onResponse(message)
            } catch (error: JSONException) {
                appendSystemNote("解析响应失败: ${error.message}")
            }
            return
        }
        if (!ok) {
            val errorText = extractErrorText(message.optJSONObject("error")).ifBlank { formatJson(message.optJSONObject("error")) }
            appendSystemNote("请求失败: $errorText")
            showNotice("请求失败: $errorText")
        }
    }

internal fun MainActivity.handleEvent(message: JSONObject) {
        if (shouldIgnoreEvent(message)) return

        val eventName = message.optString("event", "")
        val payload = message.optJSONObject("payload") ?: JSONObject()

        if (eventName == "thread/started") {
            val info = SessionInfo.fromThread(payload)
            if (info != null) {
                upsertSession(info)
                if (activeSessionId == null) {
                    selectSession(info.sessionId, syncHistory = false)
                }
            }
            return
        }

        if (payloadCarriesSessionMetadata(payload)) {
            SessionInfo.fromThread(payload)?.let(::upsertSession)
        }

        if (eventName == "session/changed") {
            val sessionId = message.optString("session_id", "")
            approvalDebugLog {
                "event session_changed sessionId=$sessionId activeSessionId=${activeSessionId.orEmpty()} syncInFlight=$syncInFlight activeTurnId=${activeTurnId.orEmpty()} pendingApprovals=${pendingApprovals.size}"
            }
            if (sessionId.isNotBlank() && sessionId == activeSessionId) {
                if (syncInFlight) {
                    sessionContentDirty = true
                } else {
                    requestSessionRefresh(sessionId)
                }
            }
            return
        }

        if (eventName == "serverRequest/resolved") {
            approvalDebugLog {
                "event server_request_resolved requestId=${firstNonEmpty(payload.optString("requestId", ""), payload.optString("request_id", ""))} threadId=${firstNonEmpty(payload.optString("threadId", ""), payload.optString("thread_id", ""))} pendingApprovals=${pendingApprovals.size}"
            }
        }

        val sessionId = message.optString("session_id", "")
        if (sessionId.isNotEmpty() && activeSessionId != null && sessionId != activeSessionId) {
            return
        }

        when (eventName) {
            "thread/goal/updated" -> {
                activeThreadGoal = ThreadGoalState.fromJson(payload.optJSONObject("goal"))
            }
            "thread/goal/cleared" -> {
                activeThreadGoal = null
                if (selectedChatMode == ChatMode.Goal) {
                    selectChatMode(ChatMode.Default)
                }
            }
            "turn/input" -> {
                handleTurnInput(message, payload)
            }

            "turn/started" -> {
                activeTurnId = firstNonEmpty(
                    message.optProtocolString("turn_id"),
                    payload.optJSONObject("turn")?.optProtocolString("id") ?: "",
                )
                interruptingTurnId = null
                updateLiveTurnStatus("已开始，等待输出…")
            }

            "item/agentMessage/delta" -> {
                handleAssistantDelta(message, payload)
            }
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
            "applyPatchApproval",
            "execCommandApproval" -> {
                approvalDebugLog {
                    "event approval_request event=$eventName requestId=${firstNonEmpty(message.optString("request_id", ""), message.optString("id", ""))} turnId=${extractExplicitTurnKey(message, payload)} decisions=${payload.optJSONArray("availableDecisions")?.toString() ?: "[]"}"
                }
                updateLiveTurnStatus(buildApprovalLiveStatus(eventName, payload))
                handleApprovalRequest(eventName, message, payload)
            }
            "item/fileChange/patchUpdated" -> {
                updateLiveTurnStatus(buildFileChangePatchLiveStatus(payload))
                handleFileChangePatchUpdated(message, payload)
            }
            "turn/diff/updated" -> {
                updateLiveTurnStatus(buildTurnDiffLiveStatus(payload))
                handleTurnDiffUpdated(message, payload)
            }
            "item/started", "item/completed" -> {
                val item = payload.optJSONObject("item")
                updateLiveTurnStatusFromItem(eventName, item)
                if (item != null) {
                    handleThreadItemEvent(eventName, payload)
                }
            }
            "warning" -> {
                approvalDebugLog { "event warning payload=${payload.toString()}" }
                updateLiveTurnStatusFromWarning(payload)
            }
            "error" -> {
                approvalDebugLog { "event error payload=${payload.toString()}" }
                updateLiveTurnStatusFromError(payload)
            }
            "thread/status/changed" -> updateLiveTurnStatusFromThreadStatus(payload.optJSONObject("status"))
            "turn/completed" -> {
                val completedTurnId = message.optProtocolString("turn_id")
                approvalDebugLog {
                    "event turn_completed turnId=$completedTurnId activeTurnId=${activeTurnId.orEmpty()} pendingApprovals=${pendingApprovals.size}"
                }
                removeCompletedTurnApprovalItems(completedTurnId)
                val completesActiveTurn = activeTurnId != null && activeTurnId == completedTurnId
                if (completesActiveTurn) {
                    activeTurnId = null
                }
                if (interruptingTurnId != null && interruptingTurnId == completedTurnId) {
                    interruptingTurnId = null
                }
                if (completesActiveTurn) {
                    updateLiveTurnStatus(null)
                }
            }

            else -> Unit
        }
    }

internal fun MainActivity.handleTurnInput(message: JSONObject, payload: JSONObject) {
        val text = firstNonEmpty(
            payload.optString("text", ""),
            payload.optString("message", ""),
        )
        val turnKey = firstNonEmpty(
            message.optProtocolString("turn_id"),
            payload.optProtocolString("turn_id"),
        )
        val itemKey = firstNonEmpty(
            payload.optProtocolString("itemId"),
            payload.optProtocolString("item_id"),
            message.optProtocolString("itemId"),
            message.optProtocolString("item_id"),
        )
        appendUserInputBubble(
            turnKey,
            itemKey,
            text,
            extractConversationUserInputImages(payload.optJSONArray("input")),
        )
    }

internal fun MainActivity.shouldIgnoreEvent(message: JSONObject): Boolean {
        val seq = message.optInt("seq", -1)
        val sessionId = message.optString("session_id", "")
        if (seq < 0 || sessionId.isEmpty()) {
            return false
        }
        val key = "$sessionId:$seq"
        if (renderedEventKeys.contains(key)) return true
        renderedEventKeys.add(key)
        return false
    }

internal fun MainActivity.payloadCarriesSessionMetadata(payload: JSONObject): Boolean {
        return payload.has("thread")
            || payload.has("cwd")
            || payload.has("model")
            || payload.has("modelProvider")
            || payload.has("approvalPolicy")
            || payload.has("sandbox")
            || payload.has("sandboxPolicy")
            || payload.has("permissions")
            || payload.has("activePermissionProfile")
            || payload.has("permissionProfile")
            || payload.has("tokenUsage")
            || payload.has("contextWindow")
            || payload.has("lastTokenUsage")
            || payload.has("totalTokenUsage")
    }

internal fun MainActivity.handleAssistantDelta(message: JSONObject, payload: JSONObject) {
        val turnKey = extractTurnKey(message, payload)
        val itemKey = extractAssistantItemKey(message = message, payload = payload)
        val delta = firstNonEmpty(
            payload.optString("delta", ""),
            payload.optString("text", ""),
            payload.optString("message", ""),
        )
        bufferAssistantDelta(turnKey, itemKey.ifBlank { "stream" }, delta)
        updateLiveTurnStatus(buildAssistantOutputLiveStatus(turnKey, itemKey.ifBlank { "stream" }))
    }

internal fun MainActivity.handleApprovalRequest(eventName: String, message: JSONObject, payload: JSONObject) {
        val requestId = firstNonEmpty(message.optString("request_id", ""), message.optString("id", ""))
        if (requestId.isBlank()) return

        val presentation = buildApprovalPresentation(eventName, payload)
        approvalDebugLog {
            "handleApprovalRequest requestId=$requestId turnId=${extractExplicitTurnKey(message, payload)} actionCount=${buildApprovalActions(eventName, payload).size}"
        }
        upsertPendingApproval(
            ApprovalDialogState(
                requestId = requestId,
                title = presentation.title,
                detail = presentation.detail,
                actions = buildApprovalActions(eventName, payload),
                diffEntries = presentation.diffEntries,
                turnId = extractExplicitTurnKey(message, payload).takeIf { it.isNotBlank() },
            ),
        )
    }
