package com.haochen.codexremote

import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

internal fun MainActivity.connectionStatusHeadline(): String {
        val entry = currentConnectionEntry()
        if (!connected) {
            val name = entry?.displayName()?.takeIf { !currentBridgeUrl.isNullOrBlank() && it.isNotBlank() }
            val detail = connectionDetail.trim()
            val isFailureState = isConnectionFailureDetail(detail)
            if (isFailureState) {
                return connectionFailureHeadline(detail, reconnectScheduled)
            }
            return if (name != null) {
                if (reconnectScheduled || reconnectAttempt > 0) "Автопереподключение: $name" else "Подключение: $name"
            } else {
                "Сейчас нет подключения"
            }
        }
        val name = entry?.displayName()?.takeIf { it.isNotBlank() }
        return if (name != null) {
            "Текущее подключение: $name"
        } else {
            "Сейчас подключено"
        }
    }

internal fun MainActivity.connectionStatusDetailText(): String {
        val entry = currentConnectionEntry()
        if (!connected) {
            if (entry == null) return connectionDetail
            val name = entry.displayName().takeIf { it.isNotBlank() }
            val detail = connectionDetail.trim()
            val isFailureState = isConnectionFailureDetail(detail)
            return buildList<String> {
                if (isFailureState) {
                    name?.takeIf { it.isNotBlank() }?.let(::add)
                    entry.maskedUrl.takeIf { it.isNotBlank() }?.let(::add)
                    connectionFailureSupportingText(detail)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add("Подробности: $it") }
                } else {
                    add(if (reconnectScheduled || reconnectAttempt > 0) "Выполняется автопереподключение" else "Устанавливаю соединение")
                    entry.maskedUrl.takeIf { it.isNotBlank() }?.let(::add)
                }
            }.joinToString("\n")
        }
        entry ?: return connectionDetail
        return entry.maskedUrl.takeIf { it.isNotBlank() } ?: connectionDetail
    }

internal fun MainActivity.drawerConnectionSummaryText(): String {
        val target = currentBridgeUrl?.takeIf { it.isNotBlank() } ?: bridgeUrl.trim().takeIf { it.isNotBlank() }
        val entry = findConnectionHistoryById(currentConnectionId)
            ?: target?.let { url -> connectionHistory.firstOrNull { it.url == url } ?: BridgeHistoryEntry.fromUrl(url) }
        val label = entry?.displayName()?.takeIf { it.isNotBlank() } ?: target
        return label?.let { "Подключиться к $it" } ?: "Не подключено"
    }

internal fun MainActivity.currentConnectionEntry(): BridgeHistoryEntry? {
        val url = currentBridgeUrl?.takeIf { it.isNotBlank() } ?: bridgeUrl.takeIf { it.isNotBlank() }
        return findConnectionHistoryById(currentConnectionId)
            ?: url?.let { target ->
            connectionHistory.firstOrNull { it.url == target } ?: BridgeHistoryEntry.fromUrl(target)
        }
    }


internal fun MainActivity.approvalPolicyDescription(policy: String): String {
        return when (policy.trim()) {
            "untrusted" -> "Самый осторожный вариант. Подтверждение требуется чаще — подходит, если вы не доверяете текущему окружению."
            "on-request" -> "Рекомендуется по умолчанию. Подтверждение запрашивается, когда Codex сам его инициирует."
            "on-failure" -> "Сначала пытается выполнить, при сбое запрашивает подтверждение."
            "never" -> "Без подтверждений — выполняет сразу в рамках прав текущей сессии."
            else -> "Будет передано Codex как есть в этом approvalPolicy."
        }
    }


internal fun MainActivity.showNotice(message: String) {
        noticeToast?.cancel()
        noticeToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        noticeToast?.show()
    }

internal fun isConnectionFailureDetail(detail: String): Boolean {
        val normalized = detail.trim()
        return normalized.startsWith("Не удалось подключиться") ||
            normalized.startsWith("Время ожидания подключения истекло") ||
            normalized.startsWith("token неверный") ||
            normalized.startsWith("Соединение разорвано") ||
            normalized.startsWith("Тайм-аут пульса") ||
            normalized.startsWith("Попытки переподключения исчерпаны") ||
            normalized.startsWith("Тайм-аут рукопожатия") ||
            normalized.startsWith("Тайм-аут рукопожатия Bridge") ||
            normalized.startsWith("Достигнуто максимальное число автопереподключений")
    }

internal fun connectionFailureHeadline(
        detail: String,
        reconnecting: Boolean = false,
    ): String {
        val normalized = detail.trim()
        return when {
            normalized.startsWith("token неверный") -> "Ошибка аутентификации, проверьте token"
            normalized.startsWith("Тайм-аут пульса") -> "Соединение прервано, ответ на пульс не получен"
            normalized.startsWith("Время ожидания подключения истекло") -> "Время ожидания подключения истекло, проверьте сеть или адрес хоста"
            normalized.startsWith("Тайм-аут рукопожатия Bridge") || normalized.startsWith("Тайм-аут рукопожатия") -> "Тайм-аут ответа Bridge, рукопожатие не завершено"
            normalized.startsWith("Достигнуто максимальное число автопереподключений") || normalized.startsWith("Попытки переподключения исчерпаны") -> "Автопереподключение остановлено, повторите вручную"
            normalized.contains("Достигнуто максимальное число автопереподключений") -> "Автопереподключение остановлено, повторите вручную"
            normalized.startsWith("Соединение разорвано") ->
                if (reconnecting) "Соединение разорвано, повторяю попытку" else "Соединение разорвано, подключитесь заново"
            normalized.startsWith("Не удалось подключиться") -> "Не удалось подключиться, повторите позже"
            else -> normalized.ifBlank { "Сейчас нет подключения" }
        }
    }

internal fun connectionFailureSupportingText(detail: String): String? {
        val normalized = detail.trim()
        return when {
            normalized.startsWith("token неверный") -> null
            normalized.startsWith("Достигнуто максимальное число автопереподключений") || normalized.startsWith("Попытки переподключения исчерпаны") -> null
            normalized.contains("Достигнуто максимальное число автопереподключений") -> null
            normalized.startsWith("Время ожидания подключения истекло") -> null
            normalized.startsWith("Тайм-аут пульса") -> null
            normalized.startsWith("Тайм-аут рукопожатия Bridge") || normalized.startsWith("Тайм-аут рукопожатия") -> null
            normalized.startsWith("Не удалось подключиться:") -> normalized.removePrefix("Не удалось подключиться:").trim()
            normalized.startsWith("Соединение разорвано:") -> normalized.removePrefix("Соединение разорвано:").trim()
            else -> normalized.takeIf { it.contains(':') }
        }
    }

internal fun isBackendConnectionFailure(error: JSONObject?): Boolean {
        return error?.optString("bridgeErrorCode", "") == "backend_connection_failed"
    }

internal fun MainActivity.turnErrorDisplayText(error: JSONObject?): String {
        val userMessage = error?.optString("userMessage", "")?.trim().orEmpty()
        return userMessage.ifBlank { extractErrorText(error) }
    }



internal fun shortId(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return if (value.length <= 8) value else value.take(8)
    }

internal fun firstNonEmpty(vararg values: String): String {
        values.forEach { if (it.trim().isNotEmpty()) return it }
        return ""
    }

internal fun normalizeProtocolString(value: Any?): String {
        val text =
            when (value) {
                null, JSONObject.NULL -> return ""
                is String -> value.trim()
                else -> value.toString().trim()
            }
        return when {
            text.isBlank() -> ""
            text.equals("null", ignoreCase = true) -> ""
            text.equals("undefined", ignoreCase = true) -> ""
            else -> text
        }
    }

internal fun JSONObject.optProtocolString(name: String): String = normalizeProtocolString(opt(name))

internal fun JSONArray.optProtocolString(index: Int): String = normalizeProtocolString(opt(index))

internal fun normalizeNullablePath(value: Any?): String? {
        val text =
            when (value) {
                null, JSONObject.NULL -> return null
                is String -> value.trim()
                else -> value.toString().trim()
            }
        return when {
            text.isBlank() -> null
            text.equals("null", ignoreCase = true) -> null
            text.equals("undefined", ignoreCase = true) -> null
            else -> text
        }
    }

internal fun formatJson(objectValue: JSONObject?): String = objectValue?.toString(2) ?: "{}"

internal fun formatJson(arrayValue: JSONArray?): String = arrayValue?.toString(2) ?: "[]"

internal fun safeJson(objectValue: JSONObject?): String = formatJson(objectValue)

internal fun describeThrowable(error: Throwable?): String {
        val message = error?.message?.takeIf { it.isNotBlank() } ?: return ""
        return ": $message"
    }
