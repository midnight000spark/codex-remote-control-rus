package com.haochen.codexremote

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File

internal enum class CodeBrowserDocumentViewMode {
    Source,
    Preview,
}

internal enum class CodeBrowserDocumentPreviewKind {
    Markdown,
    Html,
}

internal fun CodeBrowserFileContent.documentPreviewKind(): CodeBrowserDocumentPreviewKind? {
    val path = resolvedPath.lowercase()
    val mime = mimeType.trim().lowercase()
    return when {
        path.endsWith(".md") || path.endsWith(".markdown") || mime == "text/markdown" ->
            CodeBrowserDocumentPreviewKind.Markdown

        path.endsWith(".html") || path.endsWith(".htm") || mime == "text/html" ->
            CodeBrowserDocumentPreviewKind.Html

        else -> null
    }
}

private data class CodeBrowserDocumentPreviewPayload(
    val key: String,
    val html: String,
    val allowedResourceUrls: Set<String>,
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MainActivity.CodeBrowserDocumentPreview(
    content: CodeBrowserFileContent,
    kind: CodeBrowserDocumentPreviewKind,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val activeBridgeUrl = (currentBridgeUrl ?: bridgeUrl).trim()
    val payload = remember(
        content.content,
        content.resolvedPath,
        content.sessionId,
        activeBridgeUrl,
        kind,
    ) {
        buildCodeBrowserDocumentPreviewPayload(content, kind, activeBridgeUrl)
    }

    key(payload.key) {
        AndroidView(
            modifier = modifier.background(Color(0xFFF8FBF9), RoundedCornerShape(10.dp)),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.domStorageEnabled = false
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkLoads = kind == CodeBrowserDocumentPreviewKind.Html
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = handleCodeBrowserDocumentUrl(
                                rawUrl = request?.url?.toString().orEmpty(),
                                documentPath = content.resolvedPath,
                                openExternal = uriHandler::openUri,
                            )

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?,
                            ): Boolean = handleCodeBrowserDocumentUrl(
                                rawUrl = url.orEmpty(),
                                documentPath = content.resolvedPath,
                                openExternal = uriHandler::openUri,
                            )

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? = interceptCodeBrowserDocumentResource(
                                request?.url?.toString().orEmpty(),
                                payload.allowedResourceUrls,
                            )

                            @Deprecated("Deprecated in Java")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                url: String?,
                            ): WebResourceResponse? = interceptCodeBrowserDocumentResource(
                                url.orEmpty(),
                                payload.allowedResourceUrls,
                            )
                        }
                    loadCodeBrowserDocumentPreview(this, payload)
                }
            },
            update = { view ->
                if (view.tag != payload.key) {
                    loadCodeBrowserDocumentPreview(view, payload)
                }
            },
        )
    }
}

private fun MainActivity.handleCodeBrowserDocumentUrl(
    rawUrl: String,
    documentPath: String,
    openExternal: (String) -> Unit,
): Boolean {
    val normalized = rawUrl.trim()
    if (normalized.isBlank()) return true
    if (normalized.startsWith("about:blank#", ignoreCase = true)) return false

    conversationFileTargetFromLinkTarget(normalized)?.let { target ->
        openCodeBrowserFile(
            path = resolveCodeBrowserDocumentRelativePath(documentPath, target.path),
            line = target.line,
            scrollY = 0,
        )
        return true
    }

    codeBrowserRelativeLinkTarget(normalized)?.let { target ->
        openCodeBrowserFile(
            path = resolveCodeBrowserDocumentRelativePath(documentPath, target),
            line = null,
            scrollY = 0,
        )
        return true
    }

    val scheme = Uri.parse(normalized).scheme?.lowercase()
    if (scheme in setOf("http", "https", "mailto", "tel")) {
        openExternal(normalized)
    }
    return true
}

private fun codeBrowserRelativeLinkTarget(rawUrl: String): String? {
    val target =
        when {
            rawUrl.startsWith("about:blank/", ignoreCase = true) ->
                Uri.decode(rawUrl.substring("about:blank/".length))

            Uri.parse(rawUrl).scheme.isNullOrBlank() -> Uri.decode(rawUrl)
            else -> return null
        }
    return stripConversationFileLinkDecoration(target)
        .trim()
        .takeIf { it.isNotBlank() && !it.startsWith('#') }
}

internal fun resolveCodeBrowserDocumentRelativePath(
    documentPath: String,
    targetPath: String,
): String {
    val normalizedTarget = normalizeConversationLocalImagePath(targetPath).orEmpty()
    if (normalizedTarget.isBlank() || File(normalizedTarget).isAbsolute) return normalizedTarget
    val parent = File(documentPath).parentFile ?: return normalizedTarget
    return File(parent, normalizedTarget).normalize().path
}

private fun MainActivity.buildCodeBrowserDocumentPreviewPayload(
    content: CodeBrowserFileContent,
    kind: CodeBrowserDocumentPreviewKind,
    activeBridgeUrl: String,
): CodeBrowserDocumentPreviewPayload {
    val allowedResourceUrls = linkedSetOf<String>()
    val html =
        when (kind) {
            CodeBrowserDocumentPreviewKind.Markdown -> {
                val imageContext = ConversationImageRenderContext(
                    sessionId = content.sessionId,
                    bridgeUrl = activeBridgeUrl,
                )
                val markdownHtml = buildConversationMarkdownHtml(content.content) { source ->
                    val image = conversationImageFromUrl(source) ?: return@buildConversationMarkdownHtml null
                    val resolvedImage =
                        if (image.sourceType == ConversationImageSourceType.Local) {
                            image.copy(
                                source = resolveCodeBrowserDocumentRelativePath(content.resolvedPath, image.source),
                            )
                        } else {
                            image
                        }
                    resolveConversationImageUrl(resolvedImage, imageContext)?.also { resolvedUrl ->
                        if (resolvedImage.sourceType == ConversationImageSourceType.Local) {
                            allowedResourceUrls += resolvedUrl
                        }
                    }
                }
                buildCodeBrowserMarkdownPreviewDocument(markdownHtml, allowedResourceUrls)
            }

            CodeBrowserDocumentPreviewKind.Html ->
                buildCodeBrowserStaticHtmlPreviewDocument(content.content)
        }
    return CodeBrowserDocumentPreviewPayload(
        key = listOf(kind.name, content.resolvedPath, content.content.hashCode(), activeBridgeUrl).joinToString(":"),
        html = html,
        allowedResourceUrls = allowedResourceUrls,
    )
}

internal fun buildCodeBrowserMarkdownPreviewDocument(
    markdownHtml: String,
    allowedResourceUrls: Set<String>,
): String {
    val policy = buildCodeBrowserPreviewContentSecurityPolicy(allowedResourceUrls)
    return """
        <!doctype html>
        <html dir="auto">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <meta http-equiv="Content-Security-Policy" content="${escapeConversationHtmlAttribute(policy)}" />
          <style>
          ${buildConversationCss()}
          html, body {
            min-height: 100%;
            background: #f8fbf9 !important;
          }
          body {
            box-sizing: border-box;
            padding: 16px !important;
            font-size: 15px !important;
            line-height: 1.55 !important;
            overflow-wrap: anywhere;
          }
          .cr-md {
            width: 100%;
            max-width: 900px;
            margin: 0 auto;
          }
          .cr-md hr {
            border-top-color: #cfe2d3 !important;
          }
          </style>
        </head>
        <body>$markdownHtml</body>
        </html>
    """.trimIndent()
}

internal fun buildCodeBrowserStaticHtmlPreviewDocument(source: String): String {
    val headContent = """
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta http-equiv="Content-Security-Policy" content="${escapeConversationHtmlAttribute(buildCodeBrowserPreviewContentSecurityPolicy(emptySet()))}" />
    """.trimIndent()
    val headMatch = Regex("""<head(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(source)
    if (headMatch != null) {
        return source.substring(0, headMatch.range.last + 1) + headContent + source.substring(headMatch.range.last + 1)
    }

    val htmlMatch = Regex("""<html(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(source)
    if (htmlMatch != null) {
        val insertion = "<head>$headContent</head>"
        return source.substring(0, htmlMatch.range.last + 1) + insertion + source.substring(htmlMatch.range.last + 1)
    }

    return """
        <!doctype html>
        <html>
        <head>$headContent</head>
        <body>$source</body>
        </html>
    """.trimIndent()
}

private fun buildCodeBrowserPreviewContentSecurityPolicy(allowedResourceUrls: Set<String>): String {
    val imageOrigins = allowedResourceUrls.mapNotNull(::codeBrowserUrlOrigin).distinct()
    val imageSources = (listOf("data:") + imageOrigins).joinToString(" ")
    return "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src $imageSources; " +
        "font-src data:; media-src data:; connect-src 'none'; object-src 'none'; frame-src 'none'; " +
        "form-action 'none'; base-uri 'none'"
}

private fun codeBrowserUrlOrigin(rawUrl: String): String? {
    val uri = Uri.parse(rawUrl)
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val authority = uri.encodedAuthority?.takeIf { it.isNotBlank() } ?: return null
    return "$scheme://$authority"
}

private fun interceptCodeBrowserDocumentResource(
    rawUrl: String,
    allowedResourceUrls: Set<String>,
): WebResourceResponse? {
    val scheme = Uri.parse(rawUrl).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (rawUrl in allowedResourceUrls) return null
    return WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )
}

private fun loadCodeBrowserDocumentPreview(
    webView: WebView,
    payload: CodeBrowserDocumentPreviewPayload,
) {
    webView.tag = payload.key
    webView.loadDataWithBaseURL(null, payload.html, "text/html", "utf-8", null)
}
