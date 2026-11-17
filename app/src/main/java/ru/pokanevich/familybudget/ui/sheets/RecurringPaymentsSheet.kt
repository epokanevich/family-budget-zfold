package ru.pokanevich.familybudget.ui.sheets

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.ui.theme.CategoryIconBadge
import ru.pokanevich.familybudget.ui.theme.CategorySymbol
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.theme.visual
import ru.pokanevich.familybudget.ui.util.AmountExpressionEvaluator
import ru.pokanevich.familybudget.ui.util.Formatters
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private val QUICK_DAYS_OF_MONTH = listOf(1, 5, 10, 15, 20, 25, 31)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringPaymentsSheet(
    recurringPayments: List<RecurringPaymentEntity>,
    categoryRegistry: CategoryRegistry,
    onSavePayment: (RecurringPaymentEntity) -> Unit,
    onToggleActive: (RecurringPaymentEntity, Boolean) -> Unit,
    onSetPaidForCurrentMonth: (RecurringPaymentEntity, Boolean) -> Unit,
    onDeletePayment: (RecurringPaymentEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentDate = LocalDate.now()
    val currentMonth = YearMonth.from(currentDate)
    val currentMonthKey = RecurringPaymentScheduler.periodKeyFor(currentMonth)
    val monthTitle = Formatters.formatMonthTitle(currentMonth.year, currentMonth.monthValue - 1)

    val visiblePayments = remember(recurringPayments) {
        recurringPayments.filter { !it.isDeleted }
    }
    val activePayments = remember(visiblePayments, currentMonth) {
        visiblePayments
            .filter { it.isActive }
            .sortedWith(
                compareBy<RecurringPaymentEntity> {
                    RecurringPaymentScheduler.effectiveDayOfMonth(it.dayOfMonth, currentMonth)
                }.thenBy { it.title.lowercase() }
            )
    }
    val pausedPayments = remember(visiblePayments) {
        visiblePayments
            .filter { !it.isActive }
            .sortedWith(
                compareBy<RecurringPaymentEntity> { it.dayOfMonth }
                    .thenBy { it.title.lowercase() }
            )
    }

    val activeExpensePayments = remember(activePayments) {
        activePayments.filter { it.effectiveOperationType == OperationType.EXPENSE }
    }
    val totalActiveExpenseRub = remember(activeExpensePayments) {
        activeExpensePayments.sumOf { it.amountRub }
    }
    val paidExpenseRub = remember(activeExpensePayments, currentMonthKey) {
        activeExpensePayments.filter { it.isPaidInPeriod(currentMonthKey) }.sumOf { it.amountRub }
    }
    val paidExpenseCount = remember(activeExpensePayments, currentMonthKey) {
        activeExpensePayments.count { it.isPaidInPeriod(currentMonthKey) }
    }
    val activeIncomeRub = remember(activePayments) {
        activePayments.filter { it.effectiveOperationType == OperationType.INCOME }.sumOf { it.amountRub }
    }

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingPayment by remember { mutableStateOf<RecurringPaymentEntity?>(null) }
    var deletingPayment by remember { mutableStateOf<RecurringPaymentEntity?>(null) }

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
                    .padding(bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header + Add button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Регулярные платежи",
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Подписки и обязательные платежи • $monthTitle",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            editingPayment = null
                            showEditorDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = palette.accentEmeraldSoft,
                            contentColor = palette.accentEmerald
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Добавить",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (visiblePayments.isEmpty()) {
                    EmptyRecurringPaymentsCard(
                        onAddClick = {
                            editingPayment = null
                            showEditorDialog = true
                        }
                    )
                } else {
                    RecurringSummaryCard(
                        totalExpenseRub = totalActiveExpenseRub,
                        paidExpenseRub = paidExpenseRub,
                        paidCount = paidExpenseCount,
                        totalExpenseCount = activeExpensePayments.size,
                        activeIncomeRub = activeIncomeRub
                    )

                    if (activePayments.isNotEmpty()) {
                        RecurringSectionHeader(
                            title = "Активные (${activePayments.size})",
                            subtitle = "Нажмите «Оплачено», если платёж в этом месяце уже прошёл"
                        )
                        AdaptiveRecurringGrid(
                            items = activePayments,
                            isWideScreen = isWideScreen
                        ) { payment ->
                            RecurringPaymentCard(
                                payment = payment,
                                currentDate = currentDate,
                                currentMonth = currentMonth,
                                currentMonthKey = currentMonthKey,
                                categoryRegistry = categoryRegistry,
                                isCompact = isCompactCover,
                                onTogglePaid = { paid -> onSetPaidForCurrentMonth(payment, paid) },
                                onToggleActive = { onToggleActive(payment, false) },
                                onEdit = {
                                    editingPayment = payment
                                    showEditorDialog = true
                                },
                                onDelete = { deletingPayment = payment }
                            )
                        }
                    }

                    if (pausedPayments.isNotEmpty()) {
                        RecurringSectionHeader(
                            title = "На паузе (${pausedPayments.size})",
                            subtitle = "Не создают операций и не учитываются в прогнозе"
                        )
                        AdaptiveRecurringGrid(
                            items = pausedPayments,
                            isWideScreen = isWideScreen
                        ) { payment ->
                            RecurringPaymentCard(
                                payment = payment,
                                currentDate = currentDate,
                                currentMonth = currentMonth,
                                currentMonthKey = currentMonthKey,
                                categoryRegistry = categoryRegistry,
                                isCompact = isCompactCover,
                                onTogglePaid = { paid -> onSetPaidForCurrentMonth(payment, paid) },
                                onToggleActive = { onToggleActive(payment, true) },
                                onEdit = {
                                    editingPayment = payment
                                    showEditorDialog = true
                                },
                                onDelete = { deletingPayment = payment }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditorDialog) {
        AddEditRecurringPaymentDialog(
            editingPayment = editingPayment,
            currentMonthKey = currentMonthKey,
            categoryRegistry = categoryRegistry,
            onDismiss = {
                showEditorDialog = false
                editingPayment = null
            },
            onSave = { saved ->
                onSavePayment(saved)
                showEditorDialog = false
                editingPayment = null
            }
        )
    }

    val pendingDelete = deletingPayment
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deletingPayment = null },
            containerColor = palette.surfaceCard,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = {
                Text(
                    text = "Удалить платёж?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "«${pendingDelete.title}» будет удалён с обоих телефонов. Уже добавленные в ленту операции останутся в истории.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePayment(pendingDelete)
                        deletingPayment = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accentCoral,
                        contentColor = Color.White
                    )
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPayment = null }) {
                    Text("Отмена", color = palette.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun RecurringSummaryCard(
    totalExpenseRub: Double,
    paidExpenseRub: Double,
    paidCount: Int,
    totalExpenseCount: Int,
    activeIncomeRub: Double
) {
    val palette = LocalFamilyPalette.current
    val remainingRub = (totalExpenseRub - paidExpenseRub).coerceAtLeast(0.0)
    val ratio = if (totalExpenseRub > 0.0) {
        (paidExpenseRub / totalExpenseRub).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, palette.outlineSubtle)
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
                    text = "Регулярные списания: ${Formatters.formatRub(totalExpenseRub, true)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (totalExpenseCount > 0) {
                    Text(
                        text = "$paidCount из $totalExpenseCount",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (paidCount == totalExpenseCount) palette.accentEmerald else palette.accentBlue
                    )
                }
            }

            if (totalExpenseCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.outlineSubtle)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (paidCount == totalExpenseCount) palette.accentEmerald else palette.accentBlue)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Оплачено: ${Formatters.formatRub(paidExpenseRub, true)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accentEmerald,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Ожидается: ${Formatters.formatRub(remainingRub, true)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (remainingRub > 0.0) palette.accentAmber else palette.textMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (activeIncomeRub > 0.0) {
                Text(
                    text = "Регулярные поступления: +${Formatters.formatRub(activeIncomeRub, true)}/мес",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accentEmerald
                )
            }
        }
    }
}

@Composable
private fun EmptyRecurringPaymentsCard(onAddClick: () -> Unit) {
    val palette = LocalFamilyPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(palette.accentEmeraldSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Repeat,
                    contentDescription = null,
                    tint = palette.accentEmerald,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = "Пока нет регулярных платежей",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Добавьте подписки, аренду, ЖКХ, кредиты или зарплату. Можно включить автодобавление на проверку в ленту без дублей на двух телефонах или оставить только для прогноза бюджета.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Button(
                onClick = onAddClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentEmerald,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Добавить платёж", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RecurringSectionHeader(
    title: String,
    subtitle: String
) {
    val palette = LocalFamilyPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}

@Composable
private fun <T> AdaptiveRecurringGrid(
    items: List<T>,
    isWideScreen: Boolean,
    itemContent: @Composable (T) -> Unit
) {
    if (!isWideScreen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                itemContent(item)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        itemContent(rowItems[0])
                    }
                    if (rowItems.size > 1) {
                        Box(modifier = Modifier.weight(1f)) {
                            itemContent(rowItems[1])
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurringPaymentCard(
    payment: RecurringPaymentEntity,
    currentDate: LocalDate,
    currentMonth: YearMonth,
    currentMonthKey: String,
    categoryRegistry: CategoryRegistry,
    isCompact: Boolean,
    onTogglePaid: (Boolean) -> Unit,
    onToggleActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val effectiveOp = payment.effectiveOperationType
    val isIncome = effectiveOp == OperationType.INCOME
    val isTransfer = effectiveOp == OperationType.TRANSFER
    val resolvedCategory = categoryRegistry.resolve(payment.categoryId)
    val effectiveDay = RecurringPaymentScheduler.effectiveDayOfMonth(payment.dayOfMonth, currentMonth)
    val isPaidThisMonth = payment.isPaidInPeriod(currentMonthKey)
    val isDueOrPast = currentDate.dayOfMonth >= effectiveDay

    val dayDescription = if (payment.dayOfMonth != effectiveDay) {
        "${payment.dayOfMonth}-е число (в этом мес. $effectiveDay-го)"
    } else {
        "Каждое $effectiveDay-е число"
    }

    val amountText = Formatters.formatRub(payment.amountRub, showKopecksIfAny = true)
    val displayAmount = if (isIncome) "+$amountText" else amountText
    val amountColor = when {
        !payment.isActive -> palette.textSecondary
        isIncome -> palette.accentEmerald
        isTransfer -> palette.accentBlue
        else -> palette.textPrimary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (payment.isActive) 1f else 0.78f),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(
            width = 1.dp,
            color = if (payment.isActive && isPaidThisMonth) {
                palette.accentEmerald.copy(alpha = 0.35f)
            } else {
                palette.outlineSubtle
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(if (isCompact) 11.dp else 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isTransfer) {
                    Box(
                        modifier = Modifier
                            .size(if (isCompact) 36.dp else 40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.accentBlueSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = "Перевод",
                            tint = palette.accentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    CategoryIconBadge(
                        category = resolvedCategory,
                        size = if (isCompact) 36.dp else 40.dp,
                        iconSize = if (isCompact) 18.dp else 20.dp,
                        cornerRadius = 12.dp
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = payment.title.ifBlank { "Регулярный платёж" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(dayDescription)
                            if (!isTransfer) {
                                append(" • ")
                                append(resolvedCategory.shortName)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = displayAmount,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
            }

            // Status & mode badges
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val (statusText, statusBg, statusFg, statusIcon) = when {
                    !payment.isActive -> Quadruple(
                        "На паузе",
                        palette.surfaceCard,
                        palette.textMuted,
                        Icons.Rounded.Pause
                    )
                    isPaidThisMonth -> Quadruple(
                        "Оплачено в этом месяце",
                        palette.accentEmeraldSoft,
                        palette.accentEmerald,
                        Icons.Rounded.CheckCircle
                    )
                    isDueOrPast -> Quadruple(
                        "Ожидается • срок наступил",
                        palette.accentAmberSoft,
                        palette.accentAmber,
                        Icons.Rounded.Schedule
                    )
                    else -> Quadruple(
                        "Ожидается",
                        palette.accentBlueSoft,
                        palette.accentBlue,
                        Icons.Rounded.Schedule
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusFg,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusFg
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = palette.surfaceCard,
                    border = BorderStroke(1.dp, palette.outlineSubtle)
                ) {
                    Text(
                        text = if (payment.autoCreateInFeed) "Авто в ленту" else "Только прогноз",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                if (isIncome || isTransfer) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isIncome) palette.accentEmeraldSoft else palette.accentBlueSoft
                    ) {
                        Text(
                            text = if (isIncome) "Доход" else "Перевод",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isIncome) palette.accentEmerald else palette.accentBlue,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (payment.isActive) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isPaidThisMonth) palette.surfaceCard else palette.accentEmeraldSoft,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isPaidThisMonth) palette.outlineSubtle else palette.accentEmerald.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onTogglePaid(!isPaidThisMonth) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = if (isPaidThisMonth) palette.textSecondary else palette.accentEmerald,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isPaidThisMonth) "Снять отметку" else "Оплачено",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPaidThisMonth) palette.textSecondary else palette.accentEmerald
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.surfaceCard,
                        border = BorderStroke(1.dp, palette.outlineSubtle),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onToggleActive)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (payment.isActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (payment.isActive) "Пауза" else "Возобновить",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Редактировать",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Удалить",
                            tint = palette.accentCoral,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddEditRecurringPaymentDialog(
    editingPayment: RecurringPaymentEntity?,
    currentMonthKey: String,
    categoryRegistry: CategoryRegistry,
    onDismiss: () -> Unit,
    onSave: (RecurringPaymentEntity) -> Unit
) {
    val palette = LocalFamilyPalette.current
    val initialOpType = editingPayment?.effectiveOperationType ?: OperationType.EXPENSE
    val initialCategoryId = editingPayment?.let { categoryRegistry.resolve(it.categoryId).id }
        ?: if (initialOpType == OperationType.INCOME) {
            categoryRegistry.defaultIncomeCategory.id
        } else {
            categoryRegistry.resolve("UTILITIES").id
        }

    var title by remember(editingPayment) { mutableStateOf(editingPayment?.title.orEmpty()) }
    var amountText by remember(editingPayment) {
        mutableStateOf(
            if (editingPayment != null && editingPayment.amountRub > 0.0) {
                AmountExpressionEvaluator.formatForInput(editingPayment.amountRub)
            } else {
                ""
            }
        )
    }
    var operationType by remember(editingPayment) { mutableStateOf(initialOpType) }
    var dayOfMonth by remember(editingPayment) {
        mutableIntStateOf((editingPayment?.dayOfMonth ?: LocalDate.now().dayOfMonth).coerceIn(1, 31))
    }
    var selectedCategoryId by remember(editingPayment) { mutableStateOf(initialCategoryId) }
    var autoCreateInFeed by remember(editingPayment) {
        mutableStateOf(editingPayment?.autoCreateInFeed ?: true)
    }
    var paidInCurrentMonth by remember(editingPayment, currentMonthKey) {
        mutableStateOf(editingPayment?.isPaidInPeriod(currentMonthKey) ?: false)
    }

    val categoriesForType: List<CategoryDescriptor> = remember(operationType, categoryRegistry) {
        when (operationType) {
            OperationType.INCOME -> categoryRegistry.incomeCategories
            OperationType.EXPENSE -> categoryRegistry.expenseCategories
            OperationType.TRANSFER -> emptyList()
        }
    }

    val parsedAmount = remember(amountText) {
        amountText.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }
    val canSave = title.trim().isNotEmpty() && parsedAmount != null && dayOfMonth in 1..31

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceCard,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textPrimary,
        title = {
            Text(
                text = if (editingPayment == null) "Новый регулярный платёж" else "Редактировать платёж",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Operation type switcher
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        listOf(
                            OperationType.EXPENSE to "Расход",
                            OperationType.INCOME to "Доход",
                            OperationType.TRANSFER to "Перевод"
                        ).forEach { (op, label) ->
                            val selected = operationType == op
                            val accent = when (op) {
                                OperationType.EXPENSE -> palette.accentCoral
                                OperationType.INCOME -> palette.accentEmerald
                                OperationType.TRANSFER -> palette.accentBlue
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        operationType = op
                                        when (op) {
                                            OperationType.EXPENSE -> {
                                                if (categoryRegistry.resolve(selectedCategoryId).isIncome) {
                                                    selectedCategoryId = categoryRegistry.defaultExpenseCategory.id
                                                }
                                            }
                                            OperationType.INCOME -> {
                                                if (!categoryRegistry.resolve(selectedCategoryId).isIncome) {
                                                    selectedCategoryId = categoryRegistry.defaultIncomeCategory.id
                                                }
                                            }
                                            OperationType.TRANSFER -> Unit
                                        }
                                    },
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) palette.surfaceCard else Color.Transparent
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) accent else palette.textSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(48) },
                    label = { Text("Название (напр. Яндекс Плюс, Аренда)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        amountText = sanitizeRecurringAmountInput(raw)
                    },
                    label = { Text("Сумма (₽)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    suffix = { Text("₽", color = palette.textSecondary) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Day of month stepper & quick chips
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "День месяца",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = palette.surfaceElevated,
                                border = BorderStroke(1.dp, palette.outlineSubtle),
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        dayOfMonth = if (dayOfMonth > 1) dayOfMonth - 1 else 31
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Remove,
                                        contentDescription = "Меньше",
                                        tint = palette.textPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = "$dayOfMonth-е",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.accentEmerald,
                                modifier = Modifier.widthIn(min = 38.dp),
                                textAlign = TextAlign.Center
                            )
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = palette.surfaceElevated,
                                border = BorderStroke(1.dp, palette.outlineSubtle),
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        dayOfMonth = if (dayOfMonth < 31) dayOfMonth + 1 else 1
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "Больше",
                                        tint = palette.textPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        QUICK_DAYS_OF_MONTH.forEach { day ->
                            val selected = dayOfMonth == day
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) palette.accentEmeraldSoft else palette.surfaceElevated,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (selected) palette.accentEmerald else palette.outlineSubtle
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { dayOfMonth = day }
                            ) {
                                Text(
                                    text = "$day",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) palette.accentEmerald else palette.textSecondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (dayOfMonth >= 29) {
                        Text(
                            text = "В коротких месяцах (например, в феврале) сработает в последний день месяца.",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textMuted
                        )
                    }
                }

                // Category picker
                if (operationType != OperationType.TRANSFER && categoriesForType.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Категория",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(categoriesForType, key = { it.id }) { cat ->
                                val vis = cat.visual()
                                val tint = if (palette.isDark) vis.darkModeColor else vis.color
                                val selected = selectedCategoryId == cat.id
                                Surface(
                                    shape = RoundedCornerShape(11.dp),
                                    color = if (selected) tint.copy(alpha = 0.16f) else palette.surfaceElevated,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (selected) tint else palette.outlineSubtle
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(11.dp))
                                        .clickable { selectedCategoryId = cat.id }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        CategorySymbol(
                                            visual = vis,
                                            contentDescription = null,
                                            tint = if (selected) tint else palette.textSecondary,
                                            iconSize = 15.dp
                                        )
                                        Text(
                                            text = cat.shortName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (selected) tint else palette.textSecondary,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Auto-create in feed switch
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.outlineSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Создавать в ленте на проверку",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = if (autoCreateInFeed) {
                                    "В день оплаты появится карточка подтверждения"
                                } else {
                                    "Только для прогноза остатка и ручной отметки"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = autoCreateInFeed,
                            onCheckedChange = { autoCreateInFeed = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = palette.accentEmerald
                            )
                        )
                    }
                }

                // Paid this month switch
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceElevated,
                    border = BorderStroke(1.dp, palette.outlineSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Оплачено в этом месяце",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "Пропустить автосоздание в текущем месяце",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = paidInCurrentMonth,
                            onCheckedChange = { paidInCurrentMonth = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = palette.accentEmerald
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val amount = parsedAmount ?: return@Button
                    val nextPeriodKey = when {
                        paidInCurrentMonth -> currentMonthKey
                        editingPayment?.lastPaidPeriodKey == currentMonthKey -> ""
                        else -> editingPayment?.lastPaidPeriodKey.orEmpty()
                    }
                    val targetCategoryId = if (operationType == OperationType.TRANSFER) {
                        categoryRegistry.otherExpenseCategory.id
                    } else {
                        selectedCategoryId
                    }
                    val result = RecurringPaymentEntity(
                        id = editingPayment?.id ?: UUID.randomUUID().toString(),
                        title = title.trim(),
                        amountRub = amount,
                        categoryId = targetCategoryId,
                        operationType = operationType.name,
                        dayOfMonth = dayOfMonth.coerceIn(1, 31),
                        autoCreateInFeed = autoCreateInFeed,
                        lastPaidPeriodKey = nextPeriodKey,
                        isActive = editingPayment?.isActive ?: true,
                        isDeleted = false,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    onSave(result)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentEmerald,
                    contentColor = Color.White
                )
            ) {
                Text(if (editingPayment == null) "Добавить" else "Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = palette.textSecondary)
            }
        }
    )
}

private fun sanitizeRecurringAmountInput(raw: String): String {
    val sb = StringBuilder()
    var hasSeparator = false
    for (ch in raw) {
        if (ch.isDigit()) {
            sb.append(ch)
        } else if ((ch == '.' || ch == ',') && !hasSeparator) {
            sb.append('.')
            hasSeparator = true
        }
    }
    return sb.toString().take(12)
}
