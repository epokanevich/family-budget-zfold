package ru.pokanevich.familybudget.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.ai.AiBudgetReport
import ru.pokanevich.familybudget.ai.CategoryDiagnosis
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import ru.pokanevich.familybudget.data.SavingsGoalProgress
import ru.pokanevich.familybudget.ui.sheets.GoalTransferDialog
import ru.pokanevich.familybudget.ui.sheets.SavingsGoalEditorDialog
import ru.pokanevich.familybudget.ui.theme.AuthorAvatarCircle
import ru.pokanevich.familybudget.ui.theme.CategoryIconBadge
import ru.pokanevich.familybudget.ui.theme.LocalFamilyPalette
import ru.pokanevich.familybudget.ui.theme.visual
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.Formatters
import ru.pokanevich.familybudget.ui.util.PeriodWindow
import java.time.YearMonth
import kotlin.math.roundToInt

private data class ChartSlice(
    val category: CategoryDescriptor,
    val amountRub: Double,
    val count: Int
)

@Composable
fun AiAnalyticsPane(
    report: AiBudgetReport,
    geminiApiKey: String,
    window: PeriodWindow,
    showPeriodSwitcher: Boolean,
    showHeroAtTop: Boolean,
    onSelectPeriod: (BudgetPeriod) -> Unit,
    onPrevPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onJumpToCurrent: () -> Unit,
    onOpenSettings: () -> Unit,
    recurringPayments: List<RecurringPaymentEntity> = emptyList(),
    onOpenRecurringPayments: (() -> Unit)? = null,
    goalProgressList: List<SavingsGoalProgress> = emptyList(),
    isUnfoldedFoldable: Boolean = false,
    currentUserName: String = "Евгений",
    onOpenSavingsGoals: (() -> Unit)? = null,
    onSaveSavingsGoal: ((SavingsGoalEntity) -> Unit)? = null,
    onRecordGoalTransfer: ((SavingsGoalEntity, Double, Boolean, String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    val scope = rememberCoroutineScope()
    var cloudAdvice by remember { mutableStateOf<String?>(null) }
    var cloudModelUsed by remember { mutableStateOf<String?>(null) }
    var isLoadingCloudAi by remember { mutableStateOf(false) }
    var selectedChartCategoryId by remember { mutableStateOf<String?>(null) }
    var showIncomesInChart by remember { mutableStateOf(false) }
    var showNewGoalDialog by remember { mutableStateOf(false) }
    var quickGoalTransfer by remember { mutableStateOf<Pair<SavingsGoalProgress, Boolean>?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showPeriodSwitcher) {
            PeriodSwitcherBar(
                window = window,
                onSelectPeriod = onSelectPeriod,
                onPrevPeriod = onPrevPeriod,
                onNextPeriod = onNextPeriod,
                onJumpToCurrent = onJumpToCurrent
            )
        }

        if (showHeroAtTop) {
            HeroBudgetCard(report = report)
        }

        // 1. Donut Chart Card with Расходы / Доходы Toggle
        CategoryDonutCard(
            report = report,
            showIncomes = showIncomesInChart,
            onToggleMode = { incomesMode ->
                showIncomesInChart = incomesMode
                selectedChartCategoryId = null
            },
            selectedCategoryId = selectedChartCategoryId,
            onSelectCategory = { cat ->
                selectedChartCategoryId = if (selectedChartCategoryId == cat.id) null else cat.id
            }
        )

        // 2. Category Pace & Limits Card
        CategoryPaceBreakdownCard(report = report)

        // 3. Family Member Contribution & Cumulative Balance Card
        CoupleContributionCard(report = report)

        if (onOpenSavingsGoals != null || onSaveSavingsGoal != null) {
            SavingsGoalsAnalyticsCard(
                goalProgressList = goalProgressList,
                totalSavedInGoalsRub = report.totalSavedInGoalsRub,
                freeUnallocatedBalanceRub = report.freeUnallocatedBalanceRub,
                isUnfoldedFoldable = isUnfoldedFoldable,
                onDeposit = { progress -> quickGoalTransfer = progress to true },
                onWithdraw = { progress -> quickGoalTransfer = progress to false },
                onNewGoal = { showNewGoalDialog = true },
                onOpenManageSheet = { onOpenSavingsGoals?.invoke() }
            )
        }

        if (onOpenRecurringPayments != null) {
            RecurringPaymentsAnalyticsCard(
                recurringPayments = recurringPayments,
                onClick = onOpenRecurringPayments
            )
        }

        // 4. AI Financial Verdict & Gemini Advisor Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = palette.surfaceCard,
            border = BorderStroke(1.dp, palette.outlineSubtle)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(palette.accentBlueSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = palette.accentBlue,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Финансовый советник",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            Text(
                                text = if (cloudModelUsed != null) {
                                    "Gemini ($cloudModelUsed)"
                                } else {
                                    "Gemini ИИ готов (автоподбор модели)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                        }
                    }

                    Button(
                        enabled = !isLoadingCloudAi && geminiApiKey.isNotBlank(),
                        onClick = {
                            isLoadingCloudAi = true
                            scope.launch {
                                val res = AiBudgetAdvisor.fetchDeepCloudAiAdvice(geminiApiKey, report)
                                res.onSuccess { advice ->
                                    cloudAdvice = advice.text
                                    cloudModelUsed = advice.modelUsed
                                }.onFailure { err ->
                                    cloudAdvice = "Ошибка запроса: ${err.message}"
                                    cloudModelUsed = null
                                }
                                isLoadingCloudAi = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accentBlue,
                            contentColor = Color.White
                        )
                    ) {
                        if (isLoadingCloudAi) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text("Совет ИИ", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceElevated)
                        .padding(12.dp)
                ) {
                    Text(
                        text = report.headlineVerdict,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary
                    )
                }

                if (report.upcomingRecurringReserveRub > 0.0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.surfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Резерв под будущие списания: ${Formatters.formatRub(report.upcomingRecurringReserveRub)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ритм: ${report.safeDailySpendRemainingRub.roundToInt()} ₽/день",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            maxLines = 1
                        )
                    }
                }

                if (cloudAdvice != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.accentBlueSoft.copy(alpha = 0.65f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = cloudAdvice!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary
                        )
                    }
                } else if (geminiApiKey.isBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onOpenSettings)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Добавьте бесплатный ключ Gemini API в настройках для персональных советов",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.accentBlue,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(86.dp))
    }

    if (showNewGoalDialog && onSaveSavingsGoal != null) {
        SavingsGoalEditorDialog(
            initialGoal = null,
            onDismiss = { showNewGoalDialog = false },
            onSave = { goal ->
                onSaveSavingsGoal(goal)
                showNewGoalDialog = false
            }
        )
    }

    quickGoalTransfer?.let { (progress, isDeposit) ->
        if (onRecordGoalTransfer != null) {
            GoalTransferDialog(
                progress = progress,
                isDeposit = isDeposit,
                freeUnallocatedBalanceRub = report.freeUnallocatedBalanceRub,
                defaultAuthorName = currentUserName,
                onDismiss = { quickGoalTransfer = null },
                onConfirm = { amountRub, authorName, note ->
                    onRecordGoalTransfer(progress.goal, amountRub, isDeposit, authorName, note)
                    quickGoalTransfer = null
                }
            )
        }
    }
}

@Composable
private fun CategoryDonutCard(
    report: AiBudgetReport,
    showIncomes: Boolean,
    onToggleMode: (Boolean) -> Unit,
    selectedCategoryId: String?,
    onSelectCategory: (CategoryDescriptor) -> Unit
) {
    val palette = LocalFamilyPalette.current
    val slices = remember(report, showIncomes) {
        if (showIncomes) {
            report.incomeBreakdown
                .filter { it.amountRub > 0.0 }
                .sortedByDescending { it.amountRub }
                .map { ChartSlice(it.category, it.amountRub, it.transactionCount) }
        } else {
            report.allCategories
                .filter { it.spentRub > 0.0 }
                .sortedByDescending { it.spentRub }
                .map { ChartSlice(it.category, it.spentRub, it.transactionCount) }
        }
    }

    val total = if (showIncomes) report.totalIncomeThisMonthRub else report.totalSpentThisMonthRub
    val totalTx = if (showIncomes) report.totalIncomeTransactions else report.totalExpenseTransactions

    val sweepScale by animateFloatAsState(
        targetValue = if (slices.isNotEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "donutSweep"
    )

    val selectedSlice = slices.firstOrNull { it.category.id == selectedCategoryId }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PieChart,
                        contentDescription = null,
                        tint = palette.accentEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Структура",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceElevated
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        listOf(false to "Расходы", true to "Доходы").forEach { (isInc, title) ->
                            val active = showIncomes == isInc
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (active) palette.surfaceCard else Color.Transparent,
                                shadowElevation = if (active && !palette.isDark) 1.dp else 0.dp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { onToggleMode(isInc) }
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) palette.textPrimary else palette.textSecondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (slices.isEmpty() || total <= 0.0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (showIncomes) {
                            "Нет записей доходов за выбранный период"
                        } else {
                            "Нет расходов за выбранный период"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val gapDegrees = if (slices.size > 1) 2.8f else 0f
                    val availableDegrees = 360f - gapDegrees * slices.size

                    Canvas(modifier = Modifier.size(156.dp)) {
                        val baseStroke = 21.dp.toPx()
                        val selectedExtra = 5.dp.toPx()
                        var startAngle = -90f

                        slices.forEach { slice ->
                            val share = (slice.amountRub / total).toFloat().coerceIn(0f, 1f)
                            val sweep = share * availableDegrees * sweepScale
                            val vis = slice.category.visual()
                            val sliceColor = if (palette.isDark) vis.darkModeColor else vis.color
                            val isSelected = selectedCategoryId == slice.category.id
                            val isDimmed = selectedCategoryId != null && !isSelected

                            val strokeWidth = if (isSelected) baseStroke + selectedExtra else baseStroke
                            val inset = strokeWidth / 2f

                            drawArc(
                                color = if (isDimmed) sliceColor.copy(alpha = 0.25f) else sliceColor,
                                startAngle = startAngle,
                                sweepAngle = sweep.coerceAtLeast(1.5f),
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                            )
                            startAngle += sweep + gapDegrees
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        if (selectedSlice != null) {
                            val pct = ((selectedSlice.amountRub / total) * 100.0).roundToInt()
                            Text(
                                text = selectedSlice.category.shortName,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = Formatters.formatRub(selectedSlice.amountRub, true),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = palette.textPrimary
                            )
                            Text(
                                text = "$pct% • ${selectedSlice.count} оп.",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accentEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = if (showIncomes) "Доходы" else "Расходы",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                            Text(
                                text = Formatters.formatRub(total, true),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (showIncomes) palette.accentEmerald else palette.textPrimary
                            )
                            Text(
                                text = "$totalTx оп.",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textMuted
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { slice ->
                                val vis = slice.category.visual()
                                val dotColor = if (palette.isDark) vis.darkModeColor else vis.color
                                val pct = ((slice.amountRub / total) * 100.0).roundToInt()
                                val isSelected = selectedCategoryId == slice.category.id

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSelectCategory(slice.category) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) dotColor.copy(alpha = 0.14f) else palette.surfaceElevated,
                                    border = if (isSelected) BorderStroke(1.dp, dotColor) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(CircleShape)
                                                .background(dotColor)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = slice.category.shortName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.textPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = Formatters.formatRub(slice.amountRub, true),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.textSecondary,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            text = "$pct%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = dotColor
                                        )
                                    }
                                }
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPaceBreakdownCard(report: AiBudgetReport) {
    val palette = LocalFamilyPalette.current
    val sorted = remember(report.allCategories) {
        report.allCategories
            .filter { it.spentRub > 0.0 || it.recommendedLimitRub >= 10000 }
            .sortedWith(
                compareByDescending<CategoryDiagnosis> { it.spentRub > 0 }
                    .thenByDescending { it.spentRub }
            )
    }

    if (sorted.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Insights,
                    contentDescription = null,
                    tint = palette.accentBlue,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (report.window.period == BudgetPeriod.MONTH) {
                        "Темп по категориям"
                    } else {
                        "Категории за период"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary
                )
            }

            sorted.forEachIndexed { index, diag ->
                CategoryPaceRow(
                    diag = diag,
                    totalPeriodSpent = report.totalSpentThisMonthRub,
                    period = report.window.period,
                    monthProgressRatio = report.monthProgressRatio,
                    isCurrentMonth = report.isCurrentMonth
                )
                if (index < sorted.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = palette.outlineSubtle
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryPaceRow(
    diag: CategoryDiagnosis,
    totalPeriodSpent: Double,
    period: BudgetPeriod,
    monthProgressRatio: Float,
    isCurrentMonth: Boolean
) {
    val palette = LocalFamilyPalette.current
    val denom = if (period == BudgetPeriod.ALL) {
        totalPeriodSpent.coerceAtLeast(1.0)
    } else {
        diag.recommendedLimitRub.coerceAtLeast(1.0)
    }
    val fillRatio = (diag.spentRub / denom).toFloat().coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(
        targetValue = fillRatio,
        animationSpec = tween(durationMillis = 500),
        label = "catProgress"
    )

    val statusColor = when (diag.status) {
        CategoryDiagnosis.Status.OVERBURNED -> palette.accentCoral
        CategoryDiagnosis.Status.CAN_SPLURGE -> palette.accentEmerald
        CategoryDiagnosis.Status.ON_TRACK -> palette.accentBlue
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CategoryIconBadge(
                category = diag.category,
                size = 36.dp,
                iconSize = 18.dp,
                cornerRadius = 11.dp
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diag.category.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = diag.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Formatters.formatRub(diag.spentRub, true),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                if (period != BudgetPeriod.ALL) {
                    Text(
                        text = "ориентир ${Formatters.formatRub(diag.recommendedLimitRub)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textMuted
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.surfaceElevated)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedRatio)
                        .clip(RoundedCornerShape(999.dp))
                        .background(statusColor)
                )
            }
            if (period == BudgetPeriod.MONTH && isCurrentMonth && monthProgressRatio in 0.03f..0.97f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val x = size.width * monthProgressRatio
                    drawLine(
                        color = palette.textPrimary.copy(alpha = 0.45f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.6.dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
private fun CoupleContributionCard(report: AiBudgetReport) {
    val palette = LocalFamilyPalette.current
    val total = (report.eugeneSpentRub + report.anzhelikaSpentRub).coerceAtLeast(1.0)
    val eugenePct = if (report.eugeneSpentRub + report.anzhelikaSpentRub > 0) {
        ((report.eugeneSpentRub / total) * 100.0).roundToInt()
    } else 50
    val anzhelikaPct = if (report.eugeneSpentRub + report.anzhelikaSpentRub > 0) {
        100 - eugenePct
    } else 50

    val allTimeBalance = report.allTimeBalanceRub

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Общий баланс и участие пары",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary
                )
                Text(
                    text = Formatters.formatRub(allTimeBalance, showKopecksIfAny = true),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (allTimeBalance < -0.005) palette.accentCoral else palette.accentEmerald,
                    fontWeight = FontWeight.Bold
                )
            }

            // Cumulative All-Time Summary Strip
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceElevated
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Всего доходов",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary
                        )
                        Text(
                            text = "+${Formatters.formatRub(report.allTimeIncomeRub, true)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accentEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Всего расходов",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary
                        )
                        Text(
                            text = Formatters.formatRub(report.allTimeExpenseRub, true),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MemberStatBox(
                    name = "Евгений",
                    amountRub = report.eugeneSpentRub,
                    count = report.eugeneCount,
                    percent = eugenePct,
                    accentColor = palette.eugeneColor,
                    modifier = Modifier.weight(1f)
                )
                MemberStatBox(
                    name = "Анжелика",
                    amountRub = report.anzhelikaSpentRub,
                    count = report.anzhelikaCount,
                    percent = anzhelikaPct,
                    accentColor = palette.anzhelikaColor,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.surfaceElevated)
            ) {
                if (report.eugeneSpentRub + report.anzhelikaSpentRub > 0.0) {
                    val eWeight = (report.eugeneSpentRub / total).toFloat().coerceIn(0.02f, 0.98f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(eWeight)
                            .background(palette.eugeneColor)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight((1f - eWeight).coerceAtLeast(0.02f))
                            .background(palette.anzhelikaColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberStatBox(
    name: String,
    amountRub: Double,
    count: Int,
    percent: Int,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceElevated
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AuthorAvatarCircle(authorName = name, size = 26.dp)
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = Formatters.formatRub(amountRub, true),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = palette.textPrimary
            )
            Text(
                text = "$percent% • $count оп.",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RecurringPaymentsAnalyticsCard(
    recurringPayments: List<RecurringPaymentEntity>,
    onClick: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val currentMonthKey = RecurringPaymentScheduler.periodKeyFor(YearMonth.now())
    val active = remember(recurringPayments) {
        recurringPayments.filter { !it.isDeleted && it.isActive }
    }
    val activeExpenses = remember(active) {
        active.filter { it.effectiveOperationType == OperationType.EXPENSE }
    }
    val pendingExpenses = remember(activeExpenses, currentMonthKey) {
        activeExpenses.filter { !it.isPaidInPeriod(currentMonthKey) }
    }
    val pendingSum = pendingExpenses.sumOf { it.amountRub }

    val subtitleText = when {
        active.isEmpty() -> "Добавьте подписки, ЖКХ или зарплату"
        pendingExpenses.isNotEmpty() ->
            "Активных: ${active.size} • Ожидается: ${pendingExpenses.size} (${Formatters.formatRub(pendingSum, true)})"
        else -> "Активных: ${active.size} • В этом месяце всё оплачено ✓"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accentEmeraldSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Repeat,
                    contentDescription = null,
                    tint = palette.accentEmerald,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Регулярные платежи",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pendingExpenses.isNotEmpty()) palette.accentAmber else palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "Открыть регулярные платежи",
                tint = palette.textMuted,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SavingsGoalsAnalyticsCard(
    goalProgressList: List<SavingsGoalProgress>,
    totalSavedInGoalsRub: Double,
    freeUnallocatedBalanceRub: Double,
    isUnfoldedFoldable: Boolean,
    onDeposit: (SavingsGoalProgress) -> Unit,
    onWithdraw: (SavingsGoalProgress) -> Unit,
    onNewGoal: () -> Unit,
    onOpenManageSheet: () -> Unit
) {
    val palette = LocalFamilyPalette.current
    val activeGoals = remember(goalProgressList) {
        goalProgressList.filter { !it.goal.isArchived }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenManageSheet),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Семейные копилки",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "В копилках: ${Formatters.formatRub(totalSavedInGoalsRub)} • Свободно: ${Formatters.formatRub(freeUnallocatedBalanceRub)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Управление копилками",
                    tint = palette.textMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (activeGoals.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceElevated)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Копилок пока нет. Откладывайте на общие цели переводом — траты месяца останутся точными.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val useTwoColumns = isUnfoldedFoldable || maxWidth >= 520.dp
                    if (useTwoColumns) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            activeGoals.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    pair.forEach { progress ->
                                        DashboardGoalTile(
                                            progress = progress,
                                            compact = true,
                                            onDeposit = { onDeposit(progress) },
                                            onWithdraw = { onWithdraw(progress) },
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
                                DashboardGoalTile(
                                    progress = progress,
                                    compact = false,
                                    onDeposit = { onDeposit(progress) },
                                    onWithdraw = { onWithdraw(progress) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNewGoal,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accentEmeraldSoft,
                        contentColor = palette.accentEmerald
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Новая цель",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = onOpenManageSheet,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, palette.outlineSubtle)
                ) {
                    Text(
                        text = "Управление",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardGoalTile(
    progress: SavingsGoalProgress,
    compact: Boolean,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    val goal = progress.goal
    val animatedProgress by animateFloatAsState(
        targetValue = progress.progressRatio,
        animationSpec = tween(durationMillis = 550),
        label = "dashboardGoalProgress"
    )
    val barColor = if (progress.isCompleted) palette.accentEmerald else palette.accentBlue
    val percent = (progress.progressRatio * 100f).roundToInt()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = goal.emoji.ifBlank { "🎯" },
                        fontSize = if (compact) 15.sp else 17.sp
                    )
                    Text(
                        text = goal.title.ifBlank { "Копилка" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (progress.isCompleted) "✓ $percent%" else "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = barColor,
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.surfaceCard)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .clip(RoundedCornerShape(999.dp))
                        .background(barColor)
                )
            }

            Text(
                text = if (goal.targetAmountRub > 0.0) {
                    "${Formatters.formatRub(progress.savedAmountRub)} / ${Formatters.formatRub(goal.targetAmountRub)}"
                } else {
                    Formatters.formatRub(progress.savedAmountRub)
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1.35f)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onDeposit),
                    shape = RoundedCornerShape(9.dp),
                    color = palette.accentEmeraldSoft
                ) {
                    Text(
                        text = "+ Пополнить",
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accentEmerald,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                    )
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onWithdraw),
                    shape = RoundedCornerShape(9.dp),
                    color = palette.surfaceCard,
                    border = BorderStroke(1.dp, palette.outlineSubtle)
                ) {
                    Text(
                        text = "Снять",
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
