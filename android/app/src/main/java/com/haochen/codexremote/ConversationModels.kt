package com.haochen.codexremote

sealed interface ConversationItem {
    val id: String

    data class Bubble(
        override val id: String,
        val right: Boolean,
        val text: String,
        val backgroundColor: Int,
        val textColor: Int,
        val images: List<ConversationImage> = emptyList(),
        val turnKey: String? = null,
        val assistantKey: String? = null,
        val sourceItemId: String? = null,
    ) : ConversationItem

    data class SystemNote(
        override val id: String,
        val text: String,
        val itemKey: String? = null,
    ) : ConversationItem

    data class FileChange(
        override val id: String,
        val title: String,
        val summary: String,
        val status: String,
        val diffEntries: List<ConversationDiffEntry>,
        val fallbackDiff: String? = null,
        val sourceItemId: String? = null,
        val turnId: String? = null,
    ) : ConversationItem
}

enum class ConversationImageSourceType {
    Remote,
    Local,
    Inline,
}

data class ConversationImage(
    val source: String,
    val sourceType: ConversationImageSourceType,
    val alt: String = "",
)

internal data class ConversationImageRenderContext(
    val sessionId: String?,
    val bridgeUrl: String?,
)

data class ConversationDiffEntry(
    val path: String,
    val kind: String,
    val diff: String,
    val movePath: String? = null,
) {
    fun summaryPath(basePath: String? = null): String = displayPath(basePath = basePath, maxLength = Int.MAX_VALUE)

    fun filenameLabel(): String {
        val baseName = normalizedPath().substringAfterLast('/').ifBlank { normalizedPath() }
        val moveName = normalizedMovePath()?.substringAfterLast('/')?.ifBlank { normalizedMovePath().orEmpty() }
        return moveName?.let { "$baseName → $it" } ?: baseName
    }

    fun browseCandidates(): List<String> {
        return listOfNotNull(
            normalizedPath().takeIf { it.isNotBlank() },
            normalizedMovePath(),
        ).distinct()
    }

    fun browsePath(): String = browseCandidates().firstOrNull().orEmpty()

    fun displayPath(basePath: String? = null, maxLength: Int = 44): String {
        val baseLabel = compactDiffDisplayPath(normalizedPath(), basePath, maxLength)
        val moveLabel = normalizedMovePath()?.let { compactDiffDisplayPath(it, basePath, maxLength) }
        val combined = moveLabel?.let { "$baseLabel → $it" } ?: baseLabel
        if (combined.length <= maxLength) return combined
        val compactBase = baseLabel.substringAfterLast('/').ifBlank { baseLabel }
        val compactMove = moveLabel?.substringAfterLast('/')?.ifBlank { moveLabel }
        return compactMove?.let { "$compactBase → $it" } ?: compactBase
    }

    fun diffStatsLabel(): String? = buildDiffStatsLabel(diff, kind)

    fun changeLabel(): String? = buildDiffChangeLabel(diffText = diff, kind = kind, movePath = movePath)

    private fun normalizedPath(): String = sanitizeDiffPath(path).orEmpty()

    private fun normalizedMovePath(): String? = sanitizeDiffPath(movePath)
}

internal fun sanitizeDiffPath(value: String?): String? {
    val text = value?.trim().orEmpty()
    return when {
        text.isBlank() -> null
        text.equals("null", ignoreCase = true) -> null
        text.equals("undefined", ignoreCase = true) -> null
        else -> text
    }
}

internal fun compactDiffDisplayPath(
    rawPath: String,
    basePath: String?,
    maxLength: Int,
): String {
    val normalizedPath = rawPath.replace('\\', '/').removePrefix("./")
    val normalizedBase = sanitizeDiffPath(basePath)?.replace('\\', '/')?.trimEnd('/')
    val relativePath =
        when {
            normalizedBase.isNullOrBlank() -> normalizedPath.takeIf { !it.startsWith('/') } ?: normalizedPath.substringAfterLast('/')
            normalizedPath == normalizedBase -> normalizedPath.substringAfterLast('/')
            normalizedPath.startsWith("$normalizedBase/") -> normalizedPath.removePrefix("$normalizedBase/")
            normalizedPath.startsWith('/') -> normalizedPath.substringAfterLast('/')
            else -> normalizedPath
        }.ifBlank { normalizedPath.substringAfterLast('/') }
    return if (relativePath.length <= maxLength) relativePath else relativePath.substringAfterLast('/').ifBlank { relativePath }
}

internal fun buildDiffStatsLabel(
    diffText: String,
    kind: String,
): String? {
    val stats = parseDiffStats(diffText, kind)
    return stats?.toLabel() ?: when (kind.trim()) {
        "add" -> "新增"
        "delete" -> "删除"
        "update" -> "修改"
        else -> null
    }
}

internal fun buildDiffChangeLabel(
    diffText: String,
    kind: String,
    movePath: String?,
): String? {
    val kindLabel = buildDiffKindLabel(kind, movePath).takeIf { it.isNotBlank() }
    val statsLabel = buildDiffStatsLabel(diffText, kind)
    val normalizedKind = kind.trim()
    return when {
        normalizedKind == "add" || normalizedKind == "delete" ->
            listOfNotNull(kindLabel, statsLabel?.takeUnless { it == kindLabel }).joinToString(" ").takeIf { it.isNotBlank() }

        normalizedKind == "update" && !movePath.isNullOrBlank() ->
            listOfNotNull(kindLabel, statsLabel?.takeUnless { it == kindLabel || it == "修改" }).joinToString(" ").takeIf { it.isNotBlank() }

        else -> statsLabel ?: kindLabel
    }
}

internal fun buildDiffKindLabel(kind: String, movePath: String?): String {
    return when (kind.trim()) {
        "add" -> "新增"
        "delete" -> "删除"
        "update" -> if (movePath.isNullOrBlank()) "修改" else "重命名"
        else -> kind.ifBlank { "变更" }
    }
}

internal data class DiffStats(
    val additions: Int,
    val deletions: Int,
) {
    fun toLabel(): String {
        return when {
            additions > 0 && deletions > 0 -> "+$additions / -$deletions"
            additions > 0 -> "+$additions"
            deletions > 0 -> "-$deletions"
            else -> "修改"
        }
    }
}

internal fun parseDiffStats(
    diffText: String,
    kind: String? = null,
): DiffStats? {
    if (diffText.isBlank()) return null
    var additions = 0
    var deletions = 0
    diffText.replace("\r\n", "\n").lineSequence().forEach { line ->
        when {
            line.startsWith("+") && !line.startsWith("+++") -> additions += 1
            line.startsWith("-") && !line.startsWith("---") -> deletions += 1
        }
    }
    if (additions > 0 || deletions > 0) {
        return DiffStats(additions = additions, deletions = deletions)
    }
    val nonEmptyLines = diffText.lineSequence().count { it.isNotBlank() }
    return when (kind?.trim()) {
        "add" -> DiffStats(additions = nonEmptyLines.coerceAtLeast(1), deletions = 0)
        "delete" -> DiffStats(additions = 0, deletions = nonEmptyLines.coerceAtLeast(1))
        else -> null
    }
}
