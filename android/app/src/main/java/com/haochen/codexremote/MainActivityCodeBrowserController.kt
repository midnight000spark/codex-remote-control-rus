package com.haochen.codexremote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val CODE_BROWSER_FILE_READ_TIMEOUT_MS = 20000L
private const val CODE_BROWSER_FILE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
private val codeBrowserFileHttpClient = OkHttpClient()

internal data class PendingCodeBrowserFileSave(
    val downloadUrl: String,
    val displayName: String,
)

internal data class PendingCodeBrowserApkInstall(
    val contentUri: Uri,
    val displayName: String,
)

internal fun MainActivity.openCodeBrowser(itemId: String, selectedPath: String?, scrollY: Int) {
        val sessionId = activeSessionId
        val item = conversationItems.firstOrNull { it.id == itemId }
        val state =
            when (item) {
                is ConversationItem.FileChange ->
                    CodeBrowserState(
                        conversationItemId = item.id,
                        sessionId = sessionId,
                        title = item.title,
                        basePath = activeSession()?.cwd,
                        diffEntries = item.diffEntries,
                        fallbackDiff = item.fallbackDiff,
                        selectedPath = selectedPath ?: item.diffEntries.firstOrNull()?.browsePath(),
                        mode = CodeBrowserMode.Diff,
                    )

                else -> null
            }

        if (state == null || (state.diffEntries.isEmpty() && state.fallbackDiff.isNullOrBlank())) {
            showNotice("这个条目暂时没有可浏览的代码内容")
            return
        }
        chatRestoreScrollY = scrollY.coerceAtLeast(0)
        codeBrowserState = state
    }

internal fun MainActivity.openCodeBrowserFile(path: String, line: Int?, scrollY: Int) {
        val requestedPath = path.trim()
        if (requestedPath.isBlank()) {
            showNotice("文件路径为空")
            return
        }
        val sessionId = activeSessionId
        if (sessionId.isNullOrBlank()) {
            showNotice("请先选择一个会话")
            return
        }

        chatRestoreScrollY = scrollY.coerceAtLeast(0)
        val selectedLine = line?.takeIf { it > 0 }
        val title = requestedPath.substringAfterLast('/').ifBlank { requestedPath }
        codeBrowserState = CodeBrowserState(
            conversationItemId = "file:${requestedPath.hashCode()}:${selectedLine ?: 0}",
            sessionId = sessionId,
            title = title + (selectedLine?.let { " (line $it)" } ?: ""),
            basePath = activeSession()?.cwd,
            diffEntries = emptyList(),
            fallbackDiff = null,
            selectedPath = requestedPath,
            selectedLine = selectedLine,
            mode = CodeBrowserMode.File,
        )
    }

internal fun MainActivity.closeCodeBrowser() {
        codeBrowserState = null
    }

internal fun MainActivity.selectCodeBrowserPath(path: String) {
        val state = codeBrowserState ?: return
        if (state.selectedPath == path) return
        codeBrowserState = state.copy(
            selectedPath = path,
            mode = CodeBrowserMode.Diff,
            fileReadState = CodeBrowserFileReadState.Idle,
        )
    }

internal fun MainActivity.setCodeBrowserMode(mode: CodeBrowserMode) {
        val state = codeBrowserState ?: return
        if (state.mode == mode) return
        if (mode == CodeBrowserMode.Diff && !state.hasDiffContent()) return
        if (mode == CodeBrowserMode.File && !state.canReadSelectedFile()) return
        codeBrowserState = state.copy(
            mode = mode,
            fileReadState = when {
                mode == CodeBrowserMode.File -> CodeBrowserFileReadState.Idle
                else -> state.fileReadState
            },
        )
    }

internal fun MainActivity.loadCodeBrowserFileContent(state: CodeBrowserState) {
        val entry = state.selectedEntry()
        val sessionId = state.sessionId ?: activeSessionId
        if (sessionId.isNullOrBlank()) {
            codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Error("没有可读取的文件内容"))
            return
        }
        if (entry != null && entry.kind.trim() == "delete" && entry.movePath.isNullOrBlank()) {
            codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Error("该文件已经被删除，当前内容无法读取"))
            return
        }

        val candidatePaths = state.selectedFileCandidates()
        if (candidatePaths.isEmpty()) {
            codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Error("没有可读取的文件路径"))
            return
        }
        findCachedCodeBrowserFileContent(sessionId, candidatePaths)?.let { cached ->
            codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Loaded(cached))
            return
        }
        val browsePath = candidatePaths.first()
        codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Loading)
        mainHandler.postDelayed(
            {
                val current = codeBrowserState ?: return@postDelayed
                if (
                    current.conversationItemId == state.conversationItemId &&
                    current.selectedPath == browsePath &&
                    current.fileReadState is CodeBrowserFileReadState.Loading
                ) {
                    codeBrowserState = current.copy(
                        fileReadState = CodeBrowserFileReadState.Error(
                            "文件读取超时\n候选路径: ${candidatePaths.joinToString(" | ") { compactDiffDisplayPath(it, state.basePath, maxLength = 64) }}",
                        ),
                    )
                }
            },
            CODE_BROWSER_FILE_READ_TIMEOUT_MS,
        )

        fun requestAt(index: Int) {
            val requestedPath = candidatePaths.getOrNull(index)
            if (requestedPath.isNullOrBlank()) {
                val current = codeBrowserState ?: return
                if (current.conversationItemId == state.conversationItemId && current.selectedPath == browsePath) {
                    codeBrowserState = current.copy(fileReadState = CodeBrowserFileReadState.Error("文件读取失败，候选路径都不可用"))
                }
                return
            }

            val payload = JSONObject().apply {
                put("session_id", sessionId)
                put("path", requestedPath)
                put("max_bytes", 200000)
            }

            val sent = sendRequest("file.read", payload, object : ResponseHandler {
                override fun onResponse(response: JSONObject) {
                    val current = codeBrowserState ?: return
                    if (current.conversationItemId != state.conversationItemId || current.selectedPath != browsePath) return

                    val result = response.optJSONObject("payload") ?: JSONObject()
                    val content =
                        CodeBrowserFileContent(
                            sessionId = sessionId,
                            requestedPath = requestedPath,
                            resolvedPath = result.optString("resolved_path", requestedPath),
                            displayName = result.optString("name", "")
                                .trim()
                                .ifBlank { requestedPath.substringAfterLast('/').ifBlank { "文件" } },
                            mimeType = result.optString("mime_type", "application/octet-stream")
                                .trim()
                                .ifBlank { "application/octet-stream" },
                            kind = CodeBrowserFileKind.fromWireValue(result.optString("content_kind", "text")),
                            content = result.optString("content", ""),
                            truncated = result.optBoolean("truncated", false),
                            bytes = result.optInt("bytes", 0),
                            size = result.optLong("size", result.optLong("bytes", 0L)).coerceAtLeast(0L),
                            downloadable = result.optBoolean("downloadable", true),
                        )
                    rememberCodeBrowserFileContent(sessionId, content)
                    codeBrowserState = current.copy(
                        fileReadState = CodeBrowserFileReadState.Loaded(
                            content,
                        ),
                    )
                }

                override fun onError(errorText: String) {
                    if (index + 1 < candidatePaths.size) {
                        requestAt(index + 1)
                        return
                    }
                    val current = codeBrowserState ?: return
                    if (current.conversationItemId != state.conversationItemId || current.selectedPath != browsePath) return
                    codeBrowserState = current.copy(
                        fileReadState = CodeBrowserFileReadState.Error(
                            buildString {
                                append(if (errorText.isBlank()) "文件读取失败" else errorText)
                                append("\n候选路径: ")
                                append(candidatePaths.joinToString(" | ") { compactDiffDisplayPath(it, state.basePath, maxLength = 64) })
                            },
                        ),
                    )
                }

                override fun suppressDefaultErrorUi(): Boolean = true
            })

            if (!sent) {
                if (index + 1 < candidatePaths.size) {
                    requestAt(index + 1)
                    return
                }
                val current = codeBrowserState ?: return
                if (current.conversationItemId == state.conversationItemId && current.selectedPath == browsePath) {
                    codeBrowserState = current.copy(fileReadState = CodeBrowserFileReadState.Error("文件读取请求发送失败"))
                }
            }
        }

        requestAt(0)
    }


internal fun MainActivity.buildCodeBrowserFileCacheKey(
        sessionId: String,
        path: String,
    ): String {
        return "$sessionId::$path"
    }

internal fun MainActivity.findCachedCodeBrowserFileContent(
        sessionId: String,
        candidatePaths: List<String>,
    ): CodeBrowserFileContent? {
        return candidatePaths.firstNotNullOfOrNull { path ->
            codeBrowserFileCache[buildCodeBrowserFileCacheKey(sessionId, path)]
        }
    }

internal fun MainActivity.rememberCodeBrowserFileContent(
        sessionId: String,
        content: CodeBrowserFileContent,
    ) {
        codeBrowserFileCache[buildCodeBrowserFileCacheKey(sessionId, content.requestedPath)] = content
        codeBrowserFileCache[buildCodeBrowserFileCacheKey(sessionId, content.resolvedPath)] = content
    }

internal fun MainActivity.codeBrowserFileSourceUrl(content: CodeBrowserFileContent): String? {
    val activeBridgeUrl = (currentBridgeUrl ?: bridgeUrl).trim()
    if (activeBridgeUrl.isBlank() || content.sessionId.isBlank() || content.resolvedPath.isBlank()) return null
    val bridgeUri = Uri.parse(activeBridgeUrl)
    val httpScheme = when (bridgeUri.scheme?.lowercase()) {
        "ws" -> "http"
        "wss" -> "https"
        else -> return null
    }
    return bridgeUri.buildUpon()
        .scheme(httpScheme)
        .encodedPath("/v1/file")
        .clearQuery()
        .fragment(null)
        .apply {
            bridgeUri.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("token", it) }
            appendQueryParameter("session_id", content.sessionId)
            appendQueryParameter("path", content.resolvedPath)
        }
        .build()
        .toString()
}

internal fun MainActivity.requestCodeBrowserFileSave(
    content: CodeBrowserFileContent,
    launchDestination: (Intent) -> Unit,
) {
    if (!content.downloadable) {
        showNotice("这个文件当前不可下载")
        return
    }
    val downloadUrl = codeBrowserFileSourceUrl(content)
    if (downloadUrl == null) {
        showNotice("Bridge 地址无效，无法下载文件")
        return
    }
    val displayName = sanitizeCodeBrowserFileName(content.displayName)
    pendingCodeBrowserFileSave = PendingCodeBrowserFileSave(
        downloadUrl = downloadUrl,
        displayName = displayName,
    )
    launchDestination(
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = content.mimeType
            putExtra(Intent.EXTRA_TITLE, displayName)
        },
    )
}

internal fun MainActivity.cancelPendingCodeBrowserFileSave() {
    pendingCodeBrowserFileSave = null
}

internal suspend fun MainActivity.completePendingCodeBrowserFileSave(destination: Uri) {
    val pending = pendingCodeBrowserFileSave ?: return
    pendingCodeBrowserFileSave = null
    if (codeBrowserFileActionInProgress) return
    codeBrowserFileActionInProgress = true
    showNotice("正在下载文件…")
    try {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(pending.downloadUrl).get().build()
            codeBrowserFileHttpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "文件下载失败（${response.code}）" }
                val body = response.body ?: error("文件内容为空")
                contentResolver.openOutputStream(destination, "w")?.use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                } ?: error("无法写入所选位置")
            }
        }
        showNotice("文件已保存")
    } catch (error: Exception) {
        showNotice("保存文件失败：${error.message ?: "未知错误"}")
    } finally {
        codeBrowserFileActionInProgress = false
    }
}

internal suspend fun MainActivity.openCodeBrowserFileExternally(content: CodeBrowserFileContent) {
    if (codeBrowserFileActionInProgress) return
    val downloadUrl = codeBrowserFileSourceUrl(content)
    if (downloadUrl == null) {
        showNotice("Bridge 地址无效，无法打开文件")
        return
    }
    codeBrowserFileActionInProgress = true
    showNotice("正在准备文件…")
    try {
        val contentUri = withContext(Dispatchers.IO) {
            downloadCodeBrowserFileToCache(downloadUrl, content.displayName)
        }
        val isApk = content.isApkFile()
        if (isApk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingCodeBrowserApkInstall = PendingCodeBrowserApkInstall(
                contentUri = contentUri,
                displayName = content.displayName,
            )
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            ).takeIf { it.resolveActivity(packageManager) != null }
                ?: Intent(Settings.ACTION_SECURITY_SETTINGS)
            showNotice("请开启“允许来自此来源的应用”")
            startActivity(permissionIntent)
            return
        }
        launchCachedCodeBrowserFile(
            contentUri = contentUri,
            mimeType = content.mimeType,
            displayName = content.displayName,
            useChooser = !isApk,
        )
    } catch (error: Exception) {
        pendingCodeBrowserApkInstall = null
        showNotice("打开文件失败：${error.message ?: "未知错误"}")
    } finally {
        codeBrowserFileActionInProgress = false
    }
}

internal fun MainActivity.resumePendingCodeBrowserApkInstall() {
    val pending = pendingCodeBrowserApkInstall ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        pendingCodeBrowserApkInstall = null
        showNotice("未授予安装未知应用权限")
        return
    }
    pendingCodeBrowserApkInstall = null
    try {
        launchCachedCodeBrowserFile(
            contentUri = pending.contentUri,
            mimeType = "application/vnd.android.package-archive",
            displayName = pending.displayName,
            useChooser = false,
        )
    } catch (error: Exception) {
        showNotice("打开 APK 失败：${error.message ?: "未知错误"}")
    }
}

private fun MainActivity.launchCachedCodeBrowserFile(
    contentUri: Uri,
    mimeType: String,
    displayName: String,
    useChooser: Boolean,
) {
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (viewIntent.resolveActivity(packageManager) == null) {
        error("没有可打开此类型文件的应用，可使用下载保存")
    }
    startActivity(if (useChooser) Intent.createChooser(viewIntent, "打开 $displayName") else viewIntent)
}

private fun MainActivity.downloadCodeBrowserFileToCache(
    downloadUrl: String,
    displayName: String,
): Uri {
    val directory = File(cacheDir, "code-browser-files").apply { mkdirs() }
    val expireBefore = System.currentTimeMillis() - CODE_BROWSER_FILE_CACHE_MAX_AGE_MS
    directory.listFiles()?.filter { it.isFile && it.lastModified() < expireBefore }?.forEach(File::delete)
    val output = File(directory, "${System.currentTimeMillis()}-${sanitizeCodeBrowserFileName(displayName)}")
    val request = Request.Builder().url(downloadUrl).get().build()
    codeBrowserFileHttpClient.newCall(request).execute().use { response ->
        require(response.isSuccessful) { "文件下载失败（${response.code}）" }
        val body = response.body ?: error("文件内容为空")
        output.outputStream().use { destination ->
            body.byteStream().use { input -> input.copyTo(destination) }
        }
    }
    return FileProvider.getUriForFile(this, "$packageName.fileprovider", output)
}

private fun sanitizeCodeBrowserFileName(value: String): String {
    return value.trim()
        .substringAfterLast('/')
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .take(120)
        .ifBlank { "codex-file" }
}

private fun CodeBrowserFileContent.isApkFile(): Boolean {
    return mimeType.equals("application/vnd.android.package-archive", ignoreCase = true) ||
        displayName.endsWith(".apk", ignoreCase = true) ||
        resolvedPath.endsWith(".apk", ignoreCase = true)
}
