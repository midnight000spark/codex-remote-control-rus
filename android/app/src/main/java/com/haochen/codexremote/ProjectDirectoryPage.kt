package com.haochen.codexremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun MainActivity.ProjectDirectoryPickerDialog(
    state: ProjectDirectoryPickerState,
    recentPaths: List<String>,
    onDismiss: () -> Unit,
    onBrowse: (String) -> Unit,
    onSelect: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ProjectDirectoryPage(
            state = state,
            recentPaths = recentPaths,
            onDismiss = onDismiss,
            onBrowse = onBrowse,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun MainActivity.ProjectDirectoryPage(
    state: ProjectDirectoryPickerState,
    recentPaths: List<String>,
    onDismiss: () -> Unit,
    onBrowse: (String) -> Unit,
    onSelect: () -> Unit,
) {
    var pathInput by remember(state.currentPath) { mutableStateOf(state.currentPath) }
    var recentExpanded by remember { mutableStateOf(false) }
    val listStatesByPath = remember { mutableMapOf<String, LazyListState>() }
    val listStateKey = state.currentPath.ifBlank { "__bridge_default__" }
    val directoryListState = remember(listStateKey) {
        listStatesByPath.getOrPut(listStateKey) { LazyListState() }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val browseInput = {
        keyboardController?.hide()
        onBrowse(pathInput)
    }

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
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Вернуться к новому диалогу",
                        tint = uiText,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Выбрать каталог проекта",
                        color = uiText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Файловая система удалённого хоста",
                        color = uiMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Удалённый путь") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = browseInput, enabled = !state.loading) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "Открыть путь",
                                tint = uiText,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { browseInput() }),
                )
                Text(
                    text = state.currentPath.ifBlank { "Читаю каталог Bridge по умолчанию…" },
                    color = uiMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (recentPaths.isNotEmpty()) {
                    val selectedRecentPath = recentPaths.firstOrNull { it == state.currentPath }
                    SelectionMenuField(
                        label = "Недавние",
                        selectedLabel = selectedRecentPath?.let { path ->
                            workspaceDisplayName(path, fallback = path)
                        } ?: "Выбрать недавний проект",
                        expanded = recentExpanded,
                        onExpandedChange = { recentExpanded = it },
                        options = recentPaths.map { path ->
                            SelectionMenuOption(
                                value = path,
                                label = workspaceDisplayName(path, fallback = path),
                                supporting = path,
                            )
                        },
                        onSelect = onBrowse,
                        enabled = !state.loading,
                    )
                }
            }

            LazyColumn(
                state = directoryListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                state.parentPath?.let { parentPath ->
                    item(key = "parent:$parentPath") {
                        ProjectDirectoryRow(
                            title = "На уровень выше",
                            supporting = null,
                            marker = "↰",
                            onClick = { onBrowse(parentPath) },
                            enabled = !state.loading,
                        )
                    }
                }
                items(state.directories, key = { it.path }) { directory ->
                    ProjectDirectoryRow(
                        title = directory.name,
                        supporting = directory.path,
                        onClick = { onBrowse(directory.path) },
                        enabled = !state.loading,
                    )
                }

                if (state.loading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = uiPrimary,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                } else if (state.error != null) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = state.error,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                            )
                            TextButton(onClick = { onBrowse(state.requestedPath) }) {
                                Text("Повторить")
                            }
                        }
                    }
                } else if (state.directories.isEmpty()) {
                    item {
                        Text(
                            text = "В этом каталоге нет подкаталогов",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            color = uiMuted,
                            fontSize = 12.sp,
                        )
                    }
                }

                if (state.truncated) {
                    item {
                        Text(
                            text = "Каталогов много, показаны только первые 500 элементов. Можно ввести более конкретный путь.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            color = uiMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Surface(
                color = uiSurface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.currentPath.ifBlank { "Читаю каталог Bridge по умолчанию…" },
                        color = uiMuted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(
                        onClick = onSelect,
                        enabled = !state.loading && state.error == null && state.currentPath.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                    ) {
                        Text("Выбрать текущий каталог")
                    }
                }
            }
        }
    }
}

@Composable
private fun MainActivity.ProjectDirectoryRow(
    title: String,
    supporting: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    marker: String = "📁",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(marker, fontSize = 18.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = uiText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.takeIf { it.isNotBlank() }?.let { supportingText ->
                Text(
                    text = supportingText,
                    color = uiMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = uiMuted,
        )
    }
    HorizontalDivider(color = uiBorder)
}
