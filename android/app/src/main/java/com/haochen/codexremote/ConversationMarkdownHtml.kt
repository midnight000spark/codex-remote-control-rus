package com.haochen.codexremote

internal val conversationMarkdownCodeFenceRegex = Regex("""^```([a-zA-Z0-9_+-]+)?\s*$""")
internal val conversationMarkdownHeadingRegex = Regex("""^(#{1,6})\s+(.*)$""")
internal val conversationMarkdownUnorderedListRegex = Regex("""^(\s*)([-*+])\s+(.*)$""")
internal val conversationMarkdownOrderedListRegex = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
internal val conversationMarkdownTaskListRegex = Regex("""^\[( |x|X)]\s+(.*)$""")
internal val conversationMarkdownBlockQuoteRegex = Regex("""^\s*>\s?(.*)$""")
internal val conversationMarkdownDividerRegex = Regex("""^\s{0,3}([-*_])(?:\s*\1){2,}\s*$""")
internal val conversationMarkdownTableSeparatorRegex = Regex("""^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?\s*$""")

internal sealed interface ConversationMarkdownBlock {
    data class Heading(
        val level: Int,
        val content: String,
    ) : ConversationMarkdownBlock

    data class Paragraph(
        val content: String,
    ) : ConversationMarkdownBlock

    data class ListBlock(
        val items: List<ConversationMarkdownListItem>,
    ) : ConversationMarkdownBlock

    data class Quote(
        val lines: List<String>,
    ) : ConversationMarkdownBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
    ) : ConversationMarkdownBlock

    data object Divider : ConversationMarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : ConversationMarkdownBlock
}

internal data class ConversationMarkdownListItem(
    val marker: String,
    val content: String,
    val depth: Int = 0,
    val isTask: Boolean = false,
)

internal data class ConversationMarkdownListNode(
    val item: ConversationMarkdownListItem,
    val children: MutableList<ConversationMarkdownListNode> = mutableListOf(),
)

internal enum class ConversationMarkdownHtmlListType {
    Ordered,
    Unordered,
}

internal fun buildConversationMarkdownHtml(
    markdown: String,
    imageUrlResolver: ((String) -> String?)? = null,
): String {
    val blocks = parseConversationMarkdownBlocks(markdown)
    if (blocks.isEmpty()) {
        val content = markdown.replace("\r\n", "\n").trim()
        return if (content.isBlank()) {
            ""
        } else {
            """<section class="cr-md"><p>${buildConversationInlineHtml(content, imageUrlResolver)}</p></section>"""
        }
    }

    return buildString {
        append("""<section class="cr-md">""")
        blocks.forEach { block ->
            append(block.toHtml(imageUrlResolver))
        }
        append("</section>")
    }
}

internal fun parseConversationMarkdownBlocks(markdown: String): List<ConversationMarkdownBlock> {
    val normalized = markdown.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return emptyList()

    val lines = normalized.lines()
    val blocks = mutableListOf<ConversationMarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index += 1
            continue
        }

        conversationMarkdownCodeFenceRegex.matchEntire(line)?.let { match ->
            val language = match.groupValues[1].ifBlank { null }
            index += 1
            val codeLines = mutableListOf<String>()
            while (index < lines.size && !conversationMarkdownCodeFenceRegex.matches(lines[index])) {
                codeLines += lines[index]
                index += 1
            }
            if (index < lines.size && conversationMarkdownCodeFenceRegex.matches(lines[index])) {
                index += 1
            }
            blocks += ConversationMarkdownBlock.CodeBlock(language = language, code = codeLines.joinToString("\n"))
            continue
        }

        if (conversationMarkdownDividerRegex.matches(line)) {
            blocks += ConversationMarkdownBlock.Divider
            index += 1
            continue
        }

        conversationMarkdownHeadingRegex.matchEntire(line)?.let { match ->
            blocks += ConversationMarkdownBlock.Heading(
                level = match.groupValues[1].length.coerceIn(1, 6),
                content = match.groupValues[2].trim(),
            )
            index += 1
            continue
        }

        if (
            isConversationMarkdownTableHeaderLine(line) &&
            index + 1 < lines.size &&
            conversationMarkdownTableSeparatorRegex.matches(lines[index + 1])
        ) {
            val headers = splitConversationMarkdownTableRow(line)
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && isConversationMarkdownTableBodyLine(lines[index])) {
                rows += splitConversationMarkdownTableRow(lines[index])
                index += 1
            }
            blocks += ConversationMarkdownBlock.Table(headers = headers, rows = rows)
            continue
        }

        if (conversationMarkdownUnorderedListRegex.matches(line) || conversationMarkdownOrderedListRegex.matches(line)) {
            val items = mutableListOf<ConversationMarkdownListItem>()
            while (index < lines.size) {
                val current = lines[index]
                val unordered = conversationMarkdownUnorderedListRegex.matchEntire(current)
                val ordered = conversationMarkdownOrderedListRegex.matchEntire(current)
                val match = unordered ?: ordered ?: break
                val indent = match.groupValues[1].replace("\t", "    ").length
                val depth = (indent / 2).coerceAtLeast(0)
                val marker = if (unordered != null) "\u2022" else "${match.groupValues[2]}."
                val rawContent = match.groupValues[3]
                val taskMatch = conversationMarkdownTaskListRegex.matchEntire(rawContent.trim())
                items += if (taskMatch != null) {
                    ConversationMarkdownListItem(
                        marker = if (taskMatch.groupValues[1].equals("x", ignoreCase = true)) "☑" else "☐",
                        content = taskMatch.groupValues[2].trim(),
                        depth = depth,
                        isTask = true,
                    )
                } else {
                    ConversationMarkdownListItem(
                        marker = marker,
                        content = rawContent.trim(),
                        depth = depth,
                    )
                }
                index += 1
            }
            blocks += ConversationMarkdownBlock.ListBlock(items = items)
            continue
        }

        if (conversationMarkdownBlockQuoteRegex.matches(line)) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size) {
                val match = conversationMarkdownBlockQuoteRegex.matchEntire(lines[index]) ?: break
                quoteLines += match.groupValues[1].trim()
                index += 1
            }
            blocks += ConversationMarkdownBlock.Quote(lines = quoteLines)
            continue
        }

        val paragraphLines = mutableListOf<String>()
        while (index < lines.size) {
            val current = lines[index]
            if (current.isBlank()) break
            if (
                conversationMarkdownCodeFenceRegex.matches(current) ||
                conversationMarkdownDividerRegex.matches(current) ||
                conversationMarkdownHeadingRegex.matches(current) ||
                (isConversationMarkdownTableHeaderLine(current) &&
                    index + 1 < lines.size &&
                    conversationMarkdownTableSeparatorRegex.matches(lines[index + 1])) ||
                conversationMarkdownUnorderedListRegex.matches(current) ||
                conversationMarkdownOrderedListRegex.matches(current) ||
                conversationMarkdownBlockQuoteRegex.matches(current)
            ) {
                break
            }
            paragraphLines += current.trim()
            index += 1
        }
        blocks += ConversationMarkdownBlock.Paragraph(content = paragraphLines.joinToString(separator = "\n"))
    }

    return blocks
}

internal fun ConversationMarkdownBlock.toHtml(imageUrlResolver: ((String) -> String?)? = null): String =
    when (this) {
        is ConversationMarkdownBlock.Heading ->
            buildConversationHtmlTag(
                tag = "h$level",
                content = buildConversationInlineHtml(content, imageUrlResolver),
            )

        is ConversationMarkdownBlock.Paragraph ->
            buildConversationHtmlTag(
                tag = "p",
                content = buildConversationInlineHtml(content, imageUrlResolver),
            )

        is ConversationMarkdownBlock.ListBlock -> buildConversationMarkdownListHtml(items, imageUrlResolver)

        is ConversationMarkdownBlock.Quote ->
            buildConversationHtmlTag(
                tag = "blockquote",
                content = buildConversationHtmlTag("p", buildConversationInlineHtml(lines.joinToString("\n"), imageUrlResolver)),
            )

        is ConversationMarkdownBlock.CodeBlock ->
            buildString {
                append("""<div class="cr-md-code-block">""")
                language?.takeIf { it.isNotBlank() }?.let {
                    append("""<div class="cr-md-code-header">""")
                    append(buildConversationHtmlTag("""div class="cr-md-code-language"""", escapeConversationHtml(it)))
                    append("""<span class="cr-md-code-accent" aria-hidden="true"></span>""")
                    append("</div>")
                }
                append(
                    "<pre><code" +
                        language?.takeIf { it.isNotBlank() }?.let {
                            " class=\"language-${escapeConversationHtmlAttribute(it)}\""
                        }.orEmpty() +
                        ">${escapeConversationHtml(code)}</code></pre>",
                )
                append("</div>")
            }

        is ConversationMarkdownBlock.Divider -> "<hr />"

        is ConversationMarkdownBlock.Table ->
            buildString {
                append("""<div class="cr-md-table-wrap"><table><thead><tr>""")
                headers.forEach { header ->
                    append(buildConversationHtmlTag("th", buildConversationInlineHtml(header, imageUrlResolver)))
                }
                append("</tr></thead><tbody>")
                rows.forEach { row ->
                    append("<tr>")
                    row.forEach { cell ->
                        append(buildConversationHtmlTag("td", buildConversationInlineHtml(cell, imageUrlResolver)))
                    }
                    append("</tr>")
                }
                append("</tbody></table></div>")
            }
    }

internal fun buildConversationMarkdownListHtml(
    items: List<ConversationMarkdownListItem>,
    imageUrlResolver: ((String) -> String?)? = null,
): String {
    if (items.isEmpty()) return ""

    val roots = mutableListOf<ConversationMarkdownListNode>()
    val stack = mutableListOf<ConversationMarkdownListNode>()

    items.forEach { item ->
        val normalizedDepth = item.depth.coerceAtLeast(0).coerceAtMost(stack.size)
        while (stack.size > normalizedDepth) {
            stack.removeLast()
        }

        val node = ConversationMarkdownListNode(item = item)
        if (stack.isEmpty()) {
            roots += node
        } else {
            stack.last().children += node
        }
        stack += node
    }

    return renderConversationMarkdownListNodes(roots, imageUrlResolver)
}

internal fun renderConversationMarkdownListNodes(
    nodes: List<ConversationMarkdownListNode>,
    imageUrlResolver: ((String) -> String?)? = null,
): String =
    buildString {
        var index = 0
        while (index < nodes.size) {
            val listType = nodes[index].item.toHtmlListType()
            append(openConversationMarkdownListTag(listType, nodes[index].item))

            while (index < nodes.size && nodes[index].item.toHtmlListType() == listType) {
                val node = nodes[index]
                append("<li>")
                if (node.item.isTask) {
                    append(buildConversationHtmlTag("""span class="cr-md-task-marker"""", escapeConversationHtml(node.item.marker)))
                    append(' ')
                }
                append(buildConversationInlineHtml(node.item.content, imageUrlResolver))
                if (node.children.isNotEmpty()) {
                    append(renderConversationMarkdownListNodes(node.children, imageUrlResolver))
                }
                append("</li>")
                index += 1
            }

            append(closeConversationMarkdownListTag(listType))
        }
    }

internal fun ConversationMarkdownListItem.toHtmlListType(): ConversationMarkdownHtmlListType =
    if (marker.endsWith(".") && marker.dropLast(1).all(Char::isDigit)) {
        ConversationMarkdownHtmlListType.Ordered
    } else {
        ConversationMarkdownHtmlListType.Unordered
    }

internal fun openConversationMarkdownListTag(
    type: ConversationMarkdownHtmlListType,
    item: ConversationMarkdownListItem,
): String =
    when (type) {
        ConversationMarkdownHtmlListType.Ordered -> {
            val start = item.marker.removeSuffix(".").toIntOrNull()
            if (start != null && start > 1) {
                """<ol start="$start">"""
            } else {
                "<ol>"
            }
        }

        ConversationMarkdownHtmlListType.Unordered -> "<ul>"
    }

internal fun closeConversationMarkdownListTag(type: ConversationMarkdownHtmlListType): String =
    when (type) {
        ConversationMarkdownHtmlListType.Ordered -> "</ol>"
        ConversationMarkdownHtmlListType.Unordered -> "</ul>"
    }

internal fun buildConversationInlineHtml(
    text: String,
    imageUrlResolver: ((String) -> String?)? = null,
): String {
    fun parseSegment(
        source: String,
        allowAutolinks: Boolean = true,
    ): String =
        buildString {
            var index = 0
            while (index < source.length) {
                when {
                    allowAutolinks &&
                        (source.startsWith("<http://", index) || source.startsWith("<https://", index)) -> {
                        val urlEnd = source.indexOf('>', startIndex = index + 1)
                        if (urlEnd != -1) {
                            val url = source.substring(index + 1, urlEnd)
                            append("""<a href="${escapeConversationHtmlAttribute(url)}">${escapeConversationHtml(url)}</a>""")
                            index = urlEnd + 1
                            continue
                        }
                    }

                    allowAutolinks &&
                        (source.startsWith("http://", index) || source.startsWith("https://", index)) -> {
                        val end = findConversationInlineUrlEnd(source, index)
                        val candidate = source.substring(index, end)
                        val url = trimConversationTrailingUrlPunctuation(candidate)
                        if (url.isNotBlank()) {
                            val suffix = candidate.substring(url.length)
                            append("""<a href="${escapeConversationHtmlAttribute(url)}">${escapeConversationHtml(url)}</a>""")
                            append(escapeConversationHtml(suffix))
                            index = end
                            continue
                        }
                    }

                    source.startsWith("***", index) || source.startsWith("___", index) -> {
                        val delimiter = source.substring(index, index + 3)
                        val end = findConversationEmphasisEnd(source, index, delimiter)
                        if (end != -1) {
                            append("<strong><em>")
                            append(parseSegment(source.substring(index + 3, end)))
                            append("</em></strong>")
                            index = end + 3
                            continue
                        }
                    }

                    source.startsWith("**", index) || source.startsWith("__", index) -> {
                        val delimiter = source.substring(index, index + 2)
                        val end = findConversationEmphasisEnd(source, index, delimiter)
                        if (end != -1) {
                            append(buildConversationHtmlTag("strong", parseSegment(source.substring(index + 2, end))))
                            index = end + 2
                            continue
                        }
                    }

                    source.startsWith("~~", index) -> {
                        val end = source.indexOf("~~", startIndex = index + 2)
                        if (end != -1) {
                            append(buildConversationHtmlTag("del", parseSegment(source.substring(index + 2, end))))
                            index = end + 2
                            continue
                        }
                    }

                    source.startsWith("==", index) -> {
                        val end = source.indexOf("==", startIndex = index + 2)
                        if (end != -1) {
                            append(buildConversationHtmlTag("mark", parseSegment(source.substring(index + 2, end))))
                            index = end + 2
                            continue
                        }
                    }

                    source.startsWith("`", index) -> {
                        val end = source.indexOf('`', startIndex = index + 1)
                        if (end != -1) {
                            append(buildConversationInlineCodeHtml(source.substring(index + 1, end)))
                            index = end + 1
                            continue
                        }
                    }

                    source.startsWith("![", index) -> {
                        val labelEnd = source.indexOf(']', startIndex = index + 2)
                        val urlStart = labelEnd.takeIf { it != -1 }?.plus(1)
                        if (
                            labelEnd != -1 &&
                            urlStart != null &&
                            urlStart < source.length &&
                            source[urlStart] == '('
                        ) {
                            val urlEnd = source.indexOf(')', startIndex = urlStart + 1)
                            if (urlEnd != -1) {
                                val alt = source.substring(index + 2, labelEnd)
                                val rawUrl = source.substring(urlStart + 1, urlEnd).trim()
                                val resolvedUrl = if (imageUrlResolver == null) rawUrl else imageUrlResolver(rawUrl)
                                append(buildConversationImageHtml(resolvedUrl, alt))
                                index = urlEnd + 1
                                continue
                            }
                        }
                    }

                    source.startsWith("[", index) -> {
                        val labelEnd = source.indexOf(']', startIndex = index + 1)
                        val urlStart = labelEnd.takeIf { it != -1 }?.plus(1)
                        if (
                            labelEnd != -1 &&
                            urlStart != null &&
                            urlStart < source.length &&
                            source[urlStart] == '('
                        ) {
                            val urlEnd = source.indexOf(')', startIndex = urlStart + 1)
                            if (urlEnd != -1) {
                                val rawLabel = source.substring(index + 1, labelEnd)
                                val rawUrl = source.substring(urlStart + 1, urlEnd)
                                val fileTarget = conversationFileTargetFromLinkTarget(rawUrl)
                                    ?.withFallbackLine(rawLabel)
                                val href = fileTarget?.browserHref() ?: rawUrl
                                val labelLine = fileTarget?.line ?: readConversationFileLineReference(rawUrl)
                                val labelText =
                                    if (labelLine != null && isConversationLikelyFileLabel(rawLabel)) {
                                        appendConversationLineToLabel(rawLabel, labelLine)
                                    } else {
                                        fileTarget?.appendLineToLabel(rawLabel) ?: rawLabel
                                    }
                                val label = parseSegment(labelText, allowAutolinks = false)
                                val url = escapeConversationHtmlAttribute(href)
                                append("""<a href="$url">$label</a>""")
                                index = urlEnd + 1
                                continue
                            }
                        }
                    }

                    source.startsWith("*", index) || source.startsWith("_", index) -> {
                        val delimiter = source[index]
                        val end = findConversationEmphasisEnd(source, index, delimiter.toString())
                        if (end != -1) {
                            append(buildConversationHtmlTag("em", parseSegment(source.substring(index + 1, end))))
                            index = end + 1
                            continue
                        }
                    }
                }

                append(escapeConversationHtml(source[index].toString()))
                index += 1
            }
        }

    return text.split('\n').joinToString("<br />") { line -> parseSegment(line) }
}

internal fun findConversationEmphasisEnd(
    source: String,
    startIndex: Int,
    delimiter: String,
): Int {
    if (delimiter.firstOrNull() != '_') {
        return source.indexOf(delimiter, startIndex = startIndex + delimiter.length)
    }

    var searchIndex = startIndex + delimiter.length
    while (searchIndex < source.length) {
        val end = source.indexOf(delimiter, startIndex = searchIndex)
        if (end == -1) return -1
        if (isConversationUnderscoreEmphasisPair(source, startIndex, end, delimiter.length)) {
            return end
        }
        searchIndex = end + delimiter.length
    }
    return -1
}

private fun isConversationUnderscoreEmphasisPair(
    source: String,
    startIndex: Int,
    endIndex: Int,
    delimiterLength: Int,
): Boolean {
    val beforeOpen = source.getOrNull(startIndex - 1)
    val afterOpen = source.getOrNull(startIndex + delimiterLength)
    val beforeClose = source.getOrNull(endIndex - 1)
    val afterClose = source.getOrNull(endIndex + delimiterLength)
    return !beforeOpen.isConversationIdentifierChar() &&
        !afterClose.isConversationIdentifierChar() &&
        afterOpen?.isWhitespace() == false &&
        beforeClose?.isWhitespace() == false
}

private fun Char?.isConversationIdentifierChar(): Boolean =
    this?.let { it == '_' || it.isLetterOrDigit() } == true
