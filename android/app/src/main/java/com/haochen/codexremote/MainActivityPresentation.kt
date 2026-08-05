package com.haochen.codexremote

import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

internal fun MainActivity.currentSessionLabel(): String {
        val sessionId = activeSessionId ?: return "未选择会话"
        val info = sessions.firstOrNull { it.sessionId == sessionId }
        return if (info != null) {
            "当前会话: ${info.titleLine()}"
        } else {
            "当前会话: ${shortId(sessionId)}"
        }
    }

internal fun MainActivity.buildStatusLine(): String {
        val parts = mutableListOf(if (connected) "已连接" else "未连接")
        activeSessionId?.let { parts.add("会话 ${shortId(it)}") }
        activeTurnId?.let { parts.add("回合 ${shortId(it)}") }
        return parts.joinToString(" · ")
    }

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
                if (reconnectScheduled || reconnectAttempt > 0) "自动重连：$name" else "正在连接：$name"
            } else {
                "当前未连接"
            }
        }
        val name = entry?.displayName()?.takeIf { it.isNotBlank() }
        return if (name != null) {
            "当前连接：$name"
        } else {
            "当前已连接"
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
                        ?.let { add("详细信息：$it") }
                } else {
                    add(if (reconnectScheduled || reconnectAttempt > 0) "正在自动重连" else "正在建立连接")
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
        return label?.let { "连接到 $it" } ?: "未连接"
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
            "untrusted" -> "最保守。更广泛地要求审批，适合不信任当前环境时使用。"
            "on-request" -> "默认推荐。Codex 主动请求时再审批。"
            "on-failure" -> "先尝试执行，失败后再请求审批。"
            "never" -> "不进行审批，按当前会话权限直接执行。"
            else -> "将按该 approvalPolicy 原样下发给 Codex。"
        }
    }


internal fun MainActivity.showNotice(message: String) {
        noticeToast?.cancel()
        noticeToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        noticeToast?.show()
    }

internal fun isConnectionFailureDetail(detail: String): Boolean {
        val normalized = detail.trim()
        return normalized.startsWith("连接失败") ||
            normalized.startsWith("连接超时") ||
            normalized.startsWith("token 不正确") ||
            normalized.startsWith("连接已断开") ||
            normalized.startsWith("心跳超时") ||
            normalized.startsWith("重连次数已用尽") ||
            normalized.startsWith("握手超时") ||
            normalized.startsWith("Bridge 握手超时") ||
            normalized.startsWith("已达到最大自动重连次数")
    }

internal fun connectionFailureHeadline(
        detail: String,
        reconnecting: Boolean = false,
    ): String {
        val normalized = detail.trim()
        return when {
            normalized.startsWith("token 不正确") -> "认证失败，请检查 token"
            normalized.startsWith("心跳超时") -> "连接已中断，未收到心跳响应"
            normalized.startsWith("连接超时") -> "连接超时，请检查网络或主机地址"
            normalized.startsWith("Bridge 握手超时") || normalized.startsWith("握手超时") -> "Bridge 响应超时，未完成握手"
            normalized.startsWith("已达到最大自动重连次数") || normalized.startsWith("重连次数已用尽") -> "自动重连已停止，请手动重试"
            normalized.contains("已达到最大自动重连次数") -> "自动重连已停止，请手动重试"
            normalized.startsWith("连接已断开") ->
                if (reconnecting) "连接已断开，正在重试" else "连接已断开，请重新连接"
            normalized.startsWith("连接失败") -> "连接失败，请稍后重试"
            else -> normalized.ifBlank { "当前未连接" }
        }
    }

internal fun connectionFailureSupportingText(detail: String): String? {
        val normalized = detail.trim()
        return when {
            normalized.startsWith("token 不正确") -> null
            normalized.startsWith("已达到最大自动重连次数") || normalized.startsWith("重连次数已用尽") -> null
            normalized.contains("已达到最大自动重连次数") -> null
            normalized.startsWith("连接超时") -> null
            normalized.startsWith("心跳超时") -> null
            normalized.startsWith("Bridge 握手超时") || normalized.startsWith("握手超时") -> null
            normalized.startsWith("连接失败:") -> normalized.removePrefix("连接失败:").trim()
            normalized.startsWith("连接已断开:") -> normalized.removePrefix("连接已断开:").trim()
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
