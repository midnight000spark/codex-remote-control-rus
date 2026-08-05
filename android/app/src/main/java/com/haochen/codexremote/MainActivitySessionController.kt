package com.haochen.codexremote

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun MainActivity.requestSessionList() {
        if (!ensureConnected()) return
        val requestPayload = JSONObject().apply {
            if (sessionHistoryMonths > 0) {
                put("history_months", sessionHistoryMonths)
            }
        }
        sendRequest("session.list", requestPayload) { response ->
            val payload = response.optJSONObject("payload")
            val array = payload?.optJSONArray("sessions")
            val newSessions = mutableListOf<SessionInfo>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val info = array.optJSONObject(i)?.let { SessionInfo.fromSession(it) }
                    if (info != null) newSessions.add(info)
                }
            }
            replaceSessions(newSessions)
            persistLocalSessionList()
            if (activeSessionId != null && sessions.none { it.sessionId == activeSessionId }) {
                stopSessionSyncLoop()
                activeSessionId = null
                prefs.edit().remove(KEY_SESSION).apply()
            }
            if (!bootSyncRequested) {
                val targetSession = activeSessionId ?: sessions.firstOrNull()?.sessionId
                if (!targetSession.isNullOrBlank()) {
                    bootSyncRequested = true
                    selectSession(targetSession, syncHistory = true)
                }
            }
        }
    }

internal fun MainActivity.requestModelList() {
        if (!ensureConnected()) return
        val payload = JSONObject().apply {
            put("includeHidden", false)
        }

        sendRequest("model.list", payload) { response ->
            val array = response.optJSONObject("payload")?.optJSONArray("data")
            val newModels = mutableListOf<ModelInfo>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { json ->
                        val info = ModelInfo.fromJson(json)
                        if (info != null) newModels.add(info)
                    }
                }
            }
            replaceModels(newModels)
            val defaultModel = newModels.firstOrNull { it.isDefault } ?: newModels.firstOrNull()
            if (!isKnownModel(selectedModel)) {
                defaultModel?.let { selectModel(it.model) }
            }
        }
    }

internal fun MainActivity.startNewSession(
        projectPath: String?,
        model: String,
        reasoningEffort: String,
        approvalPolicy: String,
        sandbox: String,
    ) {
        if (!ensureConnected()) return
        if (model.isBlank()) {
            showNotice("请先选择一个模型")
            return
        }

        val payload = JSONObject().apply {
            put("title", workspaceDisplayName(projectPath, fallback = "新对话"))
            if (!projectPath.isNullOrBlank()) {
                put("cwd", projectPath)
            }
            put("sessionStartSource", "startup")
            put("threadSource", "user")
            put("model", model)
            put("approvalPolicy", approvalPolicy)
            put("sandbox", toSandboxModeValue(sandbox))
            put(
                "config",
                JSONObject().apply {
                    put("model_reasoning_effort", reasoningEffort)
                },
            )
        }

        if (sendRequest("session.start", payload) { response ->
                val info = response.optJSONObject("payload")?.optJSONObject("session")?.let { SessionInfo.fromSession(it) }
                if (info != null) {
                    upsertSession(info)
                    selectSession(info.sessionId, syncHistory = false)
                    currentPage = AppPage.Chat
                    selectedWorkspace = info.cwd.takeIf { it.isNotBlank() } ?: projectPath
                    syncChatSettingsFromSession(info)
                    requestActiveGoal(info.sessionId)
                    appendSystemNote("新会话已创建: ${shortId(info.sessionId)}")
                    showNotice("新对话已创建")
                }
            }
        ) {
            currentPage = AppPage.Chat
        }
    }

internal fun MainActivity.sendComposerText() {
        val text = composerText.trim()
        val attachments = composerImageAttachments.toList()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (composerImageUploadInProgress) {
            showNotice("图片仍在上传")
            return
        }
        if (!ensureConnected()) return
        val sessionId = activeSessionId
        if (sessionId.isNullOrBlank()) {
            appendSystemNote("先创建或选择一个会话")
            return
        }
        val turnId = activeTurnId
        if (!turnId.isNullOrBlank() && interruptingTurnId == turnId) {
            showNotice("正在中断当前回合")
            return
        }

        if (!turnId.isNullOrBlank()) {
            steerCurrentTurn(sessionId, turnId, text, attachments)
            return
        }
        if (selectedChatMode == ChatMode.Goal && activeThreadGoal == null) {
            chatSettingsSheet = ChatSettingsSheet.Mode
            showNotice("请先设置目标")
            return
        }

        val model = resolveModelForSend()
        val effort = resolveReasoningEffortForSend(model)
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("text", text)
            put("input", buildComposerTurnInput(text, attachments))
            if (model.isNotBlank()) {
                put("model", model)
            }
            if (effort.isNotBlank()) {
                put("effort", effort)
            }
            selectedReasoningSummary.takeIf { it.isNotBlank() }?.let { put("summary", it) }
            put("sandboxPolicy", toSandboxModeValue(selectedSandbox))
            buildCollaborationModePayload(model, effort)?.let { put("collaborationMode", it) }
            activeSession()?.approvalPolicy?.takeIf { it.isNotBlank() }?.let { put("approvalPolicy", it) }
        }

        if (!sendRequest("turn.send", payload) { response ->
            val turn = response.optJSONObject("payload")?.optJSONObject("turn")
            if (turn != null) {
                activeTurnId = turn.optString("id", activeTurnId.orEmpty())
            }
        }) {
            return
        }

        startSessionSyncLoop()
        composerText = ""
        clearComposerImageAttachments()
    }

internal fun MainActivity.steerCurrentTurn(
        sessionId: String,
        turnId: String,
        text: String,
        attachments: List<ComposerImageAttachment>,
    ) {
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("turn_id", turnId)
            put("text", text)
            put("input", buildComposerTurnInput(text, attachments))
        }

        if (!sendRequest("turn.steer", payload) { response ->
            activeTurnId = firstNonEmpty(
                response.optJSONObject("payload")?.optString("turn_id", "") ?: "",
                turnId,
            )
        }) {
            return
        }

        startSessionSyncLoop()
        composerText = ""
        clearComposerImageAttachments()
    }

private fun buildComposerTurnInput(
        text: String,
        attachments: List<ComposerImageAttachment>,
    ): JSONArray =
        JSONArray().apply {
            text.trim().takeIf { it.isNotBlank() }?.let { normalizedText ->
                put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", normalizedText)
                        put("text_elements", JSONArray())
                    },
                )
            }
            attachments.forEach { attachment ->
                put(
                    JSONObject().apply {
                        put("type", "localImage")
                        put("path", attachment.localPath)
                    },
                )
            }
        }

internal fun MainActivity.switchActiveSessionToFullAccess() {
        val session = activeSession() ?: return
        if (!ensureConnected()) return

        val payload = JSONObject().apply {
            put("session_id", session.sessionId)
            put("sandbox", "danger-full-access")
        }

        if (!sendRequest("session.update", payload) { response ->
                val updated = response.optJSONObject("payload")?.optJSONObject("session")?.let { SessionInfo.fromSession(it) }
                if (updated != null) {
                    upsertSession(updated)
                    openSessionInfoSheet()
                    showNotice("已切回完整访问")
                }
            }
        ) {
            return
        }
    }

internal fun MainActivity.interruptCurrentTurn() {
        if (!ensureConnected()) return
        val sessionId = activeSessionId
        val turnId = activeTurnId
        if (sessionId.isNullOrBlank() || turnId.isNullOrBlank()) {
            appendSystemNote("当前没有可中断的回合")
            return
        }
        if (interruptingTurnId == turnId) {
            showNotice("正在中断当前回合")
            return
        }

        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("turn_id", turnId)
        }

        interruptingTurnId = turnId
        updateLiveTurnStatus("正在中断当前回合…")
        if (!sendRequest("turn.interrupt", payload, object : ResponseHandler {
            override fun onResponse(response: JSONObject) {
                showNotice("已请求中断")
            }

            override fun onError(errorText: String) {
                if (interruptingTurnId == turnId) {
                    interruptingTurnId = null
                }
            }
        })) {
            if (interruptingTurnId == turnId) {
                interruptingTurnId = null
            }
        }
    }

internal fun MainActivity.requestSessionContent(sessionId: String) {
        if (sessionId.isBlank()) {
            syncDebugLog { "session.content skip blank session" }
            return
        }
        if (!ensureConnected()) {
            syncDebugLog { "session.content skip disconnected sessionId=$sessionId" }
            return
        }
        if (syncInFlight) {
            sessionContentDirty = true
            syncDebugLog { "session.content defer in_flight sessionId=$sessionId lastSyncedSeq=$lastSyncedSeq dirty=$sessionContentDirty" }
            return
        }
        syncInFlight = true
        val requestedSessionId = sessionId
        val payload = JSONObject().apply {
            put("session_id", sessionId)
        }

        if (!sendRequest("session.content", payload, object : ResponseHandler {
            override fun onResponse(response: JSONObject) {
                syncInFlight = false
                if (activeSessionId != requestedSessionId) {
                    sessionContentDirty = false
                    syncDebugLog { "session.content ignore inactive requested=$requestedSessionId active=$activeSessionId" }
                    return
                }
                val responsePayload = response.optJSONObject("payload")
                val snapshotItems = buildConversationItemsFromSnapshot(responsePayload)
                syncDebugLog { "session.content response sessionId=$requestedSessionId last=${responsePayload?.optInt("last_seq", lastSyncedSeq) ?: -1} entries=${responsePayload?.optJSONArray("entries")?.length() ?: 0} items=${snapshotItems.size} conversationBefore=${conversationItems.size}" }
                logSessionSyncEntries("session.content response entries", responsePayload)
                responsePayload?.optJSONObject("session")
                    ?.let(SessionInfo::fromSession)
                    ?.let(::upsertSession)
                applySessionContentSnapshot(responsePayload, snapshotItems)
                updateSessionSyncCursor(responsePayload)
                persistLocalSessionContent(requestedSessionId, responsePayload)
                updateLiveTurnStatusFromSnapshot(responsePayload)
                syncDebugLog { "session.content applied sessionId=$requestedSessionId conversationItems=${conversationItems.size} pendingApprovals=${pendingApprovals.size}" }
                flushPendingSessionRefresh(requestedSessionId)
            }

            override fun onError(errorText: String) {
                syncInFlight = false
                if (activeSessionId != requestedSessionId) {
                    sessionContentDirty = false
                    syncDebugLog { "session.content error ignored inactive requested=$requestedSessionId active=$activeSessionId error=$errorText" }
                    return
                }
                syncDebugLog { "session.content error sessionId=$requestedSessionId error=$errorText" }
                flushPendingSessionRefresh(requestedSessionId)
            }

            override fun suppressDefaultErrorUi(): Boolean = true
        })) {
            syncInFlight = false
            syncDebugLog { "session.content send_failed sessionId=$requestedSessionId" }
        }
    }

internal fun MainActivity.flushPendingSessionRefresh(sessionId: String) {
        if (!sessionContentDirty) return
        if (!connected || activeSessionId != sessionId) {
            syncDebugLog { "session.refresh skip dirty sessionId=$sessionId connected=$connected active=$activeSessionId" }
            return
        }
        sessionContentDirty = false
        syncDebugLog { "session.refresh flush_dirty sessionId=$sessionId lastSyncedSeq=$lastSyncedSeq" }
        mainHandler.post { requestSessionRefresh(sessionId) }
    }

internal fun MainActivity.applySessionContentSnapshot(payload: JSONObject?, snapshotItems: List<ConversationItem>? = null) {
        val resolvedSnapshotItems = snapshotItems ?: buildConversationItemsFromSnapshot(payload)
        val snapshotApprovals = buildPendingApprovalsFromSnapshot(payload)
        if (resolvedSnapshotItems.isEmpty() && snapshotApprovals.isEmpty() && conversationItems.isNotEmpty()) {
            syncDebugLog { "session.content apply skip empty_snapshot conversationItems=${conversationItems.size}" }
            return
        }
        if (conversationItems.matchesSnapshot(resolvedSnapshotItems) && pendingApprovals.matchesSnapshot(snapshotApprovals)) {
            syncDebugLog { "session.content apply skip unchanged items=${resolvedSnapshotItems.size} approvals=${snapshotApprovals.size}" }
            return
        }
        assistantItemIds.clear()
        toolItemIds.clear()
        fileChangeItemIds.clear()
        fileChangeTurnIds.clear()
        turnDiffItemIds.clear()
        turnDiffs.clear()

        conversationItems.clear()
        conversationItems.addAll(resolvedSnapshotItems)
        replacePendingApprovals(snapshotApprovals)
        syncDebugLog { "session.content apply replaced items=${conversationItems.size} approvals=${pendingApprovals.size}" }

        resolvedSnapshotItems.forEach { item ->
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

        codeBrowserState?.let { state ->
            if (!state.conversationItemId.startsWith("approval_") && resolvedSnapshotItems.none { it.id == state.conversationItemId }) {
                codeBrowserState = null
            }
        }
    }

private fun <T> List<T>.matchesSnapshot(snapshot: List<T>): Boolean {
    if (size != snapshot.size) return false
    return indices.all { index -> this[index] == snapshot[index] }
}

internal fun MainActivity.replacePendingApprovals(approvals: List<ApprovalDialogState>) {
        pendingApprovals.clear()
        pendingApprovals.addAll(approvals)
    }

internal fun MainActivity.upsertPendingApproval(approval: ApprovalDialogState) {
        val index = pendingApprovals.indexOfFirst { it.requestId == approval.requestId }
        if (index >= 0) {
            pendingApprovals[index] = approval
        } else {
            pendingApprovals.add(approval)
        }
    }

internal fun MainActivity.removePendingApproval(requestId: String) {
        pendingApprovals.removeAll { it.requestId == requestId }
    }

internal fun MainActivity.selectSession(sessionId: String, syncHistory: Boolean) {
        if (sessionId.isBlank()) return
        if (activeSessionId != null && activeSessionId != sessionId) {
            clearComposerImageAttachments()
        }
        val keepCurrentConversation = activeSessionId == sessionId && conversationItems.isNotEmpty()
        if (!keepCurrentConversation) {
            clearConversation()
        }
        activeSessionId = sessionId
        prefs.edit().putString(KEY_SESSION, sessionId).apply()
        projectBuckets().firstOrNull { group -> group.sessions.any { it.sessionId == sessionId } }?.let { group ->
            setProjectGroupExpanded(group.key(), true)
        }
        sessions.firstOrNull { it.sessionId == sessionId }?.let(::syncChatSettingsFromSession)
        activeThreadGoal = null
        if (connected) {
            requestActiveGoal(sessionId)
        }

        val restoredCachedContent =
            if (keepCurrentConversation) {
                false
            } else {
                applyCachedSessionContent(sessionId).also { restored ->
                    if (restored) {
                        chatRestoreScrollY = Int.MAX_VALUE
                    }
                }
            }
        syncDebugLog { "session.select sessionId=$sessionId syncHistory=$syncHistory connected=$connected keepCurrent=$keepCurrentConversation restoredCache=$restoredCachedContent cursorReady=$sessionSyncCursorReady lastSyncedSeq=$lastSyncedSeq" }

        if (syncHistory && connected) {
            requestSessionRefresh(sessionId)
            startSessionSyncLoop()
        } else {
            stopSessionSyncLoop()
        }
    }

internal fun MainActivity.replaceSessions(newSessions: List<SessionInfo>) {
        val existingSessions = sessions.associateBy { it.sessionId }
        sessions.clear()
        sessions.addAll(
            newSessions
                .map { it.mergedWith(existingSessions[it.sessionId]) }
                .sortedByDescending { it.updatedAt },
        )
        if (selectedWorkspace != null && sessions.none { it.cwd == selectedWorkspace }) {
            selectedWorkspace = null
        }
    }

internal fun MainActivity.upsertSession(info: SessionInfo) {
        val merged = info.mergedWith(sessions.firstOrNull { it.sessionId == info.sessionId })
        sessions.removeAll { it.sessionId == info.sessionId }
        sessions.add(merged)
        sessions.sortByDescending { it.updatedAt }
    }

internal fun MainActivity.clearConversation() {
        conversationItems.clear()
        assistantItemIds.clear()
        assistantDeltaBuffers.clear()
        toolItemIds.clear()
        fileChangeItemIds.clear()
        fileChangeTurnIds.clear()
        turnDiffItemIds.clear()
        turnDiffs.clear()
        renderedEventKeys.clear()
        activeTurnId = null
        chatRestoreScrollY = null
        codeBrowserState = null
        lastSyncedSeq = 0
        sessionSyncCursorReady = false
        lastSnapshotSignature = null
        lastSnapshotItemCount = 0
        syncInFlight = false
        sessionContentDirty = false
        pendingApprovals.clear()
    }


internal fun MainActivity.selectModel(modelId: String) {
        selectedModel = modelId.trim()
        prefs.edit().putString(KEY_MODEL, selectedModel).apply()
        if (selectedReasoningEffort.isNotBlank()) {
            selectedReasoningEffort = normalizeReasoningEffortForModel(selectedModel, selectedReasoningEffort)
        }
    }

internal fun MainActivity.modelInfoFor(modelId: String): ModelInfo? {
        val target = modelId.trim()
        if (target.isBlank()) return null
        return availableModels.firstOrNull { it.model == target || it.id == target }
    }

internal fun MainActivity.replaceModels(newModels: List<ModelInfo>) {
        availableModels.clear()
        availableModels.addAll(newModels.sortedWith(compareByDescending<ModelInfo> { it.isDefault }.thenBy { it.displayName.lowercase(Locale.getDefault()) }))
    }

internal fun MainActivity.reasoningEffortOptionsForModel(modelId: String): List<SelectionMenuOption> {
        val model = modelInfoFor(modelId)
        val declared = model?.supportedReasoningEfforts.orEmpty()
        if (declared.isNotEmpty()) {
            return declared.map { option ->
                SelectionMenuOption(
                    value = option.effort,
                    label = reasoningEffortMenuLabel(option.effort),
                    supporting = reasoningEffortDescription(option.effort),
                )
            }
        }
        return reasoningEffortFallbackOptions()
    }

internal fun MainActivity.reasoningEffortFallbackOptions(): List<SelectionMenuOption> {
        return listOf("minimal", "low", "medium", "high", "xhigh", "none").map { effort ->
            reasoningOptionFor(effort)
        }
    }

internal fun MainActivity.reasoningOptionFor(effort: String): SelectionMenuOption {
        return SelectionMenuOption(
            value = effort,
            label = reasoningEffortMenuLabel(effort),
            supporting = reasoningEffortDescription(effort),
        )
    }

internal fun MainActivity.defaultReasoningEffortForModel(modelId: String): String {
        val model = modelInfoFor(modelId)
        val availableEfforts = reasoningEffortOptionsForModel(modelId).map { it.value }.toSet()
        val preferred = model?.defaultReasoningEffort?.trim().orEmpty()
        if (preferred.isNotBlank() && preferred in availableEfforts) {
            return preferred
        }
        return when {
            "medium" in availableEfforts -> "medium"
            availableEfforts.isNotEmpty() -> reasoningEffortOptionsForModel(modelId).first().value
            else -> "medium"
        }
    }

internal fun MainActivity.normalizeReasoningEffortForModel(
        modelId: String,
        preferred: String,
    ): String {
        val normalized = preferred.trim()
        val options = reasoningEffortOptionsForModel(modelId)
        return when {
            normalized.isNotBlank() && options.any { it.value == normalized } -> normalized
            else -> defaultReasoningEffortForModel(modelId)
        }
    }

internal fun MainActivity.reasoningEffortMenuLabel(effort: String): String {
        return effort.trim().ifBlank { "默认" }
    }

internal fun MainActivity.reasoningEffortDescription(effort: String): String {
        return when (effort.trim()) {
            "none" -> "尽量不做额外推理，返回会更直接。"
            "minimal" -> "保留极少推理，适合很轻的任务。"
            "low" -> "较轻推理，适合常规问答和小改动。"
            "medium" -> "速度和质量更平衡，适合大多数任务。"
            "high" -> "更多推理，适合复杂实现和排查。"
            "xhigh" -> "额外高强度推理，适合更复杂的问题。"
            "max" -> "最大推理深度，适合最困难的问题。"
            "ultra" -> "最大推理深度，并允许自动委派任务。"
            else -> "按该模型支持的思考强度原样下发。"
        }
    }

internal fun MainActivity.approvalPolicyMenuOptions(): List<SelectionMenuOption> {
        return listOf(
            SelectionMenuOption("untrusted", "最保守 (untrusted)"),
            SelectionMenuOption("on-request", "按需审批 (on-request)"),
            SelectionMenuOption("on-failure", "失败后审批 (on-failure)"),
            SelectionMenuOption("never", "不审批 (never)"),
        )
    }

internal fun MainActivity.sandboxMenuOptions(): List<SelectionMenuOption> {
        return listOf(
            SelectionMenuOption("readOnly", "只读", "只能查看文件。"),
            SelectionMenuOption("workspaceWrite", "工作区", "可修改当前项目文件。"),
            SelectionMenuOption("dangerFullAccess", "完整访问", "不限制文件访问。"),
        )
    }

internal fun MainActivity.sandboxDescription(sandbox: String): String {
        return when (sandbox.trim()) {
            "readOnly" -> "只读模式。不能改文件，适合纯查看、检索和解释。"
            "workspaceWrite" -> "仅允许写当前项目工作区；更稳妥，适合日常改代码。"
            else -> "允许完整文件系统访问；能力最强，但风险也最高。"
        }
    }

internal fun MainActivity.currentSessionModel(): String {
        val sessionId = activeSessionId ?: return ""
        return sessions.firstOrNull { it.sessionId == sessionId }?.model.orEmpty().trim()
            .takeIf { isKnownModel(it) }
            .orEmpty()
    }

internal fun MainActivity.resolveModelForSend(): String {
        val candidates = listOf(
            selectedModel.trim(),
            currentSessionModel(),
            availableModels.firstOrNull()?.model.orEmpty(),
            availableModels.firstOrNull()?.id.orEmpty(),
        )
        return candidates.firstOrNull { isKnownModel(it) } ?: ""
    }

internal fun MainActivity.isKnownModel(modelId: String): Boolean {
        val target = modelId.trim()
        if (target.isBlank()) return false
        return availableModels.any { it.model == target || it.id == target }
    }


internal fun MainActivity.activeSession(): SessionInfo? = activeSessionId?.let { sessionId -> sessions.firstOrNull { it.sessionId == sessionId } }

internal fun MainActivity.currentWorkspacePath(): String? {
        return selectedWorkspace
            ?: activeSession()?.cwd?.takeIf { it.isNotBlank() }
            ?: sessions.firstOrNull { it.cwd.isNotBlank() }?.cwd
    }

internal fun MainActivity.workspacePaths(): List<String> {
        return sessions.mapNotNull { it.cwd.takeIf(String::isNotBlank) }.distinct().sorted()
    }

internal fun MainActivity.sessionsForSelectedWorkspace(): List<SessionInfo> {
        val workspace = selectedWorkspace
        return if (workspace.isNullOrBlank()) {
            sessions.sortedByDescending { it.updatedAt }
        } else {
            sessions.filter { it.cwd == workspace }.sortedByDescending { it.updatedAt }
        }
    }

internal fun MainActivity.workspaceDisplayName(path: String?, fallback: String): String {
        val normalized = path?.trim().orEmpty()
        if (normalized.isBlank()) return fallback
        return normalized.substringAfterLast('/').ifBlank { normalized }
    }

internal fun MainActivity.projectBuckets(): List<ProjectGroup> {
        val groups =
            workspacePaths().map { path ->
                ProjectGroup(
                    path = path,
                    sessions = sessions.filter { it.cwd == path }.sortedByDescending { it.updatedAt },
                )
            }
        val uncategorized = sessions.filter { it.cwd.isBlank() }.sortedByDescending { it.updatedAt }
        return buildList {
            addAll(groups.sortedByDescending { it.sessions.firstOrNull()?.updatedAt.orEmpty() })
            if (uncategorized.isNotEmpty()) {
                add(
                    ProjectGroup(
                        path = null,
                        sessions = uncategorized,
                    ),
                )
            }
        }
    }

internal fun MainActivity.openNewChatDialog(projectPath: String?) {
        if (!connected) {
            showNotice("请先连接 Linux Bridge")
            return
        }
        val defaultModel =
            resolveModelForSend().ifBlank {
                availableModels.firstOrNull()?.model.orEmpty()
            }
        newChatDraft =
            NewChatDraft(
                projectPath = projectPath?.takeIf { it.isNotBlank() },
                model = defaultModel,
                reasoningEffort = defaultReasoningEffortForModel(defaultModel),
                approvalPolicy = "never",
                sandbox = "dangerFullAccess",
            )
    }

internal fun MainActivity.openSessionInfoSheet() {
        val session = activeSession() ?: return
        sessionInfoSheetState =
            SessionInfoSheetState(
                title = session.titleLine(),
                canRebuildHistory = connected,
                canSwitchToFullAccess = !session.isDangerFullAccess(),
                rows =
                    buildList {
                        add("会话 ID" to session.sessionId)
                        add("目录" to session.cwd.ifBlank { "未提供" })
                        add("模型" to session.modelSummary())
                        add("审批策略" to session.approvalPolicy.ifBlank { "未提供" })
                        add("沙箱" to session.sandboxSummary())
                        add("上下文窗口" to session.contextWindowSummary())
                        add("最近 token" to session.lastTokenUsageSummary())
                        add("累计 token" to session.totalTokenUsageSummary())
                    },
            )
    }

internal fun MainActivity.rebuildActiveSessionHistory() {
        val sessionId = activeSessionId?.takeIf { it.isNotBlank() } ?: return
        if (!ensureConnected()) return
        sessionSyncCursorReady = false
        lastSyncedSeq = 0
        requestSessionContent(sessionId)
        sessionInfoSheetState = null
        currentPage = AppPage.Chat
        showNotice("正在重建历史记录")
    }


internal fun MainActivity.startSessionSyncLoop() {
        stopSessionSyncLoop()
        if (!connected || activeSessionId.isNullOrBlank()) return
        mainHandler.postDelayed(syncRunnable, sessionSyncIntervalMs())
    }

internal fun MainActivity.stopSessionSyncLoop() {
        mainHandler.removeCallbacks(syncRunnable)
        syncInFlight = false
    }
