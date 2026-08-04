package com.haochen.codexremote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MainActivity.NewChatSheet(
    draft: NewChatDraft,
    onDismiss: () -> Unit,
    onProjectPathClick: () -> Unit,
    onModelChange: (String) -> Unit,
    onReasoningEffortChange: (String) -> Unit,
    onApprovalPolicyChange: (String) -> Unit,
    onSandboxChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    var modelExpanded by remember { mutableStateOf(false) }
    var effortExpanded by remember { mutableStateOf(false) }
    var approvalExpanded by remember { mutableStateOf(false) }
    var sandboxExpanded by remember { mutableStateOf(false) }
    val modelOptions = availableModels.ifEmpty {
        listOfNotNull(
            selectedModel.takeIf { it.isNotBlank() }?.let {
                ModelInfo(
                    id = it,
                    model = it,
                    displayName = it,
                    description = "",
                    hidden = false,
                    isDefault = true,
                    supportedReasoningEfforts = emptyList(),
                    defaultReasoningEffort = "medium",
                )
            },
        )
    }
    val selectedModelInfo =
        modelOptions.firstOrNull { it.model == draft.model || it.id == draft.model }
            ?: modelInfoFor(draft.model)
    val reasoningOptions = reasoningEffortOptionsForModel(draft.model)
    val selectedReasoningOption =
        reasoningOptions.firstOrNull { it.value == draft.reasoningEffort }
            ?: reasoningOptionFor(draft.reasoningEffort)
    val approvalOptions = approvalPolicyMenuOptions()
    val sandboxOptions = sandboxMenuOptions()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "新对话",
                color = uiText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "设置项目目录、模型、思考强度、审批策略和沙箱。",
                color = uiMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("项目目录")
            OutlinedButton(
                onClick = onProjectPathClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = workspaceDisplayName(draft.projectPath, fallback = "Bridge 默认目录"),
                            color = uiText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = draft.projectPath ?: "点击选择远程主机上的项目目录",
                            color = uiMuted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("›", color = uiMuted, fontSize = 20.sp)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionMenuField(
                label = "模型",
                selectedLabel = selectedModelInfo?.displayName ?: draft.model.ifBlank { "请选择模型" },
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it },
                enabled = modelOptions.isNotEmpty(),
                options = modelOptions.map { model ->
                    SelectionMenuOption(
                        value = model.model,
                        label = model.displayName,
                        supporting = model.description.takeIf { it.isNotBlank() },
                    )
                },
                onSelect = onModelChange,
            )
            if (modelOptions.isEmpty()) {
                BodyText("模型列表还没加载完成。")
            } else {
                selectedModelInfo?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        color = uiMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionMenuField(
                label = "思考强度",
                selectedLabel = selectedReasoningOption.label,
                expanded = effortExpanded,
                onExpandedChange = { effortExpanded = it },
                enabled = reasoningOptions.isNotEmpty(),
                options = reasoningOptions,
                onSelect = onReasoningEffortChange,
            )
            Text(
                text = selectedReasoningOption.supporting.orEmpty(),
                color = uiMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionMenuField(
                label = "审批策略",
                selectedLabel = approvalOptions.firstOrNull { it.value == draft.approvalPolicy }?.label ?: draft.approvalPolicy,
                expanded = approvalExpanded,
                onExpandedChange = { approvalExpanded = it },
                options = approvalOptions,
                onSelect = onApprovalPolicyChange,
            )
            Text(
                text = approvalPolicyDescription(draft.approvalPolicy),
                color = uiMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text(
                text = "permission profile 不单独手填；新对话按当前默认执行，历史对话继续沿用原会话配置。",
                color = uiMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionMenuField(
                label = "沙箱",
                selectedLabel = sandboxOptions.firstOrNull { it.value == draft.sandbox }?.label ?: draft.sandbox,
                expanded = sandboxExpanded,
                onExpandedChange = { sandboxExpanded = it },
                options = sandboxOptions,
                onSelect = onSandboxChange,
            )
            Text(
                text = sandboxDescription(draft.sandbox),
                color = uiMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            Button(
                onClick = onConfirm,
                enabled = draft.model.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
            ) {
                Text("确定")
            }
        }
    }
}

@Composable
internal fun MainActivity.SelectionMenuField(
    label: String,
    selectedLabel: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<SelectionMenuOption>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    var anchorWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onExpandedChange(!expanded) },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { anchorWidthPx = it.width },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedLabel,
                        color = uiText,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (expanded) "▲" else "▼",
                        color = uiMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            DropdownMenu(
                expanded = enabled && expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = with(density) {
                    Modifier.widthIn(min = anchorWidthPx.toDp(), max = anchorWidthPx.toDp())
                },
                containerColor = uiSurface,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, uiBorder),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(option.label)
                                option.supporting?.takeIf { it.isNotBlank() }?.let { supporting ->
                                    Text(
                                        text = supporting,
                                        color = uiMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onExpandedChange(false)
                            onSelect(option.value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MainActivity.SessionInfoSheet(
    state: SessionInfoSheetState,
    onDismiss: () -> Unit,
    onRebuildHistory: () -> Unit,
    onSwitchToFullAccess: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "会话信息",
                    color = uiText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.title,
                    color = uiMuted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onRebuildHistory,
                    enabled = state.canRebuildHistory,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "重建历史记录",
                        tint = if (state.canRebuildHistory) uiPrimary else uiMuted,
                    )
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.rows.forEach { (label, value) ->
                val showSandboxUpgrade = state.canSwitchToFullAccess && label == state.sandboxRowLabel
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = uiSurfaceAlt,
                    border = BorderStroke(1.dp, uiBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (copyTextToClipboard(label, value)) {
                                        showNotice("已复制$label")
                                    } else {
                                        showNotice("复制失败")
                                    }
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = label,
                                color = uiMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = value,
                                color = uiText,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            )
                        }
                        if (showSandboxUpgrade) {
                            IconButton(onClick = onSwitchToFullAccess) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_upgrade_access),
                                    contentDescription = "切回完整访问",
                                    tint = uiPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MainActivity.SessionCard(
    session: SessionInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) uiPrimarySoft else uiSurfaceAlt,
        border = if (selected) BorderStroke(1.dp, uiPrimary) else BorderStroke(1.dp, uiBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.titleLine(),
                color = uiText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session.updatedAtLabel(),
                color = uiMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun MainActivity.ModelCard(
    model: ModelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) uiPrimarySoft else uiSurfaceAlt,
        border = if (selected) BorderStroke(1.dp, uiPrimary) else BorderStroke(1.dp, uiBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.displayName,
                    color = uiText,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.widthIn(min = 8.dp))
                Text(
                    text = if (model.isDefault) "默认" else model.model,
                    color = uiMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = model.description,
                    color = uiMuted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
