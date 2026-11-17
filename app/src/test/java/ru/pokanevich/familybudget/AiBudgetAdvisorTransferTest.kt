package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import java.util.Calendar

class AiBudgetAdvisorTransferTest {

    private fun anchorInJune2026(): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.JUNE)
        set(Calendar.DAY_OF_MONTH, 15)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun transferOperations_doNotAffectBalanceExpensesIncomesAuthorsOrCategories() {
        val txTime = anchorInJune2026()
        val oldTime = txTime - 45L * 86_400_000L
        val monthWindow = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, txTime)

        val items = listOf(
            ExpenseEntity(
                id = "inc-salary",
                amountRub = 120_000.0,
                merchant = "Зарплата",
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                authorName = "Евгений",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime
            ),
            ExpenseEntity(
                id = "exp-groceries",
                amountRub = 15_000.0,
                merchant = "Пятёрочка",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Евгений",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime
            ),
            ExpenseEntity(
                id = "exp-cafes",
                amountRub = 5_000.0,
                merchant = "Кофейня",
                categoryName = ExpenseCategory.CAFES.name,
                authorName = "Анжелика",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime
            ),
            // Transfers with various categories, authors, and periods
            ExpenseEntity(
                id = "tr-1",
                amountRub = 40_000.0,
                merchant = "Перевод на вклад",
                categoryName = ExpenseCategory.OTHER.name,
                authorName = "Евгений",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime,
                operationType = OperationType.TRANSFER.name
            ),
            ExpenseEntity(
                id = "tr-2",
                amountRub = 10_000.0,
                merchant = "Перевод Анжелике",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Анжелика",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime,
                operationType = OperationType.TRANSFER.name
            ),
            ExpenseEntity(
                id = "tr-3",
                amountRub = 25_000.0,
                merchant = "Перевод между картами",
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                authorName = "Евгений",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime,
                operationType = OperationType.TRANSFER.name
            ),
            ExpenseEntity(
                id = "tr-old",
                amountRub = 50_000.0,
                merchant = "Старый перевод",
                categoryName = ExpenseCategory.SHOPPING.name,
                authorName = "Анжелика",
                createdAtEpochMs = oldTime,
                updatedAtEpochMs = oldTime,
                operationType = OperationType.TRANSFER.name
            )
        )

        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = items,
            monthlyBudgetRub = 200_000.0,
            window = monthWindow
        )

        assertEquals(20_000.0, report.totalSpentThisMonthRub, 0.001)
        assertEquals(120_000.0, report.totalIncomeThisMonthRub, 0.001)
        assertEquals(120_000.0, report.allTimeIncomeRub, 0.001)
        assertEquals(20_000.0, report.allTimeExpenseRub, 0.001)
        assertEquals(100_000.0, report.allTimeBalanceRub, 0.001)

        assertEquals(2, report.totalExpenseTransactions)
        assertEquals(1, report.totalIncomeTransactions)
        assertEquals(15_000.0, report.eugeneSpentRub, 0.001)
        assertEquals(1, report.eugeneCount)
        assertEquals(5_000.0, report.anzhelikaSpentRub, 0.001)
        assertEquals(1, report.anzhelikaCount)

        val groceries = report.allCategories.first { it.category.id == ExpenseCategory.GROCERIES.name }
        assertEquals(15_000.0, groceries.spentRub, 0.001)
        assertEquals(1, groceries.transactionCount)

        val cafes = report.allCategories.first { it.category.id == ExpenseCategory.CAFES.name }
        assertEquals(5_000.0, cafes.spentRub, 0.001)
        assertEquals(1, cafes.transactionCount)

        val other = report.allCategories.first { it.category.id == ExpenseCategory.OTHER.name }
        assertEquals(0.0, other.spentRub, 0.001)
        assertEquals(0, other.transactionCount)

        val shopping = report.allCategories.first { it.category.id == ExpenseCategory.SHOPPING.name }
        assertEquals(0.0, shopping.spentRub, 0.001)
        assertEquals(0, shopping.transactionCount)

        val salaryBreakdown = report.incomeBreakdown.first { it.category.id == ExpenseCategory.INCOME_SALARY.name }
        assertEquals(120_000.0, salaryBreakdown.amountRub, 0.001)
        assertEquals(1, salaryBreakdown.transactionCount)
    }

    @Test
    fun onlyTransfers_produceZeroSpentIncomeAndBalanceInMonthAndAllPeriods() {
        val txTime = anchorInJune2026()
        val transfers = listOf(
            ExpenseEntity(
                id = "tr-only-1",
                amountRub = 30_000.0,
                merchant = "Перевод на карту",
                categoryName = ExpenseCategory.OTHER.name,
                authorName = "Евгений",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime,
                operationType = OperationType.TRANSFER.name
            ),
            ExpenseEntity(
                id = "tr-only-2",
                amountRub = 12_500.0,
                merchant = "Возврат себе",
                categoryName = ExpenseCategory.INCOME_OTHER.name,
                authorName = "Анжелика",
                createdAtEpochMs = txTime,
                updatedAtEpochMs = txTime,
                operationType = OperationType.TRANSFER.name
            )
        )

        val periods = listOf(
            PeriodCalculator.buildWindow(BudgetPeriod.MONTH, txTime),
            PeriodCalculator.buildWindow(BudgetPeriod.ALL, txTime)
        )

        for (window in periods) {
            val report = AiBudgetAdvisor.buildInstantLocalReport(
                expenses = transfers,
                monthlyBudgetRub = 200_000.0,
                window = window
            )
            assertEquals(0.0, report.totalSpentThisMonthRub, 0.001)
            assertEquals(0.0, report.totalIncomeThisMonthRub, 0.001)
            assertEquals(0.0, report.allTimeIncomeRub, 0.001)
            assertEquals(0.0, report.allTimeExpenseRub, 0.001)
            assertEquals(0.0, report.allTimeBalanceRub, 0.001)
            assertEquals(0, report.totalExpenseTransactions)
            assertEquals(0, report.totalIncomeTransactions)
            assertEquals(0.0, report.eugeneSpentRub, 0.001)
            assertEquals(0.0, report.anzhelikaSpentRub, 0.001)
            assertEquals(0, report.eugeneCount)
            assertEquals(0, report.anzhelikaCount)
            report.allCategories.forEach { catDiag ->
                assertEquals(0.0, catDiag.spentRub, 0.001)
                assertEquals(0, catDiag.transactionCount)
            }
            report.incomeBreakdown.forEach { incDiag ->
                assertEquals(0.0, incDiag.amountRub, 0.001)
                assertEquals(0, incDiag.transactionCount)
            }
        }
    }

    @Test
    fun reportWithTransfers_matchesReportWithoutTransfers() {
        val txTime = anchorInJune2026()
        val window = PeriodCalculator.buildWindow(BudgetPeriod.ALL, txTime)
        val baseItems = listOf(
            ExpenseEntity(
                id = "base-inc",
                amountRub = 80_000.0,
                merchant = "Аванс",
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                authorName = "Анжелика",
                createdAtEpochMs = txTime
            ),
            ExpenseEntity(
                id = "base-exp",
                amountRub = 12_345.67,
                merchant = "Лента",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Анжелика",
                createdAtEpochMs = txTime
            )
        )
        val withTransfers = baseItems + listOf(
            ExpenseEntity(
                id = "extra-tr",
                amountRub = 99_999.99,
                merchant = "Между счетами",
                categoryName = ExpenseCategory.OTHER.name,
                authorName = "Евгений",
                createdAtEpochMs = txTime,
                operationType = OperationType.TRANSFER.name
            )
        )

        val baseline = AiBudgetAdvisor.buildInstantLocalReport(baseItems, 200_000.0, window)
        val actual = AiBudgetAdvisor.buildInstantLocalReport(withTransfers, 200_000.0, window)

        assertEquals(baseline.totalSpentThisMonthRub, actual.totalSpentThisMonthRub, 0.001)
        assertEquals(baseline.totalIncomeThisMonthRub, actual.totalIncomeThisMonthRub, 0.001)
        assertEquals(baseline.allTimeIncomeRub, actual.allTimeIncomeRub, 0.001)
        assertEquals(baseline.allTimeExpenseRub, actual.allTimeExpenseRub, 0.001)
        assertEquals(baseline.allTimeBalanceRub, actual.allTimeBalanceRub, 0.001)
        assertEquals(baseline.eugeneSpentRub, actual.eugeneSpentRub, 0.001)
        assertEquals(baseline.anzhelikaSpentRub, actual.anzhelikaSpentRub, 0.001)
        assertEquals(baseline.allCategories, actual.allCategories)
        assertEquals(baseline.incomeBreakdown, actual.incomeBreakdown)
    }
}
