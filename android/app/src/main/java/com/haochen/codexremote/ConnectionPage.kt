package com.haochen.codexremote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog


@Composable
internal fun MainActivity.EditConnectionDialog(
    state: ConnectionEditDialogState,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var nameDraft by remember(state.connectionId, state.currentName) { mutableStateOf(state.currentName) }
    var urlDraft by remember(state.connectionId, state.currentUrl) { mutableStateOf(state.currentUrl) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = uiSurface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Изменить историческое подключение",
                    color = uiText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "При смене IP меняется только адрес, идентичность подключения и локальный кэш сессий сохраняются.",
                    color = uiMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("Название") },
                    placeholder = { Text("Например: домашний Mac mini") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = uiPrimary,
                        unfocusedBorderColor = uiBorder,
                        focusedTextColor = uiText,
                        unfocusedTextColor = uiText,
                        cursorColor = uiPrimary,
                    ),
                )
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("Bridge URL") },
                    placeholder = { Text("ws://192.168.31.206:8787/?token=...") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = uiPrimary,
                        unfocusedBorderColor = uiBorder,
                        focusedTextColor = uiText,
                        unfocusedTextColor = uiText,
                        cursorColor = uiPrimary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = { onConfirm(nameDraft.trim(), urlDraft.trim()) },
                        colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.RemoveConnectionDialog(
    state: ConnectionRemovalDialogState,
    onDismiss: () -> Unit,
    onKeepCache: () -> Unit,
    onDeleteCache: () -> Unit,
) {
    AppCenteredDialog(onDismiss = onDismiss) {
        Text(
            text = "Удалить историческое подключение",
            color = uiText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        BodyText("Это последний URL для этого bridge. Можно удалить только запись подключения или вместе с этим набором локального кэша.")
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = uiSurfaceAlt,
            border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Label(state.displayName)
                BodyText(state.maskedUrl)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Отмена")
                }
                OutlinedButton(
                    onClick = onKeepCache,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Только удалить подключение")
                }
            }
            Button(
                onClick = onDeleteCache,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
            ) {
                Text("Удалить и очистить кэш")
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivity.ConnectionPage() {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 6.dp)
            .imePadding()
            .navigationBarsPadding()
            .then(Modifier),
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = uiSurface),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle("Подключиться к Linux Bridge")
                        ConnectionStatusBadge(active = connected)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    BodyText("Вставьте полный адрес ws://, который выводит `npm run bridge`, желательно с портом и token.")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = uiSurface),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Label("Bridge URL")
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = bridgeUrl,
                        onValueChange = { bridgeUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("ws://192.168.31.206:8787/?token=...") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                toggleConnection()
                            },
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = uiPrimary,
                            unfocusedBorderColor = uiBorder,
                            focusedTextColor = uiText,
                            unfocusedTextColor = uiText,
                            cursorColor = uiPrimary,
                        ),
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                pasteBridgeUrl()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Вставить")
                        }
                        Button(
                            onClick = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                toggleConnection()
                            },
                            modifier = Modifier.weight(1.4f),
                            colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                        ) {
                            Text(if (connected) "Отключиться" else "Подключиться")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = uiSurfaceAlt,
                        border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            StatusDot(active = connected)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = connectionStatusHeadline(),
                                    color = uiText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = connectionStatusDetailText(),
                                    color = uiMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                }
            }

            if (connectionHistory.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = uiSurface),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        SectionTitle("История подключений")
                        Spacer(modifier = Modifier.height(8.dp))
                        BodyText("Нажатие подставляет адрес, долгое нажатие позволяет изменить или удалить адрес, а также сразу подключиться или переключиться.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            connectionHistory.forEach { entry ->
                                HistoryConnectionCard(
                                    entry = entry,
                                    active = currentConnectionId == entry.id && !currentBridgeUrl.isNullOrBlank(),
                                    onApply = { applyHistoryConnection(entry) },
                                    onConnect = { connectToHistory(entry) },
                                    onEdit = { openEditConnectionDialog(entry) },
                                    onDelete = { requestRemoveConnection(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    connectionEditState?.let { state ->
        EditConnectionDialog(
            state = state,
            onDismiss = { connectionEditState = null },
            onConfirm = { name, url ->
                val updated = try {
                    updateConnectionHistory(state.connectionId, name, url)
                } catch (error: Exception) {
                    connectionDetail = "Недействительный адрес подключения: ${error.message}"
                    showNotice(connectionDetail)
                    return@EditConnectionDialog
                }
                if (updated == null) {
                    connectionDetail = "Историческое подключение не существует"
                    showNotice(connectionDetail)
                    connectionEditState = null
                    return@EditConnectionDialog
                }
                if (currentConnectionId == updated.id && connected) {
                    connectionDetail = "Подключено к ${updated.displayName()}"
                } else if (currentConnectionId == updated.id || bridgeUrl.trim() == updated.url.trim()) {
                    connectionDetail = "Заполнено ${updated.displayName()}, достаточно нажать «Подключиться»"
                }
                connectionEditState = null
            },
        )
    }

    connectionRemovalState?.let { state ->
        RemoveConnectionDialog(
            state = state,
            onDismiss = { connectionRemovalState = null },
            onKeepCache = { confirmRemoveConnection(state.connectionId, deleteCache = false) },
            onDeleteCache = { confirmRemoveConnection(state.connectionId, deleteCache = true) },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MainActivity.HistoryConnectionCard(
    entry: BridgeHistoryEntry,
    active: Boolean,
    onApply: () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isConnectedState = active && connected
    val isConnectingState = active && !connected
    val connectLabel = when {
        isConnectedState -> "Текущий"
        connected -> "Переключить"
        else -> "Подключиться"
    }
    var actionsExpanded by remember(entry.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (active) uiPrimarySoft else uiSurfaceAlt,
        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, uiPrimary) else androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onApply,
                    onLongClick = { actionsExpanded = true },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 92.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.displayName(),
                    color = uiText,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.subtitleLine(),
                    color = uiMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.lastUsedLabel(),
                    color = uiMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .widthIn(min = 88.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (isConnectedState) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "✓",
                            color = uiOnline,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Подключено",
                            color = uiText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    FilledTonalButton(onClick = onConnect, enabled = !isConnectingState) {
                        if (isConnectingState) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    color = uiPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(if (reconnectScheduled || reconnectAttempt > 0) "Переподключение" else "Подключение")
                            }
                        } else {
                            Text(connectLabel)
                        }
                    }
                }
            }
            DropdownMenu(
                expanded = actionsExpanded,
                onDismissRequest = { actionsExpanded = false },
                modifier = Modifier.align(Alignment.TopEnd),
                containerColor = uiSurface,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
            ) {
                DropdownMenuItem(
                    text = { Text("Изменить") },
                    onClick = {
                        actionsExpanded = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    onClick = {
                        actionsExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
