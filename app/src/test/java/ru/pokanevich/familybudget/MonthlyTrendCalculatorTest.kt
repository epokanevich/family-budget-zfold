package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.MonthlyTrendCalculator
import ru.pokanevich.familybudget.data.OperationType
import java.time.LocalDateTime
import java.time.ZoneId

class MonthlyTrendCalculatorTest {

    private val zoneId: ZoneId = ZoneId.of("Europe/Moscow")

    private fun epochAt(year: Int, month: Int, day: Int = 15): Long =
        LocalDateTime.of(year, month, day, 12, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

    @Test
    fun calculate_groupsAcrossYearBoundary_andFillsEmptyMonthsWithZeros() {
        val anchor = epochAt(2026, 2, 20)
        val expenses = listOf(
            ExpenseEntity(
                id = "e-nov",
                amountRub = 12_000.0,
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = epochAt(2025, 11, 10)
            ),
            ExpenseEntity(
                id = "i-dec",
                amountRub = 150_000.0,
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                operationType = OperationType.INCOME.name,
                createdAtEpochMs = epochAt(2025, 12, 25)
            ),
            ExpenseEntity(
                id = "e-dec",
                amountRub = 45_000.0,
                categoryName = ExpenseCategory.UTILITIES.name,
                createdAtEpochMs = epochAt(2025, 12, 28)
            ),
            ExpenseEntity(
                id = "e-jan",
                amountRub = 30_000.0,
                categoryName = ExpenseCategory.CAFES.name,
                createdAtEpochMs = epochAt(2026, 1, 5)
            ),
            // Out of 6-month window (2025-08 is before 2025-09)
            ExpenseEntity(
                id = "e-old",
                amountRub = 99_999.0,
                categoryName = ExpenseCategory.OTHER.name,
                createdAtEpochMs = epochAt(2025, 8, 31)
            )
        )

        val points = MonthlyTrendCalculator.calculate(
            expenses = expenses,
            registry = CategoryRegistry.DEFAULT,
            monthsCount = 6,
            filterCategoryId = null,
            anchorEpochMs = anchor,
            zoneId = zoneId
        )

        assertEquals(6, points.size)
        assertEquals(
            listOf("2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02"),
            points.map { it.yearMonthKey }
        )
        assertEquals(listOf("Сен", "Окт", "Ноя", "Дек", "Янв", "Фев"), points.map { it.label })

        // Empty months: 2025-09, 2025-10, 2026-02
        for (emptyIdx in listOf(0, 1, 5)) {
            assertEquals(0.0, points[emptyIdx].expenseRub, 0.001)
            assertEquals(0.0, points[emptyIdx].incomeRub, 0.001)
            assertEquals(0.0, points[emptyIdx].netRub, 0.001)
        }

        // 2025-11: negative net
        assertEquals(12_000.0, points[2].expenseRub, 0.001)
        assertEquals(0.0, points[2].incomeRub, 0.001)
        assertEquals(-12_000.0, points[2].netRub, 0.001)

        // 2025-12: income + expense
        assertEquals(45_000.0, points[3].expenseRub, 0.001)
        assertEquals(150_000.0, points[3].incomeRub, 0.001)
        assertEquals(105_000.0, points[3].netRub, 0.001)

        // 2026-01
        assertEquals(30_000.0, points[4].expenseRub, 0.001)
        assertEquals(0.0, points[4].incomeRub, 0.001)
        assertEquals(-30_000.0, points[4].netRub, 0.001)
    }

    @Test
    fun calculate_excludesDeletedPendingAndTransfers() {
        val anchor = epochAt(2026, 3, 15)
        val expenses = listOf(
            ExpenseEntity(
                id = "valid-exp",
                amountRub = 5_000.0,
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = epochAt(2026, 3, 10)
            ),
            ExpenseEntity(
                id = "valid-inc",
                amountRub = 20_000.0,
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                operationType = OperationType.INCOME.name,
                createdAtEpochMs = epochAt(2026, 3, 11)
            ),
            ExpenseEntity(
                id = "deleted-exp",
                amountRub = 50_000.0,
                categoryName = ExpenseCategory.GROCERIES.name,
                isDeleted = true,
                createdAtEpochMs = epochAt(2026, 3, 12)
            ),
            ExpenseEntity(
                id = "pending-exp",
                amountRub = 7_500.0,
                categoryName = ExpenseCategory.GROCERIES.name,
                isPendingConfirmation = true,
                createdAtEpochMs = epochAt(2026, 3, 12)
            ),
            ExpenseEntity(
                id = "transfer-op",
                amountRub = 100_000.0,
                categoryName = ExpenseCategory.OTHER.name,
                operationType = OperationType.TRANSFER.name,
                createdAtEpochMs = epochAt(2026, 3, 13)
            )
        )

        val points = MonthlyTrendCalculator.calculate(
            expenses = expenses,
            registry = CategoryRegistry.DEFAULT,
            monthsCount = 6,
            filterCategoryId = null,
            anchorEpochMs = anchor,
            zoneId = zoneId
        )

        val march = points.last()
        assertEquals("2026-03", march.yearMonthKey)
        assertEquals(5_000.0, march.expenseRub, 0.001)
        assertEquals(20_000.0, march.incomeRub, 0.001)
        assertEquals(15_000.0, march.netRub, 0.001)
    }

    @Test
    fun calculate_filtersByCategory_includingCustomCategories() {
        val customCat = CategoryDescriptor(
            id = "custom_pets",
            displayName = "Питомцы",
            shortName = "Питомцы",
            emoji = "🐾",
            colorHex = 0xFF8B5CF6,
            isIncome = false,
            isBuiltIn = false
        )
        val registry = CategoryRegistry(listOf(customCat))
        val anchor = epochAt(2026, 4, 15)

        val expenses = listOf(
            ExpenseEntity(
                id = "p1",
                amountRub = 2_400.0,
                categoryName = "custom_pets",
                createdAtEpochMs = epochAt(2026, 3, 5)
            ),
            ExpenseEntity(
                id = "p2",
                amountRub = 1_600.0,
                categoryName = "custom_pets",
                createdAtEpochMs = epochAt(2026, 4, 5)
            ),
            ExpenseEntity(
                id = "other",
                amountRub = 15_000.0,
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = epochAt(2026, 4, 6)
            )
        )

        val points = MonthlyTrendCalculator.calculate(
            expenses = expenses,
            registry = registry,
            monthsCount = 12,
            filterCategoryId = "custom_pets",
            anchorEpochMs = anchor,
            zoneId = zoneId
        )

        assertEquals(12, points.size)
        assertEquals("2025-05", points.first().yearMonthKey)
        assertEquals("2026-04", points.last().yearMonthKey)

        val march = points[10]
        val april = points[11]
        assertEquals(2_400.0, march.expenseRub, 0.001)
        assertEquals(0.0, march.incomeRub, 0.001)
        assertEquals(1_600.0, april.expenseRub, 0.001)
        assertEquals(0.0, april.incomeRub, 0.001)
    }
}
