package com.haochen.codexremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val MAX_CONVERSATION_IMAGE_BYTES = 12 * 1024 * 1024
private const val CONVERSATION_IMAGE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
private val conversationImageHttpClient = OkHttpClient()

internal data class DownloadedConversationImage(
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
)

internal fun MainActivity.openConversationImage(source: String) {
    val normalizedSource = source.trim()
    if (normalizedSource.isBlank()) return
    conversationImageViewerState = ConversationImageViewerState(normalizedSource)
}

internal fun MainActivity.openConversationImageActions(
    source: String,
    anchorX: Int,
    anchorY: Int,
) {
    val normalizedSource = source.trim()
    if (normalizedSource.isBlank()) return
    conversationLongPressState =
        ConversationLongPressState(
            kind = ConversationLongPressKind.Image,
            source = normalizedSource,
            anchorX = anchorX,
            anchorY = anchorY,
        )
}

internal fun MainActivity.openConversationLinkActions(
    source: String,
    scrollY: Int,
    anchorX: Int,
    anchorY: Int,
) {
    val normalizedSource = source.trim()
    if (normalizedSource.isBlank()) return
    conversationLongPressState =
        ConversationLongPressState(
            kind = ConversationLongPressKind.Link,
            source = normalizedSource,
            anchorX = anchorX,
            anchorY = anchorY,
            scrollY = scrollY,
        )
}

internal fun MainActivity.openConversationLink(
    source: String,
    scrollY: Int,
) {
    try {
        handleConversationUrl(
            rawUrl = source,
            currentScrollY = scrollY,
            onOpenLink = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            onOpenCodeBrowser = { itemId, path, currentScrollY ->
                openCodeBrowser(itemId, path, currentScrollY)
            },
            onOpenCodeBrowserFile = { path, line, currentScrollY ->
                openCodeBrowserFile(path, line, currentScrollY)
            },
        )
    } catch (error: Exception) {
        showNotice("Не удалось открыть ссылку: ${error.message ?: "Неизвестная ошибка"}")
    }
}

internal fun MainActivity.copyConversationLink(source: String) {
    val copyValue = conversationLinkCopyValue(source)
    if (copyValue.isBlank()) return
    if (copyTextToClipboard("Ссылка", copyValue)) {
        showNotice("Ссылка скопирована")
    } else {
        showNotice("Не удалось скопировать ссылку")
    }
}

internal suspend fun MainActivity.copyConversationImage(source: String) {
    try {
        val image = downloadConversationImage(source)
        val contentUri = withContext(Dispatchers.IO) { writeConversationImageToCache(image) }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: error("Нет доступа к буферу обмена")
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "Изображение", contentUri))
        showNotice("Изображение скопировано")
    } catch (error: Exception) {
        showNotice("Не удалось скопировать изображение: ${error.message ?: "Неизвестная ошибка"}")
    }
}

internal suspend fun MainActivity.requestConversationImageSave(
    source: String,
    launchDestination: (String) -> Unit,
) {
    try {
        showNotice("Готовлю изображение…")
        val image = downloadConversationImage(source)
        pendingConversationImageSave = image
        launchDestination(image.displayName)
    } catch (error: Exception) {
        pendingConversationImageSave = null
        showNotice("Не удалось сохранить изображение: ${error.message ?: "Неизвестная ошибка"}")
    }
}

internal suspend fun MainActivity.completePendingConversationImageSave(destination: Uri) {
    val image = pendingConversationImageSave ?: return
    pendingConversationImageSave = null
    try {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(destination, "w")?.use { output ->
                output.write(image.bytes)
            } ?: error("Не удалось записать в выбранное место")
        }
        showNotice("Изображение сохранено")
    } catch (error: Exception) {
        showNotice("Не удалось сохранить изображение: ${error.message ?: "Неизвестная ошибка"}")
    }
}

private suspend fun downloadConversationImage(source: String): DownloadedConversationImage =
    withContext(Dispatchers.IO) {
        if (source.startsWith("data:image/", ignoreCase = true)) {
            decodeInlineConversationImage(source)
        } else {
            downloadRemoteConversationImage(source)
        }
    }

private fun decodeInlineConversationImage(source: String): DownloadedConversationImage {
    val separatorIndex = source.indexOf(',')
    require(separatorIndex > 5) { "Недействительный формат данных изображения" }
    val header = source.substring(5, separatorIndex)
    require(header.contains(";base64", ignoreCase = true)) { "Этот формат данных изображения пока не поддерживается" }
    val mimeType = normalizeConversationImageMimeType(header.substringBefore(';'))
        ?: error("Недействительный тип изображения")
    val bytes = Base64.decode(source.substring(separatorIndex + 1), Base64.DEFAULT)
    require(bytes.isNotEmpty()) { "Содержимое изображения пусто" }
    require(bytes.size <= MAX_CONVERSATION_IMAGE_BYTES) { "Изображение больше 12 MB" }
    return DownloadedConversationImage(
        bytes = bytes,
        mimeType = mimeType,
        displayName = buildConversationImageDisplayName(source, mimeType),
    )
}

private fun downloadRemoteConversationImage(source: String): DownloadedConversationImage {
    require(source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
        "Недействительный адрес изображения"
    }
    val request = Request.Builder().url(source).get().build()
    return conversationImageHttpClient.newCall(request).execute().use { response ->
        require(response.isSuccessful) { "Не удалось скачать изображение (${response.code})" }
        val body = response.body ?: error("Содержимое изображения пусто")
        val contentLength = body.contentLength()
        require(contentLength < 0 || contentLength <= MAX_CONVERSATION_IMAGE_BYTES) { "Изображение больше 12 MB" }
        val bytes = body.bytes()
        require(bytes.isNotEmpty()) { "Содержимое изображения пусто" }
        require(bytes.size <= MAX_CONVERSATION_IMAGE_BYTES) { "Изображение больше 12 MB" }
        val mimeType = normalizeConversationImageMimeType(body.contentType()?.toString())
            ?: detectConversationImageMimeType(bytes)
            ?: error("Загруженное содержимое не является поддерживаемым изображением")
        DownloadedConversationImage(
            bytes = bytes,
            mimeType = mimeType,
            displayName = buildConversationImageDisplayName(source, mimeType),
        )
    }
}

private fun MainActivity.writeConversationImageToCache(image: DownloadedConversationImage): Uri {
    val directory = File(cacheDir, "conversation-images").apply { mkdirs() }
    val expireBefore = System.currentTimeMillis() - CONVERSATION_IMAGE_CACHE_MAX_AGE_MS
    directory.listFiles()?.filter { it.lastModified() < expireBefore }?.forEach(File::delete)
    val output = File(directory, "${System.currentTimeMillis()}-${image.displayName}")
    output.writeBytes(image.bytes)
    return FileProvider.getUriForFile(this, "$packageName.fileprovider", output)
}

private fun conversationLinkCopyValue(source: String): String {
    val uri = Uri.parse(source)
    if (uri.scheme == "codex-code-browser" && uri.host == "file") {
        val path = uri.getQueryParameter("path").orEmpty()
        val line = uri.getQueryParameter("line")?.toIntOrNull()?.takeIf { it > 0 }
        return if (line == null) path else "$path:$line"
    }
    return source.trim()
}

private fun normalizeConversationImageMimeType(value: String?): String? {
    val normalized = value?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it.startsWith("image/") }
}

private fun detectConversationImageMimeType(bytes: ByteArray): String? {
    if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) {
        return "image/png"
    }
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF8")) {
        return "image/gif"
    }
    if (
        bytes.size >= 12 &&
        String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    ) {
        return "image/webp"
    }
    if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
        return "image/bmp"
    }
    return null
}

private fun buildConversationImageDisplayName(source: String, mimeType: String): String {
    val uri = Uri.parse(source)
    val sourceName = uri.getQueryParameter("path")?.substringAfterLast('/')
        ?: uri.lastPathSegment?.substringAfterLast('/')
    val sanitizedStem = sourceName
        ?.substringBeforeLast('.', sourceName)
        ?.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        ?.trim('-', '.', '_')
        ?.take(80)
        ?.takeIf { it.isNotBlank() }
        ?: "codex-image"
    return "$sanitizedStem.${conversationImageExtension(mimeType)}"
}

private fun conversationImageExtension(mimeType: String): String =
    when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/svg+xml" -> "svg"
        else -> "png"
    }
