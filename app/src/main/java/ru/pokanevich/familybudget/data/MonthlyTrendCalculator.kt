package ru.pokanevich.familybudget.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * Одна точка помесячного тренда доходов, расходов и чистого баланса.
 */
data class MonthlyTrendPoint(
    val yearMonthKey: String,
    val label: String,
    val expenseRub: Double,
    val incomeRub: Double,
    val netRub: Double
)

/**
 * Калькулятор помесячной динамики доходов и расходов за 6 или 12 месяцев.
 * Исключает удалённые записи, неподтверждённые пуши и внутренние переводы (TRANSFER).
 */
object MonthlyTrendCalculator {

    private val shortMonthLabels = listOf(
        "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
        "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"
    )

    fun calculate(
        expenses: List<ExpenseEntity>,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        monthsCount: Int = 6,
        filterCategoryId: String? = null,
        anchorEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<MonthlyTrendPoint> {
        val count = monthsCount.coerceAtLeast(1)
        val anchorZoned = Instant.ofEpochMilli(anchorEpochMs).atZone(zoneId)
        val endMonth = YearMonth.of(anchorZoned.year, anchorZoned.monthValue)
        val startMonth = endMonth.minusMonths((count - 1).toLong())

        val startEpochMs = startMonth.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endExclusiveEpochMs = endMonth.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val monthBuckets = ArrayList<YearMonth>(count)
        val expenseSums = DoubleArray(count)
        val incomeSums = DoubleArray(count)
        val indexByYearMonth = HashMap<YearMonth, Int>(count * 2)

        for (i in 0 until count) {
            val ym = startMonth.plusMonths(i.toLong())
            monthBuckets.add(ym)
            indexByYearMonth[ym] = i
        }

        val normalizedFilterId = filterCategoryId?.takeIf { it.isNotBlank() }

        for (expense in expenses) {
            if (expense.isDeleted || expense.isPendingConfirmation) continue
            if (expense.createdAtEpochMs < startEpochMs || expense.createdAtEpochMs >= endExclusiveEpochMs) continue
            if (!expense.amountRub.isFinite() || expense.amountRub <= 0.0) continue

            val opType = expense.resolveOperationType(registry)
            if (opType == OperationType.TRANSFER) continue

            if (normalizedFilterId != null && expense.resolveCategory(registry).id != normalizedFilterId) {
                continue
            }

            val zoned = Instant.ofEpochMilli(expense.createdAtEpochMs).atZone(zoneId)
            val ym = YearMonth.of(zoned.year, zoned.monthValue)
            val idx = indexByYearMonth[ym] ?: continue

            when (opType) {
                OperationType.INCOME -> incomeSums[idx] += expense.amountRub
                OperationType.EXPENSE -> expenseSums[idx] += expense.amountRub
                OperationType.TRANSFER -> Unit
            }
        }

        return monthBuckets.mapIndexed { idx, ym ->
            val exp = expenseSums[idx]
            val inc = incomeSums[idx]
            MonthlyTrendPoint(
                yearMonthKey = "%04d-%02d".format(Locale.US, ym.year, ym.monthValue),
                label = shortMonthLabels[ym.monthValue - 1],
                expenseRub = exp,
                incomeRub = inc,
                netRub = inc - exp
            )
        }
    }
}
