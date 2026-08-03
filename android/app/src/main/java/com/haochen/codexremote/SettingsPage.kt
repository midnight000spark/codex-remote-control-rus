package com.haochen.codexremote

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MainActivity.SettingsPage() {
    val scrollState = rememberScrollState()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(::exportSettingsBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::prepareSettingsBackupImport)
    }
    var pendingNotificationMode by remember { mutableStateOf<NotificationMode?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestedMode = pendingNotificationMode
        pendingNotificationMode = null
        if (granted && requestedMode != null) {
            updateNotificationMode(requestedMode)
        } else if (!granted) {
            showNotice("未获得通知权限")
        }
    }
    val enableNotificationMode: (NotificationMode) -> Unit = { mode ->
        if (hasNotificationPermission()) {
            updateNotificationMode(mode)
        } else {
            pendingNotificationMode = mode
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var startupPageExpanded by remember { mutableStateOf(false) }
    var reconnectAttemptsExpanded by remember { mutableStateOf(false) }
    var sessionHistoryMonthExpanded by remember { mutableStateOf(false) }
    var sessionSyncIntervalExpanded by remember { mutableStateOf(false) }
    var bridgeConnectTimeoutExpanded by remember { mutableStateOf(false) }
    var bridgePingIntervalExpanded by remember { mutableStateOf(false) }
    val startupPageOptions = startupPageMenuOptions()
    val reconnectAttemptOptions = autoReconnectAttemptMenuOptions()
    val sessionHistoryMonthOptions = sessionHistoryMonthMenuOptions()
    val sessionSyncIntervalOptions = sessionSyncIntervalMenuOptions()
    val bridgeConnectTimeoutOptions = bridgeConnectTimeoutMenuOptions()
    val bridgePingIntervalOptions = bridgePingIntervalMenuOptions()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 6.dp)
            .navigationBarsPadding()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("对话")
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Label("显示消息时间")
                            BodyText("在用户和助手消息下方显示发送时间。")
                        }
                        Switch(
                            checked = showMessageTimestamps,
                            onCheckedChange = { updateShowMessageTimestamps(it) },
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("自动重连")
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Label("自动重连")
                            BodyText("连接意外断开时，按设置自动重试连接。")
                        }
                        Switch(
                            checked = autoReconnectEnabled,
                            onCheckedChange = { updateAutoReconnectEnabled(it) },
                        )
                    }
                }

                SelectionMenuField(
                    label = "最大重连次数",
                    selectedLabel = autoReconnectMaxAttemptsLabel(),
                    expanded = reconnectAttemptsExpanded,
                    onExpandedChange = { reconnectAttemptsExpanded = it },
                    options = reconnectAttemptOptions,
                    enabled = autoReconnectEnabled,
                    onSelect = { value ->
                        updateAutoReconnectMaxAttempts(value.toIntOrNull() ?: 0)
                    },
                )
                BodyText(autoReconnectMaxAttemptsDescription())
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("通知")
                NotificationSwitchRow(
                    label = "任意消息通知",
                    description = "收到完整回复、异常结束或需要审批时通知。",
                    checked = notificationMode == NotificationMode.AnyMessage,
                    onCheckedChange = { checked ->
                        if (checked) {
                            enableNotificationMode(NotificationMode.AnyMessage)
                        } else {
                            updateNotificationMode(NotificationMode.Off)
                        }
                    },
                )
                NotificationSwitchRow(
                    label = "仅完成通知",
                    description = "仅在任务完成、中断或失败结束时通知。",
                    checked = notificationMode == NotificationMode.CompletionOnly,
                    onCheckedChange = { checked ->
                        if (checked) {
                            enableNotificationMode(NotificationMode.CompletionOnly)
                        } else {
                            updateNotificationMode(NotificationMode.Off)
                        }
                    },
                )
                BodyText("两个开关互斥。当前正在查看的会话会静默，其他会话仍会通知；仅在应用进程存活且 Bridge 保持连接时生效。")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("备份")
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Label("设置和连接信息")
                        BodyText("导出 Bridge URL、token、连接历史和本页设置；不包含会话缓存、聊天内容或签名文件。")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("导入")
                    }
                    Button(
                        onClick = { exportLauncher.launch(defaultBackupFileName()) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                    ) {
                        Text("导出")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("同步")
                SelectionMenuField(
                    label = "历史范围",
                    selectedLabel = sessionHistoryMonthLabel(),
                    expanded = sessionHistoryMonthExpanded,
                    onExpandedChange = { sessionHistoryMonthExpanded = it },
                    options = sessionHistoryMonthOptions,
                    onSelect = { value ->
                        updateSessionHistoryMonths(value.toIntOrNull() ?: DEFAULT_SESSION_HISTORY_MONTHS)
                    },
                )
                BodyText(sessionHistoryMonthDescription())
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Label("打开侧边栏自动刷新")
                            BodyText("关闭后使用缓存，仍可点击侧边栏里的“刷新”手动更新。")
                        }
                        Switch(
                            checked = refreshSessionsOnDrawerOpen,
                            onCheckedChange = { updateRefreshSessionsOnDrawerOpen(it) },
                        )
                    }
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Label("增量同步")
                            BodyText("开启后只同步有变化的回合；关闭后每次轮询拉取完整快照。")
                        }
                        Switch(
                            checked = sessionIncrementalSyncEnabled,
                            onCheckedChange = { updateSessionIncrementalSyncEnabled(it) },
                        )
                    }
                }
                SelectionMenuField(
                    label = "会话同步间隔",
                    selectedLabel = sessionSyncIntervalLabel(),
                    expanded = sessionSyncIntervalExpanded,
                    onExpandedChange = { sessionSyncIntervalExpanded = it },
                    options = sessionSyncIntervalOptions,
                    onSelect = { value ->
                        updateSessionSyncIntervalSeconds(value.toIntOrNull() ?: DEFAULT_SESSION_SYNC_INTERVAL_SECONDS)
                    },
                )
                BodyText(sessionSyncIntervalDescription())
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("连接")
                SelectionMenuField(
                    label = "连接超时",
                    selectedLabel = bridgeConnectTimeoutLabel(),
                    expanded = bridgeConnectTimeoutExpanded,
                    onExpandedChange = { bridgeConnectTimeoutExpanded = it },
                    options = bridgeConnectTimeoutOptions,
                    onSelect = { value ->
                        updateBridgeConnectTimeoutSeconds(value.toIntOrNull() ?: DEFAULT_BRIDGE_CONNECT_TIMEOUT_SECONDS)
                    },
                )
                BodyText(bridgeConnectTimeoutDescription())
                SelectionMenuField(
                    label = "心跳间隔",
                    selectedLabel = bridgePingIntervalLabel(),
                    expanded = bridgePingIntervalExpanded,
                    onExpandedChange = { bridgePingIntervalExpanded = it },
                    options = bridgePingIntervalOptions,
                    onSelect = { value ->
                        updateBridgePingIntervalSeconds(value.toIntOrNull() ?: DEFAULT_BRIDGE_PING_INTERVAL_SECONDS)
                    },
                )
                BodyText(bridgePingIntervalDescription())
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("启动")
                SelectionMenuField(
                    label = "默认页面",
                    selectedLabel = startupPageOptions.firstOrNull {
                        it.value == startupPagePreference.preferenceValue()
                    }?.label ?: "聊天",
                    expanded = startupPageExpanded,
                    onExpandedChange = { startupPageExpanded = it },
                    options = startupPageOptions,
                    onSelect = { value ->
                        updateStartupPagePreference(appPageFromPreference(value))
                    },
                )
                BodyText("应用冷启动时默认先打开这个页面；连接成功后仍会按当前流程进入聊天页。")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("缓存")
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Label("清除全部缓存")
                        BodyText("删除所有本地会话缓存，包括旧版本残留。执行后会先清空界面；如果当前在线，会立即从 Bridge 重新拉取。")
                    }
                }
                OutlinedButton(
                    onClick = { clearAllCacheDialogVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除全部缓存")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = uiSurface),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("关于")
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Label("应用版本")
                        Text(
                            text = appVersionLabel(),
                            color = uiText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Label("构建信息")
                        AboutInfoRow("包名", appPackageLabel())
                        AboutInfoRow("构建类型", appBuildTypeLabel())
                    }
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Label("主要功能")
                        appFeatureSummaries().forEach { feature ->
                            BodyText("• $feature")
                        }
                    }
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Label("作者")
                        AboutInfoRow("作者", "Hao Chen")
                        AboutInfoRow(
                            label = "GitHub",
                            value = projectGithubUrl(),
                            onClick = { openExternalUrl(projectGithubUrl()) },
                            isLink = true,
                        )
                        BodyText("仅建议在可信私有网络中使用。")
                    }
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Label("联系与支持")
                        BodyText("如果这个工具对你有帮助，可以支持后续维护与打磨。")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { copySupportAccountToClipboard() },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "支付宝账户",
                                    color = uiMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = supportAlipayAccount(),
                                    color = uiText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            OutlinedButton(onClick = { copySupportAccountToClipboard() }) {
                                Text("复制账号")
                            }
                        }
                        BodyText("转账时可备注 ID 或项目名，感谢支持。")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }

    if (clearAllCacheDialogVisible) {
        AppCenteredDialog(onDismiss = { clearAllCacheDialogVisible = false }) {
            Text(
                text = "清除全部缓存",
                color = uiText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            BodyText("会删除当前版本和旧版本残留的本地缓存。界面会立即清空；如果当前在线，会随后从 Bridge 重新加载。")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { clearAllCacheDialogVisible = false }) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        clearAllCacheDialogVisible = false
                        clearAllSessionCaches()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                ) {
                    Text("确认清除")
                }
            }
        }
    }

    backupImportPreview?.let { preview ->
        AppCenteredDialog(onDismiss = { cancelSettingsBackupImport() }) {
            Text(
                text = "导入备份",
                color = uiText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            BodyText(
                "将覆盖当前连接信息和本页设置。备份包含 ${preview.connectionCount} 条连接记录" +
                    if (preview.hasCurrentConnection) "，并包含当前连接。" else "。",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { cancelSettingsBackupImport() }) {
                    Text("取消")
                }
                Button(
                    onClick = { confirmSettingsBackupImport(preview) },
                    colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                ) {
                    Text("确认导入")
                }
            }
        }
    }
}

@Composable
private fun MainActivity.NotificationSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = uiSurfaceAlt,
        border = BorderStroke(1.dp, uiBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Label(label)
                BodyText(description)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun MainActivity.AboutInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    isLink: Boolean = false,
) {
    Column(
        modifier =
            if (onClick != null) {
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
            } else {
                Modifier
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = uiMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = if (isLink) uiPrimary else uiText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
