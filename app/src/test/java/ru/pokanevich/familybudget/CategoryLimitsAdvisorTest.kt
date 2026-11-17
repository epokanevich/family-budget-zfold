package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.ai.CategoryDiagnosis
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import java.util.Calendar

class CategoryLimitsAdvisorTest {

    private fun anchorAtMidJune2026(): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.JUNE)
        set(Calendar.DAY_OF_MONTH, 15)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun fixedRubVsPercent_scalesCorrectlyAcrossDayWeekMonthYearAndAll() {
        val anchor = anchorAtMidJune2026()
        val monthlyBudget = 300_000.0

        // GROCERIES: PERCENT = 20% (from built-in default)
        // custom_fixed: FIXED_RUB = 30_000 ₽/мес
        val fixedCategory = CategoryDescriptor(
            id = "custom_fixed",
            displayName = "Образование",
            shortName = "Образ.",
            emoji = "📚",
            colorHex = 0xFF3A7DF0L,
            isIncome = false,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.FIXED_RUB,
            limitValue = 30_000.0,
            isArchived = false
        )
        val registry = CategoryRegistry(listOf(fixedCategory))

        val expenses = listOf(
            ExpenseEntity(
                id = "e1",
                amountRub = 5_000.0,
                merchant = "Курсы",
                categoryName = "custom_fixed",
                createdAtEpochMs = anchor,
                updatedAtEpochMs = anchor
            )
        )

        // DAY: periodBudget = 300_000 / 30 = 10_000; PERCENT(20%) = 2_000; FIXED_RUB = 30_000 / 30 = 1_000
        val dayReport = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudget,
            window = PeriodCalculator.buildWindow(BudgetPeriod.DAY, anchor),
            registry = registry
        )
        val dayGroceries = dayReport.allCategories.first { it.category.id == ExpenseCategory.GROCERIES.name }
        val dayFixed = dayReport.allCategories.first { it.category.id == "custom_fixed" }
        assertEquals(2_000.0, dayGroceries.recommendedLimitRub, 0.01)
        assertEquals(1_000.0, dayFixed.recommendedLimitRub, 0.01)
        assertEquals(CategoryDiagnosis.Status.OVERBURNED, dayFixed.status)

        // WEEK: periodBudget = 300_000 * 7 / 30 = 70_000; PERCENT(20%) = 14_000; FIXED_RUB = 30_000 * 7 / 30 = 7_000
        val weekReport = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudget,
            window = PeriodCalculator.buildWindow(BudgetPeriod.WEEK, anchor),
            registry = registry
        )
        val weekGroceries = weekReport.allCategories.first { it.category.id == ExpenseCategory.GROCERIES.name }
        val weekFixed = weekReport.allCategories.first { it.category.id == "custom_fixed" }
        assertEquals(14_000.0, weekGroceries.recommendedLimitRub, 0.01)
        assertEquals(7_000.0, weekFixed.recommendedLimitRub, 0.01)

        // MONTH: periodBudget = 300_000; PERCENT(20%) = 60_000; FIXED_RUB = 30_000
        val monthReport = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudget,
            window = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, anchor),
            registry = registry
        )
        val monthGroceries = monthReport.allCategories.first { it.category.id == ExpenseCategory.GROCERIES.name }
        val monthFixed = monthReport.allCategories.first { it.category.id == "custom_fixed" }
        assertEquals(60_000.0, monthGroceries.recommendedLimitRub, 0.01)
        assertEquals(30_000.0, monthFixed.recommendedLimitRub, 0.01)

        // YEAR: periodBudget = 3_600_000; PERCENT(20%) = 720_000; FIXED_RUB = 30_000 * 12 = 360_000
        val yearReport = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudget,
            window = PeriodCalculator.buildWindow(BudgetPeriod.YEAR, anchor),
            registry = registry
        )
        val yearGroceries = yearReport.allCategories.first { it.category.id == ExpenseCategory.GROCERIES.name }
        val yearFixed = yearReport.allCategories.first { it.category.id == "custom_fixed" }
        assertEquals(720_000.0, yearGroceries.recommendedLimitRub, 0.01)
        assertEquals(360_000.0, yearFixed.recommendedLimitRub, 0.01)

        // ALL: FIXED_RUB not compared with limit (0.0, ON_TRACK), and PERCENT also stays ON_TRACK
        val allReport = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudget,
            window = PeriodCalculator.buildWindow(BudgetPeriod.ALL, anchor),
            registry = registry
        )
        val allFixed = allReport.allCategories.first { it.category.id == "custom_fixed" }
        val allGroceries = allReport.allCategories.first { it.category.id == ExpenseCategory.GROCERIES.name }
        assertEquals(0.0, allFixed.recommendedLimitRub, 0.01)
        assertEquals(CategoryDiagnosis.Status.ON_TRACK, allFixed.status)
        assertEquals(CategoryDiagnosis.Status.ON_TRACK, allGroceries.status)
    }

    @Test
    fun archivedCategories_excludedFromPickerButShownInReportWhenHistoricalExpensesExist() {
        val anchor = anchorAtMidJune2026()
        val oldMonthAnchor = anchor - 40L * 86_400_000L

        val archivedWithHistory = CategoryDescriptor(
            id = "custom_old_hobby",
            displayName = "Старое хобби",
            shortName = "Хобби",
            emoji = "🎨",
            colorHex = 0xFF9368CEL,
            isIncome = false,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.FIXED_RUB,
            limitValue = 10_000.0,
            isArchived = true
        )
        val archivedWithoutHistory = CategoryDescriptor(
            id = "custom_unused",
            displayName = "Неиспользуемая",
            shortName = "Неисп.",
            emoji = "📦",
            colorHex = 0xFF64748BL,
            isIncome = false,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.PERCENT,
            limitValue = 5.0,
            isArchived = true
        )
        val registry = CategoryRegistry(listOf(archivedWithHistory, archivedWithoutHistory))

        // В пикере новых операций архивных категорий быть не должно
        assertFalse(registry.expenseCategories.any { it.id == "custom_old_hobby" })
        assertFalse(registry.expenseCategories.any { it.id == "custom_unused" })

        val expenses = listOf(
            ExpenseEntity(
                id = "hist-1",
                amountRub = 4_500.0,
                merchant = "Художественный салон",
                categoryName = "custom_old_hobby",
                createdAtEpochMs = anchor,
                updatedAtEpochMs = anchor
            ),
            ExpenseEntity(
                id = "hist-old",
                amountRub = 1_200.0,
                merchant = "Кисти",
                categoryName = "custom_old_hobby",
                createdAtEpochMs = oldMonthAnchor,
                updatedAtEpochMs = oldMonthAnchor
            )
        )

        val monthReport = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = 200_000.0,
            window = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, anchor),
            registry = registry
        )

        val shownArchived = monthReport.allCategories.firstOrNull { it.category.id == "custom_old_hobby" }
        assertNotNull("Archived category with period expenses must stay in report", shownArchived)
        assertEquals(4_500.0, shownArchived!!.spentRub, 0.01)
        assertEquals(1, shownArchived.transactionCount)

        val hiddenArchived = monthReport.allCategories.firstOrNull { it.category.id == "custom_unused" }
        assertNull("Archived category without period expenses should not clutter report", hiddenArchived)
        assertTrue(monthReport.totalSpentThisMonthRub == 4_500.0)
    }
}
