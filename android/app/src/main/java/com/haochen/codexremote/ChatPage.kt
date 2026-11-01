package com.haochen.codexremote

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainActivity.ChatPage() {
    val activeSession = activeSession()
    val hasConversationItems = conversationItems.isNotEmpty()
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        scope.launch { addComposerImageAttachments(uris) }
    }

    LaunchedEffect(hasConversationItems) {
        if (!hasConversationItems) {
            chatCanScrollToBottom = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (conversationItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BodyText(
                        text = if (activeSession == null) "Сначала создайте или выберите сессию в проекте, затем начинайте чат." else "Здесь будет показан поток сообщений текущей сессии.",
                        modifier = Modifier.fillMaxWidth(0.8f),
                    )
                }
            } else {
                ConversationHistoryWebView(
                    items = conversationItems,
                    sessionId = activeSessionId,
                    imageBridgeUrl = currentBridgeUrl ?: bridgeUrl,
                    displayBasePath = activeSession?.cwd,
                    showMessageTimestamps = showMessageTimestamps,
                    restoreScrollY = chatRestoreScrollY,
                    scrollCommand = chatScrollCommand,
                    onScrollRestored = {
                        chatRestoreScrollY = null
                    },
                    onScrollCommandHandled = { command ->
                        if (chatScrollCommand == command) {
                            chatScrollCommand = null
                        }
                    },
                    onCanScrollToBottomChange = { canScroll ->
                        chatCanScrollToBottom = canScroll
                    },
                    onOpenCodeBrowser = { itemId, path, scrollY ->
                        openCodeBrowser(itemId, path, scrollY)
                    },
                    onOpenCodeBrowserFile = { path, line, scrollY ->
                        openCodeBrowserFile(path, line, scrollY)
                    },
                    onOpenImage = { source -> openConversationImage(source) },
                    onLongPressImage = { source, anchorX, anchorY ->
                        openConversationImageActions(source, anchorX, anchorY)
                    },
                    onLongPressLink = { source, scrollY, anchorX, anchorY ->
                        openConversationLinkActions(source, scrollY, anchorX, anchorY)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                )
                if (chatCanScrollToBottom) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp, bottom = 8.dp)
                            .size(40.dp)
                            .clickable { scrollConversationTo(ConversationScrollTarget.Bottom) },
                        contentAlignment = Alignment.Center,
                    ) {
                        DownArrowGlyph(
                            contentDescription = "Перейти вниз",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .padding(start = 4.dp, end = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            liveTurnStatus?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = status,
                    color = uiMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ChatComposerImageStrip()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hasComposerInput = composerText.isNotBlank() || composerImageAttachments.isNotEmpty()
            val canSend =
                connected &&
                    activeSessionId != null &&
                    hasComposerInput &&
                    !composerImageUploadInProgress
            val canAttach =
                connected &&
                    activeSessionId != null &&
                    !composerImageUploadInProgress &&
                    composerImageAttachments.size < MAX_COMPOSER_IMAGES
            val canUseSendButton = canSend || (connected && activeSessionId != null && activeTurnId != null)
            IconButton(
                enabled = canAttach,
                onClick = { imagePicker.launch(arrayOf("image/*")) },
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_gallery),
                    contentDescription = "Добавить изображение",
                    tint = if (canAttach) uiPrimary else uiOffline,
                )
            }
            OutlinedTextField(
                value = composerText,
                onValueChange = { composerText = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                singleLine = false,
                minLines = 1,
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(if (activeTurnId != null) "Дополнительная команда" else "Введите сообщение") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = uiPrimary,
                    unfocusedBorderColor = uiBorder,
                    focusedTextColor = uiText,
                    unfocusedTextColor = uiText,
                    cursorColor = uiPrimary,
                ),
            )

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        if (canUseSendButton) {
                            if (activeTurnId != null) uiPrimary.copy(alpha = 0.88f) else uiPrimary
                        } else {
                            uiOffline
                        },
                        CircleShape,
                    )
                    .combinedClickable(
                        enabled = canUseSendButton,
                        onClick = { if (canSend) sendComposerText() },
                        onLongClick = {
                            if (activeTurnId != null) {
                                interruptCurrentTurn()
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SendGlyph(
                    contentDescription = if (activeTurnId != null) "Отправить дополнительную команду, долгое нажатие прерывает" else "Отправить",
                    modifier = Modifier.size(26.dp),
                )
                if (activeTurnId != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(14.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                            .padding(3.dp)
                            .background(uiPrimary, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }

        ChatSettingsBar()
    }
}

@Composable
private fun SendGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
    ) {
        val path = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.14f)
            lineTo(size.width * 0.92f, size.height * 0.50f)
            lineTo(size.width * 0.08f, size.height * 0.86f)
            lineTo(size.width * 0.24f, size.height * 0.56f)
            lineTo(size.width * 0.57f, size.height * 0.50f)
            lineTo(size.width * 0.24f, size.height * 0.44f)
            close()
        }
        drawPath(path, Color.White)
    }
}

@Composable
private fun MainActivity.DownArrowGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
    ) {
        val strokeWidth = size.minDimension * 0.13f
        drawLine(
            color = uiPrimary,
            start = Offset(size.width * 0.18f, size.height * 0.14f),
            end = Offset(size.width * 0.50f, size.height * 0.42f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = uiPrimary,
            start = Offset(size.width * 0.82f, size.height * 0.14f),
            end = Offset(size.width * 0.50f, size.height * 0.42f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = uiPrimary,
            start = Offset(size.width * 0.18f, size.height * 0.44f),
            end = Offset(size.width * 0.50f, size.height * 0.72f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = uiPrimary,
            start = Offset(size.width * 0.82f, size.height * 0.44f),
            end = Offset(size.width * 0.50f, size.height * 0.72f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = uiPrimary,
            start = Offset(size.width * 0.14f, size.height * 0.92f),
            end = Offset(size.width * 0.86f, size.height * 0.92f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun MainActivity.ApprovalDialog(
    approval: ApprovalDialogState,
    onDecision: (ApprovalAction) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    text = approval.title,
                    color = uiText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                approval.detail.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        color = uiMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
                if (approval.diffEntries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Затронутые файлы: ${approval.diffEntries.size} шт.",
                            color = uiMuted,
                            fontSize = 13.sp,
                        )
                        Column {
                            approval.diffEntries.forEachIndexed { index, entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.displayPath(basePath = currentWorkspacePath(), maxLength = 72),
                                        color = uiText,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = entry.changeLabel().orEmpty(),
                                        color = uiMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (index < approval.diffEntries.lastIndex) {
                                    HorizontalDivider(color = uiBorder.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = uiBorder.copy(alpha = 0.8f))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    approval.actions.forEach { action ->
                        val accepted = action.isPrimary
                        val buttonColors =
                            if (accepted) {
                                ButtonDefaults.buttonColors(containerColor = uiPrimary, contentColor = Color.White)
                            } else {
                                ButtonDefaults.outlinedButtonColors(contentColor = uiText)
                        }
                        if (accepted) {
                            Button(
                                onClick = { onDecision(action) },
                                colors = buttonColors,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(action.label)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onDecision(action) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = uiText),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(action.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
