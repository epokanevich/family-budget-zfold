package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.CsvExporter
import ru.pokanevich.familybudget.data.CsvImporter
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.SavingsGoalCalculator
import java.time.LocalDateTime
import java.time.ZoneId

class CsvImporterTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `parseCsvBytes performs lossless roundtrip with quotes, newlines, and formula guards`() {
        val original = listOf(
            ExpenseEntity(
                id = "e1",
                amountRub = 1234.56,
                merchant = "Пятёрочка; \"Север\"",
                categoryName = ExpenseCategory.GROCERIES.name,
                authorName = "Анжелика",
                sourceType = "PUSH_SBER",
                note = "-скидка 10%\nвторая строка",
                splitGroupId = "split-42",
                createdAtEpochMs = epochMs(2026, 11, 20, 14, 25),
                updatedAtEpochMs = epochMs(2026, 11, 20, 14, 25)
            ),
            ExpenseEntity(
                id = "e2",
                amountRub = 95000.0,
                merchant = "+бонус к зарплате",
                categoryName = ExpenseCategory.INCOME_SALARY.name,
                authorName = "Евгений",
                sourceType = "MANUAL",
                operationType = OperationType.INCOME.name,
                createdAtEpochMs = epochMs(2026, 11, 15, 10, 0),
                updatedAtEpochMs = epochMs(2026, 11, 15, 10, 0)
            ),
            ExpenseEntity(
                id = "e3",
                amountRub = 5000.0,
                merchant = "На отпуск",
                categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT,
                authorName = "Евгений",
                sourceType = "MANUAL",
                operationType = OperationType.TRANSFER.name,
                createdAtEpochMs = epochMs(2026, 11, 16, 11, 15),
                updatedAtEpochMs = epochMs(2026, 11, 16, 11, 15)
            )
        )

        val csvBytes = CsvExporter.buildCsvBytes(original)
        val batch = CsvImporter.parseCsvBytes(csvBytes, zoneId = zone)

        assertEquals(0, batch.skippedInvalidRows)
        assertEquals(3, batch.items.size)

        val first = batch.items[0]
        assertEquals(1234.56, first.amountRub, 0.001)
        assertEquals("Пятёрочка; \"Север\"", first.merchant)
        assertEquals(ExpenseCategory.GROCERIES.name, first.categoryName)
        assertEquals("-скидка 10%\nвторая строка", first.note)
        assertEquals("Анжелика", first.authorName)
        assertEquals("PUSH_SBER", first.sourceType)
        assertEquals("split-42", first.splitGroupId)

        val goalRow = batch.items[1]
        assertEquals(SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT, goalRow.categoryName)
        assertEquals(OperationType.TRANSFER, goalRow.effectiveOperationType)

        val incomeRow = batch.items[2]
        assertEquals("+бонус к зарплате", incomeRow.merchant)
        assertEquals(ExpenseCategory.INCOME_SALARY.name, incomeRow.categoryName)
        assertEquals(OperationType.INCOME, incomeRow.effectiveOperationType)
    }

    @Test
    fun `deduplicateAgainstExisting skips minute-matched rows even with different seconds while keeping new and second identical purchase`() {
        val existing = listOf(
            ExpenseEntity(
                id = "existing-uuid-1",
                amountRub = 450.0,
                merchant = "Кофемания",
                categoryName = ExpenseCategory.CAFES.name,
                authorName = "Евгений",
                // Секунды отличны от 00, но минута совпадает с CSV (14:25)
                createdAtEpochMs = epochMs(2026, 11, 20, 14, 25, second = 43)
            ),
            ExpenseEntity(
                id = "deleted-row",
                amountRub = 700.0,
                merchant = "Аптека",
                categoryName = ExpenseCategory.HEALTH.name,
                authorName = "Евгений",
                isDeleted = true,
                createdAtEpochMs = epochMs(2026, 11, 20, 15, 10, second = 12)
            )
        )

        val csvInput = listOf(
            // Дубль existing-uuid-1 по сумме/минуте/мерчанту
            ExpenseEntity(
                id = "csv-dup-1",
                amountRub = 450.0,
                merchant = "Кофемания",
                categoryName = ExpenseCategory.CAFES.name,
                authorName = "Евгений",
                createdAtEpochMs = epochMs(2026, 11, 20, 14, 25, second = 0)
            ),
            // Вторая такая же покупка в ту же минуту — не дубликат existing-uuid-1 (1-to-1 multiset)
            ExpenseEntity(
                id = "csv-second-coffee",
                amountRub = 450.0,
                merchant = "Кофемания",
                categoryName = ExpenseCategory.CAFES.name,
                authorName = "Евгений",
                createdAtEpochMs = epochMs(2026, 11, 20, 14, 25, second = 0)
            ),
            // Вторая новая строка (deleted-row не считается активным дублем)
            ExpenseEntity(
                id = "csv-new-pharmacy",
                amountRub = 700.0,
                merchant = "Аптека",
                categoryName = ExpenseCategory.HEALTH.name,
                authorName = "Евгений",
                createdAtEpochMs = epochMs(2026, 11, 20, 15, 10, second = 0)
            )
        )

        val res = CsvImporter.deduplicateAgainstExisting(csvInput, existing, zoneId = zone)

        assertEquals(1, res.skippedDuplicates)
        assertEquals(2, res.toInsert.size)
        assertTrue(res.toInsert.any { it.id == "csv-second-coffee" })
        assertTrue(res.toInsert.any { it.id == "csv-new-pharmacy" })
    }
}
