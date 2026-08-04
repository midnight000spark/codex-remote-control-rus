package com.haochen.codexremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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
                    text = "编辑历史连接",
                    color = uiText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "IP 变化时只改地址，连接身份和本地会话缓存会保留下来。",
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
                    label = { Text("名称") },
                    placeholder = { Text("例如：家里 Mac mini") },
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
                        Text("取消")
                    }
                    Button(
                        onClick = { onConfirm(nameDraft.trim(), urlDraft.trim()) },
                        colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                    ) {
                        Text("保存")
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
            text = "移除历史连接",
            color = uiText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        BodyText("这是这个 bridge 的最后一个 URL。你可以只移除连接记录，或同时删除这组本地缓存。")
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
                    Text("取消")
                }
                OutlinedButton(
                    onClick = onKeepCache,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("仅移除连接")
                }
            }
            Button(
                onClick = onDeleteCache,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
            ) {
                Text("移除并删缓存")
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
                        SectionTitle("连接 Linux Bridge")
                        ConnectionStatusBadge(active = connected)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    BodyText("直接粘贴 `npm run bridge` 打印出来的完整 ws:// 地址，最好带上端口和 token。")
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
                            Text("粘贴")
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
                            Text(if (connected) "断开连接" else "连接")
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
                        SectionTitle("历史连接")
                        Spacer(modifier = Modifier.height(8.dp))
                        BodyText("单击回填地址，长按可编辑地址或移除，也可以直接连接或切换。")
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
                    connectionDetail = "连接地址无效: ${error.message}"
                    showNotice(connectionDetail)
                    return@EditConnectionDialog
                }
                if (updated == null) {
                    connectionDetail = "历史连接不存在"
                    showNotice(connectionDetail)
                    connectionEditState = null
                    return@EditConnectionDialog
                }
                if (currentConnectionId == updated.id && connected) {
                    connectionDetail = "已连接到 ${updated.displayName()}"
                } else if (currentConnectionId == updated.id || bridgeUrl.trim() == updated.url.trim()) {
                    connectionDetail = "已回填 ${updated.displayName()}，点击连接即可"
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
        isConnectedState -> "当前"
        connected -> "切换"
        else -> "连接"
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
                            text = "已连接",
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
                                Text(if (reconnectScheduled || reconnectAttempt > 0) "重连中" else "连接中")
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
                    text = { Text("编辑") },
                    onClick = {
                        actionsExpanded = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("移除") },
                    onClick = {
                        actionsExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
