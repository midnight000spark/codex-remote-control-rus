package com.haochen.codexremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun MainActivity.CodeBrowserPage(
    state: CodeBrowserState,
    onSaveFile: (CodeBrowserFileContent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedEntry = state.selectedEntry()
    val selectedDiffText = selectedEntry?.diff ?: state.fallbackDiff.orEmpty()
    val selectedPathLooksBinary = isLikelyBinaryFilePath(selectedEntry?.browsePath() ?: state.selectedPath)
    val diffStats = buildDiffStatsLine(diffEntries = state.diffEntries, fallbackDiff = state.fallbackDiff)
    var fileListExpanded by remember(state.conversationItemId) { mutableStateOf(false) }
    val hasDiffContent = state.hasDiffContent()
    val selectedPathLabel =
        (selectedEntry?.displayPath(basePath = state.basePath, maxLength = 96)
            ?: state.selectedPath?.takeIf { it.isNotBlank() }?.let {
                compactDiffDisplayPath(it, state.basePath, maxLength = 96)
            }
            ?: "Файл не выбран") + state.lineSuffixLabel()

    LaunchedEffect(state.conversationItemId, state.mode, state.selectedPath, selectedDiffText, state.fileReadState) {
        val needsFileMetadata =
            state.mode == CodeBrowserMode.File ||
                (
                    state.mode == CodeBrowserMode.Diff &&
                        (selectedDiffText.isBlank() || selectedPathLooksBinary || isBinaryDiffText(selectedDiffText))
                )
        if (needsFileMetadata && state.canReadSelectedFile() && state.fileReadState is CodeBrowserFileReadState.Idle) {
            loadCodeBrowserFileContent(state)
        }
    }

    Box(
        modifier = modifier
            .background(uiBackground)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hasDiffContent) "Изменение файла" else "Просмотр файлов",
                    color = uiText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                diffStats?.let {
                    DiffStatsText(
                        label = it,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (state.diffEntries.isNotEmpty()) {
                val visibleEntries =
                    if (fileListExpanded || state.diffEntries.size <= 4) {
                        state.diffEntries
                    } else {
                        state.diffEntries.take(4)
                    }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    visibleEntries.forEachIndexed { index, entry ->
                        CodeBrowserFileRow(
                            label = entry.filenameLabel(),
                            stats = entry.changeLabel(),
                            selected = state.selectedPath == entry.browsePath(),
                            onClick = {
                                selectCodeBrowserPath(entry.browsePath())
                                setCodeBrowserMode(CodeBrowserMode.Diff)
                            },
                        )
                        if (index < visibleEntries.lastIndex) {
                            HorizontalDivider(color = uiBorder.copy(alpha = 0.75f))
                        }
                    }
                    if (state.diffEntries.size > 4) {
                        HorizontalDivider(color = uiBorder.copy(alpha = 0.75f))
                        TextButton(
                            onClick = { fileListExpanded = !fileListExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (fileListExpanded) {
                                    "Свернуть список файлов"
                                } else {
                                    "Показать остальные ${state.diffEntries.size - visibleEntries.size} файлов"
                                },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                CodeBrowserModeButton(
                    text = "Diff",
                    selected = state.mode == CodeBrowserMode.Diff,
                    enabled = hasDiffContent,
                    modifier = Modifier.weight(1f),
                    onClick = { setCodeBrowserMode(CodeBrowserMode.Diff) },
                )
                CodeBrowserModeButton(
                    text = "Файл",
                    selected = state.mode == CodeBrowserMode.File,
                    enabled = state.canReadSelectedFile(),
                    modifier = Modifier.weight(1f),
                    onClick = { setCodeBrowserMode(CodeBrowserMode.File) },
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = uiSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
            ) {
                when (state.mode) {
                    CodeBrowserMode.Diff -> {
                        val loadedContent = (state.fileReadState as? CodeBrowserFileReadState.Loaded)?.content
                        val showBinaryDiff =
                            isBinaryDiffText(selectedDiffText) ||
                                selectedPathLooksBinary ||
                                (loadedContent != null && loadedContent.kind != CodeBrowserFileKind.Text)
                        if (showBinaryDiff) {
                            CodeBrowserBinaryDiffPane(
                                state = state,
                                onSaveFile = onSaveFile,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (selectedDiffText.isBlank() && state.fileReadState is CodeBrowserFileReadState.Loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = uiPrimary)
                            }
                        } else if (selectedDiffText.isBlank()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                BodyText("Для этого элемента пока нет diff для отображения.")
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (selectedEntry != null) {
                                    Text(
                                        text = selectedPathLabel,
                                        color = uiMuted,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                CodeBrowserTextPane(
                                    text = selectedDiffText,
                                    mode = CodeTextMode.Diff,
                                    pathHint = selectedEntry?.browsePath(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }
                    }

                    CodeBrowserMode.File -> {
                        when (val fileState = state.fileReadState) {
                            CodeBrowserFileReadState.Idle -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = selectedPathLabel,
                                        color = uiMuted,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        BodyText("Готовлюсь читать содержимое файла…")
                                    }
                                }
                            }

                            CodeBrowserFileReadState.Loading -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = selectedPathLabel,
                                        color = uiMuted,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = uiPrimary)
                                    }
                                }
                            }

                            is CodeBrowserFileReadState.Error -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = selectedPathLabel,
                                        color = uiMuted,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BodyText(fileState.message)
                                    }
                                }
                            }

                            is CodeBrowserFileReadState.Loaded -> {
                                CodeBrowserLoadedFilePane(
                                    state = state,
                                    content = fileState.content,
                                    onSaveFile = onSaveFile,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.CodeBrowserModeButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text(text)
        }
    }
}

@Composable
internal fun MainActivity.CodeBrowserFileRow(
    label: String,
    stats: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) uiPrimarySoft else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = uiText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        stats?.let {
            DiffStatsText(
                label = it,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun MainActivity.DiffStatsText(
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    val annotated =
        remember(label, fontSize, fontWeight) {
            buildAnnotatedString {
                var index = 0
                while (index < label.length) {
                    when {
                        label[index] == '+' -> {
                            val end = readDiffStatsSegmentEnd(label, index + 1)
                            withStyle(SpanStyle(color = uiPrimary, fontWeight = fontWeight)) {
                                append(label.substring(index, end))
                            }
                            index = end
                        }

                        label[index] == '-' -> {
                            val end = readDiffStatsSegmentEnd(label, index + 1)
                            withStyle(SpanStyle(color = Color(0xFFDC2626), fontWeight = fontWeight)) {
                                append(label.substring(index, end))
                            }
                            index = end
                        }

                        else -> {
                            withStyle(SpanStyle(color = uiMuted, fontWeight = fontWeight)) {
                                append(label[index])
                            }
                            index += 1
                        }
                    }
                }
            }
        }
    Text(
        text = annotated,
        color = uiMuted,
        fontSize = fontSize,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun MainActivity.readDiffStatsSegmentEnd(
    label: String,
    start: Int,
): Int {
    var index = start
    while (index < label.length && label[index].isDigit()) {
        index += 1
    }
    return index
}

@Composable
internal fun MainActivity.CodeBrowserTextPane(
    text: String,
    mode: CodeTextMode,
    pathHint: String? = null,
    scrollToLine: Int? = null,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val lazyListState = rememberLazyListState()
    val cacheKey = remember(mode, pathHint, text.length, text.hashCode()) {
        buildCodeBrowserRenderCacheKey(text, mode, pathHint)
    }
    val rendered by produceState<CodeBrowserRenderedContent?>(
        initialValue = codeBrowserRenderCache[cacheKey],
        key1 = cacheKey,
    ) {
        codeBrowserRenderCache[cacheKey]?.let { cached ->
            value = cached
            return@produceState
        }
        value = null
        val computed =
            withContext(Dispatchers.Default) {
                buildCodeBrowserRenderedContent(text, mode, pathHint)
            }
        codeBrowserRenderCache[cacheKey] = computed
        value = computed
    }

    if (rendered == null) {
        Box(
            modifier = modifier
                .background(c(0xFFF8FBF9), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = uiPrimary)
        }
        return
    }

    val renderedContent = rendered
    val renderedLines = renderedContent?.lines.orEmpty()

    LaunchedEffect(renderedContent, mode, pathHint, scrollToLine, renderedLines.size) {
        val line = scrollToLine?.takeIf { mode == CodeTextMode.File && it > 0 && renderedLines.isNotEmpty() } ?: return@LaunchedEffect
        val targetItem = line.coerceIn(1, renderedLines.size)
        lazyListState.animateScrollToItem(index = targetItem, scrollOffset = -64)
    }

    SelectionContainer {
        if (renderedLines.isNotEmpty()) {
            CodeBrowserLineList(
                renderedContent = renderedContent,
                mode = mode,
                listState = lazyListState,
                modifier = modifier,
            )
        } else {
            Box(
                modifier = modifier
                    .background(c(0xFFF8FBF9), RoundedCornerShape(10.dp))
                    .verticalScroll(verticalScroll)
                    .padding(vertical = 8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (renderedContent?.lightweight == true) {
                        Text(
                            text = "Большие файлы переключены на облегчённый рендеринг — в приоритете скорость открытия.",
                            color = uiMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                    Text(
                        text = renderedContent?.text ?: AnnotatedString(""),
                        modifier = Modifier.padding(horizontal = 10.dp),
                        color = uiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.CodeBrowserLineList(
    renderedContent: CodeBrowserRenderedContent?,
    mode: CodeTextMode,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val lines = renderedContent?.lines.orEmpty()
    val lineNumberWidth = maxOf(2, renderedContent?.lineNumberWidth ?: lines.size.toString().length)

    LazyColumn(
        state = listState,
        modifier = modifier
            .background(c(0xFFF8FBF9), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        if (renderedContent?.lightweight == true) {
            item(key = "lightweight") {
                Text(
                    text = "Большие файлы переключены на облегчённый рендеринг — в приоритете скорость открытия.",
                    color = uiMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        item(key = "header") {
            CodeBrowserCodeBlockHeader(mode = mode, lineCount = lines.size)
            HorizontalDivider(color = c(0xFFE0ECE4))
        }
        items(
            count = lines.size,
            key = { index -> "line-$index" },
        ) { index ->
            val line = lines[index]
            if (mode == CodeTextMode.Diff) {
                CodeBrowserDiffLine(index = index, line = line, lineNumberWidth = lineNumberWidth)
            } else {
                CodeBrowserFileLine(index = index, line = line, lineNumberWidth = lineNumberWidth)
            }
        }
    }
}

@Composable
internal fun MainActivity.CodeBrowserCodeBlockHeader(
    mode: CodeTextMode,
    lineCount: Int,
) {
    val accentColor =
        when (mode) {
            CodeTextMode.Diff -> c(0xFF0EA5E9)
            CodeTextMode.File -> uiPrimary
        }
    val label =
        when (mode) {
            CodeTextMode.Diff -> "diff"
            CodeTextMode.File -> "code"
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c(0xFFF3F8F4))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(accentColor, CircleShape),
            )
            Text(
                text = label,
                color = c(0xFF4F6F5A),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "$lineCount lines",
            color = c(0xFF7C9484),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
internal fun MainActivity.CodeBrowserDiffLine(
    index: Int,
    line: CodeBrowserRenderedLine,
    lineNumberWidth: Int,
) {
    val gutterWidth = (lineNumberWidth * 8 + 18).dp
    val background =
        when (line.kind) {
            DiffLineKind.Added -> c(0xFFE5F7EA)
            DiffLineKind.Deleted -> c(0xFFFFEBEB)
            DiffLineKind.Hunk -> c(0xFFEFF8FF)
            DiffLineKind.Meta -> c(0xFFF3F7F4)
            else -> Color.Transparent
        }
    val gutterBackground =
        when (line.kind) {
            DiffLineKind.Added -> c(0xFFD5F1DE)
            DiffLineKind.Deleted -> c(0xFFFFDADA)
            DiffLineKind.Hunk -> c(0xFFE0F2FE)
            DiffLineKind.Meta -> c(0xFFE8F0EB)
            else -> c(0xFFEFF6F1)
        }
    val lineNumberColor =
        when (line.kind) {
            DiffLineKind.Added -> c(0xFF137A43)
            DiffLineKind.Deleted -> c(0xFFB91C1C)
            DiffLineKind.Hunk -> c(0xFF0369A1)
            else -> c(0xFF789283)
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.lineNumber?.toString()?.padStart(lineNumberWidth, ' ').orEmpty(),
            modifier = Modifier
                .width(gutterWidth)
                .heightIn(min = 21.dp)
                .background(gutterBackground)
                .padding(end = 8.dp),
            color = lineNumberColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.End,
        )
        Text(
            text = line.contentTextWithoutDiffMarker(),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            color = uiText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
internal fun MainActivity.CodeBrowserFileLine(
    index: Int,
    line: CodeBrowserRenderedLine,
    lineNumberWidth: Int,
) {
    val gutterWidth = (lineNumberWidth * 8 + 18).dp
    val rowBackground = if (index % 2 == 0) Color.Transparent else c(0xFFFAFCFA)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = (index + 1).toString().padStart(lineNumberWidth, ' '),
            modifier = Modifier
                .width(gutterWidth)
                .heightIn(min = 21.dp)
                .background(c(0xFFEFF6F1))
                .padding(end = 8.dp),
            color = c(0xFF789283),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.End,
        )
        Text(
            text = line.text,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            color = uiText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

internal fun CodeBrowserRenderedLine.contentTextWithoutDiffMarker(): AnnotatedString {
    return if ((kind == DiffLineKind.Added || kind == DiffLineKind.Deleted) && text.length > 0) {
        text.subSequence(1, text.length)
    } else {
        text
    }
}
