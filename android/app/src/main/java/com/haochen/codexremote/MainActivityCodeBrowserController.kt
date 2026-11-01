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
            showNotice("Для этого элемента пока нет кода для просмотра")
            return
        }
        chatRestoreScrollY = scrollY.coerceAtLeast(0)
        codeBrowserState = state
    }

internal fun MainActivity.openCodeBrowserFile(path: String, line: Int?, scrollY: Int) {
        val requestedPath = path.trim()
        if (requestedPath.isBlank()) {
            showNotice("Путь к файлу пуст")
            return
        }
        val sessionId = activeSessionId
        if (sessionId.isNullOrBlank()) {
            showNotice("Сначала выберите сессию")
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
            codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Error("Нет содержимого файла для чтения"))
            return
        }
        if (entry != null && entry.kind.trim() == "delete" && entry.movePath.isNullOrBlank()) {
            codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Error("Файл уже удалён, текущее содержимое прочитать нельзя"))
            return
        }

        val candidatePaths = state.selectedFileCandidates()
        if (candidatePaths.isEmpty()) {
            codeBrowserState = state.copy(fileReadState = CodeBrowserFileReadState.Error("Нет пути к файлу для чтения"))
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
                            "Тайм-аут чтения файла\nВозможные пути: ${candidatePaths.joinToString(" | ") { compactDiffDisplayPath(it, state.basePath, maxLength = 64) }}",
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
                    codeBrowserState = current.copy(fileReadState = CodeBrowserFileReadState.Error("Не удалось прочитать файл, ни один из вариантов пути недоступен"))
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
                                .ifBlank { requestedPath.substringAfterLast('/').ifBlank { "Файл" } },
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
                                append(if (errorText.isBlank()) "Не удалось прочитать файл" else errorText)
                                append("\nВозможные пути: ")
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
                    codeBrowserState = current.copy(fileReadState = CodeBrowserFileReadState.Error("Не удалось отправить запрос на чтение файла"))
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
        showNotice("Этот файл сейчас нельзя скачать")
        return
    }
    val downloadUrl = codeBrowserFileSourceUrl(content)
    if (downloadUrl == null) {
        showNotice("Недействительный адрес Bridge, не удалось скачать файл")
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
    showNotice("Скачиваю файл…")
    try {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(pending.downloadUrl).get().build()
            codeBrowserFileHttpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Не удалось скачать файл (${response.code})" }
                val body = response.body ?: error("Содержимое файла пусто")
                contentResolver.openOutputStream(destination, "w")?.use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                } ?: error("Не удалось записать в выбранное место")
            }
        }
        showNotice("Файл сохранён")
    } catch (error: Exception) {
        showNotice("Не удалось сохранить файл: ${error.message ?: "Неизвестная ошибка"}")
    } finally {
        codeBrowserFileActionInProgress = false
    }
}

internal suspend fun MainActivity.openCodeBrowserFileExternally(content: CodeBrowserFileContent) {
    if (codeBrowserFileActionInProgress) return
    val downloadUrl = codeBrowserFileSourceUrl(content)
    if (downloadUrl == null) {
        showNotice("Недействительный адрес Bridge, не удалось открыть файл")
        return
    }
    codeBrowserFileActionInProgress = true
    showNotice("Готовлю файл…")
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
            showNotice("Включите «Разрешить приложения из этого источника»")
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
        showNotice("Не удалось открыть файл: ${error.message ?: "Неизвестная ошибка"}")
    } finally {
        codeBrowserFileActionInProgress = false
    }
}

internal fun MainActivity.resumePendingCodeBrowserApkInstall() {
    val pending = pendingCodeBrowserApkInstall ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        pendingCodeBrowserApkInstall = null
        showNotice("Нет разрешения на установку неизвестных приложений")
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
        showNotice("Не удалось открыть APK: ${error.message ?: "Неизвестная ошибка"}")
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
        error("Нет приложения для открытия файлов этого типа; можно скачать и сохранить")
    }
    startActivity(if (useChooser) Intent.createChooser(viewIntent, "Открыть $displayName") else viewIntent)
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
        require(response.isSuccessful) { "Не удалось скачать файл (${response.code})" }
        val body = response.body ?: error("Содержимое файла пусто")
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
