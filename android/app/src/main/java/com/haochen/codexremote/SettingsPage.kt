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
            showNotice("Нет разрешения на уведомления")
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
                SectionTitle("Диалог")
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
                            Label("Показывать время сообщений")
                            BodyText("Показывать время отправки под сообщениями пользователя и ассистента.")
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
                SectionTitle("Автопереподключение")
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
                            Label("Автопереподключение")
                            BodyText("При неожиданном обрыве соединение автоматически переподключается согласно настройкам.")
                        }
                        Switch(
                            checked = autoReconnectEnabled,
                            onCheckedChange = { updateAutoReconnectEnabled(it) },
                        )
                    }
                }

                SelectionMenuField(
                    label = "Максимум попыток переподключения",
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
                SectionTitle("Уведомления")
                NotificationSwitchRow(
                    label = "Уведомления о любых сообщениях",
                    description = "Уведомлять о полном ответе, аварийном завершении или необходимости подтверждения.",
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
                    label = "Только уведомление о завершении",
                    description = "Уведомлять только по завершении, прерывании или сбое задачи.",
                    checked = notificationMode == NotificationMode.CompletionOnly,
                    onCheckedChange = { checked ->
                        if (checked) {
                            enableNotificationMode(NotificationMode.CompletionOnly)
                        } else {
                            updateNotificationMode(NotificationMode.Off)
                        }
                    },
                )
                BodyText("Два переключателя взаимоисключающие. Текущая открытая сессия будет без уведомлений, остальные сессии уведомления присылают; работает только пока процесс приложения жив и Bridge остаётся подключённым.")
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
                SectionTitle("Резервная копия")
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
                        Label("Настройки и данные подключения")
                        BodyText("Экспортирует Bridge URL, token, историю подключений и настройки этой страницы; не включает кэш сессий, содержимое чатов или файлы подписи.")
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
                        Text("Импорт")
                    }
                    Button(
                        onClick = { exportLauncher.launch(defaultBackupFileName()) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                    ) {
                        Text("Экспорт")
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
                SectionTitle("Синхронизировать")
                SelectionMenuField(
                    label = "Диапазон истории",
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
                            Label("Автообновление при открытии боковой панели")
                            BodyText("После выключения используется кэш; обновить вручную можно кнопкой «Обновить» в боковой панели.")
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
                            Label("Инкрементальная синхронизация")
                            BodyText("При включении синхронизируются только изменившиеся ходы; при выключении каждый опрос загружает полный снимок.")
                        }
                        Switch(
                            checked = sessionIncrementalSyncEnabled,
                            onCheckedChange = { updateSessionIncrementalSyncEnabled(it) },
                        )
                    }
                }
                SelectionMenuField(
                    label = "Интервал синхронизации сессий",
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
                SectionTitle("Подключиться")
                SelectionMenuField(
                    label = "Время ожидания подключения истекло",
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
                    label = "Интервал пульса",
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
                SectionTitle("Запуск")
                SelectionMenuField(
                    label = "Страница по умолчанию",
                    selectedLabel = startupPageOptions.firstOrNull {
                        it.value == startupPagePreference.preferenceValue()
                    }?.label ?: "Чат",
                    expanded = startupPageExpanded,
                    onExpandedChange = { startupPageExpanded = it },
                    options = startupPageOptions,
                    onSelect = { value ->
                        updateStartupPagePreference(appPageFromPreference(value))
                    },
                )
                BodyText("При холодном запуске приложение по умолчанию сначала открывает эту страницу; после успешного подключения оно всё равно переходит в чат по текущему сценарию.")
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
                SectionTitle("Кэш")
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
                        Label("Очистить весь кэш")
                        BodyText("Удалить весь локальный кэш сессий, включая остатки старых версий. После этого интерфейс сначала очистится; если вы онлайн, данные сразу загрузятся заново из Bridge.")
                    }
                }
                OutlinedButton(
                    onClick = { clearAllCacheDialogVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Очистить весь кэш")
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
                SectionTitle("О программе")
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
                        Label("Версия приложения")
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
                        Label("Сведения о сборке")
                        AboutInfoRow("Имя пакета", appPackageLabel())
                        AboutInfoRow("Тип сборки", appBuildTypeLabel())
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
                        Label("Основные функции")
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
                        Label("Автор")
                        AboutInfoRow("Автор", "Hao Chen")
                        AboutInfoRow(
                            label = "GitHub",
                            value = projectGithubUrl(),
                            onClick = { openExternalUrl(projectGithubUrl()) },
                            isLink = true,
                        )
                        BodyText("Рекомендуется только в доверенной частной сети.")
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
                        Label("Связь и поддержка")
                        BodyText("Если этот инструмент вам полезен, вы можете поддержать его дальнейшую поддержку и доработку.")
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
                                    text = "Счёт Alipay",
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
                                Text("Копировать аккаунт")
                            }
                        }
                        BodyText("При переводе можно указать ID или название проекта. Спасибо за поддержку.")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }

    if (clearAllCacheDialogVisible) {
        AppCenteredDialog(onDismiss = { clearAllCacheDialogVisible = false }) {
            Text(
                text = "Очистить весь кэш",
                color = uiText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            BodyText("Будет удалён локальный кэш текущей версии и остатки старых версий. Интерфейс сразу очистится; если вы онлайн, затем данные перезагрузятся из Bridge.")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { clearAllCacheDialogVisible = false }) {
                    Text("Отмена")
                }
                Button(
                    onClick = {
                        clearAllCacheDialogVisible = false
                        clearAllSessionCaches()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                ) {
                    Text("Подтвердить очистку")
                }
            }
        }
    }

    backupImportPreview?.let { preview ->
        AppCenteredDialog(onDismiss = { cancelSettingsBackupImport() }) {
            Text(
                text = "Импорт резервной копии",
                color = uiText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            BodyText(
                "Перезапишет текущие данные подключения и настройки страницы. Резервная копия содержит ${preview.connectionCount} записей подключений" +
                    if (preview.hasCurrentConnection) ", включая текущее подключение." else ".",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { cancelSettingsBackupImport() }) {
                    Text("Отмена")
                }
                Button(
                    onClick = { confirmSettingsBackupImport(preview) },
                    colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                ) {
                    Text("Подтвердить импорт")
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
