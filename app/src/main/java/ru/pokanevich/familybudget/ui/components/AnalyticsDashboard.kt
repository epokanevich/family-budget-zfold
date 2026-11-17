package ru.pokanevich.familybudget.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.Forum
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.ai.AiBudgetReport
import ru.pokanevich.familybudget.ai.AiChatMessage
import ru.pokanevich.familybudget.ai.CategoryDiagnosis
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.FamilyPreferences
import ru.pokanevich.familybudget.data.MonthlyTrendCalculator
import ru.pokanevich.familybudget.data.MonthlyTrendPoint
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import ru.pokanevich.familybudget.data.SavingsGoalProgress
import ru.pokanevich.familybudget.ui.sheets.AiChatInlinePanel
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
import kotlin.math.max
import kotlin.math.min
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
    allExpenses: List<ExpenseEntity> = emptyList(),
    categoryRegistry: CategoryRegistry = CategoryRegistry.DEFAULT,
    isAiChatOpen: Boolean = false,
    onOpenAiChat: () -> Unit = {},
    onCloseAiChat: () -> Unit = {},
    aiChatMessages: List<AiChatMessage> = emptyList(),
    isAiChatSending: Boolean = false,
    aiChatModelUsed: String? = null,
    onSendAiChatQuestion: (String) -> Unit = {},
    onClearAiChat: () -> Unit = {},
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

    val confirmedRecordsCount = remember(allExpenses) {
        allExpenses.count { !it.isDeleted && !it.isPendingConfirmation }
    }

    if (isUnfoldedFoldable && isAiChatOpen) {
        AiChatInlinePanel(
            messages = aiChatMessages,
            isSending = isAiChatSending,
            activeModelName = aiChatModelUsed ?: cloudModelUsed ?: AiBudgetAdvisor.lastUsedModel(),
            totalRecordsCount = confirmedRecordsCount,
            geminiApiKey = geminiApiKey,
            onSendQuestion = onSendAiChatQuestion,
            onClearChat = onClearAiChat,
            onOpenSettings = onOpenSettings,
            onBackToAnalytics = onCloseAiChat,
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (isUnfoldedFoldable) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = palette.surfaceElevated
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.surfaceCard,
                        shadowElevation = if (!palette.isDark) 1.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        Text(
                            text = "Аналитика",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenAiChat)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = palette.accentBlue,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (aiChatMessages.isNotEmpty()) {
                                    "Чат с ИИ (${aiChatMessages.size})"
                                } else {
                                    "Чат с ИИ"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.accentBlue,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
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

        // 1.5. Monthly Trends Card (6 / 12 months, paired bars + balance/average line)
        MonthlyTrendsCard(
            allExpenses = allExpenses,
            categoryRegistry = categoryRegistry
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
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
                            val displayedModel = cloudModelUsed ?: aiChatModelUsed
                            Text(
                                text = if (displayedModel != null) {
                                    "Gemini ($displayedModel)"
                                } else {
                                    "Gemini ИИ готов (автоподбор модели)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

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

                OutlinedButton(
                    onClick = onOpenAiChat,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.accentBlue.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = palette.accentBlueSoft.copy(alpha = 0.55f),
                        contentColor = palette.accentBlue
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Спросить ИИ по базе",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (aiChatMessages.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = palette.accentBlue
                        ) {
                            Text(
                                text = "${aiChatMessages.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
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
                    text = if (FamilyPreferences.isSoloProfile) "Общий баланс за всё время" else "Общий баланс и участие пары",
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

            if (!FamilyPreferences.isSoloProfile) {
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

@Composable
fun MonthlyTrendsCard(
    allExpenses: List<ExpenseEntity>,
    categoryRegistry: CategoryRegistry,
    modifier: Modifier = Modifier
) {
    val palette = LocalFamilyPalette.current
    var monthsCount by rememberSaveable { mutableIntStateOf(6) }
    var filterCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMonthKey by remember { mutableStateOf<String?>(null) }

    val trendPoints by produceState(
        initialValue = emptyList<MonthlyTrendPoint>(),
        key1 = allExpenses,
        key2 = categoryRegistry,
        key3 = Pair(monthsCount, filterCategoryId)
    ) {
        value = withContext(Dispatchers.Default) {
            MonthlyTrendCalculator.calculate(
                expenses = allExpenses,
                registry = categoryRegistry,
                monthsCount = monthsCount,
                filterCategoryId = filterCategoryId
            )
        }
    }

    val selectedPoint = remember(trendPoints, selectedMonthKey) {
        trendPoints.firstOrNull { it.yearMonthKey == selectedMonthKey }
    }

    val activeFilterCategory = remember(categoryRegistry, filterCategoryId) {
        filterCategoryId?.let { categoryRegistry.resolve(it) }
    }

    val filterCategories = remember(categoryRegistry) {
        categoryRegistry.expenseCategories + categoryRegistry.incomeCategories
    }

    val avgLineValue = remember(trendPoints, filterCategoryId, activeFilterCategory) {
        if (trendPoints.isEmpty()) return@remember 0.0
        if (filterCategoryId == null) {
            trendPoints.map { it.netRub }.average()
        } else {
            val isIncomeCat = activeFilterCategory?.isIncome == true
            val values = trendPoints.map { if (isIncomeCat) it.incomeRub else it.expenseRub }
            val nonZero = values.filter { it > 0.0 }
            if (nonZero.isNotEmpty()) nonZero.average() else 0.0
        }
    }

    val hasAnyData = remember(trendPoints) {
        trendPoints.any { it.expenseRub > 0.0 || it.incomeRub > 0.0 }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceCard,
        border = BorderStroke(1.dp, palette.outlineSubtle)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header + 6 мес / 12 мес toggle
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
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(palette.accentBlueSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Insights,
                            contentDescription = null,
                            tint = palette.accentBlue,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Динамика по месяцам",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activeFilterCategory?.let { "${it.emoji} ${it.displayName}" }
                                ?: "Доходы, расходы и баланс",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = palette.surfaceElevated
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        listOf(6 to "6 мес", 12 to "12 мес").forEach { (count, title) ->
                            val active = monthsCount == count
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (active) palette.surfaceCard else Color.Transparent,
                                shadowElevation = if (active && !palette.isDark) 1.dp else 0.dp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { monthsCount = count }
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

            // Category filter chips ("Все расходы" vs selected category)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val allActive = filterCategoryId == null
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (allActive) palette.accentBlueSoft else palette.surfaceElevated,
                    border = if (allActive) BorderStroke(1.dp, palette.accentBlue) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { filterCategoryId = null }
                ) {
                    Text(
                        text = "Все расходы",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (allActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (allActive) palette.accentBlue else palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                filterCategories.forEach { cat ->
                    val isSelected = filterCategoryId == cat.id
                    val vis = cat.visual()
                    val catColor = if (palette.isDark) vis.darkModeColor else vis.color
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) catColor.copy(alpha = 0.16f) else palette.surfaceElevated,
                        border = if (isSelected) BorderStroke(1.dp, catColor) else null,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                filterCategoryId = if (isSelected) null else cat.id
                            }
                    ) {
                        Text(
                            text = "${cat.emoji} ${cat.shortName}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) catColor else palette.textSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Legend row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (filterCategoryId == null || activeFilterCategory?.isIncome == true) {
                    TrendLegendItem(color = palette.accentEmerald, label = "Доход", isLine = false)
                }
                if (filterCategoryId == null || activeFilterCategory?.isIncome == false) {
                    TrendLegendItem(color = palette.accentCoral, label = "Расход", isLine = false)
                }
                TrendLegendItem(
                    color = palette.accentBlue,
                    label = if (filterCategoryId == null) "Баланс" else "Среднее",
                    isLine = true
                )
            }

            if (trendPoints.isEmpty() || !hasAnyData) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (filterCategoryId == null) {
                            "Нет подтверждённых операций за выбранный период"
                        } else {
                            "Нет операций по выбранной категории за $monthsCount мес."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val incomeColor = palette.accentEmerald
                val expenseColor = palette.accentCoral
                val lineColor = palette.accentBlue
                val gridColor = palette.outlineSubtle
                val highlightBg = palette.accentBlue.copy(alpha = 0.09f)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(168.dp)
                            .pointerInput(trendPoints) {
                                detectTapGestures { offset ->
                                    if (trendPoints.isEmpty() || size.width <= 0) return@detectTapGestures
                                    val idx = ((offset.x / size.width.toFloat()) * trendPoints.size)
                                        .toInt()
                                        .coerceIn(0, trendPoints.lastIndex)
                                    val tappedKey = trendPoints[idx].yearMonthKey
                                    selectedMonthKey = if (selectedMonthKey == tappedKey) null else tappedKey
                                }
                            }
                    ) {
                        val count = trendPoints.size
                        if (count == 0) return@Canvas

                        val topPad = 10.dp.toPx()
                        val bottomPad = 6.dp.toPx()
                        val plotHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                        val maxBarValue = trendPoints.maxOf { max(it.incomeRub, it.expenseRub) }
                        val maxLineValue = if (filterCategoryId == null) {
                            trendPoints.maxOf { it.netRub }
                        } else {
                            avgLineValue
                        }
                        val minLineValue = if (filterCategoryId == null) {
                            trendPoints.minOf { it.netRub }
                        } else {
                            0.0
                        }

                        val maxPositive = max(max(maxBarValue, maxLineValue), 1.0)
                        val minVal = min(0.0, minLineValue)
                        val totalRange = (maxPositive - minVal).coerceAtLeast(1.0)

                        fun valueToY(value: Double): Float {
                            val ratioFromTop = ((maxPositive - value) / totalRange).toFloat()
                            return topPad + ratioFromTop * plotHeight
                        }

                        val zeroY = valueToY(0.0)

                        // Horizontal guide lines
                        val guideFractions = listOf(0f, 0.5f)
                        guideFractions.forEach { frac ->
                            val gy = topPad + (zeroY - topPad) * frac
                            drawLine(
                                color = gridColor.copy(alpha = 0.55f),
                                start = Offset(0f, gy),
                                end = Offset(size.width, gy),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                            )
                        }
                        // Baseline at 0
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, zeroY),
                            end = Offset(size.width, zeroY),
                            strokeWidth = 1.2.dp.toPx()
                        )

                        val slotWidth = size.width / count.toFloat()
                        val showPairedBars = filterCategoryId == null
                        val barCorner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        val maxSingleBarWidth = if (count <= 6) 14.dp.toPx() else 8.dp.toPx()
                        val barWidth = if (showPairedBars) {
                            min(maxSingleBarWidth, slotWidth * 0.28f)
                        } else {
                            min(maxSingleBarWidth * 1.35f, slotWidth * 0.45f)
                        }
                        val barGap = if (showPairedBars) min(3.dp.toPx(), slotWidth * 0.06f) else 0f

                        // Selected column highlight + bars
                        trendPoints.forEachIndexed { idx, point ->
                            val slotLeft = idx * slotWidth
                            val centerX = slotLeft + slotWidth / 2f

                            if (selectedMonthKey == point.yearMonthKey) {
                                drawRoundRect(
                                    color = highlightBg,
                                    topLeft = Offset(slotLeft + 2.dp.toPx(), 0f),
                                    size = Size((slotWidth - 4.dp.toPx()).coerceAtLeast(1f), size.height),
                                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                                )
                            }

                            if (showPairedBars) {
                                val pairWidth = barWidth * 2f + barGap
                                val incLeft = centerX - pairWidth / 2f
                                val expLeft = incLeft + barWidth + barGap

                                if (point.incomeRub > 0.0) {
                                    val incTop = valueToY(point.incomeRub)
                                    val incHeight = (zeroY - incTop).coerceAtLeast(2.dp.toPx())
                                    drawRoundRect(
                                        color = incomeColor,
                                        topLeft = Offset(incLeft, zeroY - incHeight),
                                        size = Size(barWidth, incHeight),
                                        cornerRadius = barCorner
                                    )
                                }
                                if (point.expenseRub > 0.0) {
                                    val expTop = valueToY(point.expenseRub)
                                    val expHeight = (zeroY - expTop).coerceAtLeast(2.dp.toPx())
                                    drawRoundRect(
                                        color = expenseColor,
                                        topLeft = Offset(expLeft, zeroY - expHeight),
                                        size = Size(barWidth, expHeight),
                                        cornerRadius = barCorner
                                    )
                                }
                            } else {
                                val isIncomeCat = activeFilterCategory?.isIncome == true
                                val barValue = if (isIncomeCat) point.incomeRub else point.expenseRub
                                val barColor = if (isIncomeCat) incomeColor else expenseColor
                                if (barValue > 0.0) {
                                    val barTop = valueToY(barValue)
                                    val barHeight = (zeroY - barTop).coerceAtLeast(2.dp.toPx())
                                    drawRoundRect(
                                        color = barColor,
                                        topLeft = Offset(centerX - barWidth / 2f, zeroY - barHeight),
                                        size = Size(barWidth, barHeight),
                                        cornerRadius = barCorner
                                    )
                                }
                            }
                        }

                        // Overlay line: Balance line (all ops) OR Average dashed line (category filter)
                        if (filterCategoryId == null) {
                            val linePath = Path()
                            val pointOffsets = ArrayList<Offset>(count)
                            trendPoints.forEachIndexed { idx, point ->
                                val x = idx * slotWidth + slotWidth / 2f
                                val y = valueToY(point.netRub)
                                pointOffsets.add(Offset(x, y))
                                if (idx == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                            }
                            drawPath(
                                path = linePath,
                                color = lineColor,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                            pointOffsets.forEachIndexed { idx, pt ->
                                val isSel = trendPoints[idx].yearMonthKey == selectedMonthKey
                                drawCircle(
                                    color = lineColor,
                                    radius = if (isSel) 4.5.dp.toPx() else 3.dp.toPx(),
                                    center = pt
                                )
                            }
                        } else if (avgLineValue > 0.0) {
                            val avgY = valueToY(avgLineValue)
                            drawLine(
                                color = lineColor,
                                start = Offset(0f, avgY),
                                end = Offset(size.width, avgY),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                            )
                        }
                    }

                    // Month labels row (also tappable)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        trendPoints.forEach { point ->
                            val isSelected = selectedMonthKey == point.yearMonthKey
                            Text(
                                text = point.label,
                                fontSize = if (monthsCount <= 6) 11.sp else 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) palette.accentBlue else palette.textMuted,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        selectedMonthKey = if (isSelected) null else point.yearMonthKey
                                    }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                // Exact values tooltip / hint for selected month
                if (selectedPoint != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = palette.surfaceElevated,
                        border = BorderStroke(1.dp, palette.outlineSubtle)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatTrendMonthTitle(selectedPoint.yearMonthKey),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary
                                )
                                if (filterCategoryId != null && avgLineValue > 0.0) {
                                    Text(
                                        text = "Среднее: ${Formatters.formatRub(avgLineValue)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.accentBlue,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Доходы",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textMuted
                                    )
                                    Text(
                                        text = Formatters.formatRub(selectedPoint.incomeRub, showKopecksIfAny = true),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.accentEmerald
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Расходы",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textMuted
                                    )
                                    Text(
                                        text = Formatters.formatRub(selectedPoint.expenseRub, showKopecksIfAny = true),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.accentCoral
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Баланс",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textMuted
                                    )
                                    val netSign = if (selectedPoint.netRub > 0.0) "+" else ""
                                    Text(
                                        text = "$netSign${Formatters.formatRub(selectedPoint.netRub, showKopecksIfAny = true)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedPoint.netRub >= 0.0) palette.accentBlue else palette.accentCoral
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Нажмите на месяц, чтобы увидеть точные суммы в ₽",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendLegendItem(
    color: Color,
    label: String,
    isLine: Boolean
) {
    val palette = LocalFamilyPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (isLine) {
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}

private fun formatTrendMonthTitle(yearMonthKey: String): String {
    val parts = yearMonthKey.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return yearMonthKey
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return yearMonthKey
    return Formatters.formatMonthTitle(year, (month - 1).coerceIn(0, 11))
}
