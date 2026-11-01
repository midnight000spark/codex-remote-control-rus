package com.haochen.codexremote

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
internal fun MainActivity.CodeBrowserLoadedFilePane(
    state: CodeBrowserState,
    content: CodeBrowserFileContent,
    onSaveFile: (CodeBrowserFileContent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = compactDiffDisplayPath(content.resolvedPath, state.basePath, maxLength = 96) + state.lineSuffixLabel(),
            color = uiMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (content.kind) {
            CodeBrowserFileKind.Text -> {
                val documentPreviewKind = remember(content.resolvedPath, content.mimeType) {
                    content.documentPreviewKind()
                }
                var documentViewMode by remember(content.sessionId, content.resolvedPath) {
                    mutableStateOf(CodeBrowserDocumentViewMode.Source)
                }
                if (content.truncated) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = uiPrimarySoft,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Сейчас загружены только первые ${formatCodeBrowserFileSize(content.bytes.toLong())}",
                                color = uiMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { onSaveFile(content) },
                                enabled = !codeBrowserFileActionInProgress && content.downloadable,
                            ) {
                                Text("Скачать файл полностью")
                            }
                        }
                    }
                }
                if (documentPreviewKind != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CodeBrowserModeButton(
                            text = "Исходный код",
                            selected = documentViewMode == CodeBrowserDocumentViewMode.Source,
                            modifier = Modifier.weight(1f),
                            onClick = { documentViewMode = CodeBrowserDocumentViewMode.Source },
                        )
                        CodeBrowserModeButton(
                            text = "Предпросмотр",
                            selected = documentViewMode == CodeBrowserDocumentViewMode.Preview,
                            enabled = !content.truncated,
                            modifier = Modifier.weight(1f),
                            onClick = { documentViewMode = CodeBrowserDocumentViewMode.Preview },
                        )
                    }
                }
                if (
                    documentPreviewKind != null &&
                    documentViewMode == CodeBrowserDocumentViewMode.Preview &&
                    !content.truncated
                ) {
                    CodeBrowserDocumentPreview(
                        content = content,
                        kind = documentPreviewKind,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    CodeBrowserTextPane(
                        text = content.content,
                        mode = CodeTextMode.File,
                        pathHint = content.resolvedPath,
                        scrollToLine = state.selectedLine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }

            CodeBrowserFileKind.Image -> {
                val source = remember(content.sessionId, content.resolvedPath, currentBridgeUrl, bridgeUrl) {
                    codeBrowserFileSourceUrl(content)
                }
                if (source == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BodyText("Адрес изображения недоступен")
                    }
                } else {
                    CodeBrowserImagePreview(
                        source = source,
                        onClick = { openConversationImage(source) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        OutlinedButton(
                            onClick = { onSaveFile(content) },
                            enabled = !codeBrowserFileActionInProgress && content.downloadable,
                        ) {
                            Text("Скачать")
                        }
                        Button(
                            onClick = { openConversationImage(source) },
                            enabled = !codeBrowserFileActionInProgress,
                        ) {
                            Text("Открыть на весь экран")
                        }
                    }
                }
            }

            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CodeBrowserFileCard(
                        content = content,
                        headline = "Предпросмотр в приложении пока не поддерживается",
                        onSaveFile = onSaveFile,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.CodeBrowserBinaryDiffPane(
    state: CodeBrowserState,
    onSaveFile: (CodeBrowserFileContent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.selectedEntry()
    val deleted = entry?.kind?.trim() == "delete" && entry.movePath.isNullOrBlank()
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entry?.let {
            Text(
                text = it.displayPath(basePath = state.basePath, maxLength = 96),
                color = uiMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                deleted -> CodeBrowserUnavailableFileCard("Двоичный файл удалён", "Текущую версию нельзя скачать")
                state.fileReadState is CodeBrowserFileReadState.Loaded -> {
                    CodeBrowserFileCard(
                        content = state.fileReadState.content,
                        headline = "Двоичный файл изменён, текстовую разницу показать нельзя",
                        onSaveFile = onSaveFile,
                    )
                }
                state.fileReadState is CodeBrowserFileReadState.Error -> {
                    CodeBrowserUnavailableFileCard(
                        headline = "Для двоичного файла текстовая разница недоступна",
                        detail = state.fileReadState.message,
                    )
                }
                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = uiPrimary)
                        Text(
                            text = "Читаю сведения о файле…",
                            color = uiMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActivity.CodeBrowserFileCard(
    content: CodeBrowserFileContent,
    headline: String,
    onSaveFile: (CodeBrowserFileContent) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val imageSource = if (content.kind == CodeBrowserFileKind.Image) codeBrowserFileSourceUrl(content) else null
    Surface(
        modifier = Modifier.fillMaxWidth(0.9f),
        shape = RoundedCornerShape(16.dp),
        color = uiSurfaceAlt,
        border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(uiPrimarySoft, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = content.fileBadge(),
                    color = uiPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = headline,
                color = uiText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = content.displayName,
                color = uiText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "${content.kind.displayLabel()} · ${formatCodeBrowserFileSize(content.size)}",
                color = uiMuted,
                fontSize = 12.sp,
            )
            Text(
                text = content.mimeType,
                color = uiMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (imageSource != null) {
                            openConversationImage(imageSource)
                        } else {
                            scope.launch { openCodeBrowserFileExternally(content) }
                        }
                    },
                    enabled = !codeBrowserFileActionInProgress,
                ) {
                    Text(if (content.kind == CodeBrowserFileKind.Image) "Просмотр" else "Открыть")
                }
                OutlinedButton(
                    onClick = { onSaveFile(content) },
                    enabled = !codeBrowserFileActionInProgress && content.downloadable,
                ) {
                    Text("Скачать")
                }
            }
        }
    }
}

@Composable
private fun MainActivity.CodeBrowserUnavailableFileCard(headline: String, detail: String) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = headline,
            color = uiText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail,
            color = uiMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CodeBrowserImagePreview(
    source: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color(0xFFF8FBF9), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = false
                    settings.loadsImagesAutomatically = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = WebViewClient()
                    loadCodeBrowserImagePreview(this, source)
                }
            },
            update = { view ->
                if (view.tag != source) loadCodeBrowserImagePreview(view, source)
            },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick),
        )
    }
}

private fun loadCodeBrowserImagePreview(webView: WebView, source: String) {
    webView.tag = source
    val html =
        """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <style>
            html, body { width: 100%; height: 100%; margin: 0; background: #f8fbf9; }
            body { display: flex; align-items: center; justify-content: center; overflow: hidden; }
            img { display: block; max-width: 100%; max-height: 100%; width: auto; height: auto; object-fit: contain; }
          </style>
        </head>
        <body><img src="${escapeConversationHtmlAttribute(source)}" /></body>
        </html>
        """.trimIndent()
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

private fun CodeBrowserFileContent.fileBadge(): String =
    when (kind) {
        CodeBrowserFileKind.Image -> "IMG"
        CodeBrowserFileKind.Pdf -> "PDF"
        CodeBrowserFileKind.Audio -> "AUDIO"
        CodeBrowserFileKind.Video -> "VIDEO"
        CodeBrowserFileKind.Text -> "TXT"
        CodeBrowserFileKind.Binary -> displayName.substringAfterLast('.', "FILE").uppercase().take(5).ifBlank { "FILE" }
    }
