package ru.pokanevich.familybudget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.CsvExporter
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import java.nio.charset.StandardCharsets

class CsvExporterTest {

    @Test
    fun buildCsvBytes_startsWithUtf8Bom() {
        val bytes = CsvExporter.buildCsvBytes(emptyList())
        assertTrue(bytes.size >= 3)
        // UTF-8 BOM: 0xEF, 0xBB, 0xBF
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertArrayEquals(bomBytes, bytes.copyOfRange(0, 3))
    }

    @Test
    fun buildCsvBytes_hasCorrectHeaderAndDelimiter() {
        val bytes = CsvExporter.buildCsvBytes(emptyList())
        val csvText = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val lines = csvText.split("\r\n")

        val expectedHeader = "Дата;Время;Тип;Категория;Сумма (₽);Магазин/Описание;Заметка;Автор;Источник;Split ID"
        assertEquals(expectedHeader, lines.first())
    }

    @Test
    fun buildCsvBytes_filtersDeletedAndPendingAndSortsDescending() {
        val older = ExpenseEntity(
            id = "1",
            amountRub = 100.0,
            merchant = "Магнит",
            categoryName = ExpenseCategory.GROCERIES.name,
            createdAtEpochMs = 1_000_000L
        )
        val newer = ExpenseEntity(
            id = "2",
            amountRub = 200.0,
            merchant = "Пятёрочка",
            categoryName = ExpenseCategory.GROCERIES.name,
            createdAtEpochMs = 2_000_000L
        )
        val deleted = ExpenseEntity(
            id = "3",
            amountRub = 300.0,
            merchant = "Удалено",
            isDeleted = true,
            createdAtEpochMs = 3_000_000L
        )
        val pending = ExpenseEntity(
            id = "4",
            amountRub = 400.0,
            merchant = "Входящий пуш",
            isPendingConfirmation = true,
            createdAtEpochMs = 4_000_000L
        )

        val bytes = CsvExporter.buildCsvBytes(listOf(older, deleted, pending, newer))
        val csvText = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val lines = csvText.split("\r\n").filter { it.isNotBlank() }

        assertEquals(3, lines.size) // Заголовок + 2 записи
        assertFalse(csvText.contains("Удалено"))
        assertFalse(csvText.contains("Входящий пуш"))

        // newer (200.0) должен быть первой строкой после заголовка, older (100.0) — второй
        assertTrue(lines[1].contains("Пятёрочка"))
        assertTrue(lines[1].contains("200.00"))
        assertTrue(lines[2].contains("Магнит"))
        assertTrue(lines[2].contains("100.00"))
    }

    @Test
    fun buildCsvBytes_formatsOperationTypesCorrectly() {
        val expense = ExpenseEntity(
            id = "1",
            amountRub = 150.0,
            merchant = "Кофе",
            categoryName = ExpenseCategory.CAFES.name,
            operationType = "EXPENSE"
        )
        val income = ExpenseEntity(
            id = "2",
            amountRub = 50000.0,
            merchant = "Премия",
            categoryName = ExpenseCategory.INCOME_SALARY.name,
            operationType = "INCOME"
        )
        val transfer = ExpenseEntity(
            id = "3",
            amountRub = 10000.0,
            merchant = "На накопительный счёт",
            categoryName = ExpenseCategory.OTHER.name,
            operationType = "TRANSFER"
        )

        val bytes = CsvExporter.buildCsvBytes(listOf(expense, income, transfer))
        val csvText = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")

        assertTrue(csvText.contains(";Расход;"))
        assertTrue(csvText.contains(";Доход;"))
        assertTrue(csvText.contains(";Перевод;"))
    }

    @Test
    fun buildCsvBytes_escapesQuotesSemicolonsNewlinesAndFormulas() {
        val complexExpense = ExpenseEntity(
            id = "1",
            amountRub = 999.0,
            merchant = "ООО \"Рога; и копыта\"",
            note = "=SUM(A1:A10)\nВторая строка; с точкой",
            categoryName = ExpenseCategory.SHOPPING.name,
            splitGroupId = "split-xyz-123"
        )

        val bytes = CsvExporter.buildCsvBytes(listOf(complexExpense))
        val csvText = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")

        // Проверка экранирования кавычек и точки с запятой в merchant
        assertTrue(csvText.contains("\"ООО \"\"Рога; и копыта\"\"\""))

        // Проверка нейтрализации ведущей формулы '=' (добавление ') и переноса строки
        assertTrue(csvText.contains("\"'=SUM(A1:A10)\nВторая строка; с точкой\""))

        // Split ID присутствует
        assertTrue(csvText.contains("split-xyz-123"))
    }

    @Test
    fun escapeField_neutralizesFormulaCharsInTextFields() {
        assertEquals("\"'=1+2\"", CsvExporter.escapeField("=1+2", isTextField = true))
        assertEquals("\"'+cmd\"", CsvExporter.escapeField("+cmd", isTextField = true))
        assertEquals("\"'-minus\"", CsvExporter.escapeField("-minus", isTextField = true))
        assertEquals("\"'@mention\"", CsvExporter.escapeField("@mention", isTextField = true))

        // Не текстовое поле не экранирует формулы
        assertEquals("-10.5", CsvExporter.escapeField("-10.5", isTextField = false))
    }
}
