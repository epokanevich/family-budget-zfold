package ru.pokanevich.familybudget.widget

import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.Formatters
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import kotlin.math.roundToInt

/**
 * Снимок данных для домашнего виджета бюджета.
 */
data class WidgetSnapshot(
    val safeDailyRub: Double,
    val spentThisMonthRub: Double,
    val monthlyBudgetRub: Double,
    val freeBudgetAfterReserveRub: Double,
    val upcomingRecurringReserveRub: Double,
    val pendingPushCount: Int,
    val daysLeftInMonth: Int,
    val isHidden: Boolean,
    val safeDailyText: String,
    val spentText: String,
    val budgetProgressText: String,
    val reserveText: String,
    val pendingBadgeText: String?,
    val spentProgressPercent: Int
)

/**
 * Чистый построитель [WidgetSnapshot] для текущего месяца.
 * Использует [AiBudgetAdvisor.buildInstantLocalReport] с учётом резерва будущих списаний
 * и без учёта переводов (`TRANSFER`).
 */
object WidgetSnapshotBuilder {

    const val MASKED_AMOUNT = "•••• ₽"

    fun build(
        expenses: List<ExpenseEntity>,
        monthlyBudgetRub: Double,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        recurringPayments: List<RecurringPaymentEntity> = emptyList(),
        savingsGoals: List<SavingsGoalEntity> = emptyList(),
        hideAmountsOnWidget: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): WidgetSnapshot {
        val currentMonthWindow = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, nowMs)
        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudgetRub,
            window = currentMonthWindow,
            registry = registry,
            recurringPayments = recurringPayments,
            nowMs = nowMs,
            savingsGoals = savingsGoals
        )

        val pendingPushCount = expenses.count { !it.isDeleted && it.isPendingConfirmation }
        val pendingBadgeText = if (pendingPushCount > 0) pendingPushCount.toString() else null

        val safeDailyText = if (hideAmountsOnWidget) {
            "$MASKED_AMOUNT/день"
        } else {
            "${Formatters.formatRub(report.safeDailySpendRemainingRub)}/день"
        }

        val spentText = if (hideAmountsOnWidget) {
            MASKED_AMOUNT
        } else {
            Formatters.formatRub(report.totalSpentThisMonthRub)
        }

        val budgetProgressText = if (hideAmountsOnWidget) {
            "$MASKED_AMOUNT / $MASKED_AMOUNT"
        } else {
            "${Formatters.formatRub(report.totalSpentThisMonthRub)} / ${Formatters.formatRub(report.monthlyBudgetRub)}"
        }

        val reserveText = if (hideAmountsOnWidget) {
            MASKED_AMOUNT
        } else {
            Formatters.formatRub(report.upcomingRecurringReserveRub)
        }

        val spentProgressPercent = if (hideAmountsOnWidget || report.monthlyBudgetRub <= 0.0) {
            0
        } else {
            ((report.totalSpentThisMonthRub / report.monthlyBudgetRub) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }

        return WidgetSnapshot(
            safeDailyRub = report.safeDailySpendRemainingRub,
            spentThisMonthRub = report.totalSpentThisMonthRub,
            monthlyBudgetRub = report.monthlyBudgetRub,
            freeBudgetAfterReserveRub = report.freeBudgetAfterReserveRub,
            upcomingRecurringReserveRub = report.upcomingRecurringReserveRub,
            pendingPushCount = pendingPushCount,
            daysLeftInMonth = report.daysLeftInMonth,
            isHidden = hideAmountsOnWidget,
            safeDailyText = safeDailyText,
            spentText = spentText,
            budgetProgressText = budgetProgressText,
            reserveText = reserveText,
            pendingBadgeText = pendingBadgeText,
            spentProgressPercent = spentProgressPercent
        )
    }
}
