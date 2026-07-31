package com.haochen.codexremote

import org.json.JSONObject

internal enum class AppPage {
    Connection,
    Chat,
    Settings,
}

internal enum class ConversationScrollTarget {
    Top,
    Bottom,
}

internal enum class ConversationLongPressKind {
    Image,
    Link,
}

internal data class ConversationLongPressState(
    val kind: ConversationLongPressKind,
    val source: String,
    val anchorX: Int,
    val anchorY: Int,
    val scrollY: Int = 0,
)

internal data class ConversationImageViewerState(
    val source: String,
)

internal enum class ChatMode {
    Default,
    Plan,
    Goal,
}

internal enum class ChatSettingsSheet {
    Mode,
    Model,
    Sandbox,
}

internal data class ThreadGoalState(
    val threadId: String,
    val objective: String,
    val status: String,
    val tokenBudget: Int?,
    val tokensUsed: Int,
    val timeUsedSeconds: Int,
) {
    companion object {
        fun fromJson(objectValue: JSONObject?): ThreadGoalState? {
            objectValue ?: return null
            val threadId = objectValue.optString("threadId", objectValue.optString("thread_id", "")).trim()
            val objective = objectValue.optString("objective", "").trim()
            if (threadId.isBlank() && objective.isBlank()) return null
            val budget = objectValue.optInt("tokenBudget", -1).takeIf { it > 0 }
            return ThreadGoalState(
                threadId = threadId,
                objective = objective,
                status = objectValue.optString("status", "active").trim().ifBlank { "active" },
                tokenBudget = budget,
                tokensUsed = objectValue.optInt("tokensUsed", 0).coerceAtLeast(0),
                timeUsedSeconds = objectValue.optInt("timeUsedSeconds", 0).coerceAtLeast(0),
            )
        }
    }
}

internal data class ConversationScrollCommand(
    val target: ConversationScrollTarget,
    val nonce: Long,
)

internal data class ApprovalPresentation(
    val title: String,
    val detail: String,
    val diffEntries: List<ConversationDiffEntry>,
)

internal data class ApprovalAction(
    val label: String,
    val responsePayload: JSONObject,
    val isPrimary: Boolean = false,
)

internal data class ApprovalDialogState(
    val requestId: String,
    val title: String,
    val detail: String,
    val actions: List<ApprovalAction>,
    val diffEntries: List<ConversationDiffEntry>,
    val turnId: String? = null,
)

internal data class ConnectionEditDialogState(
    val connectionId: String,
    val currentName: String,
    val currentUrl: String,
)

internal data class ConnectionRemovalDialogState(
    val connectionId: String,
    val displayName: String,
    val maskedUrl: String,
)

internal data class CodeBrowserState(
    val conversationItemId: String,
    val sessionId: String?,
    val title: String,
    val basePath: String?,
    val diffEntries: List<ConversationDiffEntry>,
    val fallbackDiff: String?,
    val selectedPath: String?,
    val selectedLine: Int? = null,
    val mode: CodeBrowserMode = CodeBrowserMode.Diff,
    val fileReadState: CodeBrowserFileReadState = CodeBrowserFileReadState.Idle,
) {
    fun selectedEntry(): ConversationDiffEntry? {
        if (diffEntries.isEmpty()) return null
        return diffEntries.firstOrNull { it.browsePath() == selectedPath } ?: diffEntries.first()
    }

    fun selectedFileCandidates(): List<String> {
        return selectedEntry()?.browseCandidates()
            ?: listOfNotNull(selectedPath?.trim()?.takeIf { it.isNotBlank() })
    }

    fun canReadSelectedFile(): Boolean = selectedFileCandidates().isNotEmpty()

    fun hasDiffContent(): Boolean = diffEntries.isNotEmpty() || !fallbackDiff.isNullOrBlank()

    fun lineSuffixLabel(): String = selectedLine?.takeIf { it > 0 }?.let { " (line $it)" }.orEmpty()
}

internal enum class CodeBrowserMode {
    Diff,
    File,
}

internal data class ProjectGroup(
    val path: String?,
    val sessions: List<SessionInfo>,
) {
    val displayName: String
        get() = path?.let { it.substringAfterLast('/').ifBlank { it } } ?: "未分类历史"

    fun key(): String = path ?: "__uncategorized__"
}

internal data class NewChatDraft(
    val projectPath: String?,
    val model: String,
    val reasoningEffort: String,
    val approvalPolicy: String,
    val sandbox: String,
)

internal data class SessionInfoSheetState(
    val title: String,
    val rows: List<Pair<String, String>>,
    val canRebuildHistory: Boolean = false,
    val sandboxRowLabel: String = "沙箱",
    val canSwitchToFullAccess: Boolean = false,
)

internal data class SelectionMenuOption(
    val value: String,
    val label: String,
    val supporting: String? = null,
)

internal data class BackupImportPreview(
    val document: String,
    val connectionCount: Int,
    val hasCurrentConnection: Boolean,
)

internal fun interface ResponseHandler {
    fun onResponse(response: JSONObject)

    fun onError(errorText: String) {}

    fun suppressDefaultErrorUi(): Boolean = false
}
