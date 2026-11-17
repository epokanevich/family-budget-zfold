package ru.pokanevich.familybudget.ui.sheets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pokanevich.familybudget.data.FamilyPreferences
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import ru.pokanevich.familybudget.data.SavingsGoalProgress
import ru.pokanevich.familybudget.ui.theme.AuthorAvatarCircle
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.AmountExpressionEvaluator
import ru.pokanevich.familybudget.ui.util.Formatters
import java.util.UUID
import kotlin.math.roundToInt

private val GOAL_EMOJI_PRESETS = listOf(
    "🎯", "✈️", "🚗", "🏠", "🛋️", "💻", "👶", "🐾", "💍", "🛡️", "🎓", "🌴"
)

private val QUICK_TRANSFER_AMOUNTS = listOf(1_000, 5_000, 10_000, 25_000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsGoalsSheet(
    goalProgressList: List<SavingsGoalProgress>,
    totalSavedInGoalsRub: Double,
    freeUnallocatedBalanceRub: Double,
    currentUserName: String,
    onSaveGoal: (SavingsGoalEntity) -> Unit,
    onToggleArchived: (SavingsGoalEntity, Boolean) -> Unit,
    onDeleteGoal: (SavingsGoalEntity) -> Unit,
    onRecordTransfer: (
        goal: SavingsGoalEntity,
        amountRub: Double,
        isDeposit: Boolean,
        authorName: String,
        note: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val activeGoals = remember(goalProgressList) {
        goalProgressList.filter { !it.goal.isArchived }
    }
    val archivedGoals = remember(goalProgressList) {
        goalProgressList.filter { it.goal.isArchived }
    }

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<SavingsGoalEntity?>(null) }
    var transferTarget by remember { mutableStateOf<Pair<SavingsGoalProgress, Boolean>?>(null) }
    var deletingGoal by remember { mutableStateOf<SavingsGoalEntity?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceCard,
        contentColor = palette.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isWideScreen = maxWidth >= 600.dp
            val isCompactCover = maxWidth < 360.dp

            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isCompactCover) 14.dp else 20.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.accentEmeraldSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Savings,
                                contentDescription = null,
                                tint = palette.accentEmerald,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Семейные копилки",
                                style = MaterialTheme.typography.titleLarge,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Накопления без искажения бюджета",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                        }
                    }

                    Button(
                        onClick = {
                            editingGoal = null
                            showEditorDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accentEmerald,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Новая цель", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Summary strip
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = palette.surfaceElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Всего в копилках",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                            Text(
                                text = Formatters.formatRub(totalSavedInGoalsRub, true),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = palette.accentEmerald
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "Свободно (вне копилок)",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                            Text(
                                text = Formatters.formatRub(freeUnallocatedBalanceRub, true),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (freeUnallocatedBalanceRub < -0.005) {
                                    palette.accentCoral
                                } else {
                                    palette.textPrimary
                                }
                            )
                        }
                    }
                }

                if (activeGoals.isEmpty() && archivedGoals.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = palette.surfaceElevated
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "🎯 Пока нет семейных копилок",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Создайте цель на отпуск, подушку безопасности или крупную покупку. Пополнения работают как перевод и не портят статистику трат.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                    }
                }

                if (activeGoals.isNotEmpty()) {
                    if (isWideScreen) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            activeGoals.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    pair.forEach { progress ->
                                        SheetGoalCard(
                                            progress = progress,
                                            onDeposit = { transferTarget = progress to true },
                                            onWithdraw = { transferTarget = progress to false },
                                            onEdit = {
                                                editingGoal = progress.goal
                                                showEditorDialog = true
                                            },
                                            onToggleArchive = {
                                                onToggleArchived(progress.goal, true)
                                            },
                                            onDelete = { deletingGoal = progress.goal },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            activeGoals.forEach { progress ->
                                SheetGoalCard(
                                    progress = progress,
                                    onDeposit = { transferTarget = progress to true },
                                    onWithdraw = { transferTarget = progress to false },
                                    onEdit = {
                                        editingGoal = progress.goal
                                        showEditorDialog = true
                                    },
                                    onToggleArchive = {
                                        onToggleArchived(progress.goal, true)
                                    },
                                    onDelete = { deletingGoal = progress.goal },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                if (archivedGoals.isNotEmpty()) {
                    Text(
                        text = "Архив целей (${archivedGoals.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        archivedGoals.forEach { progress ->
                            SheetGoalCard(
                                progress = progress,
                                onDeposit = { transferTarget = progress to true },
                                onWithdraw = { transferTarget = progress to false },
                                onEdit = {
                                    editingGoal = progress.goal
                                    showEditorDialog = true
                                },
                                onToggleArchive = {
                                    onToggleArchived(progress.goal, false)
                                },
                                onDelete = { deletingGoal = progress.goal },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(0.76f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditorDialog) {
        SavingsGoalEditorDialog(
            initialGoal = editingGoal,
            onDismiss = {
                showEditorDialog = false
                editingGoal = null
            },
            onSave = { savedGoal ->
                onSaveGoal(savedGoal)
                showEditorDialog = false
                editingGoal = null
            }
        )
    }

    transferTarget?.let { (progress, isDeposit) ->
        GoalTransferDialog(
            progress = progress,
            isDeposit = isDeposit,
            freeUnallocatedBalanceRub = freeUnallocatedBalanceRub,
            defaultAuthorName = currentUserName,
            onDismiss = { transferTarget = null },
            onConfirm = { amountRub, authorName, note ->
                onRecordTransfer(progress.goal, amountRub, isDeposit, authorName, note)
                transferTarget = null
            }
        )
    }

    deletingGoal?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingGoal = null },
            containerColor = palette.surfaceCard,
            title = {
                Text(
                    text = "Удалить копилку?",
                    color = palette.textPrimary
                )
            },
            text = {
                Text(
                    text = "Цель «${target.emoji} ${target.title}» будет скрыта. Уже созданные переводы останутся в истории ленты.",
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteGoal(target)
                        deletingGoal = null
                    }
                ) {
                    Text("Удалить", color = palette.accentCoral, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingGoal = null }) {
                    Text("Отмена", color = palette.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun SheetGoalCard(
    progress: SavingsGoalProgress,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    val goal = progress.goal
    val animatedProgress by animateFloatAsState(
        targetValue = progress.progressRatio,
        animationSpec = tween(durationMillis = 500),
        label = "goalSheetProgress"
    )
    val accentColor = when {
        progress.isCompleted -> palette.accentEmerald
        else -> palette.accentBlue
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = goal.emoji.ifBlank { "🎯" },
                        fontSize = 20.sp
                    )
                    Column {
                        Text(
                            text = goal.title.ifBlank { "Копилка" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val percent = (progress.progressRatio * 100f).roundToInt()
                        Text(
                            text = if (goal.targetAmountRub > 0.0) {
                                "${Formatters.formatRub(progress.savedAmountRub)} из ${Formatters.formatRub(goal.targetAmountRub)} ($percent%)"
                            } else {
                                "Накоплено: ${Formatters.formatRub(progress.savedAmountRub)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Редактировать",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onToggleArchive, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (goal.isArchived) {
                                Icons.Rounded.Unarchive
                            } else {
                                Icons.Rounded.Archive
                            },
                            contentDescription = if (goal.isArchived) "Восстановить" else "В архив",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Удалить",
                            tint = palette.accentCoral,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (goal.targetAmountRub > 0.0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.surfaceCard)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedProgress)
                            .clip(RoundedCornerShape(999.dp))
                            .background(accentColor)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDeposit,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accentEmerald,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Text(
                        text = "+ Пополнить",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onWithdraw,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, palette.outlineSubtle),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Text(
                        text = "Снять",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavingsGoalEditorDialog(
    initialGoal: SavingsGoalEntity?,
    onDismiss: () -> Unit,
    onSave: (SavingsGoalEntity) -> Unit
) {
    val palette = LocalFamilyPalette.current
    var title by remember(initialGoal) { mutableStateOf(initialGoal?.title.orEmpty()) }
    var emoji by remember(initialGoal) { mutableStateOf(initialGoal?.emoji?.ifBlank { "🎯" } ?: "🎯") }
    var targetText by remember(initialGoal) {
        mutableStateOf(
            initialGoal?.targetAmountRub
                ?.takeIf { it > 0.0 }
                ?.let { AmountExpressionEvaluator.formatForInput(it) }
                ?: ""
        )
    }
    var initialText by remember(initialGoal) {
        mutableStateOf(
            initialGoal?.initialAmountRub
                ?.takeIf { it > 0.0 }
                ?.let { AmountExpressionEvaluator.formatForInput(it) }
                ?: "0"
        )
    }

    val parsedTarget = AmountExpressionEvaluator.evaluate(targetText)
    val parsedInitial = remember(initialText) {
        val trimmed = initialText.trim()
        if (trimmed.isEmpty() || trimmed == "0" || trimmed == "0.0" || trimmed == "0,0") {
            0.0
        } else {
            AmountExpressionEvaluator.evaluate(trimmed)
        }
    }
    val canSave = title.trim().isNotEmpty() && parsedTarget != null && parsedInitial != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceCard,
        title = {
            Text(
                text = if (initialGoal == null) "Новая копилка" else "Редактировать цель",
                color = palette.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GOAL_EMOJI_PRESETS.forEach { preset ->
                        val selected = emoji == preset
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { emoji = preset },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) palette.accentEmeraldSoft else palette.surfaceElevated,
                            border = BorderStroke(
                                1.dp,
                                if (selected) palette.accentEmerald else palette.outlineSubtle
                            )
                        ) {
                            Text(
                                text = preset,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название цели") },
                    placeholder = { Text("Например, Отпуск на море") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("Целевая сумма (₽)") },
                    placeholder = { Text("Например, 150000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = initialText,
                    onValueChange = { initialText = it },
                    label = { Text("Начальный остаток (₽)") },
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val target = parsedTarget ?: return@Button
                    val initial = parsedInitial ?: 0.0
                    val nextGoal = (initialGoal ?: SavingsGoalEntity(id = UUID.randomUUID().toString())).copy(
                        title = title.trim(),
                        emoji = emoji.trim().ifBlank { "🎯" },
                        targetAmountRub = target,
                        initialAmountRub = initial,
                        isDeleted = false,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    onSave(nextGoal)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentEmerald,
                    contentColor = Color.White
                )
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = palette.textSecondary)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalTransferDialog(
    progress: SavingsGoalProgress,
    isDeposit: Boolean,
    freeUnallocatedBalanceRub: Double,
    defaultAuthorName: String,
    onDismiss: () -> Unit,
    onConfirm: (amountRub: Double, authorName: String, note: String) -> Unit
) {
    val palette = LocalFamilyPalette.current
    val goal = progress.goal
    var amountText by remember(progress.goal.id, isDeposit) { mutableStateOf("") }
    var selectedAuthor by remember(defaultAuthorName) { mutableStateOf(defaultAuthorName) }
    var noteText by remember { mutableStateOf("") }

    val parsedAmount = AmountExpressionEvaluator.evaluate(amountText)
    val canConfirm = parsedAmount != null && parsedAmount > 0.0
    val accent = if (isDeposit) palette.accentEmerald else palette.accentBlue

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceCard,
        title = {
            Text(
                text = if (isDeposit) {
                    "${goal.emoji} Пополнить копилку"
                } else {
                    "${goal.emoji} Снять с копилки"
                },
                color = palette.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = goal.title.ifBlank { "Копилка" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "В копилке: ${Formatters.formatRub(progress.savedAmountRub, true)} • Свободно: ${Formatters.formatRub(freeUnallocatedBalanceRub, true)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary
                        )
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Сумма (₽)") },
                    placeholder = { Text("Например, 5000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_TRANSFER_AMOUNTS.forEach { preset ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { amountText = preset.toString() },
                            shape = RoundedCornerShape(999.dp),
                            color = palette.surfaceElevated
                        ) {
                            Text(
                                text = Formatters.formatRub(preset.toDouble()),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (!isDeposit && progress.savedAmountRub > 0.0) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable {
                                    amountText = AmountExpressionEvaluator.formatForInput(progress.savedAmountRub)
                                },
                            shape = RoundedCornerShape(999.dp),
                            color = palette.accentBlueSoft
                        ) {
                            Text(
                                text = "Всё (${Formatters.formatRub(progress.savedAmountRub)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accentBlue,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (!FamilyPreferences.isSoloProfile) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Евгений", "Анжелика").forEach { person ->
                            val selected = selectedAuthor == person
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedAuthor = person },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) palette.surfaceElevated else palette.surfaceCard,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) accent else palette.outlineSubtle
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AuthorAvatarCircle(authorName = person, size = 20.dp)
                                    Text(
                                        text = person,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Заметка (необязательно)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canConfirm,
                onClick = {
                    val amt = parsedAmount ?: return@Button
                    onConfirm(amt, selectedAuthor, noteText.trim())
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White
                )
            ) {
                Text(if (isDeposit) "Пополнить" else "Снять")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = palette.textSecondary)
            }
        }
    )
}
