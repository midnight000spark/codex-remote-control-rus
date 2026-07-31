package com.haochen.codexremote

internal fun buildConversationPageHtml(
    items: List<ConversationItem>,
    displayBasePath: String?,
    imageContext: ConversationImageRenderContext = ConversationImageRenderContext(null, null),
): String = buildConversationRenderedPageHtml(buildConversationRenderedItems(items, displayBasePath, imageContext))

internal fun buildConversationRenderedPageHtml(
    renderedItems: List<ConversationRenderedItem>,
    renderTag: String? = null,
): String {
    val bodyHtml = renderedItems.joinToString("") { it.html }
    val style = buildConversationCss()
    val script = buildConversationScript()
    val renderTagAttribute =
        renderTag
            ?.takeIf { it.isNotBlank() }
            ?.let { """ data-cr-render-tag="${escapeConversationHtmlAttribute(it)}"""" }
            .orEmpty()
    return """
        <!DOCTYPE html>
        <html dir="auto">
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, width=device-width, viewport-fit=cover" />
            <style>
            $style
            </style>
            <script>
            $script
            </script>
        </head>
        <body$renderTagAttribute>
            <main>
                <article>
                    <section class="cr-conversation">
                        $bodyHtml
                    </section>
                </article>
            </main>
        </body>
        </html>
    """.trimIndent()
}

internal data class ConversationRenderedItem(
    val id: String,
    val type: String,
    val html: String,
)

internal data class ConversationRenderCacheEntry(
    val signature: String,
    val renderedItem: ConversationRenderedItem,
)

internal fun buildConversationRenderedItems(
    items: List<ConversationItem>,
    displayBasePath: String?,
    imageContext: ConversationImageRenderContext = ConversationImageRenderContext(null, null),
): List<ConversationRenderedItem> =
    items.map { item -> buildConversationRenderedItem(item, displayBasePath, imageContext) }

internal fun buildConversationRenderedItem(
    item: ConversationItem,
    displayBasePath: String?,
    imageContext: ConversationImageRenderContext = ConversationImageRenderContext(null, null),
): ConversationRenderedItem {
    val type =
        when (item) {
            is ConversationItem.Bubble -> if (item.right) "user" else "assistant"
            is ConversationItem.SystemNote -> "system"
            is ConversationItem.FileChange -> "fileChange"
        }
    val contentHtml =
        when (item) {
            is ConversationItem.Bubble -> item.toHtml(imageContext)
            is ConversationItem.SystemNote -> item.toHtml()
            is ConversationItem.FileChange -> item.toHtml(displayBasePath)
        }
    return ConversationRenderedItem(
        id = item.id,
        type = type,
        html = wrapConversationRenderedItem(item.id, type, contentHtml),
    )
}

internal fun buildConversationRenderSignature(
    item: ConversationItem,
    displayBasePath: String?,
    imageContext: ConversationImageRenderContext = ConversationImageRenderContext(null, null),
): String =
    when (item) {
        is ConversationItem.Bubble ->
            listOf(
                "bubble",
                item.id,
                item.right.toString(),
                item.text,
                imageContext.sessionId.orEmpty(),
                imageContext.bridgeUrl.orEmpty(),
                item.images.joinToString("\u001E") { image ->
                    listOf(image.sourceType.name, image.source, image.alt).joinToString("\u001D")
                },
                item.backgroundColor.toString(),
                item.textColor.toString(),
                item.turnKey.orEmpty(),
                item.assistantKey.orEmpty(),
                item.sourceItemId.orEmpty(),
            ).joinToString("\u001F")

        is ConversationItem.SystemNote ->
            listOf(
                "system",
                item.id,
                item.text,
                item.itemKey.orEmpty(),
            ).joinToString("\u001F")

        is ConversationItem.FileChange ->
            listOf(
                "fileChange",
                item.id,
                item.title,
                item.summary,
                item.status,
                item.fallbackDiff.orEmpty(),
                item.sourceItemId.orEmpty(),
                item.turnId.orEmpty(),
                displayBasePath.orEmpty(),
                item.diffEntries.joinToString("\u001E") { entry ->
                    listOf(entry.path, entry.kind, entry.diff, entry.movePath.orEmpty()).joinToString("\u001D")
                },
            ).joinToString("\u001F")
    }

internal fun buildConversationItemsHtml(
    items: List<ConversationItem>,
    displayBasePath: String?,
    imageContext: ConversationImageRenderContext = ConversationImageRenderContext(null, null),
): String =
    buildConversationRenderedItems(items, displayBasePath, imageContext).joinToString("") { it.html }

internal fun wrapConversationRenderedItem(
    id: String,
    type: String,
    contentHtml: String,
): String =
    """
    <div
        class="cr-rendered-item"
        data-cr-item-id="${escapeConversationHtmlAttribute(id)}"
        data-cr-item-type="${escapeConversationHtmlAttribute(type)}"
    >
        $contentHtml
    </div>
    """.trimIndent()

internal fun ConversationItem.Bubble.toHtml(imageContext: ConversationImageRenderContext): String {
    val textHtml =
        if (text.isBlank()) {
            ""
        } else if (right) {
            buildPlainTextHtml(text)
        } else {
            buildConversationMarkdownHtml(text) { source ->
                conversationImageFromUrl(source)?.let { image -> resolveConversationImageUrl(image, imageContext) }
            }
        }
    val imagesHtml = buildConversationImagesHtml(images, imageContext)
    val contentHtml = textHtml + imagesHtml
    return if (right) {
        """
        <div class="cr-message user">
            <div class="cr-bubble user">$contentHtml</div>
        </div>
        """.trimIndent()
    } else {
        """
        <div class="cr-message assistant">
            <div class="cr-bubble assistant">$contentHtml</div>
        </div>
        """.trimIndent()
    }
}

internal fun buildConversationImagesHtml(
    images: List<ConversationImage>,
    imageContext: ConversationImageRenderContext,
): String {
    if (images.isEmpty()) return ""
    return images.joinToString(
        prefix = """<div class="cr-conversation-images">""",
        postfix = "</div>",
        separator = "",
    ) { image ->
        buildConversationImageHtml(resolveConversationImageUrl(image, imageContext), image.alt)
    }
}

internal fun buildConversationImageHtml(
    source: String?,
    alt: String,
): String {
    val normalizedSource = source?.trim().orEmpty()
    val safeAlt = escapeConversationHtmlAttribute(alt)
    if (normalizedSource.isBlank()) {
        return """<span class="cr-conversation-image-frame"><span class="cr-conversation-image-error">图片无法加载</span></span>"""
    }
    return """
        <span class="cr-conversation-image-frame">
            <img
                class="cr-conversation-image"
                src="${escapeConversationHtmlAttribute(normalizedSource)}"
                alt="$safeAlt"
                loading="lazy"
                decoding="async"
                onerror="this.hidden=true;this.nextElementSibling.hidden=false"
            />
            <span class="cr-conversation-image-error" hidden>图片加载失败</span>
        </span>
    """.trimIndent()
}

internal fun ConversationItem.SystemNote.toHtml(): String =
    """
    <div class="cr-note-row">
        <div class="cr-note">${buildPlainTextHtml(text)}</div>
    </div>
    """.trimIndent()

internal fun ConversationItem.FileChange.toHtml(displayBasePath: String?): String {
    val diffStats = buildConversationDiffStatsLine(diffEntries, fallbackDiff)
    val metaHtml = buildConversationFileChangeMetaHtml(
        title = title.ifBlank { "文件修改" },
        stats = diffStats,
        browserItemId = id,
    )
    return """
        <div class="cr-message assistant">
            <div class="cr-bubble file-change">
                $metaHtml
                ${buildConversationDiffDetailsHtml(diffEntries, fallbackDiff, browserItemId = id, displayBasePath = displayBasePath)}
            </div>
        </div>
    """.trimIndent()
}

internal fun buildConversationDiffDetailsHtml(
    diffEntries: List<ConversationDiffEntry>,
    fallbackDiff: String?,
    browserItemId: String? = null,
    displayBasePath: String?,
): String {
    if (diffEntries.isEmpty() && fallbackDiff.isNullOrBlank()) return ""

    val content =
        if (diffEntries.isNotEmpty()) {
            buildString {
                append("""<div class="cr-diff-list">""")
                diffEntries.forEachIndexed { index, entry ->
                    append(
                        buildConversationDiffEntryHtml(
                            entry = entry,
                            entryIndex = index,
                            browserItemId = browserItemId,
                            displayBasePath = displayBasePath,
                        ),
                    )
                }
                append("</div>")
            }
        } else {
            buildConversationFallbackDiffHtml(
                diffText = fallbackDiff.orEmpty(),
                defaultLabel = "查看 diff",
                browserItemId = browserItemId,
            )
        }
    return """<div class="cr-inline-diff">$content</div>"""
}

internal fun buildConversationDiffCodeHtml(
    diffText: String,
    pathHint: String? = null,
): String =
    diffText.replace("\r\n", "\n")
        .split('\n')
        .joinToString("") { line ->
            val language = detectConversationCodeLanguage(pathHint)
            when (classifyConversationDiffLine(line)) {
                ConversationDiffLineKind.Added ->
                    """
                    <span class="cr-diff-line add">
                        <span class="cr-diff-prefix add">+</span><span class="cr-diff-content">${buildConversationHighlightedCodeHtml(line.drop(1), language)}</span>
                    </span>
                    """.trimIndent()

                ConversationDiffLineKind.Deleted ->
                    """
                    <span class="cr-diff-line delete">
                        <span class="cr-diff-prefix delete">-</span><span class="cr-diff-content">${buildConversationHighlightedCodeHtml(line.drop(1), language)}</span>
                    </span>
                    """.trimIndent()

                ConversationDiffLineKind.Hunk ->
                    """<span class="cr-diff-line hunk">${escapeConversationHtml(line)}</span>"""

                ConversationDiffLineKind.Meta ->
                    """<span class="cr-diff-line meta">${escapeConversationHtml(line)}</span>"""

                ConversationDiffLineKind.Context ->
                    if (line.startsWith(" ")) {
                        """
                        <span class="cr-diff-line">
                            <span class="cr-diff-prefix context"> </span><span class="cr-diff-content">${buildConversationHighlightedCodeHtml(line.drop(1), language)}</span>
                        </span>
                        """.trimIndent()
                    } else {
                        """<span class="cr-diff-line">${buildConversationHighlightedCodeHtml(line, language)}</span>"""
                    }
            }
        }

internal fun buildConversationDiffEntryHtml(
    entry: ConversationDiffEntry,
    entryIndex: Int,
    browserItemId: String?,
    displayBasePath: String?,
): String {
    val panelId = "cr-diff-${browserItemId?.hashCode() ?: 0}-$entryIndex"
    val label = entry.displayPath(basePath = displayBasePath, maxLength = 52)
    val statsLabel = entry.changeLabel()
    val href =
        browserItemId?.takeIf { it.isNotBlank() }?.let {
            buildString {
                append("codex-code-browser://open?itemId=")
                append(encodeUrlComponent(it))
                val browsePath = entry.browsePath()
                if (browsePath.isNotBlank()) {
                    append("&path=")
                    append(encodeUrlComponent(browsePath))
                }
            }
        }
    val titleHtml =
        if (href != null) {
            """<span class="cr-diff-file-link">${escapeConversationHtml(label)}</span>"""
        } else {
            """<span class="cr-diff-file-link static">${escapeConversationHtml(label)}</span>"""
        }
    val statsHtml = statsLabel?.let { buildConversationDiffStatsHtml(it) }.orEmpty()
    return """
        <div class="cr-diff-item">
            <div class="cr-diff-item-row" onclick="return toggleConversationDiff(event, '$panelId');">
                $titleHtml
                <span class="cr-diff-inline-stats">$statsHtml</span>
                <span
                    class="cr-diff-toggle"
                    data-panel-id="$panelId"
                    aria-hidden="true"
                ></span>
            </div>
            <div class="cr-diff-panel" id="$panelId">
                <pre><code>${buildConversationDiffCodeHtml(entry.diff.ifBlank { "无可显示 diff" }, entry.browsePath())}</code></pre>
            </div>
        </div>
    """.trimIndent()
}

internal fun buildConversationFallbackDiffHtml(
    diffText: String,
    defaultLabel: String,
    browserItemId: String?,
): String {
    val panelId = "cr-diff-${browserItemId?.hashCode() ?: 0}-fallback"
    val href =
        browserItemId?.takeIf { it.isNotBlank() }?.let {
            "codex-code-browser://open?itemId=${encodeUrlComponent(it)}"
        }
    val titleHtml =
        if (href != null) {
            """<span class="cr-diff-file-link">${escapeConversationHtml(defaultLabel)}</span>"""
        } else {
            """<span class="cr-diff-file-link static">${escapeConversationHtml(defaultLabel)}</span>"""
        }
    val statsLabel = buildConversationDiffStatsLine(emptyList(), diffText)
    return """
        <div class="cr-diff-item">
            <div class="cr-diff-item-row" onclick="return toggleConversationDiff(event, '$panelId');">
                $titleHtml
                ${statsLabel?.let { """<span class="cr-diff-inline-stats">${buildConversationDiffStatsHtml(it)}</span>""" }.orEmpty()}
                <span
                    class="cr-diff-toggle"
                    data-panel-id="$panelId"
                    aria-hidden="true"
                ></span>
            </div>
            <div class="cr-diff-panel" id="$panelId">
                <pre><code>${buildConversationDiffCodeHtml(diffText.ifBlank { "无可显示 diff" })}</code></pre>
            </div>
        </div>
    """.trimIndent()
}

internal fun conversationKindLabel(kind: String, movePath: String?): String {
    return buildDiffKindLabel(kind, movePath)
}

internal enum class ConversationDiffLineKind {
    Added,
    Deleted,
    Hunk,
    Meta,
    Context,
}

internal fun classifyConversationDiffLine(line: String): ConversationDiffLineKind {
    return when {
        line.startsWith("+") && !line.startsWith("+++") -> ConversationDiffLineKind.Added
        line.startsWith("-") && !line.startsWith("---") -> ConversationDiffLineKind.Deleted
        line.startsWith("@@") -> ConversationDiffLineKind.Hunk
        line.startsWith("diff --git") ||
            line.startsWith("index ") ||
            line.startsWith("+++ ") ||
            line.startsWith("--- ") ||
            line.startsWith("rename from ") ||
            line.startsWith("rename to ") ||
            line.startsWith("new file mode ") ||
            line.startsWith("deleted file mode ") ||
            line.startsWith("similarity index ") -> ConversationDiffLineKind.Meta
        else -> ConversationDiffLineKind.Context
    }
}

internal fun buildConversationDiffStatsLine(
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

internal fun buildConversationFileChangeMetaHtml(
    title: String,
    stats: String?,
    browserItemId: String?,
): String {
    val innerHtml = buildString {
        append("""<span class="cr-file-change-open-title">${escapeConversationHtml(title)}</span>""")
        if (!stats.isNullOrBlank()) {
            append("""<span class="cr-diff-inline-stats">${buildConversationDiffStatsHtml(stats)}</span>""")
        }
        append("""<span class="cr-file-change-open-hint">查看详情 ▸</span>""")
    }
    val titleHtml =
        browserItemId?.takeIf { it.isNotBlank() }?.let {
            val href = "codex-code-browser://open?itemId=${encodeUrlComponent(it)}"
            """<a class="cr-file-change-open" href="$href">$innerHtml</a>"""
        } ?: """<span class="cr-file-change-open">$innerHtml</span>"""
    return """<div class="cr-file-change-meta">$titleHtml</div>"""
}

internal fun buildConversationDiffStatsHtml(label: String): String =
    buildString {
        var index = 0
        while (index < label.length) {
            when {
                label[index] == '+' -> {
                    val end = readConversationDiffStatsEnd(label, index + 1)
                    append("""<span class="cr-diff-stats-add">${escapeConversationHtml(label.substring(index, end))}</span>""")
                    index = end
                }

                label[index] == '-' -> {
                    val end = readConversationDiffStatsEnd(label, index + 1)
                    append("""<span class="cr-diff-stats-delete">${escapeConversationHtml(label.substring(index, end))}</span>""")
                    index = end
                }

                else -> {
                    append(escapeConversationHtml(label[index].toString()))
                    index += 1
                }
            }
        }
    }

internal fun readConversationDiffStatsEnd(
    label: String,
    start: Int,
): Int {
    var index = start
    while (index < label.length && label[index].isDigit()) {
        index += 1
    }
    return index
}

internal fun buildPlainTextHtml(text: String): String =
    text.replace("\r\n", "\n")
        .split('\n')
        .joinToString("<br />") { line -> escapeConversationHtml(line) }
