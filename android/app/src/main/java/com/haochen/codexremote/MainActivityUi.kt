package com.haochen.codexremote

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivity.RemoteApp() {
    val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val imageSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { destination ->
        if (destination == null) {
            pendingConversationImageSave = null
        } else {
            scope.launch { completePendingConversationImageSave(destination) }
        }
    }
    val codeBrowserFileSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val destination = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || destination == null) {
            cancelPendingCodeBrowserFileSave()
        } else {
            scope.launch { completePendingCodeBrowserFileSave(destination) }
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    BackHandler(enabled = !drawerState.isOpen && currentPage != AppPage.Chat) {
        if (!handleTopLevelBack()) {
            finish()
        }
    }

    val scheme = lightColorScheme(
        primary = uiPrimary,
        secondary = uiMuted,
        tertiary = uiPrimarySoft,
        background = uiBackground,
        surface = uiSurface,
        surfaceVariant = uiSurfaceAlt,
        onPrimary = Color.White,
        onSecondary = uiText,
        onTertiary = uiText,
        onBackground = uiText,
        onSurface = uiText,
        onSurfaceVariant = uiMuted,
    )

    MaterialTheme(colorScheme = scheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            scrimColor = uiScrim,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    drawerContainerColor = uiSurface,
                    drawerContentColor = uiText,
                ) {
                    AppDrawerContent(
                        onCloseDrawer = {
                            scope.launch {
                                drawerState.close()
                            }
                        },
                    )
                }
            },
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = uiBackground,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    AppTopBar(
                        onOpenDrawer = {
                            if (connected && refreshSessionsOnDrawerOpen) {
                                requestSessionList()
                            }
                            scope.launch {
                                drawerState.open()
                            }
                        },
                    )
                },
            ) { scaffoldPadding ->
                val horizontalPadding = if (currentPage == AppPage.Chat) 8.dp else 16.dp
                val verticalPadding = if (currentPage == AppPage.Chat) 8.dp else 12.dp
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                        .background(uiBackground),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    ) {
                        when (currentPage) {
                            AppPage.Connection -> ConnectionPage()
                            AppPage.Chat -> ChatPage()
                            AppPage.Settings -> SettingsPage()
                        }
                    }
                }
            }
        }

        codeBrowserState?.let { state ->
            BackHandler { closeCodeBrowser() }
            Dialog(
                onDismissRequest = { closeCodeBrowser() },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = uiBackground,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(uiSurface)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { closeCodeBrowser() }) {
                                    Icon(
                                        painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                                        contentDescription = "关闭文件查看",
                                        tint = uiText,
                                    )
                                }
                                Text(
                                    text = state.title.ifBlank { "文件修改" },
                                    color = uiText,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        CodeBrowserPage(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            onSaveFile = { content ->
                                requestCodeBrowserFileSave(content) { intent ->
                                    codeBrowserFileSaveLauncher.launch(intent)
                                }
                            },
                        )
                    }
                }
            }
        }

        conversationImageViewerState?.let { state ->
            ConversationImageViewerDialog(
                state = state,
                onDismiss = { conversationImageViewerState = null },
                onLongPress = { source, anchorX, anchorY ->
                    openConversationImageActions(source, anchorX, anchorY)
                },
            )
        }

        conversationLongPressState?.let { state ->
            ConversationLongPressDialog(
                state = state,
                onDismiss = { conversationLongPressState = null },
                onCopyImage = {
                    conversationLongPressState = null
                    scope.launch { copyConversationImage(state.source) }
                },
                onSaveImage = {
                    conversationLongPressState = null
                    scope.launch {
                        requestConversationImageSave(state.source) { displayName ->
                            imageSaveLauncher.launch(displayName)
                        }
                    }
                },
                onOpenLink = {
                    conversationLongPressState = null
                    openConversationLink(state.source, state.scrollY)
                },
                onCopyLink = {
                    conversationLongPressState = null
                    copyConversationLink(state.source)
                },
            )
        }

        newChatDraft?.let { draft ->
            AppCenteredDialog(onDismiss = { newChatDraft = null }) {
                NewChatSheet(
                    draft = draft,
                    onDismiss = { newChatDraft = null },
                    onModelChange = { model ->
                        newChatDraft =
                            draft.copy(
                                model = model,
                                reasoningEffort = normalizeReasoningEffortForModel(model, draft.reasoningEffort),
                            )
                    },
                    onReasoningEffortChange = { effort -> newChatDraft = draft.copy(reasoningEffort = effort) },
                    onApprovalPolicyChange = { policy -> newChatDraft = draft.copy(approvalPolicy = policy) },
                    onSandboxChange = { sandbox -> newChatDraft = draft.copy(sandbox = sandbox) },
                    onConfirm = {
                        startNewSession(
                            projectPath = draft.projectPath,
                            model = draft.model,
                            reasoningEffort = draft.reasoningEffort,
                            approvalPolicy = draft.approvalPolicy,
                            sandbox = draft.sandbox,
                        )
                        newChatDraft = null
                        scope.launch {
                            drawerState.close()
                        }
                    },
                )
            }
        }

        sessionInfoSheetState?.let { state ->
            AppCenteredDialog(onDismiss = { sessionInfoSheetState = null }) {
                SessionInfoSheet(
                    state = state,
                    onDismiss = { sessionInfoSheetState = null },
                    onRebuildHistory = { rebuildActiveSessionHistory() },
                    onSwitchToFullAccess = { switchActiveSessionToFullAccess() },
                )
            }
        }

        pendingApprovals.firstOrNull()?.let { approval ->
            ApprovalDialog(
                approval = approval,
                onDecision = { action ->
                    sendApproval(approval.requestId, action)
                },
                onDismiss = {
                    showNotice("还有待处理审批，请先完成后再继续")
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MainActivity.AppTopBar(onOpenDrawer: () -> Unit) {
    val title =
        when (currentPage) {
            AppPage.Chat -> activeSession()?.titleLine() ?: "Codex Remote"
            AppPage.Connection -> "连接设置"
            AppPage.Settings -> "设置"
        }

    TopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onDoubleClick = {
                        if (currentPage == AppPage.Chat) {
                            scrollConversationTo(ConversationScrollTarget.Top)
                        }
                    },
                ),
                color = uiText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            TextButton(onClick = onOpenDrawer) {
                Text("☰")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = uiSurface,
            titleContentColor = uiText,
            actionIconContentColor = uiText,
            navigationIconContentColor = uiText,
        ),
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    end = if (currentPage == AppPage.Chat) 14.dp else 8.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            ) {
                if (currentPage == AppPage.Chat && activeSession() != null) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(
                                onClick = { openSessionInfoSheet() },
                                onLongClick = { rebuildActiveSessionHistory() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_dialog_info),
                            contentDescription = "会话信息，长按重建历史记录",
                            tint = uiMuted,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                StatusDot(
                    active = connected,
                    onClick = { handleConnectionStatusClick() },
                    onLongClick = { openConnectionPage() },
                )
            }
        },
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainActivity.StatusDot(
    active: Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val actionModifier =
        if (onClick != null || onLongClick != null) {
            Modifier
                .size(40.dp)
                .combinedClickable(
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                )
        } else {
            Modifier.size(10.dp)
        }
    Box(
        modifier = actionModifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = if (active) uiOnline else uiOffline, shape = CircleShape),
        )
    }
}

@Composable
internal fun MainActivity.ConnectionStatusBadge(active: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) uiPrimarySoft else uiSurfaceAlt,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) uiPrimary else uiBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(active = active)
            Text(
                text = if (active) "已连接" else "未连接",
                color = uiText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun MainActivity.AppCenteredDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxDialogHeight = screenHeight * 0.84f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = uiSurface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxDialogHeight),
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}


@Composable
internal fun MainActivity.SectionTitle(text: String) {
    Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = uiText)
}

@Composable
internal fun MainActivity.Label(text: String) {
    Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = uiText)
}

@Composable
internal fun MainActivity.BodyText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text = text, modifier = modifier, fontSize = 13.sp, color = uiMuted, lineHeight = 20.sp)
}
