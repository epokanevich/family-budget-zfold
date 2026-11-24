package ru.pokanevich.familybudget.data

import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private val ruLocale = Locale("ru", "RU")
    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yyyy", ruLocale) }
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", ruLocale) }

    private val amountFormatter = ThreadLocal.withInitial {
        DecimalFormat("0.00", DecimalFormatSymbols(Locale.US).apply {
            decimalSeparator = '.'
        })
    }

    private const val CSV_DELIMITER = ";"
    private const val CSV_EOL = "\r\n"

    private val FORMULA_CHARS = charArrayOf('=', '+', '-', '@')

    fun buildCsvBytes(
        expenses: List<ExpenseEntity>,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT
    ): ByteArray {
        val filteredAndSorted = expenses
            .asSequence()
            .filter { !it.isDeleted && !it.isPendingConfirmation }
            .sortedByDescending { it.createdAtEpochMs }
            .toList()

        val outputStream = ByteArrayOutputStream()

        // UTF-8 BOM: 0xEF, 0xBB, 0xBF
        outputStream.write(0xEF)
        outputStream.write(0xBB)
        outputStream.write(0xBF)

        val writer = OutputStreamWriter(outputStream, StandardCharsets.UTF_8)

        // Header
        val headers = listOf(
            "Дата",
            "Время",
            "Тип",
            "Категория",
            "Сумма (₽)",
            "Магазин/Описание",
            "Заметка",
            "Автор",
            "Источник",
            "Split ID"
        )
        writer.write(headers.joinToString(separator = CSV_DELIMITER, postfix = CSV_EOL))

        for (expense in filteredAndSorted) {
            val date = Date(expense.createdAtEpochMs)
            val dateStr = dateFormat.get()!!.format(date)
            val timeStr = timeFormat.get()!!.format(date)

            val typeStr = when (expense.resolveOperationType(registry)) {
                OperationType.EXPENSE -> "Расход"
                OperationType.INCOME -> "Доход"
                OperationType.TRANSFER -> "Перевод"
            }

            val categoryStr = when (expense.categoryName) {
                SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT -> "Пополнение копилки"
                SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW -> "Снятие с копилки"
                else -> registry.resolve(expense.categoryName).displayName
            }
            val amountStr = amountFormatter.get()!!.format(expense.amountRub)
            val merchantStr = expense.merchant
            val noteStr = expense.note
            val authorStr = expense.authorName
            val sourceStr = expense.sourceType
            val splitIdStr = expense.splitGroupId

            val row = listOf(
                escapeField(dateStr, isTextField = false),
                escapeField(timeStr, isTextField = false),
                escapeField(typeStr, isTextField = false),
                escapeField(categoryStr, isTextField = true),
                escapeField(amountStr, isTextField = false),
                escapeField(merchantStr, isTextField = true),
                escapeField(noteStr, isTextField = true),
                escapeField(authorStr, isTextField = true),
                escapeField(sourceStr, isTextField = false),
                escapeField(splitIdStr, isTextField = false)
            )

            writer.write(row.joinToString(separator = CSV_DELIMITER, postfix = CSV_EOL))
        }

        writer.flush()
        return outputStream.toByteArray()
    }

    /**
     * Экранирует значение для CSV:
     * 1. В текстовых полях нейтрализует формульные инъекции (=, +, -, @ в начале строки) через одинарную кавычку.
     * 2. Если поле содержит разделитель (;), кавычки ("), символы перевода строки (\r, \n),
     *    или начинается с одинарной кавычки / пробела, оборачивает в двойные кавычки, удваивая внутренние кавычки ("").
     */
    internal fun escapeField(raw: String, isTextField: Boolean = false): String {
        var value = raw
        if (isTextField && value.isNotEmpty()) {
            val firstChar = value[0]
            if (firstChar in FORMULA_CHARS) {
                value = "'$value"
            }
        }

        val needsQuotes = value.contains(CSV_DELIMITER) ||
            value.contains("\"") ||
            value.contains("\n") ||
            value.contains("\r") ||
            value.startsWith("'")

        return if (needsQuotes) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
