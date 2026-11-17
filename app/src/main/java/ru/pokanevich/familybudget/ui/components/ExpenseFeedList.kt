package ru.pokanevich.familybudget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.pokanevich.familybudget.ai.AiBudgetReport
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.ui.theme.CategoryIconBadge
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.Formatters
import ru.pokanevich.familybudget.ui.util.PeriodWindow

enum class FeedFilter(val label: String) {
    ALL("Все"),
    EXPENSES("Расходы"),
    INCOMES("Доходы"),
    EUGENE("Евгений"),
    ANZHELIKA("Анжелика")
}

@Composable
fun PeriodSwitcherBar(
    window: PeriodWindow,
    onSelectPeriod: (BudgetPeriod) -> Unit,
    onPrevPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onJumpToCurrent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // День | Неделя | Месяц | Год | Всё
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = palette.surfaceCard,
            border = BorderStroke(1.dp, palette.outlineSubtle)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BudgetPeriod.entries.forEach { period ->
                    val selected = window.period == period
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectPeriod(period) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) palette.accentEmeraldSoft else Color.Transparent
                    ) {
                        Text(
                            text = period.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) palette.accentEmerald else palette.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp)
                        )
                    }
                }
            }
        }

        if (window.period != BudgetPeriod.ALL) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surfaceCard,
                    border = BorderStroke(1.dp, palette.outlineSubtle)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = onPrevPeriod,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronLeft,
                                contentDescription = "Предыдущий период",
                                tint = palette.textPrimary
                            )
                        }

                        Text(
                            text = window.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )

                        IconButton(
                            onClick = onNextPeriod,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Следующий период",
                                tint = palette.textPrimary
                            )
                        }
                    }
                }

                if (!window.isCurrentPeriod) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = palette.accentEmeraldSoft,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onJumpToCurrent)
                    ) {
                        Text(
                            text = "Сейчас",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accentEmerald,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExpensesFeedPane(
    confirmedExpenses: List<ExpenseEntity>,
    pendingPushExpenses: List<ExpenseEntity>,
    report: AiBudgetReport,
    window: PeriodWindow,
    showHeroInline: Boolean,
    isPushAccessGranted: Boolean,
    isBatteryUnrestricted: Boolean,
    onSelectPeriod: (BudgetPeriod) -> Unit,
    onPrevPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onJumpToCurrent: () -> Unit,
    onOpenPushSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onConfirmPending: (ExpenseEntity, String) -> Unit,
    onDismissPending: (ExpenseEntity) -> Unit,
    onExpenseClick: (ExpenseEntity) -> Unit,
    onAddClick: () -> Unit,
    registry: CategoryRegistry = CategoryRegistry.DEFAULT
) {
    val palette = LocalFamilyPalette.current
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(FeedFilter.ALL) }
    var selectedCategoryFilterId by remember { mutableStateOf<String?>(null) }

    val periodExpenses = remember(confirmedExpenses, window) {
        if (window.period == BudgetPeriod.ALL) {
            confirmedExpenses
        } else {
            confirmedExpenses.filter { it.createdAtEpochMs in window.startMs..window.endMs }
        }
    }

    val filteredExpenses = remember(periodExpenses, searchQuery, activeFilter, selectedCategoryFilterId, registry) {
        val q = searchQuery.trim().lowercase()
        periodExpenses.filter { exp ->
            val resolvedCategory = registry.resolve(exp.categoryName)
            val isInc = exp.isIncome(registry)
            val isTr = exp.isTransfer(registry)
            val matchesQuery = q.isEmpty() ||
                exp.merchant.lowercase().contains(q) ||
                resolvedCategory.displayName.lowercase().contains(q) ||
                exp.note.lowercase().contains(q)
            val matchesFilter = when (activeFilter) {
                FeedFilter.ALL -> true
                FeedFilter.EXPENSES -> !isInc && !isTr
                FeedFilter.INCOMES -> isInc
                FeedFilter.EUGENE -> exp.authorName.equals("Евгений", ignoreCase = true)
                FeedFilter.ANZHELIKA -> exp.authorName.equals("Анжелика", ignoreCase = true)
            }
            val matchesCategory = selectedCategoryFilterId == null || resolvedCategory.id == selectedCategoryFilterId
            matchesQuery && matchesFilter && matchesCategory
        }
    }

    val groupedByDay = remember(filteredExpenses, registry) {
        filteredExpenses
            .groupBy { Formatters.dayKey(it.createdAtEpochMs) }
            .entries
            .sortedByDescending { it.key }
            .map { entry ->
                val dayExpenseSum = entry.value.filter { !it.isIncome(registry) && !it.isTransfer(registry) }.sumOf { it.amountRub }
                val dayIncomeSum = entry.value.filter { it.isIncome(registry) }.sumOf { it.amountRub }
                DayExpenseGroup(
                    dayKey = entry.key,
                    headerTitle = Formatters.formatDayHeader(entry.value.first().createdAtEpochMs),
                    dayExpenseTotal = dayExpenseSum,
                    dayIncomeTotal = dayIncomeSum,
                    items = entry.value.sortedByDescending { it.createdAtEpochMs }
                )
            }
    }

    val activeCategories = remember(periodExpenses, registry) {
        periodExpenses.map { registry.resolve(it.categoryName) }.distinctBy { it.id }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
    ) {
        item {
            PeriodSwitcherBar(
                window = window,
                onSelectPeriod = onSelectPeriod,
                onPrevPeriod = onPrevPeriod,
                onNextPeriod = onNextPeriod,
                onJumpToCurrent = onJumpToCurrent
            )
        }

        if (showHeroInline) {
            item {
                HeroBudgetCard(report = report)
            }
        }

        if (!isPushAccessGranted) {
            item {
                PushPermissionBanner(onOpenPushSettings = onOpenPushSettings)
            }
        } else if (!isBatteryUnrestricted) {
            item {
                BatteryExemptionBanner(onRequestBatteryExemption = onRequestBatteryExemption)
            }
        }

        if (pendingPushExpenses.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Новые из банка",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.textPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(palette.accentBlueSoft)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${pendingPushExpenses.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accentBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            items(pendingPushExpenses, key = { "pending_${it.id}" }) { pending ->
                PendingPushCard(
                    expense = pending,
                    onConfirm = { catId -> onConfirmPending(pending, catId) },
                    onDismiss = { onDismissPending(pending) },
                    registry = registry
                )
            }
        }

        // Search & Quick Filter Bar
        if (periodExpenses.isNotEmpty() || searchQuery.isNotBlank()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchInputPill(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it }
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(FeedFilter.entries) { filter ->
                            val selected = activeFilter == filter && selectedCategoryFilterId == null
                            FilterPillChip(
                                label = filter.label,
                                selected = selected,
                                accentColor = when (filter) {
                                    FeedFilter.EUGENE -> palette.eugeneColor
                                    FeedFilter.ANZHELIKA -> palette.anzhelikaColor
                                    FeedFilter.INCOMES -> palette.accentEmerald
                                    FeedFilter.EXPENSES -> palette.accentCoral
                                    FeedFilter.ALL -> palette.accentEmerald
                                },
                                onClick = {
                                    activeFilter = filter
                                    if (filter == FeedFilter.ALL) {
                                        selectedCategoryFilterId = null
                                    }
                                }
                            )
                        }

                        items(activeCategories, key = { it.id }) { cat ->
                            val selected = selectedCategoryFilterId == cat.id
                            FilterPillChip(
                                label = cat.shortName,
                                selected = selected,
                                accentColor = palette.accentEmerald,
                                onClick = {
                                    selectedCategoryFilterId = if (selected) null else cat.id
                                }
                            )
                        }
                    }
                }
            }
        }

        if (groupedByDay.isEmpty()) {
            item {
                EmptyFeedCard(
                    hasPeriodExpenses = periodExpenses.isNotEmpty(),
                    onResetFilters = {
                        searchQuery = ""
                        activeFilter = FeedFilter.ALL
                        selectedCategoryFilterId = null
                    },
                    onAddClick = onAddClick
                )
            }
        } else {
            groupedByDay.forEach { group ->
                item(key = "header_${group.dayKey}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = group.headerTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textSecondary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (group.dayIncomeTotal > 0.0) {
                                Text(
                                    text = "+${Formatters.formatRub(group.dayIncomeTotal, true)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.accentEmerald,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (group.dayExpenseTotal > 0.0) {
                                Text(
                                    text = Formatters.formatRub(group.dayExpenseTotal, true),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textMuted,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                item(key = "card_${group.dayKey}") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = palette.surfaceCard,
                        border = BorderStroke(1.dp, palette.outlineSubtle)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            group.items.forEachIndexed { index, expense ->
                                ExpenseTransactionRow(
                                    expense = expense,
                                    resolvedCategory = registry.resolve(expense.categoryName),
                                    isIncome = expense.isIncome(registry),
                                    isTransfer = expense.isTransfer(registry),
                                    onClick = { onExpenseClick(expense) }
                                )
                                if (index < group.items.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 68.dp, end = 14.dp),
                                        thickness = 0.7.dp,
                                        color = palette.outlineSubtle
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DayExpenseGroup(
    val dayKey: Long,
    val headerTitle: String,
    val dayExpenseTotal: Double,
    val dayIncomeTotal: Double,
    val items: List<ExpenseEntity>
)

@Composable
private fun ExpenseTransactionRow(
    expense: ExpenseEntity,
    resolvedCategory: CategoryDescriptor,
    isIncome: Boolean,
    isTransfer: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val isAnzhelika = expense.authorName.equals("Анжелика", ignoreCase = true)
    val authorColor = if (isAnzhelika) palette.anzhelikaColor else palette.eugeneColor

    val sourceTag = when (expense.sourceType) {
        "PUSH_SBER" -> "Сбер"
        "PUSH_MIRPAY" -> "Mir Pay"
        "QR_RECEIPT" -> "Чек ФНС"
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CategoryIconBadge(
            category = resolvedCategory,
            size = 42.dp,
            iconSize = 20.dp,
            cornerRadius = 13.dp
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = expense.merchant,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (expense.note.isNotBlank()) {
                Text(
                    text = expense.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(authorColor)
                )
                Text(
                    text = expense.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textMuted
                )
                if (isTransfer) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.surfaceElevated)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "Перевод",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Text(
                        text = resolvedCategory.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (expense.splitGroupId.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.accentBlueSoft)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "Split",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.accentBlue
                        )
                    }
                }
                if (sourceTag != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.surfaceElevated)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = sourceTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            val formatted = Formatters.formatRub(expense.amountRub, showKopecksIfAny = true)
            Text(
                text = if (isIncome) "+$formatted" else formatted,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    isIncome -> palette.accentEmerald
                    isTransfer -> palette.textSecondary
                    else -> palette.textPrimary
                }
            )
            Text(
                text = Formatters.formatTimeOnly(expense.createdAtEpochMs),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted
            )
        }
    }
}

@Composable
private fun SearchInputPill(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val palette = LocalFamilyPalette.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.size(18.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Поиск по магазину, категории или заметке...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textMuted
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.textPrimary),
                    cursorBrush = SolidColor(palette.accentEmerald),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Очистить поиск",
                    tint = palette.textMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") }
                )
            }
        }
    }
}

@Composable
private fun FilterPillChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) accentColor.copy(alpha = 0.14f) else palette.surfaceCard,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) accentColor else palette.outlineSubtle
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accentColor else palette.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EmptyFeedCard(
    hasPeriodExpenses: Boolean,
    onResetFilters: () -> Unit,
    onAddClick: () -> Unit
) {
    val palette = LocalFamilyPalette.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.accentEmeraldSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                    tint = palette.accentEmerald,
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = if (hasPeriodExpenses) "Ничего не найдено" else "За этот период операций нет",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary
            )

            Text(
                text = if (hasPeriodExpenses) {
                    "Попробуйте изменить поисковый запрос или сбросить фильтр."
                } else {
                    "Добавьте операцию вручную, отсканируйте QR-код чека ФНС или переключите период выше."
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = if (hasPeriodExpenses) onResetFilters else onAddClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentEmerald,
                    contentColor = Color.White
                )
            ) {
                if (!hasPeriodExpenses) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (hasPeriodExpenses) "Сбросить фильтры" else "Добавить операцию",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
