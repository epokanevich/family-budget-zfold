package ru.pokanevich.familybudget.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.ui.theme.CategoryIconBadge
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.util.Formatters
import java.util.UUID
import kotlin.math.roundToInt

private val CURATED_EMOJIS = listOf(
    "📚", "💄", "✈️", "🏋️", "🎬", "🎵",
    "🛠️", "🛋️", "🧴", "🪴", "🐕", "🎂",
    "🚕", "⛽", "🏥", "💊", "👕", "👟",
    "📱", "💻", "🎓", "🧾", "💼", "🪙",
    "💎", "🏡", "🧺", "🍕"
)

private val CURATED_COLORS_HEX = listOf(
    0xFF3A7DF0L,
    0xFF9368CEL,
    0xFF2FA393L,
    0xFF569E39L,
    0xFFB85605L,
    0xFFE53935L,
    0xFF1A9AB8L,
    0xFFD61980L,
    0xFF9EA800L,
    0xFF059669L,
    0xFF1E4F99L,
    0xFF64748BL
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesAndLimitsSheet(
    categoryRegistry: CategoryRegistry,
    monthlyBudgetRub: Double,
    onSaveCategories: (List<CategoryDescriptor>) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var draftCategories by remember(categoryRegistry) {
        mutableStateOf(categoryRegistry.allCategories)
    }
    var limitInputTexts by remember(categoryRegistry) {
        mutableStateOf(
            categoryRegistry.allCategories.associate { cat ->
                cat.id to formatLimitInput(cat.limitValue)
            }
        )
    }
    var showAddDialog by remember { mutableStateOf(false) }

    val activeExpenses = remember(draftCategories) {
        draftCategories.filter { !it.isIncome && !it.isArchived }
    }
    val activeIncomes = remember(draftCategories) {
        draftCategories.filter { it.isIncome && !it.isArchived }
    }
    val archivedCategories = remember(draftCategories) {
        draftCategories.filter { it.isArchived }
    }

    val allocatedRub = remember(activeExpenses, monthlyBudgetRub) {
        activeExpenses.sumOf { cat ->
            when (cat.limitMode) {
                CategoryLimitMode.PERCENT -> monthlyBudgetRub * (cat.limitValue / 100.0)
                CategoryLimitMode.FIXED_RUB -> cat.limitValue
            }
        }
    }

    fun updateCategory(id: String, transform: (CategoryDescriptor) -> CategoryDescriptor) {
        draftCategories = draftCategories.map { if (it.id == id) transform(it) else it }
    }

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
                    Text(
                        text = "Категории и ориентиры",
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { showAddDialog = true },
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
                            text = if (isCompactCover) "Добавить" else "Добавить категорию",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Allocation indicator card
                AllocationIndicatorCard(
                    allocatedRub = allocatedRub,
                    monthlyBudgetRub = monthlyBudgetRub
                )

                // Active expense categories
                SectionHeader(
                    title = "Категории расходов (${activeExpenses.size})",
                    subtitle = "Настройте ориентир в ₽ или % от месячного бюджета"
                )

                AdaptiveCategoryGrid(
                    items = activeExpenses,
                    isWideScreen = isWideScreen
                ) { cat ->
                    ExpenseCategoryLimitCard(
                        category = cat,
                        limitText = limitInputTexts[cat.id] ?: formatLimitInput(cat.limitValue),
                        monthlyBudgetRub = monthlyBudgetRub,
                        isCompact = isCompactCover,
                        onLimitTextChange = { raw ->
                            val sanitized = sanitizeNumericInput(raw)
                            limitInputTexts = limitInputTexts + (cat.id to sanitized)
                            val parsed = sanitized.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                            updateCategory(cat.id) { it.copy(limitValue = parsed) }
                        },
                        onLimitModeChange = { newMode ->
                            if (newMode != cat.limitMode) {
                                val convertedValue = convertLimitValue(
                                    currentValue = cat.limitValue,
                                    fromMode = cat.limitMode,
                                    toMode = newMode,
                                    monthlyBudgetRub = monthlyBudgetRub
                                )
                                limitInputTexts = limitInputTexts + (cat.id to formatLimitInput(convertedValue))
                                updateCategory(cat.id) {
                                    it.copy(limitMode = newMode, limitValue = convertedValue)
                                }
                            }
                        },
                        onArchive = {
                            updateCategory(cat.id) { it.copy(isArchived = true) }
                        }
                    )
                }

                // Active income categories
                SectionHeader(
                    title = "Категории доходов (${activeIncomes.size})",
                    subtitle = "Используются при добавлении поступлений"
                )

                AdaptiveCategoryGrid(
                    items = activeIncomes,
                    isWideScreen = isWideScreen
                ) { cat ->
                    IncomeCategoryCard(
                        category = cat,
                        onArchive = {
                            updateCategory(cat.id) { it.copy(isArchived = true) }
                        }
                    )
                }

                // Archived categories
                if (archivedCategories.isNotEmpty()) {
                    SectionHeader(
                        title = "Архив (${archivedCategories.size})",
                        subtitle = "Скрыты из пикера новых операций, но сохранены в истории и отчётах"
                    )

                    AdaptiveCategoryGrid(
                        items = archivedCategories,
                        isWideScreen = isWideScreen
                    ) { cat ->
                        ArchivedCategoryCard(
                            category = cat,
                            onRestore = {
                                updateCategory(cat.id) { it.copy(isArchived = false) }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        onSaveCategories(draftCategories)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accentEmerald,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Сохранить изменения",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddCategoryDialog(
            monthlyBudgetRub = monthlyBudgetRub,
            onDismiss = { showAddDialog = false },
            onAdd = { newCategory ->
                draftCategories = draftCategories + newCategory
                limitInputTexts = limitInputTexts + (newCategory.id to formatLimitInput(newCategory.limitValue))
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AllocationIndicatorCard(
    allocatedRub: Double,
    monthlyBudgetRub: Double
) {
    val palette = LocalFamilyPalette.current
    val isOverAllocated = monthlyBudgetRub > 0.0 && allocatedRub > monthlyBudgetRub * 1.001
    val ratio = if (monthlyBudgetRub > 0.0) {
        (allocatedRub / monthlyBudgetRub).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val pct = if (monthlyBudgetRub > 0.0) {
        ((allocatedRub / monthlyBudgetRub) * 100.0).roundToInt()
    } else {
        0
    }
    val accent = if (isOverAllocated) palette.accentCoral else palette.accentEmerald

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, if (isOverAllocated) palette.accentCoral.copy(alpha = 0.45f) else palette.outlineSubtle)
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
                    text = "Распределено по ориентирам: ${Formatters.formatRub(allocatedRub)} из ${Formatters.formatRub(monthlyBudgetRub)} бюджета",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }

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
                        .background(accent)
                )
            }

            if (isOverAllocated) {
                Text(
                    text = "Сумма ориентиров превышает месячный бюджет на ${Formatters.formatRub(allocatedRub - monthlyBudgetRub)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accentCoral
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
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
private fun <T> AdaptiveCategoryGrid(
    items: List<T>,
    isWideScreen: Boolean,
    itemContent: @Composable (T) -> Unit
) {
    if (!isWideScreen) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun ExpenseCategoryLimitCard(
    category: CategoryDescriptor,
    limitText: String,
    monthlyBudgetRub: Double,
    isCompact: Boolean,
    onLimitTextChange: (String) -> Unit,
    onLimitModeChange: (CategoryLimitMode) -> Unit,
    onArchive: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val equivalentHint = when (category.limitMode) {
        CategoryLimitMode.PERCENT -> {
            val rub = monthlyBudgetRub * (category.limitValue / 100.0)
            "≈ ${Formatters.formatRub(rub)}/мес"
        }
        CategoryLimitMode.FIXED_RUB -> {
            val pct = if (monthlyBudgetRub > 0.0) {
                ((category.limitValue / monthlyBudgetRub) * 100.0).roundToInt()
            } else 0
            "≈ $pct% бюджета"
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier.padding(if (isCompact) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryIconBadge(
                    category = category,
                    size = if (isCompact) 34.dp else 38.dp,
                    iconSize = if (isCompact) 17.dp else 19.dp,
                    cornerRadius = 11.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = equivalentHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }
                if (!category.isBuiltIn) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onArchive),
                        shape = RoundedCornerShape(10.dp),
                        color = palette.surfaceCard,
                        border = BorderStroke(1.dp, palette.outlineSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Archive,
                                contentDescription = "В архив",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            if (!isCompact) {
                                Text(
                                    text = "В архив",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LimitModeToggle(
                    selectedMode = category.limitMode,
                    onSelect = onLimitModeChange
                )
                OutlinedTextField(
                    value = limitText,
                    onValueChange = onLimitTextChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    suffix = {
                        Text(
                            text = if (category.limitMode == CategoryLimitMode.PERCENT) "%" else "₽",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle,
                        focusedContainerColor = palette.surfaceCard,
                        unfocusedContainerColor = palette.surfaceCard
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun IncomeCategoryCard(
    category: CategoryDescriptor,
    onArchive: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryIconBadge(
                category = category,
                size = 36.dp,
                iconSize = 18.dp,
                cornerRadius = 11.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (category.isBuiltIn) "Базовая категория дохода" else "Пользовательская категория",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
            }
            if (!category.isBuiltIn) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onArchive),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.surfaceCard,
                    border = BorderStroke(1.dp, palette.outlineSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Archive,
                            contentDescription = "В архив",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "В архив",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedCategoryCard(
    category: CategoryDescriptor,
    onRestore: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceElevated.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.alpha(0.65f)) {
                CategoryIconBadge(
                    category = category,
                    size = 36.dp,
                    iconSize = 18.dp,
                    cornerRadius = 11.dp
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (category.isIncome) "Архив • Доход" else "Архив • Расход",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textMuted
                )
            }
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRestore),
                shape = RoundedCornerShape(10.dp),
                color = palette.accentEmeraldSoft,
                border = BorderStroke(1.dp, palette.accentEmerald.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Unarchive,
                        contentDescription = "Восстановить",
                        tint = palette.accentEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Восстановить",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accentEmerald
                    )
                }
            }
        }
    }
}

@Composable
private fun LimitModeToggle(
    selectedMode: CategoryLimitMode,
    onSelect: (CategoryLimitMode) -> Unit
) {
    val palette = LocalFamilyPalette.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            listOf(
                CategoryLimitMode.PERCENT to "%",
                CategoryLimitMode.FIXED_RUB to "₽"
            ).forEach { (mode, label) ->
                val selected = selectedMode == mode
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelect(mode) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) palette.accentEmeraldSoft else Color.Transparent
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) palette.accentEmerald else palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCategoryDialog(
    monthlyBudgetRub: Double,
    onDismiss: () -> Unit,
    onAdd: (CategoryDescriptor) -> Unit
) {
    val palette = LocalFamilyPalette.current
    var name by remember { mutableStateOf("") }
    var isIncome by remember { mutableStateOf(false) }
    var selectedEmoji by remember { mutableStateOf(CURATED_EMOJIS.first()) }
    var selectedColorHex by remember { mutableStateOf(CURATED_COLORS_HEX.first()) }
    var limitMode by remember { mutableStateOf(CategoryLimitMode.PERCENT) }
    var limitText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceCard,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textPrimary,
        title = {
            Text(
                text = "Новая категория",
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Название категории") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accentEmerald,
                        unfocusedBorderColor = palette.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Type selector: Расход / Доход
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(false to "Расход", true to "Доход").forEach { (incomeFlag, label) ->
                        val selected = isIncome == incomeFlag
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { isIncome = incomeFlag },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) palette.accentEmeraldSoft else palette.surfaceElevated,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (selected) palette.accentEmerald else palette.outlineSubtle
                            )
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) palette.accentEmerald else palette.textSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }

                // Emoji picker
                Text(
                    text = "Иконка",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CURATED_EMOJIS.forEach { emoji ->
                        val selected = selectedEmoji == emoji
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) palette.accentEmeraldSoft else palette.surfaceElevated)
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) palette.accentEmerald else palette.outlineSubtle,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedEmoji = emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 18.sp)
                        }
                    }
                }

                // Color picker
                Text(
                    text = "Цвет",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CURATED_COLORS_HEX.forEach { hex ->
                        val selected = selectedColorHex == hex
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selected) 2.5.dp else 0.dp,
                                    color = if (selected) palette.textPrimary else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColorHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Optional limit for expense categories
                if (!isIncome) {
                    Text(
                        text = "Ориентир расходов (необязательно)",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LimitModeToggle(
                            selectedMode = limitMode,
                            onSelect = { newMode ->
                                if (newMode != limitMode) {
                                    val currentVal = limitText.replace(',', '.').toDoubleOrNull() ?: 0.0
                                    val converted = convertLimitValue(currentVal, limitMode, newMode, monthlyBudgetRub)
                                    limitMode = newMode
                                    limitText = if (converted > 0.0) formatLimitInput(converted) else ""
                                }
                            }
                        )
                        OutlinedTextField(
                            value = limitText,
                            onValueChange = { limitText = sanitizeNumericInput(it) },
                            placeholder = { Text("0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            suffix = {
                                Text(
                                    text = if (limitMode == CategoryLimitMode.PERCENT) "%" else "₽",
                                    color = palette.textSecondary
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = palette.accentEmerald,
                                unfocusedBorderColor = palette.outlineSubtle
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            val trimmedName = name.trim()
            Button(
                enabled = trimmedName.isNotEmpty(),
                onClick = {
                    val parsedLimit = if (isIncome) {
                        0.0
                    } else {
                        limitText.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    }
                    val id = "custom_${UUID.randomUUID().toString().replace("-", "").take(8)}"
                    onAdd(
                        CategoryDescriptor(
                            id = id,
                            displayName = trimmedName,
                            shortName = trimmedName.take(12),
                            emoji = selectedEmoji,
                            colorHex = selectedColorHex,
                            isIncome = isIncome,
                            isBuiltIn = false,
                            limitMode = if (isIncome) CategoryLimitMode.PERCENT else limitMode,
                            limitValue = parsedLimit,
                            isArchived = false,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accentEmerald,
                    contentColor = Color.White
                )
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = palette.textSecondary)
            }
        }
    )
}

private fun sanitizeNumericInput(raw: String): String {
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
    return sb.toString().take(9)
}

private fun formatLimitInput(value: Double): String {
    if (!value.isFinite() || value <= 0.0) return "0"
    val roundedInt = value.roundToInt()
    return if (kotlin.math.abs(value - roundedInt.toDouble()) < 0.05) {
        roundedInt.toString()
    } else {
        val oneDecimal = (value * 10.0).roundToInt() / 10.0
        oneDecimal.toString()
    }
}

private fun convertLimitValue(
    currentValue: Double,
    fromMode: CategoryLimitMode,
    toMode: CategoryLimitMode,
    monthlyBudgetRub: Double
): Double {
    if (currentValue <= 0.0 || monthlyBudgetRub <= 0.0) return 0.0
    return when {
        fromMode == CategoryLimitMode.PERCENT && toMode == CategoryLimitMode.FIXED_RUB ->
            (monthlyBudgetRub * (currentValue / 100.0)).roundToInt().toDouble()
        fromMode == CategoryLimitMode.FIXED_RUB && toMode == CategoryLimitMode.PERCENT ->
            ((currentValue / monthlyBudgetRub) * 1000.0).roundToInt() / 10.0
        else -> currentValue
    }
}
