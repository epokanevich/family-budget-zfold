package ru.pokanevich.familybudget.data

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class CsvImportResult(
    val parsedTotal: Int,
    val newlyAdded: Int,
    val skippedDuplicates: Int
)

data class CsvParsedBatch(
    val items: List<ExpenseEntity>,
    val skippedInvalidRows: Int
)

data class CsvDeduplicationResult(
    val toInsert: List<ExpenseEntity>,
    val skippedDuplicates: Int
)

/**
 * Парсер и дедупликатор собственного CSV-формата [CsvExporter] (UTF-8 BOM, разделитель `;`, 10 колонок).
 */
object CsvImporter {

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)
    private val TIME_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    )
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@')

    fun parseCsvBytes(
        bytes: ByteArray,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        defaultAuthorName: String = "Евгений",
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): CsvParsedBatch {
        val text = String(bytes, StandardCharsets.UTF_8)
        return parseCsvText(
            rawText = text,
            registry = registry,
            defaultAuthorName = defaultAuthorName,
            zoneId = zoneId,
            nowEpochMs = nowEpochMs
        )
    }

    fun parseCsvText(
        rawText: String,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        defaultAuthorName: String = "Евгений",
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): CsvParsedBatch {
        val cleaned = rawText.removePrefix("\uFEFF")
        val rows = parseDelimitedRows(cleaned, delimiter = ';')
        if (rows.isEmpty()) {
            throw IllegalStateException("CSV-файл пуст")
        }

        val header = rows.first().map { it.trim() }
        val looksLikeNativeCsv = header.size >= 6 &&
            header[0].contains("Дата", ignoreCase = true) &&
            header[4].contains("Сумма", ignoreCase = true)
        if (!looksLikeNativeCsv) {
            throw IllegalStateException("Неверный формат заголовка CSV (ожидается экспорт Family Budget)")
        }

        val parsed = mutableListOf<ExpenseEntity>()
        val occurrenceByFingerprint = HashMap<String, Int>()
        var skippedInvalid = 0

        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.all { it.isBlank() }) continue
            if (row.size < 6) {
                skippedInvalid++
                continue
            }

            val dateStr = row[0].trim()
            val timeStr = row[1].trim()
            val typeStr = row[2].trim()
            val categoryStr = unescapeFormulaPrefix(row[3].trim())
            val amountRaw = row[4].trim()
                .replace("\u00A0", "")
                .replace(" ", "")
                .replace("₽", "")
                .replace(",", ".")
            val merchantStr = unescapeFormulaPrefix(row[5].trim())
            val noteStr = if (row.size > 6) unescapeFormulaPrefix(row[6].trim()) else ""
            val authorStr = if (row.size > 7) {
                unescapeFormulaPrefix(row[7].trim()).ifBlank { defaultAuthorName }
            } else {
                defaultAuthorName
            }
            val sourceStr = if (row.size > 8) {
                row[8].trim().ifBlank { "MANUAL" }
            } else {
                "MANUAL"
            }
            val splitGroupStr = if (row.size > 9) row[9].trim() else ""

            val epochMs = parseDateTimeEpochMs(dateStr, timeStr, zoneId)
            val amount = amountRaw.toDoubleOrNull()
            if (epochMs == null || amount == null || !amount.isFinite() || amount <= 0.0) {
                skippedInvalid++
                continue
            }

            val opType = resolveOperationType(typeStr)
            val resolvedCategoryId = resolveCategoryId(categoryStr, opType, registry)
            val storedOpType = if (opType == OperationType.EXPENSE) "" else opType.name
            val amountKopecks = MoneyMath.toKopecks(amount)
            val goalId = ""

            val baseFingerprint = listOf(
                dateStr,
                timeStr,
                opType.name,
                resolvedCategoryId,
                amountKopecks.toString(),
                merchantStr.lowercase(Locale.ROOT),
                authorStr.lowercase(Locale.ROOT),
                splitGroupStr
            ).joinToString("|")
            val occ = (occurrenceByFingerprint[baseFingerprint] ?: 0) + 1
            occurrenceByFingerprint[baseFingerprint] = occ

            val deterministicId = UUID.nameUUIDFromBytes(
                "csv_v1|$occ|$baseFingerprint".toByteArray(StandardCharsets.UTF_8)
            ).toString()

            val dedupSig = if (sourceStr.startsWith("PUSH_")) "AMT_$amountKopecks" else ""

            parsed.add(
                ExpenseEntity(
                    id = deterministicId,
                    amountRub = MoneyMath.fromKopecks(amountKopecks),
                    merchant = merchantStr.ifBlank { "Покупка по карте" },
                    categoryName = resolvedCategoryId,
                    authorName = authorStr,
                    sourceType = sourceStr,
                    isPendingConfirmation = false,
                    isDeleted = false,
                    isSynced = false,
                    createdAtEpochMs = epochMs,
                    updatedAtEpochMs = nowEpochMs,
                    rawPushOrQrText = "",
                    dedupSignature = dedupSig,
                    note = noteStr,
                    splitGroupId = splitGroupStr,
                    operationType = storedOpType,
                    goalId = goalId
                )
            )
        }

        return CsvParsedBatch(items = parsed, skippedInvalidRows = skippedInvalid)
    }

    fun deduplicateAgainstExisting(
        candidates: List<ExpenseEntity>,
        existing: List<ExpenseEntity>,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CsvDeduplicationResult {
        if (candidates.isEmpty()) return CsvDeduplicationResult(emptyList(), 0)

        val existingIds = existing.asSequence().map { it.id }.toHashSet()
        val activeBucketCounts = HashMap<DeduplicationKey, Int>()
        for (item in existing) {
            if (item.isDeleted) continue
            val key = buildDeduplicationKey(item, registry, zoneId)
            activeBucketCounts[key] = (activeBucketCounts[key] ?: 0) + 1
        }

        val toInsert = ArrayList<ExpenseEntity>(candidates.size)
        var skipped = 0

        for (candidate in candidates) {
            if (candidate.id in existingIds) {
                skipped++
                continue
            }
            val key = buildDeduplicationKey(candidate, registry, zoneId)
            val available = activeBucketCounts[key] ?: 0
            if (available > 0) {
                activeBucketCounts[key] = available - 1
                skipped++
            } else {
                toInsert.add(candidate)
            }
        }

        return CsvDeduplicationResult(toInsert = toInsert, skippedDuplicates = skipped)
    }

    private data class DeduplicationKey(
        val amountKopecks: Long,
        val operationType: OperationType,
        val minuteEpoch: Long,
        val merchantNormalized: String,
        val authorNormalized: String
    )

    private fun buildDeduplicationKey(
        expense: ExpenseEntity,
        registry: CategoryRegistry,
        zoneId: ZoneId
    ): DeduplicationKey {
        val minuteInstant = Instant.ofEpochMilli(expense.createdAtEpochMs)
            .atZone(zoneId)
            .truncatedTo(ChronoUnit.MINUTES)
            .toEpochSecond() / 60L
        return DeduplicationKey(
            amountKopecks = MoneyMath.toKopecks(expense.amountRub),
            operationType = expense.resolveOperationType(registry),
            minuteEpoch = minuteInstant,
            merchantNormalized = expense.merchant.trim().lowercase(Locale.ROOT),
            authorNormalized = expense.authorName.trim().lowercase(Locale.ROOT)
        )
    }

    private fun parseDateTimeEpochMs(dateStr: String, timeStr: String, zoneId: ZoneId): Long? {
        val date = runCatching { LocalDate.parse(dateStr, DATE_FORMATTER) }.getOrNull() ?: return null
        val time = TIME_FORMATTERS.firstNotNullOfOrNull { fmt ->
            runCatching { LocalTime.parse(timeStr, fmt) }.getOrNull()
        } ?: return null
        return LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun resolveOperationType(raw: String): OperationType = when (raw.trim().lowercase(Locale.ROOT)) {
        "доход", "income" -> OperationType.INCOME
        "перевод", "transfer" -> OperationType.TRANSFER
        else -> OperationType.EXPENSE
    }

    private fun resolveCategoryId(
        rawCategory: String,
        opType: OperationType,
        registry: CategoryRegistry
    ): String {
        val trimmed = rawCategory.trim()
        if (trimmed.equals("Пополнение копилки", ignoreCase = true)) {
            return SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT
        }
        if (trimmed.equals("Снятие с копилки", ignoreCase = true)) {
            return SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW
        }
        val allDescriptors = registry.expenseCategories + registry.incomeCategories
        allDescriptors.firstOrNull { desc ->
            desc.id.equals(trimmed, ignoreCase = true) ||
                desc.displayName.equals(trimmed, ignoreCase = true) ||
                desc.shortName.equals(trimmed, ignoreCase = true)
        }?.let { return it.id }

        ExpenseCategory.entries.firstOrNull { cat ->
            cat.name.equals(trimmed, ignoreCase = true) ||
                cat.displayName.equals(trimmed, ignoreCase = true) ||
                cat.shortName.equals(trimmed, ignoreCase = true)
        }?.let { return it.name }

        return if (opType == OperationType.INCOME) {
            ExpenseCategory.INCOME_OTHER.name
        } else {
            ExpenseCategory.OTHER.name
        }
    }

    internal fun unescapeFormulaPrefix(value: String): String {
        if (value.length >= 2 && value[0] == '\'' && value[1] in FORMULA_PREFIXES) {
            return value.substring(1)
        }
        return value
    }

    /**
     * Разбирает CSV-таблицу с поддержкой кавычек, удвоенных кавычек `""` и переносов строк внутри полей.
     */
    internal fun parseDelimitedRows(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var currentRow = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    delimiter -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> {
                        if (i + 1 < len && text[i + 1] == '\n') i++
                        currentRow.add(field.toString())
                        field.setLength(0)
                        rows.add(currentRow)
                        currentRow = ArrayList()
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                        rows.add(currentRow)
                        currentRow = ArrayList()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }

        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            rows.add(currentRow)
        }

        return rows
    }
}
