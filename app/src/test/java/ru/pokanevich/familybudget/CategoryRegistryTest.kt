package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.ai.AiBudgetAdvisor
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.CsvExporter
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import java.nio.charset.StandardCharsets

class CategoryRegistryTest {

    @Test
    fun defaultRegistry_containsAll14ExpenseAnd4IncomeBuiltInCategories() {
        val registry = CategoryRegistry.DEFAULT

        assertEquals(18, registry.allCategories.size)
        assertEquals(14, registry.expenseCategories.size)
        assertEquals(4, registry.incomeCategories.size)

        ExpenseCategory.entries.forEach { enumCat ->
            val resolved = registry.resolve(enumCat.name)
            assertTrue("Expected ${enumCat.name} to be known", registry.isKnown(enumCat.name))
            assertEquals(enumCat.name, resolved.id)
            assertEquals(enumCat.displayName, resolved.displayName)
            assertEquals(enumCat.shortName, resolved.shortName)
            assertEquals(enumCat.emoji, resolved.emoji)
            assertEquals(enumCat.defaultColorHex, resolved.colorHex)
            assertEquals(enumCat.isIncome, resolved.isIncome)
            assertTrue(resolved.isBuiltIn)
            assertFalse(resolved.isArchived)
            assertEquals(CategoryLimitMode.PERCENT, resolved.limitMode)
            assertEquals(enumCat.recommendedSharePercent.toDouble(), resolved.limitValue, 0.0001)
        }
    }

    @Test
    fun resolve_legacyV1V2Keys_mapsToBuiltInCategories() {
        val registry = CategoryRegistry.DEFAULT
        val legacyMappings = mapOf(
            "BABY_AND_PETS" to ExpenseCategory.PETS,
            "CAFES_AND_DELIVERY" to ExpenseCategory.CAFES,
            "HOME_AND_UTILITIES" to ExpenseCategory.UTILITIES,
            "TRANSPORT_AND_MOTO" to ExpenseCategory.TRANSPORT,
            "HEALTH_AND_PHARMACY" to ExpenseCategory.HEALTH,
            "LEISURE_AND_HOBBIES" to ExpenseCategory.LEISURE
        )

        legacyMappings.forEach { (legacyKey, expectedEnum) ->
            assertTrue("Legacy key $legacyKey should be known", registry.isKnown(legacyKey))
            val resolved = registry.resolve(legacyKey)
            assertEquals(expectedEnum.name, resolved.id)
            assertEquals(expectedEnum.displayName, resolved.displayName)
            assertTrue(resolved.isBuiltIn)
            assertEquals(expectedEnum.isIncome, resolved.isIncome)

            val legacyExpense = ExpenseEntity(
                id = "legacy-$legacyKey",
                amountRub = 500.0,
                merchant = "Тест",
                categoryName = legacyKey
            )
            assertEquals(expectedEnum.name, legacyExpense.resolveCategory(registry).id)
            assertEquals(OperationType.EXPENSE, legacyExpense.resolveOperationType(registry))
        }
    }

    @Test
    fun resolve_unknownCustomId_returnsDisplayFallbackAndPreservesOriginalId() {
        val registry = CategoryRegistry.DEFAULT
        val unknownId = "custom_f7c910a2"

        assertFalse(registry.isKnown(unknownId))
        assertNull(registry.find(unknownId))

        val resolved = registry.resolve(unknownId)
        assertEquals(unknownId, resolved.id)
        assertEquals("Другое", resolved.displayName)
        assertEquals("Другое", resolved.shortName)
        assertEquals("❓", resolved.emoji)
        assertEquals(ExpenseCategory.OTHER.defaultColorHex, resolved.colorHex)
        assertFalse(resolved.isIncome)
        assertFalse(resolved.isBuiltIn)

        val entity = ExpenseEntity(
            id = "op-unknown-cat",
            amountRub = 1490.0,
            merchant = "Неизвестная категория",
            categoryName = unknownId
        )
        // Исходный categoryName не перезаписывается
        assertEquals(unknownId, entity.categoryName)
        assertEquals(unknownId, entity.resolveCategory(registry).id)
        assertEquals("Другое", entity.resolveCategory(registry).displayName)
        assertEquals(OperationType.EXPENSE, entity.resolveOperationType(registry))

        val restored = ExpenseEntity.fromJsonObject(entity.toJsonObject(registry))
        assertNotNull(restored)
        assertEquals(unknownId, restored!!.categoryName)
    }

    @Test
    fun resolve_blankCategoryName_fallsBackToOtherBuiltIn() {
        val registry = CategoryRegistry.DEFAULT
        val fromEmpty = registry.resolve("")
        val fromSpaces = registry.resolve("   ")

        assertEquals(ExpenseCategory.OTHER.name, fromEmpty.id)
        assertTrue(fromEmpty.isBuiltIn)
        assertEquals(ExpenseCategory.OTHER.name, fromSpaces.id)
        assertTrue(fromSpaces.isBuiltIn)
    }

    @Test
    fun effectiveOperationType_usesRegistryIsIncomeAndSupportsCustomCategories() {
        val customIncome = CategoryDescriptor(
            id = "custom_freelance",
            displayName = "Фриланс",
            shortName = "Фриланс",
            emoji = "💻",
            colorHex = 0xFF10B981L,
            isIncome = true,
            isBuiltIn = false
        )
        val customExpense = CategoryDescriptor(
            id = "custom_coffee_beans",
            displayName = "Зерновой кофе",
            shortName = "Зерно",
            emoji = "🫘",
            colorHex = 0xFF8B5CF6L,
            isIncome = false,
            isBuiltIn = false,
            limitMode = CategoryLimitMode.FIXED_RUB,
            limitValue = 4000.0
        )
        val customRegistry = CategoryRegistry(listOf(customIncome, customExpense))

        val legacyBuiltInIncome = ExpenseEntity(
            id = "inc-builtin",
            amountRub = 100_000.0,
            merchant = "Зарплата",
            categoryName = ExpenseCategory.INCOME_SALARY.name,
            operationType = ""
        )
        assertEquals(OperationType.INCOME, legacyBuiltInIncome.effectiveOperationType)
        assertEquals(OperationType.INCOME, legacyBuiltInIncome.resolveOperationType(customRegistry))

        val customIncomeEntity = ExpenseEntity(
            id = "inc-custom",
            amountRub = 35_000.0,
            merchant = "Проект",
            categoryName = customIncome.id,
            operationType = ""
        )
        assertEquals(OperationType.INCOME, customIncomeEntity.resolveOperationType(customRegistry))
        assertTrue(customIncomeEntity.isIncome(customRegistry))
        assertFalse(customIncomeEntity.toJsonObject(customRegistry).has("operationType"))

        val customExpenseEntity = ExpenseEntity(
            id = "exp-custom",
            amountRub = 1_800.0,
            merchant = "Обжарщик",
            categoryName = customExpense.id,
            operationType = ""
        )
        assertEquals(OperationType.EXPENSE, customExpenseEntity.resolveOperationType(customRegistry))
        assertFalse(customExpenseEntity.isIncome(customRegistry))

        val csv = String(
            CsvExporter.buildCsvBytes(listOf(customIncomeEntity, customExpenseEntity), customRegistry),
            StandardCharsets.UTF_8
        )
        assertTrue(csv.contains(";Доход;Фриланс;"))
        assertTrue(csv.contains(";Расход;Зерновой кофе;"))
    }

    @Test
    fun advisorAndPickers_handleArchivedAndUnknownFallbackCategoriesSafely() {
        val archivedCustom = CategoryDescriptor(
            id = "custom_archived",
            displayName = "Старый кружок",
            shortName = "Кружок",
            emoji = "🎨",
            colorHex = 0xFFF59E0BL,
            isIncome = false,
            isBuiltIn = false,
            isArchived = true
        )
        val registry = CategoryRegistry(listOf(archivedCustom))

        // Архивная категория исключена из списка пикера, но резолвится для исторических записей
        assertFalse(registry.expenseCategories.any { it.id == archivedCustom.id })
        assertEquals("Старый кружок", registry.resolve(archivedCustom.id).displayName)

        val now = 1_780_000_000_000L
        val window = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, now)
        val expenses = listOf(
            ExpenseEntity(
                id = "e-arch",
                amountRub = 2_500.0,
                merchant = "Студия",
                categoryName = archivedCustom.id,
                createdAtEpochMs = now
            ),
            ExpenseEntity(
                id = "e-unknown",
                amountRub = 1_500.0,
                merchant = "С другого устройства",
                categoryName = "custom_from_other_phone",
                createdAtEpochMs = now
            )
        )

        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = 100_000.0,
            window = window,
            registry = registry
        )

        assertEquals(4_000.0, report.totalSpentThisMonthRub, 0.001)
        val archivedDiag = report.allCategories.firstOrNull { it.category.id == archivedCustom.id }
        assertNotNull(archivedDiag)
        assertEquals(2_500.0, archivedDiag!!.spentRub, 0.001)

        val unknownDiag = report.allCategories.firstOrNull { it.category.id == "custom_from_other_phone" }
        assertNotNull(unknownDiag)
        assertEquals("Другое", unknownDiag!!.category.displayName)
        assertEquals(1_500.0, unknownDiag.spentRub, 0.001)
    }
}
