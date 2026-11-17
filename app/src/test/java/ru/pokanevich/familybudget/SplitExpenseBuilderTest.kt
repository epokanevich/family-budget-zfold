package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.SplitExpenseBuilder
import ru.pokanevich.familybudget.data.SplitPartInput

class SplitExpenseBuilderTest {

    @Test
    fun buildFromScratch_createsPartsWithSharedSplitGroupIdAndTimestamp() {
        val now = 1_700_000_123_000L
        val parts = listOf(
            SplitPartInput(amountRub = 1200.0, category = ExpenseCategory.GROCERIES, note = "Еда"),
            SplitPartInput(amountRub = 450.5, category = ExpenseCategory.HOUSEHOLD, note = "Химия"),
            SplitPartInput(amountRub = 349.5, category = ExpenseCategory.HEALTH, note = "")
        )

        val result = SplitExpenseBuilder.build(
            original = null,
            parts = parts,
            totalAmountRub = 2000.0,
            merchant = "Перекрёсток",
            authorName = "Анжелика",
            note = "Большая закупка",
            nowEpochMs = now
        )

        assertEquals(3, result.size)
        val groupId = result.first().splitGroupId
        assertTrue(groupId.isNotBlank())
        assertEquals(3, result.map { it.id }.toSet().size)
        assertEquals(2000.0, result.sumOf { it.amountRub }, 0.001)

        result.forEach { entity ->
            assertEquals(groupId, entity.splitGroupId)
            assertEquals(now, entity.createdAtEpochMs)
            assertEquals(now, entity.updatedAtEpochMs)
            assertEquals("Перекрёсток", entity.merchant)
            assertEquals("Анжелика", entity.authorName)
            assertEquals("MANUAL", entity.sourceType)
            assertEquals("", entity.dedupSignature)
            assertFalse(entity.isSynced)
            assertFalse(entity.isPendingConfirmation)
            assertEquals(OperationType.EXPENSE, entity.effectiveOperationType)
        }

        assertEquals("Еда • Большая закупка", result[0].note)
        assertEquals("Химия • Большая закупка", result[1].note)
        assertEquals("Большая закупка", result[2].note)
    }

    @Test
    fun buildFromPushOriginal_preservesIdAndDedupSignatureOnFirstPartOnly() {
        val original = ExpenseEntity(
            id = "push-uuid-001",
            amountRub = 1500.0,
            merchant = "PYATEROCHKA",
            categoryName = ExpenseCategory.GROCERIES.name,
            authorName = "Евгений",
            sourceType = "PUSH_MIRPAY",
            isPendingConfirmation = true,
            isSynced = true,
            createdAtEpochMs = 1_700_000_000_000L,
            updatedAtEpochMs = 1_700_000_000_000L,
            rawPushOrQrText = "Оплата 1500 ₽ PYATEROCHKA",
            dedupSignature = "AMT_1500.00"
        )
        val now = 1_700_000_050_000L

        val result = SplitExpenseBuilder.build(
            original = original,
            parts = listOf(
                SplitPartInput(1000.0, ExpenseCategory.GROCERIES, "Продукты"),
                SplitPartInput(500.0, ExpenseCategory.HOUSEHOLD, "Бытовое")
            ),
            totalAmountRub = 1500.0,
            merchant = "Пятёрочка",
            authorName = "Евгений",
            note = "",
            nowEpochMs = now
        )

        assertEquals(2, result.size)
        val first = result[0]
        val second = result[1]

        // Общий splitGroupId и исходный createdAtEpochMs
        assertTrue(first.splitGroupId.isNotBlank())
        assertEquals(first.splitGroupId, second.splitGroupId)
        assertEquals(original.createdAtEpochMs, first.createdAtEpochMs)
        assertEquals(original.createdAtEpochMs, second.createdAtEpochMs)

        // Первая часть сохраняет id, sourceType, rawPushOrQrText и исходную dedupSignature
        assertEquals(original.id, first.id)
        assertEquals("PUSH_MIRPAY", first.sourceType)
        assertEquals(original.rawPushOrQrText, first.rawPushOrQrText)
        assertEquals("AMT_1500.00", first.dedupSignature)
        assertFalse(first.isPendingConfirmation)
        assertFalse(first.isSynced)
        assertEquals(now, first.updatedAtEpochMs)

        // Вторая часть получает новый UUID и пустой dedupSignature
        assertNotEquals(original.id, second.id)
        assertEquals("", second.dedupSignature)
        assertEquals("", second.rawPushOrQrText)
        assertFalse(second.isPendingConfirmation)
        assertFalse(second.isSynced)
    }

    @Test
    fun build_acceptsSumWithinOneKopeckTolerance() {
        val result = SplitExpenseBuilder.build(
            original = null,
            parts = listOf(
                SplitPartInput(333.33, ExpenseCategory.GROCERIES),
                SplitPartInput(333.33, ExpenseCategory.HOUSEHOLD),
                SplitPartInput(333.33, ExpenseCategory.OTHER)
            ),
            totalAmountRub = 1000.00,
            merchant = "Лента",
            authorName = "Евгений",
            note = ""
        )

        assertEquals(3, result.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun build_rejectsSinglePart() {
        SplitExpenseBuilder.build(
            original = null,
            parts = listOf(SplitPartInput(500.0, ExpenseCategory.GROCERIES)),
            totalAmountRub = 500.0,
            merchant = "Ашан",
            authorName = "Евгений",
            note = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun build_rejectsZeroOrNegativePartAmount() {
        SplitExpenseBuilder.build(
            original = null,
            parts = listOf(
                SplitPartInput(500.0, ExpenseCategory.GROCERIES),
                SplitPartInput(0.0, ExpenseCategory.HOUSEHOLD)
            ),
            totalAmountRub = 500.0,
            merchant = "Ашан",
            authorName = "Евгений",
            note = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun build_rejectsSumMismatchBeyondTolerance() {
        SplitExpenseBuilder.build(
            original = null,
            parts = listOf(
                SplitPartInput(400.0, ExpenseCategory.GROCERIES),
                SplitPartInput(599.98, ExpenseCategory.HOUSEHOLD)
            ),
            totalAmountRub = 1000.0,
            merchant = "Ашан",
            authorName = "Евгений",
            note = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun build_rejectsNonPositiveTotalAmount() {
        SplitExpenseBuilder.build(
            original = null,
            parts = listOf(
                SplitPartInput(100.0, ExpenseCategory.GROCERIES),
                SplitPartInput(100.0, ExpenseCategory.HOUSEHOLD)
            ),
            totalAmountRub = 0.0,
            merchant = "Ашан",
            authorName = "Евгений",
            note = ""
        )
    }
}
