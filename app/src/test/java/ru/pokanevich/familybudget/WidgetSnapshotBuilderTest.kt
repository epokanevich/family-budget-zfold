package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import ru.pokanevich.familybudget.widget.WidgetSnapshotBuilder

class WidgetSnapshotBuilderTest {

    @Test
    fun build_calculatesSafeDailyWithReserveAndExcludesTransfers() {
        val nowMs = System.currentTimeMillis()
        val window = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, nowMs)
        val monthlyBudget = 100_000.0

        val expenses = listOf(
            ExpenseEntity(
                id = "exp-1",
                amountRub = 20_000.0,
                merchant = "Супермаркет",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Евгений",
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            ),
            ExpenseEntity(
                id = "transfer-1",
                amountRub = 50_000.0,
                merchant = "Копилка",
                categoryName = "GOAL_DEPOSIT",
                authorName = "Евгений",
                operationType = OperationType.TRANSFER.name,
                goalId = "goal-1",
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            ),
            ExpenseEntity(
                id = "deleted-1",
                amountRub = 15_000.0,
                merchant = "Удалённый",
                categoryName = ExpenseCategory.OTHER.name,
                authorName = "Евгений",
                isDeleted = true,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            )
        )

        val recurring = listOf(
            RecurringPaymentEntity(
                id = "rec-1",
                title = "Аренда",
                amountRub = 30_000.0,
                categoryId = ExpenseCategory.UTILITIES.name,
                operationType = OperationType.EXPENSE.name,
                dayOfMonth = 25,
                isActive = true,
                isDeleted = false
            )
        )

        val snapshot = WidgetSnapshotBuilder.build(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudget,
            recurringPayments = recurring,
            hideAmountsOnWidget = false,
            nowMs = nowMs
        )

        val expectedFreeAfterReserve = 100_000.0 - 20_000.0 - 30_000.0
        val expectedSafeDaily = expectedFreeAfterReserve / window.daysLeftInPeriod.toDouble()

        assertEquals(20_000.0, snapshot.spentThisMonthRub, 0.01)
        assertEquals(30_000.0, snapshot.upcomingRecurringReserveRub, 0.01)
        assertEquals(expectedFreeAfterReserve, snapshot.freeBudgetAfterReserveRub, 0.01)
        assertEquals(expectedSafeDaily, snapshot.safeDailyRub, 0.01)
        assertEquals(20, snapshot.spentProgressPercent)
        assertTrue(snapshot.safeDailyText.endsWith("/день"))
        assertEquals(0, snapshot.pendingPushCount)
        assertNull(snapshot.pendingBadgeText)
    }

    @Test
    fun build_countsPendingPushExpensesExcludingDeleted() {
        val nowMs = System.currentTimeMillis()
        val expenses = listOf(
            ExpenseEntity(
                id = "pending-1",
                amountRub = 500.0,
                merchant = "Кофе",
                categoryName = ExpenseCategory.CAFES.name,
                authorName = "Евгений",
                isPendingConfirmation = true,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            ),
            ExpenseEntity(
                id = "pending-2",
                amountRub = 1_200.0,
                merchant = "Аптека",
                categoryName = ExpenseCategory.HEALTH.name,
                authorName = "Анжелика",
                isPendingConfirmation = true,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            ),
            ExpenseEntity(
                id = "pending-deleted",
                amountRub = 999.0,
                merchant = "Дубль",
                categoryName = ExpenseCategory.OTHER.name,
                authorName = "Евгений",
                isPendingConfirmation = true,
                isDeleted = true,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            ),
            ExpenseEntity(
                id = "confirmed-1",
                amountRub = 2_000.0,
                merchant = "Продукты",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Евгений",
                isPendingConfirmation = false,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            )
        )

        val snapshot = WidgetSnapshotBuilder.build(
            expenses = expenses,
            monthlyBudgetRub = 100_000.0,
            nowMs = nowMs
        )

        assertEquals(2, snapshot.pendingPushCount)
        assertEquals("2", snapshot.pendingBadgeText)
        assertEquals(2_000.0, snapshot.spentThisMonthRub, 0.01)
    }

    @Test
    fun build_masksAmountsWhenHideAmountsOnWidgetIsTrue() {
        val nowMs = System.currentTimeMillis()
        val expenses = listOf(
            ExpenseEntity(
                id = "exp-1",
                amountRub = 45_000.0,
                merchant = "Магазин",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Евгений",
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            ),
            ExpenseEntity(
                id = "pending-1",
                amountRub = 750.0,
                merchant = "Такси",
                categoryName = ExpenseCategory.TRANSPORT.name,
                authorName = "Евгений",
                isPendingConfirmation = true,
                createdAtEpochMs = nowMs,
                updatedAtEpochMs = nowMs
            )
        )

        val snapshot = WidgetSnapshotBuilder.build(
            expenses = expenses,
            monthlyBudgetRub = 150_000.0,
            hideAmountsOnWidget = true,
            nowMs = nowMs
        )

        assertTrue(snapshot.isHidden)
        assertEquals("•••• ₽/день", snapshot.safeDailyText)
        assertEquals("•••• ₽", snapshot.spentText)
        assertEquals("•••• ₽ / •••• ₽", snapshot.budgetProgressText)
        assertEquals("•••• ₽", snapshot.reserveText)
        assertEquals(0, snapshot.spentProgressPercent)
        assertEquals(1, snapshot.pendingPushCount)
        assertEquals("1", snapshot.pendingBadgeText)
    }
}
