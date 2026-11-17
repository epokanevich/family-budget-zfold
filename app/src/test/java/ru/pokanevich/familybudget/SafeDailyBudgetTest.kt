package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodWindow
import java.util.Calendar

class SafeDailyBudgetTest {

    private fun epochInMonth(year: Int, monthZeroBased: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun monthStartMs(year: Int, monthZeroBased: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun monthEndMs(year: Int, monthZeroBased: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

    private fun buildMonthWindow(
        year: Int,
        monthZeroBased: Int,
        isCurrentPeriod: Boolean,
        daysLeftInPeriod: Int = 10,
        progressRatio: Float = 0.67f
    ): PeriodWindow = PeriodWindow(
        period = BudgetPeriod.MONTH,
        startMs = monthStartMs(year, monthZeroBased),
        endMs = monthEndMs(year, monthZeroBased),
        title = "Ноябрь 2026",
        isCurrentPeriod = isCurrentPeriod,
        progressRatio = progressRatio,
        totalDaysInPeriod = 30,
        daysLeftInPeriod = daysLeftInPeriod
    )

    @Test
    fun unpaidActiveExpenseRecurring_reducesSafeDailyBudgetAndUpdatesVerdict() {
        val nowMs = epochInMonth(2026, Calendar.NOVEMBER, 20)
        val window = buildMonthWindow(2026, Calendar.NOVEMBER, isCurrentPeriod = true, daysLeftInPeriod = 10)

        val expenses = listOf(
            ExpenseEntity(
                id = "exp-1",
                amountRub = 40_000.0,
                merchant = "Продукты",
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            )
        )
        val recurring = listOf(
            RecurringPaymentEntity(
                id = "rent-1",
                title = "Аренда",
                amountRub = 30_000.0,
                categoryId = ExpenseCategory.UTILITIES.name,
                operationType = OperationType.EXPENSE.name,
                dayOfMonth = 25,
                lastPaidPeriodKey = "",
                isActive = true,
                isDeleted = false
            )
        )

        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = 100_000.0,
            window = window,
            recurringPayments = recurring,
            nowMs = nowMs
        )

        // 100_000 - 40_000 - 30_000 = 30_000; / 10 days = 3_000 / day
        assertEquals(30_000.0, report.upcomingRecurringReserveRub, 0.01)
        assertEquals(30_000.0, report.freeBudgetAfterReserveRub, 0.01)
        assertEquals(3_000.0, report.safeDailySpendRemainingRub, 0.01)
        assertTrue(report.headlineVerdict.contains("резерв", ignoreCase = true))
    }

    @Test
    fun alreadyPaidRecurring_viaLastPaidPeriodKeyOrConfirmedExpenseId_isNotSubtractedTwice() {
        val nowMs = epochInMonth(2026, Calendar.NOVEMBER, 20)
        val currentKey = RecurringPaymentScheduler.periodKeyFor(nowMs)
        val window = buildMonthWindow(2026, Calendar.NOVEMBER, isCurrentPeriod = true, daysLeftInPeriod = 10)

        val paidByFlag = RecurringPaymentEntity(
            id = "inet-1",
            title = "Интернет",
            amountRub = 1_000.0,
            operationType = OperationType.EXPENSE.name,
            lastPaidPeriodKey = currentKey,
            isActive = true
        )
        val paidByExpenseRecord = RecurringPaymentEntity(
            id = "music-1",
            title = "Музыка",
            amountRub = 500.0,
            operationType = OperationType.EXPENSE.name,
            lastPaidPeriodKey = "",
            isActive = true
        )
        val stillUnpaid = RecurringPaymentEntity(
            id = "gym-1",
            title = "Спортзал",
            amountRub = 5_000.0,
            operationType = OperationType.EXPENSE.name,
            lastPaidPeriodKey = "2026-10",
            isActive = true
        )

        val expenses = listOf(
            ExpenseEntity(
                id = RecurringPaymentScheduler.deterministicExpenseId("music-1", currentKey),
                amountRub = 500.0,
                merchant = "Музыка",
                categoryName = ExpenseCategory.LEISURE.name,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs,
                isPendingConfirmation = false,
                isDeleted = false
            )
        )

        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = 100_000.0,
            window = window,
            recurringPayments = listOf(paidByFlag, paidByExpenseRecord, stillUnpaid),
            nowMs = nowMs
        )

        // Only stillUnpaid (5_000) should be reserved; music-1 (500) is already in totalSpent
        assertEquals(5_000.0, report.upcomingRecurringReserveRub, 0.01)
        assertEquals(94_500.0, report.freeBudgetAfterReserveRub, 0.01)
        assertEquals(9_450.0, report.safeDailySpendRemainingRub, 0.01)
    }

    @Test
    fun inactiveDeletedAndIncomeRecurring_areNotReserved() {
        val nowMs = epochInMonth(2026, Calendar.NOVEMBER, 15)
        val window = buildMonthWindow(2026, Calendar.NOVEMBER, isCurrentPeriod = true, daysLeftInPeriod = 15)

        val recurring = listOf(
            RecurringPaymentEntity(
                id = "inactive-1",
                title = "Пауза",
                amountRub = 10_000.0,
                operationType = OperationType.EXPENSE.name,
                isActive = false,
                isDeleted = false
            ),
            RecurringPaymentEntity(
                id = "deleted-1",
                title = "Удалённая",
                amountRub = 20_000.0,
                operationType = OperationType.EXPENSE.name,
                isActive = true,
                isDeleted = true
            ),
            RecurringPaymentEntity(
                id = "salary-1",
                title = "Зарплата",
                amountRub = 150_000.0,
                operationType = OperationType.INCOME.name,
                isActive = true,
                isDeleted = false
            )
        )

        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = emptyList(),
            monthlyBudgetRub = 90_000.0,
            window = window,
            recurringPayments = recurring,
            nowMs = nowMs
        )

        assertEquals(0.0, report.upcomingRecurringReserveRub, 0.01)
        assertEquals(90_000.0, report.freeBudgetAfterReserveRub, 0.01)
        assertEquals(6_000.0, report.safeDailySpendRemainingRub, 0.01)
    }

    @Test
    fun pastMonths_haveZeroReserve() {
        val nowMs = epochInMonth(2026, Calendar.NOVEMBER, 15)
        val pastWindow = buildMonthWindow(
            year = 2026,
            monthZeroBased = Calendar.OCTOBER,
            isCurrentPeriod = false,
            daysLeftInPeriod = 0,
            progressRatio = 1.0f
        )

        val recurring = listOf(
            RecurringPaymentEntity(
                id = "rent-1",
                title = "Аренда",
                amountRub = 30_000.0,
                operationType = OperationType.EXPENSE.name,
                lastPaidPeriodKey = "",
                isActive = true,
                isDeleted = false
            )
        )

        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = emptyList(),
            monthlyBudgetRub = 100_000.0,
            window = pastWindow,
            recurringPayments = recurring,
            nowMs = nowMs
        )

        assertEquals(0.0, report.upcomingRecurringReserveRub, 0.01)
        assertEquals(100_000.0, report.freeBudgetAfterReserveRub, 0.01)
        assertEquals(0.0, report.safeDailySpendRemainingRub, 0.01)
    }
}
