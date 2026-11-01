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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
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
internal fun MainActivity.AppDrawerContent(onCloseDrawer: () -> Unit) {
    val projectGroups = projectBuckets()
    syncProjectGroupExpansion(projectGroups)
    syncProjectGroupSessionListExpansion(projectGroups)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Боковая панель")
                IconButton(onClick = onCloseDrawer) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "Свернуть боковую панель",
                        tint = uiMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = uiSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Настройки подключения",
                                color = uiText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = drawerConnectionSummaryText(),
                                color = uiMuted,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        StatusDot(active = connected)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                currentPage = AppPage.Connection
                                onCloseDrawer()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Настройки подключения")
                        }
                        Button(
                            onClick = { openNewChatDialog(currentWorkspacePath()) },
                            enabled = connected,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                        ) {
                            Text("Новый диалог")
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = uiSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Настройки",
                        color = uiText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Автопереподключение, страница запуска по умолчанию, версия приложения.",
                        color = uiMuted,
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            openSettingsPage()
                            onCloseDrawer()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Открыть настройки")
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Проект")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { requestSessionList() },
                        enabled = connected,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("Обновить", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { expandAllProjectGroups(projectGroups) },
                        enabled = projectGroups.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("Развернуть всё", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { collapseAllProjectGroups(projectGroups) },
                        enabled = projectGroups.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("Свернуть всё", fontSize = 12.sp)
                    }
                }
            }
        }

        if (projectGroups.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = uiSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
                ) {
                    BodyText(
                        text = if (connected) "После подключения здесь появятся проекты и история сессий." else "Истории в локальном кэше нет. После подключения к bridge проекты и сессии синхронизируются.",
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        } else {
            items(items = projectGroups, key = { it.key() }) { group ->
                ProjectGroupCard(
                    group = group,
                    onNewChat = {
                        openNewChatDialog(group.path)
                        onCloseDrawer()
                    },
                    onSelectSession = { session ->
                        selectSession(session.sessionId, true)
                        selectedWorkspace = group.path
                        currentPage = AppPage.Chat
                        onCloseDrawer()
                    },
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun MainActivity.ProjectGroupCard(
    group: ProjectGroup,
    onNewChat: () -> Unit,
    onSelectSession: (SessionInfo) -> Unit,
) {
    val expanded = isProjectGroupExpanded(group)
    val sessionListExpanded = isProjectGroupSessionListExpanded(group)
    val visibleSessions =
        if (sessionListExpanded) {
            group.sessions
        } else {
            group.sessions.take(PROJECT_GROUP_DEFAULT_VISIBLE_SESSIONS)
        }
    val hiddenCount = (group.sessions.size - visibleSessions.size).coerceAtLeast(0)

    Card(
        colors = CardDefaults.cardColors(containerColor = uiSurface),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { setProjectGroupExpanded(group.key(), !expanded) },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = group.displayName,
                        color = uiText,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${group.sessions.size}",
                        color = uiMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = if (expanded) "▴" else "▾",
                        color = uiMuted,
                        fontSize = 16.sp,
                    )
                }
                TextButton(onClick = onNewChat) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_input_add),
                        contentDescription = "Новый диалог",
                        tint = uiPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            if (expanded) {
                if (group.sessions.isEmpty()) {
                    BodyText("У этого проекта пока нет истории сессий.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleSessions.forEach { session ->
                            SessionCard(
                                session = session,
                                selected = session.sessionId == activeSessionId,
                                onClick = { onSelectSession(session) },
                            )
                        }
                        if (hiddenCount > 0) {
                            TextButton(
                                onClick = { setProjectGroupSessionListExpanded(group.key(), true) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Показать всю историю проекта (+$hiddenCount)", fontSize = 12.sp)
                            }
                        } else if (group.sessions.size > PROJECT_GROUP_DEFAULT_VISIBLE_SESSIONS) {
                            TextButton(
                                onClick = { setProjectGroupSessionListExpanded(group.key(), false) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Свернуть историю", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun MainActivity.syncProjectGroupExpansion(projectGroups: List<ProjectGroup>) {
    val nextKeys = projectGroups.mapTo(linkedSetOf()) { it.key() }
    projectGroupExpanded.keys.toList().forEach { key ->
        if (key !in nextKeys) {
            projectGroupExpanded.remove(key)
        }
    }
    projectGroups.forEach { group ->
        projectGroupExpanded.putIfAbsent(group.key(), group.sessions.any { it.sessionId == activeSessionId })
    }
}

internal fun MainActivity.syncProjectGroupSessionListExpansion(projectGroups: List<ProjectGroup>) {
    val nextKeys = projectGroups.mapTo(linkedSetOf()) { it.key() }
    projectGroupSessionListExpanded.keys.toList().forEach { key ->
        if (key !in nextKeys) {
            projectGroupSessionListExpanded.remove(key)
        }
    }
    projectGroups.forEach { group ->
        val containsActiveSession = group.sessions.indexOfFirst { it.sessionId == activeSessionId }
        val shouldExpand = containsActiveSession >= PROJECT_GROUP_DEFAULT_VISIBLE_SESSIONS
        if (shouldExpand) {
            projectGroupSessionListExpanded[group.key()] = true
        } else {
            projectGroupSessionListExpanded.putIfAbsent(group.key(), false)
        }
    }
}

internal fun MainActivity.isProjectGroupExpanded(group: ProjectGroup): Boolean {
    return projectGroupExpanded[group.key()] ?: group.sessions.any { it.sessionId == activeSessionId }
}

internal fun MainActivity.setProjectGroupExpanded(groupKey: String, expanded: Boolean) {
    projectGroupExpanded[groupKey] = expanded
}

internal fun MainActivity.isProjectGroupSessionListExpanded(group: ProjectGroup): Boolean {
    return projectGroupSessionListExpanded[group.key()] ?: false
}

internal fun MainActivity.setProjectGroupSessionListExpanded(groupKey: String, expanded: Boolean) {
    projectGroupSessionListExpanded[groupKey] = expanded
}

internal fun MainActivity.expandAllProjectGroups(projectGroups: List<ProjectGroup>) {
    projectGroups.forEach { group ->
        projectGroupExpanded[group.key()] = true
    }
}

internal fun MainActivity.collapseAllProjectGroups(projectGroups: List<ProjectGroup>) {
    projectGroups.forEach { group ->
        projectGroupExpanded[group.key()] = false
    }
}

@Composable
internal fun MainActivity.DrawerWorkspaceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) uiPrimary else uiSurfaceAlt,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, uiPrimary) else androidx.compose.foundation.BorderStroke(1.dp, uiBorder),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            color = if (selected) Color.White else uiText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
