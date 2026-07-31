package com.haochen.codexremote

import android.net.Uri
import android.text.TextUtils
import java.io.File

internal fun buildConversationHtmlTag(
    tag: String,
    content: String,
): String = "<$tag>$content</${tag.substringBefore(' ')}>"

internal fun escapeConversationHtml(text: String): String = TextUtils.htmlEncode(text)

internal fun escapeConversationHtmlAttribute(text: String): String = TextUtils.htmlEncode(text)

internal fun encodeUrlComponent(value: String): String = Uri.encode(value)

internal fun conversationImageFromUrl(
    source: String,
    alt: String = "",
): ConversationImage? {
    val normalized = source.trim()
    if (normalized.isBlank()) return null
    val sourceType = when {
        normalized.startsWith("data:image/", ignoreCase = true) -> ConversationImageSourceType.Inline
        normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true) -> ConversationImageSourceType.Remote
        else -> ConversationImageSourceType.Local
    }
    return ConversationImage(source = normalized, sourceType = sourceType, alt = alt.trim())
}

internal fun resolveConversationImageUrl(
    image: ConversationImage,
    context: ConversationImageRenderContext,
): String? =
    when (image.sourceType) {
        ConversationImageSourceType.Remote -> image.source.takeIf { source ->
            source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)
        }

        ConversationImageSourceType.Inline -> {
            val source = image.source.trim()
            when {
                source.startsWith("data:image/", ignoreCase = true) -> source
                source.isNotBlank() -> "data:image/png;base64,${source.filterNot(Char::isWhitespace)}"
                else -> null
            }
        }

        ConversationImageSourceType.Local -> buildConversationBridgeImageUrl(
            bridgeUrl = context.bridgeUrl,
            sessionId = context.sessionId,
            path = normalizeConversationLocalImagePath(image.source),
        )
    }

internal fun buildConversationBridgeImageUrl(
    bridgeUrl: String?,
    sessionId: String?,
    path: String?,
): String? {
    val normalizedBridgeUrl = bridgeUrl?.trim().orEmpty()
    val normalizedSessionId = sessionId?.trim().orEmpty()
    val normalizedPath = path?.trim().orEmpty()
    if (normalizedBridgeUrl.isBlank() || normalizedSessionId.isBlank() || normalizedPath.isBlank()) return null

    val bridgeUri = Uri.parse(normalizedBridgeUrl)
    val httpScheme = when (bridgeUri.scheme?.lowercase()) {
        "ws" -> "http"
        "wss" -> "https"
        else -> return null
    }
    return bridgeUri.buildUpon()
        .scheme(httpScheme)
        .encodedPath("/v1/image")
        .clearQuery()
        .fragment(null)
        .apply {
            bridgeUri.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("token", it) }
            appendQueryParameter("session_id", normalizedSessionId)
            appendQueryParameter("path", normalizedPath)
        }
        .build()
        .toString()
}

internal fun normalizeConversationLocalImagePath(source: String): String? {
    val normalized = source.trim()
    if (normalized.isBlank()) return null
    if (normalized.startsWith("file:", ignoreCase = true)) {
        return Uri.parse(normalized).path?.takeIf { it.isNotBlank() }
    }
    return Uri.decode(normalized)
}

internal data class ConversationFileTarget(
    val path: String,
    val line: Int? = null,
) {
    fun browserHref(): String =
        buildString {
            append("codex-code-browser://file?path=")
            append(encodeUrlComponent(path))
            line?.takeIf { it > 0 }?.let {
                append("&line=")
                append(it)
            }
        }
}

internal fun ConversationFileTarget.withFallbackLine(text: String): ConversationFileTarget {
    if (line != null) return this
    return copy(line = readConversationFileLineReference(text))
}

internal fun ConversationFileTarget.appendLineToLabel(label: String): String {
    return appendConversationLineToLabel(label, line)
}

internal fun appendConversationLineToLabel(label: String, line: Int?): String {
    val lineNumber = line?.takeIf { it > 0 } ?: return label
    if (readConversationFileLineReference(label) != null) return label
    return "$label (line $lineNumber)"
}

internal fun isConversationLikelyFileLabel(label: String): Boolean {
    val normalized = stripConversationFileLineTextSuffix(label.trim()).replace('\\', '/')
    if (normalized.isBlank() || normalized.any(Char::isWhitespace)) return false
    val name = normalized.substringAfterLast('/').ifBlank { normalized }
    if (name.isBlank() || name == "." || name == "..") return false
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
    return ext.isNotBlank() &&
        ext.length in 1..12 &&
        ext.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

internal fun conversationFileTargetFromLinkTarget(target: String): ConversationFileTarget? {
    val trimmed = target.trim()
    if (trimmed.isBlank()) return null

    val uri = Uri.parse(trimmed)
    val scheme = uri.scheme

    if (scheme.equals("codex-code-browser", ignoreCase = true)) {
        val path = uri.getQueryParameter("path") ?: return null
        val line = uri.getQueryParameter("line")?.toIntOrNull()?.takeIf { it > 0 }
        return normalizeConversationFileBrowserTarget(path)?.let { target ->
            if (target.line != null || line == null) target else target.copy(line = line)
        }
    }

    if (scheme.equals("file", ignoreCase = true)) {
        return Uri.parse(trimmed).path?.let(::normalizeConversationFileBrowserTarget)
            ?.withFallbackLine(trimmed)
    }

    if (trimmed.startsWith("about:blank/", ignoreCase = true)) {
        return Uri.decode(trimmed.substring("about:blank/".length))
            .let(::normalizeConversationFileBrowserTarget)
    }

    conversationFileTargetFromEditorUri(uri, scheme)?.let { return it }

    if (!scheme.isNullOrBlank()) return null

    return normalizeConversationFileBrowserTarget(Uri.decode(trimmed))
}

internal fun isConversationLikelyFilePath(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    if (normalized.isBlank()) return false
    if (normalized == "." || normalized == "..") return false
    if (normalized.startsWith("#")) return false

    val pathWithoutLine = stripConversationFileLineSuffix(normalized)
    val name = pathWithoutLine.substringAfterLast('/').ifBlank { pathWithoutLine }
    if (name.isBlank() || name == "." || name == "..") return false

    val hasPathSeparator = pathWithoutLine.contains('/')
    val hasExtension = name.substringAfterLast('.', missingDelimiterValue = "").let { ext ->
        ext.isNotBlank() && ext.length in 1..12 && ext.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }
    val isAbsolute = File(pathWithoutLine).isAbsolute
    return isAbsolute || (hasPathSeparator && hasExtension)
}

internal fun normalizeConversationFileBrowserTarget(path: String): ConversationFileTarget? {
    val decoded = Uri.decode(path.trim()).let { value ->
        if (value.startsWith('<') && value.endsWith('>')) value.substring(1, value.lastIndex).trim() else value
    }
    val normalized = stripConversationFileLineTextSuffix(stripConversationFileLinkDecoration(decoded))
    if (!isConversationLikelyFilePath(normalized)) return null
    val line = readConversationFileLineReference(decoded) ?: readConversationFileLineSuffix(normalized)
    return ConversationFileTarget(
        path = stripConversationFileLineSuffix(normalized),
        line = line,
    )
}

internal fun conversationFileTargetFromEditorUri(uri: Uri, scheme: String?): ConversationFileTarget? {
    val normalizedScheme = scheme?.lowercase().orEmpty()
    if (normalizedScheme !in setOf("vscode", "vscode-insiders", "cursor", "zed", "sublime", "txmt")) {
        return null
    }
    val candidates = listOfNotNull(
        uri.getQueryParameter("path"),
        uri.getQueryParameter("file"),
        uri.getQueryParameter("url"),
        uri.path,
    )
    return candidates.firstNotNullOfOrNull { candidate ->
        if (candidate.startsWith("file:", ignoreCase = true)) {
            conversationFileTargetFromLinkTarget(candidate)
        } else {
            normalizeConversationFileBrowserTarget(candidate)
        }
    }
}

internal fun stripConversationFileLinkDecoration(path: String): String {
    return path
        .substringBefore('#')
        .substringBefore('?')
}

internal fun stripConversationFileLineTextSuffix(path: String): String {
    return path.replace(Regex("""\s*\(\s*line\s+\d+\s*\)\s*$""", RegexOption.IGNORE_CASE), "")
}

internal fun stripConversationFileLineSuffix(path: String): String {
    val lineMatch = Regex("""^(.+):\d+(?::\d+)?$""").matchEntire(path)
    return lineMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: path
}

internal fun readConversationFileLineSuffix(path: String): Int? {
    val lineMatch = Regex("""^.+:(\d+)(?::\d+)?$""").matchEntire(path)
    return lineMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
}

internal fun readConversationFileLineReference(text: String): Int? {
    val trimmed = text.trim()
    val patterns = listOf(
        Regex("""^.+:(\d+)(?::\d+)?$"""),
        Regex("""(?i)[#?&](?:line=|line-|L)(\d+)(?:\D|$)"""),
        Regex("""(?i)\(\s*line\s+(\d+)\s*\)\s*$"""),
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }
}

internal fun trimConversationTrailingUrlPunctuation(candidate: String): String {
    var url = candidate
    while (url.isNotEmpty()) {
        val trailing = url.last()
        url =
            when (trailing) {
                ',', '.', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？' -> url.dropLast(1)
                ')', ']', '}', '）', '】', '》' ->
                    if (shouldTrimConversationClosingDelimiter(url, trailing)) {
                        url.dropLast(1)
                    } else {
                        return url
                    }
                else -> return url
            }
    }
    return url
}

internal fun shouldTrimConversationClosingDelimiter(
    url: String,
    closingDelimiter: Char,
): Boolean {
    val openingDelimiter =
        when (closingDelimiter) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            '）' -> '（'
            '】' -> '【'
            '》' -> '《'
            else -> return false
        }
    return url.count { it == closingDelimiter } > url.count { it == openingDelimiter }
}

internal fun findConversationInlineUrlEnd(
    text: String,
    startIndex: Int,
): Int {
    var index = startIndex
    while (index < text.length && !text[index].isWhitespace()) {
        index += 1
    }
    return index
}
