package ru.pokanevich.familybudget.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.data.SavingsGoalCalculator
import ru.pokanevich.familybudget.ui.theme.AuthorPill
import ru.pokanevich.familybudget.ui.theme.CategoryIconBadge
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseDetailSheet(
    expense: ExpenseEntity,
    allExpenses: List<ExpenseEntity> = emptyList(),
    onSelectExpense: ((ExpenseEntity) -> Unit)? = null,
    onDismiss: () -> Unit,
    onEdit: (ExpenseEntity) -> Unit,
    onDelete: (ExpenseEntity) -> Unit,
    registry: CategoryRegistry = CategoryRegistry.DEFAULT
) {
    val palette = LocalFamilyPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resolvedCategory = SavingsGoalCalculator.resolveDisplayCategory(expense, registry)
    val isIncome = expense.isIncome(registry)
    val isTransfer = expense.isTransfer(registry)
    val transferTitle = when (expense.categoryName) {
        SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT -> "Пополнение копилки"
        SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW -> "Снятие с копилки"
        else -> "Перевод между своими"
    }
    val splitSiblings = if (expense.splitGroupId.isNotBlank()) {
        allExpenses.filter {
            !it.isDeleted && it.splitGroupId == expense.splitGroupId && it.id != expense.id
        }
    } else {
        emptyList()
    }
    val splitTotalAmount = expense.amountRub + splitSiblings.sumOf { it.amountRub }

    val sourceLabel = when (expense.sourceType) {
        "PUSH_SBER" -> "Автоперехват СберБанк"
        "PUSH_MIRPAY" -> "Автоперехват Mir Pay"
        "PUSH_TBANK" -> "Автоперехват Т-Банк"
        "PUSH_ALFA" -> "Автоперехват Альфа-Банк"
        "PUSH_VTB" -> "Автоперехват ВТБ"
        "PUSH_OZON" -> "Автоперехват Ozon Банк"
        "QR_RECEIPT" -> "QR-код чека ФНС"
        RecurringPaymentScheduler.SOURCE_TYPE_RECURRING -> "Регулярный платёж"
        "IMPORT_MM" -> "Перенесено из Money Manager"
        else -> "Добавлено вручную"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceCard,
        contentColor = palette.textPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CategoryIconBadge(
                category = resolvedCategory,
                size = 58.dp,
                iconSize = 28.dp,
                cornerRadius = 18.dp
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = expense.merchant,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center
                )
                val formatted = Formatters.formatRub(expense.amountRub, showKopecksIfAny = true)
                Text(
                    text = if (isIncome) "+$formatted" else formatted,
                    style = MaterialTheme.typography.headlineLarge,
                    color = when {
                        isIncome -> palette.accentEmerald
                        isTransfer -> palette.textSecondary
                        else -> palette.textPrimary
                    }
                )
                if (isTransfer) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.surfaceElevated,
                        border = BorderStroke(1.dp, palette.outlineSubtle)
                    ) {
                        Text(
                            text = transferTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
                if (expense.splitGroupId.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.accentBlueSoft
                    ) {
                        Text(
                            text = "Split",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accentBlue,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Metadata Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = palette.surfaceElevated
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailInfoRow(
                        label = when {
                            isIncome -> "Чей доход"
                            isTransfer -> "Кто перевёл"
                            else -> "Оплатил(а)"
                        }
                    ) {
                        AuthorPill(authorName = expense.authorName)
                    }
                    HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)
                    if (isTransfer) {
                        DetailInfoRow(label = "Статус") {
                            Text(
                                text = transferTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = palette.textSecondary
                            )
                        }
                    } else {
                        DetailInfoRow(label = "Категория") {
                            Text(
                                text = resolvedCategory.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = palette.textPrimary
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)
                    DetailInfoRow(label = "Дата и время") {
                        Text(
                            text = Formatters.formatFullDateTime(expense.createdAtEpochMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary
                        )
                    }
                    HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)
                    DetailInfoRow(label = "Источник") {
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            if (expense.note.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = palette.surfaceElevated
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Заметка",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary
                        )
                        Text(
                            text = expense.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary
                        )
                    }
                }
            }

            if (splitSiblings.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = palette.surfaceElevated
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Другие части чека (Split)",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.textSecondary
                            )
                            Text(
                                text = "Итого: ${Formatters.formatRub(splitTotalAmount, showKopecksIfAny = true)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.accentBlue
                            )
                        }
                        splitSiblings.forEachIndexed { idx, sibling ->
                            if (idx > 0) {
                                HorizontalDivider(thickness = 0.6.dp, color = palette.outlineSubtle)
                            }
                            val siblingCategory = registry.resolve(sibling.categoryName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (onSelectExpense != null) {
                                            Modifier.clickable { onSelectExpense(sibling) }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CategoryIconBadge(
                                    category = siblingCategory,
                                    size = 30.dp,
                                    iconSize = 16.dp,
                                    cornerRadius = 9.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = siblingCategory.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (sibling.note.isNotBlank()) {
                                        Text(
                                            text = sibling.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = palette.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Text(
                                    text = Formatters.formatRub(sibling.amountRub, showKopecksIfAny = true),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                            }
                        }
                    }
                }
            }

            if (expense.rawPushOrQrText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceElevated.copy(alpha = 0.65f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = expense.rawPushOrQrText,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted
                    )
                }
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onDelete(expense) },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.accentCoral.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(vertical = 13.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = palette.accentCoral,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Удалить",
                        color = palette.accentCoral,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = { onEdit(expense) },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accentEmerald,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Изменить",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    label: String,
    valueContent: @Composable () -> Unit
) {
    val palette = LocalFamilyPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary
        )
        valueContent()
    }
}
