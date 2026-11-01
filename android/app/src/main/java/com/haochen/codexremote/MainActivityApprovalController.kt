package com.haochen.codexremote

import android.util.Log
import org.json.JSONObject

internal const val APPROVAL_LOG_TAG = "CodexRemoteApproval"

internal inline fun approvalDebugLog(message: () -> String) {
    if (Log.isLoggable(APPROVAL_LOG_TAG, Log.DEBUG)) {
        Log.d(APPROVAL_LOG_TAG, message())
    }
}

internal fun MainActivity.sendApproval(requestId: String, action: ApprovalAction) {
        val payload = JSONObject().apply {
            put("session_id", activeSessionId ?: "")
            put("request_id", requestId)
            action.responsePayload.keys().forEach { key ->
                put(key, cloneJsonValue(action.responsePayload.opt(key)))
            }
        }
        approvalDebugLog {
            "sendApproval requestId=$requestId sessionId=${activeSessionId.orEmpty()} action=${action.label} payload=${payload.toString()}"
        }

        if (!sendRequest("approval.response", payload, object : ResponseHandler {
            override fun onResponse(response: JSONObject) {
                approvalDebugLog {
                    "sendApproval response_ok requestId=$requestId activeTurnId=${activeTurnId.orEmpty()} pendingApprovals=${pendingApprovals.size}"
                }
                appendSystemNote("Результат подтверждения отправлен")
                removePendingApproval(requestId)
            }

            override fun onError(errorText: String) {
                approvalDebugLog { "sendApproval response_error requestId=$requestId error=$errorText" }
            }
        })) {
            approvalDebugLog { "sendApproval send_failed requestId=$requestId" }
            appendSystemNote("Не удалось отправить подтверждение")
        } else {
            appendSystemNote("Подтверждение отправлено: ${action.label}")
        }
    }


internal fun MainActivity.buildApprovalPresentation(eventName: String, payload: JSONObject): ApprovalPresentation {
        val lines = mutableListOf<String>()
        val diffEntries = payload.optJSONObject("fileChanges").toApprovalDiffEntries()
        val reason = payload.optString("reason", "")
        val command = payload.optString("command", "")
        val cwd = payload.optString("cwd", "")
        val grantRoot = payload.optString("grantRoot", "")
        if (reason.isNotBlank()) lines.add(reason)
        if (command.isNotBlank()) lines.add("Команда: $command")
        if (cwd.isNotBlank()) lines.add("Каталог: $cwd")
        if (grantRoot.isNotBlank()) lines.add("Каталог с правами: $grantRoot")
        if (diffEntries.isNotEmpty()) lines.add("Изменённые файлы: ${diffEntries.size} шт.")
        if (lines.isEmpty()) lines.add(safeJson(payload))
        return ApprovalPresentation(
            title = when (eventName) {
                "item/fileChange/requestApproval", "applyPatchApproval" -> "Изменение файла требует подтверждения"
                "item/permissions/requestApproval" -> "Повышение прав требует подтверждения"
                "execCommandApproval", "item/commandExecution/requestApproval" -> "Выполнение команды требует подтверждения"
                else -> "Требуется подтверждение"
            },
            detail = lines.joinToString("\n"),
            diffEntries = diffEntries,
        )
    }

internal fun MainActivity.buildApprovalActions(eventName: String, payload: JSONObject): List<ApprovalAction> {
        if (eventName == "item/permissions/requestApproval") {
            val permissions = payload.optJSONObject("permissions") ?: JSONObject()
            return listOf(
                ApprovalAction(
                    label = "Разрешено для этого хода",
                    responsePayload = JSONObject().apply {
                        put("permissions", cloneJsonValue(permissions))
                        put("scope", "turn")
                    },
                    isPrimary = true,
                ),
                ApprovalAction(
                    label = "Разрешено для этой сессии",
                    responsePayload = JSONObject().apply {
                        put("permissions", cloneJsonValue(permissions))
                        put("scope", "session")
                    },
                ),
                ApprovalAction(
                    label = "Отклонить",
                    responsePayload = JSONObject().apply {
                        put("error", "declined by user")
                    },
                ),
            )
        }

        val available = payload.optJSONArray("availableDecisions")
        val rawActions =
            if (available != null && available.length() > 0) {
                buildList {
                    for (i in 0 until available.length()) {
                        buildApprovalActionFromRawDecision(eventName, available.opt(i))?.let(::add)
                    }
                }
            } else {
                emptyList()
            }
        if (rawActions.isNotEmpty()) return rawActions

        return when (eventName) {
            "execCommandApproval", "applyPatchApproval" -> listOf(
                ApprovalAction(
                    label = "Принять",
                    responsePayload = JSONObject().put("decision", "approved"),
                    isPrimary = true,
                ),
                ApprovalAction(
                    label = "Отклонить",
                    responsePayload = JSONObject().put("decision", "denied"),
                ),
            )
            else -> listOf(
                ApprovalAction(
                    label = "Принять",
                    responsePayload = JSONObject().put("decision", "accept"),
                    isPrimary = true,
                ),
                ApprovalAction(
                    label = "Отклонить",
                    responsePayload = JSONObject().put("decision", "decline"),
                ),
            )
        }
    }

internal fun MainActivity.buildApprovalActionFromRawDecision(eventName: String, rawDecision: Any?): ApprovalAction? {
        val label = labelApprovalDecision(eventName, rawDecision) ?: return null
        return ApprovalAction(
            label = label,
            responsePayload = JSONObject().put("decision", cloneJsonValue(rawDecision)),
            isPrimary = isPrimaryApprovalDecision(rawDecision),
        )
    }

internal fun MainActivity.labelApprovalDecision(eventName: String, rawDecision: Any?): String? {
        val decisionKey =
            when (rawDecision) {
                is JSONObject -> rawDecision.keys().asSequence().firstOrNull().orEmpty()
                is String -> rawDecision
                else -> ""
            }
        if (decisionKey.isBlank()) return null
        return when (decisionKey) {
            "accept", "approved" -> "Принять"
            "acceptForSession", "approved_for_session" -> "Разрешено для этой сессии"
            "decline", "denied" -> "Отклонить"
            "cancel", "abort", "timed_out" -> "Отмена"
            "acceptWithExecpolicyAmendment", "approved_execpolicy_amendment" -> "Принять и запомнить правило"
            "applyNetworkPolicyAmendment", "network_policy_amendment" -> "Применить сетевые правила"
            else -> if (eventName == "item/permissions/requestApproval") "Разрешить" else decisionKey
        }
    }

internal fun MainActivity.isPrimaryApprovalDecision(rawDecision: Any?): Boolean {
        return when (rawDecision) {
            is String -> rawDecision == "accept" || rawDecision == "approved" || rawDecision == "acceptForSession" || rawDecision == "approved_for_session"
            is JSONObject -> {
                val key = rawDecision.keys().asSequence().firstOrNull().orEmpty()
                key == "acceptWithExecpolicyAmendment"
                    || key == "applyNetworkPolicyAmendment"
                    || key == "approved_execpolicy_amendment"
                    || key == "network_policy_amendment"
            }
            else -> false
        }
    }
