package com.haochen.codexremote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainActivity.ChatSettingsBar() {
    val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sandboxSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tokenLabel = tokenUsageToolbarLabel()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatSettingButton(
                label = chatModeLabel(),
                enabled = connected && activeSessionId != null,
                onClick = { chatSettingsSheet = ChatSettingsSheet.Mode },
            )
            ChatSettingButton(
                label = sandboxShortLabel(),
                enabled = connected && activeSessionId != null,
                onClick = { chatSettingsSheet = ChatSettingsSheet.Sandbox },
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tokenLabel?.let { label ->
                Text(
                    text = label,
                    color = uiPrimary.copy(alpha = 0.92f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            ChatSettingButton(
                label = modelToolbarLabel(),
                enabled = connected && activeSessionId != null,
                onClick = { chatSettingsSheet = ChatSettingsSheet.Model },
            )
        }
    }

    when (chatSettingsSheet) {
        ChatSettingsSheet.Mode -> ModalBottomSheet(
            onDismissRequest = { chatSettingsSheet = null },
            modifier = Modifier.imePadding(),
            sheetState = modeSheetState,
            containerColor = uiSurface,
        ) {
            ChatModeSheet(onDismiss = { chatSettingsSheet = null })
        }
        ChatSettingsSheet.Model -> ModalBottomSheet(
            onDismissRequest = { chatSettingsSheet = null },
            sheetState = modelSheetState,
            containerColor = uiSurface,
        ) {
            ModelReasoningSheet(onDismiss = { chatSettingsSheet = null })
        }
        ChatSettingsSheet.Sandbox -> ModalBottomSheet(
            onDismissRequest = { chatSettingsSheet = null },
            sheetState = sandboxSheetState,
            containerColor = uiSurface,
        ) {
            SandboxSheet(onDismiss = { chatSettingsSheet = null })
        }
        null -> Unit
    }
}

@Composable
private fun MainActivity.ChatSettingButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .heightIn(min = 28.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 48.dp)
                .padding(horizontal = 7.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (enabled) uiText else uiMuted.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MainActivity.ChatModeSheet(onDismiss: () -> Unit) {
    var goalDraft by remember(activeThreadGoal?.objective) { mutableStateOf(activeThreadGoal?.objective.orEmpty()) }
    var budgetDraft by remember(activeThreadGoal?.tokenBudget) { mutableStateOf(activeThreadGoal?.tokenBudget?.toString().orEmpty()) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun revealInputs() {
        scope.launch {
            delay(220)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    SheetColumn(scrollState = scrollState, maxHeight = 640.dp) {
        SheetTitle("Режим сотрудничества")
        chatModeOptions().forEach { option ->
            SelectableOptionRow(
                label = option.label,
                supporting = option.supporting.orEmpty(),
                selected = selectedChatMode.preferenceValue() == option.value,
                onClick = {
                    selectChatMode(chatModeFromPreference(option.value))
                    if (option.value != ChatMode.Goal.preferenceValue()) onDismiss()
                },
            )
        }

        HorizontalDivider(color = uiBorder.copy(alpha = 0.8f))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Текущая цель", color = uiText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = activeThreadGoal?.objective?.takeIf { it.isNotBlank() } ?: "Цель не задана",
                color = uiMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            OutlinedTextField(
                value = goalDraft,
                onValueChange = { goalDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) revealInputs() },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                label = { Text("Цель") },
                placeholder = { Text("Например: реализовать поддержку режимов на Android") },
                colors = inputFieldColors(),
            )
            OutlinedTextField(
                value = budgetDraft,
                onValueChange = { value -> budgetDraft = value.filter { it.isDigit() }.take(9) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) revealInputs() },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                label = { Text("Бюджет Token") },
                placeholder = { Text("Необязательно") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = inputFieldColors(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { clearActiveGoal() },
                    enabled = activeThreadGoal != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Очистить цель")
                }
                Button(
                    onClick = { setActiveGoal(goalDraft, budgetDraft) },
                    enabled = goalDraft.trim().isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = uiPrimary),
                ) {
                    Text("Сделать текущей целью")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainActivity.ModelReasoningSheet(onDismiss: () -> Unit) {
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
    }.sortedBy { it.displayName.lowercase() }
    val currentModel = resolveModelForSend()
    val currentEffort = selectedReasoningEffort

    SheetColumn {
        SheetTitle("Модель и рассуждения")
        Text("Интенсивность рассуждений", color = uiText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        SegmentedButtonRows(
            options = chatReasoningEffortOptions(currentModel),
            columns = 5,
            selected = { option -> currentEffort == option.value },
            label = { option -> reasoningEffortShortLabel(option.value) },
            onClick = { option -> selectReasoningEffort(option.value) },
        )

        Text("Сводка рассуждений", color = uiText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        SegmentedButtonRows(
            options = reasoningSummaryOptions(),
            columns = 4,
            selected = { option -> normalizeReasoningSummary(selectedReasoningSummary) == option.value },
            label = { option -> option.label },
            onClick = { option -> selectReasoningSummary(option.value) },
        )

        HorizontalDivider(color = uiBorder.copy(alpha = 0.8f))
        Text("Модель", color = uiText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (modelOptions.isEmpty()) {
            Text("Нет доступных моделей", color = uiMuted, fontSize = 12.sp)
        } else {
            modelOptions.forEach { model ->
                SelectableOptionRow(
                    label = model.displayName,
                    supporting = model.description.takeIf { it.isNotBlank() }
                        ?: if (model.isDefault) "По умолчанию" else model.model,
                    selected = model.model == currentModel || model.id == currentModel,
                    onClick = { selectModel(model.model) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = uiPrimary)) {
                Text("Завершено")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun MainActivity.SandboxSheet(onDismiss: () -> Unit) {
    SheetColumn {
        SheetTitle("Режим песочницы")
        sandboxMenuOptions().forEach { option ->
            SelectableOptionRow(
                label = option.label,
                supporting = option.supporting.orEmpty(),
                selected = normalizeSandboxPreference(selectedSandbox) == option.value,
                onClick = {
                    selectSandbox(option.value)
                    onDismiss()
                },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SheetColumn(
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    maxHeight: androidx.compose.ui.unit.Dp = 520.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun MainActivity.SheetTitle(text: String) {
    Text(
        text = text,
        color = uiText,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MainActivity.SelectableOptionRow(
    label: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) uiPrimarySoft else uiSurfaceAlt,
        border = BorderStroke(1.dp, if (selected) uiPrimary else uiBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selected) "●" else "○",
                color = if (selected) uiPrimary else uiMuted,
                fontSize = 14.sp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = uiText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                supporting.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = uiMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun MainActivity.ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 30.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) uiPrimary else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) uiPrimary else uiBorder),
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else uiText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MainActivity.SegmentedButtonRows(
    options: List<SelectionMenuOption>,
    columns: Int,
    selected: (SelectionMenuOption) -> Boolean,
    label: (SelectionMenuOption) -> String,
    onClick: (SelectionMenuOption) -> Unit,
) {
    val rows = options.chunked(columns.coerceAtLeast(1))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowOptions.forEach { option ->
                    ChoiceChip(
                        label = label(option),
                        selected = selected(option),
                        onClick = { onClick(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MainActivity.inputFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = uiPrimary,
    unfocusedBorderColor = uiBorder,
    focusedTextColor = uiText,
    unfocusedTextColor = uiText,
    cursorColor = uiPrimary,
)
