package com.haochen.codexremote

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal const val MAX_COMPOSER_IMAGES = 4
private const val MAX_COMPOSER_IMAGE_BYTES = 12_000_000
private const val COMPOSER_IMAGE_PREVIEW_SIZE = 256
private val composerImageHttpClient = OkHttpClient()

internal suspend fun MainActivity.addComposerImageAttachments(uris: List<Uri>) {
    val sessionId = activeSessionId?.takeIf { it.isNotBlank() }
    val activeBridgeUrl = (currentBridgeUrl ?: bridgeUrl).trim()
    if (sessionId == null || activeBridgeUrl.isBlank()) {
        showNotice("Сначала подключитесь и выберите сессию")
        return
    }
    val remaining = (MAX_COMPOSER_IMAGES - composerImageAttachments.size).coerceAtLeast(0)
    if (remaining == 0) {
        showNotice("Можно добавить не более $MAX_COMPOSER_IMAGES изображений")
        return
    }
    val selected = uris.distinct().take(remaining)
    if (selected.isEmpty()) return

    composerImageUploadInProgress = true
    var added = 0
    var lastError: String? = null
    try {
        for (uri in selected) {
            val attachment = runCatching {
                withContext(Dispatchers.IO) {
                    readAndUploadComposerImage(uri, sessionId, activeBridgeUrl)
                }
            }.onFailure { error ->
                lastError = error.message ?: "Неизвестная ошибка"
            }.getOrNull() ?: continue
            if (activeSessionId != sessionId || (currentBridgeUrl ?: bridgeUrl).trim() != activeBridgeUrl) {
                break
            }
            composerImageAttachments.add(attachment)
            added += 1
        }
    } finally {
        composerImageUploadInProgress = false
    }
    if (uris.size > selected.size) {
        showNotice("Можно добавить не более $MAX_COMPOSER_IMAGES изображений")
    } else if (added == 0 && lastError != null) {
        showNotice("Не удалось добавить изображение: $lastError")
    } else if (lastError != null) {
        showNotice("Не удалось добавить часть изображений: $lastError")
    }
}

internal fun MainActivity.removeComposerImageAttachment(id: String) {
    composerImageAttachments.removeAll { it.id == id }
}

internal fun MainActivity.clearComposerImageAttachments() {
    composerImageAttachments.clear()
}

private fun MainActivity.readAndUploadComposerImage(
    uri: Uri,
    sessionId: String,
    bridgeUrl: String,
): ComposerImageAttachment {
    val mimeType = contentResolver.getType(uri)
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.startsWith("image/") }
        ?: error("Не удалось определить тип изображения")
    val bytes = contentResolver.openInputStream(uri)?.use(::readComposerImageBytes)
        ?: error("Не удалось прочитать изображение")
    val preview = decodeComposerImagePreview(bytes) ?: error("Не удалось создать предпросмотр изображения")
    val uploadUrl = buildConversationImageUploadUrl(bridgeUrl, sessionId)
        ?: error("Недействительный адрес Bridge")
    val request = Request.Builder()
        .url(uploadUrl)
        .post(bytes.toRequestBody(mimeType.toMediaType()))
        .build()
    val localPath = composerImageHttpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val code = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
            error(code.ifBlank { "Не удалось загрузить на Bridge (${response.code})" })
        }
        JSONObject(body).optString("path", "").trim().takeIf { it.isNotBlank() }
            ?: error("Bridge не вернул путь к изображению")
    }
    return ComposerImageAttachment(
        id = UUID.randomUUID().toString(),
        displayName = queryComposerImageDisplayName(uri),
        mimeType = mimeType,
        localPath = localPath,
        preview = preview.asImageBitmap(),
    )
}

private fun readComposerImageBytes(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_COMPOSER_IMAGE_BYTES) { "Изображение больше 12 MB" }
        output.write(buffer, 0, count)
    }
    require(total > 0) { "Содержимое изображения пусто" }
    return output.toByteArray()
}

private fun decodeComposerImagePreview(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > COMPOSER_IMAGE_PREVIEW_SIZE * 2 ||
        bounds.outHeight / sampleSize > COMPOSER_IMAGE_PREVIEW_SIZE * 2
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

private fun MainActivity.queryComposerImageDisplayName(uri: Uri): String {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    return name?.trim()?.takeIf { it.isNotBlank() } ?: "Изображение"
}

private fun buildConversationImageUploadUrl(bridgeUrl: String, sessionId: String): String? {
    val bridgeUri = Uri.parse(bridgeUrl)
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
            appendQueryParameter("session_id", sessionId)
        }
        .build()
        .toString()
}
