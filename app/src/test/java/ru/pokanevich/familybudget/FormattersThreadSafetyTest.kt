package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.CsvExporter
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.MoneyMath
import ru.pokanevich.familybudget.data.sumRubOf
import ru.pokanevich.familybudget.ui.util.Formatters
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FormattersThreadSafetyTest {

    @Test
    fun `Formatters and CsvExporter handle concurrent multi-threaded formatting without corruption`() {
        val sampleExpenses = (1..20).map { i ->
            ExpenseEntity(
                id = "exp-$i",
                amountRub = i * 123.45,
                merchant = "Магазин $i",
                categoryName = ExpenseCategory.GROCERIES.name,
                createdAtEpochMs = 1_763_400_000_000L + i * 86_400_000L,
                updatedAtEpochMs = 1_763_400_000_000L + i * 86_400_000L
            )
        }

        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..40).map { worker ->
                Callable<Boolean> {
                    for (i in 0 until 25) {
                        val rub = (worker * 100 + i) + 0.45
                        val formatted = Formatters.formatRub(rub, showKopecksIfAny = true)
                        assertTrue(formatted.contains(",45\u00A0₽"))
                        assertTrue(Formatters.formatTimeOnly(1_763_400_000_000L + i * 60_000L).matches(Regex("\\d{2}:\\d{2}")))
                        assertTrue(Formatters.formatDayHeader(1_763_400_000_000L + i * 86_400_000L).isNotBlank())
                    }
                    val csvBytes = CsvExporter.buildCsvBytes(sampleExpenses)
                    String(csvBytes, Charsets.UTF_8).contains("123.45")
                }
            }
            val futures = executor.invokeAll(tasks)
            executor.shutdown()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            futures.forEach { assertTrue(it.get()) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `MoneyMath eliminates binary floating-point accumulation drift`() {
        val values = List(1000) { 0.1 }
        assertEquals(100.0, values.sumRubOf { it }, 0.0)
        assertEquals(10L, MoneyMath.toKopecks(0.1))
        assertEquals(123456L, MoneyMath.toKopecks(1234.56))
        assertEquals(1234.56, MoneyMath.fromKopecks(123456L), 0.0)
    }
}
