package ru.pokanevich.familybudget.ai

import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.MonthlyTrendCalculator
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.data.SavingsGoalCalculator
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodWindow
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Компактный срез семейной базы для одного вопроса в чате с ИИ-советником.
 *
 * ВАЖНО: ни одно поле этого класса не формируется из [ExpenseEntity.rawPushOrQrText]
 * (там исходный текст пуша банка с номерами карт и балансами).
 */
data class AiChatContextSnapshot(
    /** Базовая сводка: 12 месяцев, категории текущего месяца, регулярные платежи, копилки, топ мерчантов. */
    val baseSummary: String,
    /** Описание критериев локального поиска по вопросу (для прозрачности модели). */
    val searchCriteria: String,
    /** Агрегаты по ВСЕМ найденным записям (а не только по приложенным), чтобы ИИ не считал суммы по выборке. */
    val matchedAggregates: String,
    /** Приложенные записи в формате `yyyy-MM-dd | Тип | Категория | Сумма ₽ | Мерчант | Заметка | Автор`. */
    val relevantRecordLines: List<String>,
    /** Сколько записей всего подошло под критерии поиска. */
    val totalMatchedCount: Int,
    /** Сколько подтверждённых неудалённых записей в базе всего. */
    val totalRecordsInDatabase: Int
) {
    val attachedRecordsCount: Int get() = relevantRecordLines.size

    fun toPromptText(): String = buildString {
        appendLine("=== СВОДКА СЕМЕЙНОЙ БАЗЫ ===")
        appendLine(baseSummary.trim())
        appendLine()
        appendLine("=== ЛОКАЛЬНЫЙ ПОИСК ПО ВОПРОСУ ===")
        appendLine(searchCriteria.trim())
        if (matchedAggregates.isNotBlank()) {
            appendLine(matchedAggregates.trim())
        }
        if (relevantRecordLines.isNotEmpty()) {
            appendLine("Записи (дата | тип | категория | сумма | мерчант | заметка | автор):")
            relevantRecordLines.forEach { appendLine(it) }
        }
    }.trim()

    /** Короткая подпись для UI: сколько записей ушло в промпт. */
    fun shortUiNote(): String = when {
        totalMatchedCount == 0 -> "Сводка базы ($totalRecordsInDatabase зап.), без отдельных записей"
        attachedRecordsCount < totalMatchedCount ->
            "Сводка + $attachedRecordsCount из $totalMatchedCount найденных записей"
        else -> "Сводка + $attachedRecordsCount найденных записей"
    }
}

/**
 * Готовит для Gemini компактный срез базы (4 000+ записей нельзя отправлять целиком из-за лимитов Free Tier):
 * 1) базовый снимок агрегатов;
 * 2) до [MAX_RELEVANT_RECORDS] самых релевантных вопросу записей, найденных локально
 *    по ключевым словам, категориям, заметкам, месяцам/году.
 *
 * Поле [ExpenseEntity.rawPushOrQrText] никогда не читается этим объектом.
 */
object AiDatabaseContextBuilder {

    const val MAX_RELEVANT_RECORDS = 50
    const val TOP_MERCHANTS_LIMIT = 15
    private const val SUMMARY_MONTHS = 12
    private const val MAX_FREE_TEXT_LENGTH = 60
    private const val MAX_MONTH_BREAKDOWN_LINES = 12

    private val ruLocale = Locale("ru", "RU")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private val monthStems = listOf(
        "январ", "феврал", "март", "апрел", "ма", "июн",
        "июл", "август", "сентябр", "октябр", "ноябр", "декабр"
    )
    private val monthNamesNominative = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    )

    /** Синонимы: вопрос «про маркетплейсы» должен находить OZON / Wildberries и т.п. */
    private val synonymGroups: Map<String, List<String>> = mapOf(
        "маркетплейс" to listOf(
            "ozon", "озон", "wildberries", "вайлдберриз", "валдберис", "wb ", "яндекс маркет",
            "яндекс.маркет", "yandex market", "market", "мегамаркет", "megamarket", "aliexpress",
            "алиэкспресс", "lamoda", "ламода", "avito", "авито"
        ),
        "озон" to listOf("ozon", "озон"),
        "вайлдберр" to listOf("wildberries", "вайлдберриз", "wb "),
        "такси" to listOf("такси", "taxi", "yandex.go", "яндекс го", "yandex go", "uber", "ситимобил"),
        "доставк" to listOf("самокат", "samokat", "яндекс еда", "yandex.eda", "delivery", "деливери", "доставк"),
        "кофе" to listOf("кофе", "coffee", "кофейн", "skuratov"),
        "аптек" to listOf("аптек", "apteka", "ригла", "планета здоровья", "горздрав"),
        "заправк" to listOf("азс", "лукойл", "lukoil", "газпромнефть", "роснефть", "татнефть", "shell"),
        "бензин" to listOf("азс", "лукойл", "lukoil", "газпромнефть", "роснефть", "татнефть", "shell"),
        "подписк" to listOf("подписк", "яндекс плюс", "yandex plus", "кинопоиск", "okko", "ivi", "spotify", "youtube"),
        "жкх" to listOf("жкх", "коммунал", "квартплат", "энерг", "водоканал", "интернет", "ростелеком")
    )

    private val shortTokenSynonyms: Map<String, List<String>> = mapOf(
        "вб" to listOf("wildberries", "вайлдберриз", "wb "),
        "wb" to listOf("wildberries", "вайлдберриз", "wb ")
    )

    private val stopWords = setOf(
        "что", "как", "чем", "где", "кто", "это", "эти", "эта", "этот", "все", "всё", "всего",
        "сколько", "мы", "нас", "наш", "наша", "наши", "наших", "нашем", "мне", "меня", "вы", "ты",
        "на", "за", "по", "из", "от", "до", "для", "при", "про", "под", "над", "без", "или", "and",
        "ушло", "ушли", "уходит", "было", "были", "был", "была", "есть", "будет", "можно", "нужно",
        "стоит", "какие", "какой", "какая", "каких", "каком", "почему", "зачем", "когда", "самые",
        "самый", "самая", "больше", "меньше", "чаще", "реже", "очень", "ещё", "еще", "уже", "сейчас",
        "покажи", "скажи", "расскажи", "посчитай", "сравни", "назови", "дай", "подскажи"
    )

    /** Слова-«шум» для поиска (типы операций, периоды и общие финансовые термины) — по префиксу. */
    private val stopPrefixes = listOf(
        "потрат", "трат", "тратил", "расход", "доход", "сэконом", "эконом", "денег", "деньг", "рубл",
        "месяц", "недел", "полгод", "полугод", "года", "году", "год", "сегодн", "вчера", "текущ",
        "прошл", "позапрошл", "этом", "этот", "последн", "бюджет", "семь", "семей", "итог", "сумм",
        "средн", "динамик", "тренд", "покупа", "купил", "оплат", "плат", "запис", "операц",
        "лимит", "категор", "совет", "помоги", "анализ", "разбор", "общ", "всег", "сколь"
    )

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    fun build(
        question: String,
        expenses: List<ExpenseEntity>,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        monthlyBudgetRub: Double = 0.0,
        recurringPayments: List<RecurringPaymentEntity> = emptyList(),
        savingsGoals: List<SavingsGoalEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        maxRecords: Int = MAX_RELEVANT_RECORDS
    ): AiChatContextSnapshot {
        val confirmed = confirmedPool(expenses)
        val base = buildBaseSnapshot(
            expenses = expenses,
            registry = registry,
            monthlyBudgetRub = monthlyBudgetRub,
            recurringPayments = recurringPayments,
            savingsGoals = savingsGoals,
            nowMs = nowMs,
            zoneId = zoneId
        )
        val search = searchRelevant(question, confirmed, registry, nowMs, zoneId, maxRecords)
        return AiChatContextSnapshot(
            baseSummary = base,
            searchCriteria = search.criteriaText,
            matchedAggregates = search.aggregatesText,
            relevantRecordLines = search.records.map { formatRecordLine(it, registry, zoneId) },
            totalMatchedCount = search.totalMatched,
            totalRecordsInDatabase = confirmed.size
        )
    }

    /** Подходящие для анализа записи: подтверждённые и не удалённые. */
    fun confirmedPool(expenses: List<ExpenseEntity>): List<ExpenseEntity> =
        expenses.filter { !it.isDeleted && !it.isPendingConfirmation && it.amountRub.isFinite() }

    /** Локальный поиск до [limit] самых релевантных вопросу записей. */
    fun findRelevantRecords(
        question: String,
        expenses: List<ExpenseEntity>,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        limit: Int = MAX_RELEVANT_RECORDS
    ): List<ExpenseEntity> =
        searchRelevant(question, confirmedPool(expenses), registry, nowMs, zoneId, limit).records

    /** `yyyy-MM-dd | Тип | Категория | Сумма ₽ | Мерчант | Заметка | Автор` — без rawPushOrQrText. */
    fun formatRecordLine(
        expense: ExpenseEntity,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val date = Instant.ofEpochMilli(expense.createdAtEpochMs).atZone(zoneId).toLocalDate().format(dateFormatter)
        val type = when (expense.resolveOperationType(registry)) {
            OperationType.EXPENSE -> "Расход"
            OperationType.INCOME -> "Доход"
            OperationType.TRANSFER -> "Перевод"
        }
        val category = sanitizeFreeText(SavingsGoalCalculator.resolveDisplayCategory(expense, registry).displayName)
        val merchant = sanitizeFreeText(expense.merchant).ifBlank { "—" }
        val note = sanitizeFreeText(expense.note).ifBlank { "—" }
        val author = sanitizeFreeText(expense.authorName).ifBlank { "—" }
        return "$date | $type | $category | ${formatRub(expense.amountRub)} | $merchant | $note | $author"
    }

    /**
     * Дополнительная страховка для свободного текста (мерчант/заметка):
     * маскирует номера карт и длинные цифровые последовательности, убирает переводы строк и разделитель `|`.
     */
    fun sanitizeFreeText(raw: String, maxLength: Int = MAX_FREE_TEXT_LENGTH): String {
        if (raw.isBlank()) return ""
        var text = raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('|', '/')
        // Полные номера карт / счетов (в т.ч. с пробелами или дефисами между группами)
        text = text.replace(Regex("""\d(?:[ \-]?\d){11,}"""), "[номер скрыт]")
        // Хвосты карт вида *1234, •1234, ••1234, карта 1234
        text = text.replace(Regex("""[*•·]+\s?\d{4}"""), "*****")
        text = text.replace(Regex("""(?i)(карт[аеыу]?|card|mir|мир|visa|счёт|счет)\s*[*•·]*\s*\d{4}\b"""), "$1 ****")
        text = text.replace(Regex("""\s+"""), " ").trim()
        return if (text.length > maxLength) text.take(maxLength - 1).trimEnd() + "…" else text
    }

    // ---------------------------------------------------------------------------------------------
    // Base snapshot
    // ---------------------------------------------------------------------------------------------

    fun buildBaseSnapshot(
        expenses: List<ExpenseEntity>,
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        monthlyBudgetRub: Double = 0.0,
        recurringPayments: List<RecurringPaymentEntity> = emptyList(),
        savingsGoals: List<SavingsGoalEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val confirmed = confirmedPool(expenses)
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val sb = StringBuilder()
        sb.appendLine("Сегодня: ${today.format(dateFormatter)}. Подтверждённых записей в базе: ${confirmed.size}.")
        if (confirmed.isNotEmpty()) {
            val first = confirmed.minOf { it.createdAtEpochMs }
            sb.appendLine(
                "История ведётся с ${Instant.ofEpochMilli(first).atZone(zoneId).toLocalDate().format(dateFormatter)}."
            )
        }

        // 1. Помесячная сводка за 12 месяцев без TRANSFER
        val trend = MonthlyTrendCalculator.calculate(
            expenses = confirmed,
            registry = registry,
            monthsCount = SUMMARY_MONTHS,
            anchorEpochMs = nowMs,
            zoneId = zoneId
        )
        val totalInc = trend.sumOf { it.incomeRub }
        val totalExp = trend.sumOf { it.expenseRub }
        sb.appendLine()
        sb.appendLine("[Последние 12 месяцев, без внутренних переводов/копилок]")
        trend.forEach { p ->
            sb.appendLine("${p.yearMonthKey}: доходы ${formatRub(p.incomeRub)}, расходы ${formatRub(p.expenseRub)}, сальдо ${formatRub(p.netRub, signed = true)}")
        }
        val monthsWithExpenses = trend.count { it.expenseRub > 0.0 }.coerceAtLeast(1)
        sb.appendLine(
            "Итого за 12 мес.: доходы ${formatRub(totalInc)}, расходы ${formatRub(totalExp)}, " +
                "сальдо ${formatRub(totalInc - totalExp, signed = true)}, средний расход в месяц ${formatRub(totalExp / monthsWithExpenses)}."
        )

        // 2. Текущий месяц по категориям с лимитами
        val currentMonth = YearMonth.from(today)
        val monthStartMs = currentMonth.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val monthEndMs = currentMonth.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
        val totalDaysInMonth = currentMonth.lengthOfMonth()
        val currentDay = today.dayOfMonth.coerceIn(1, totalDaysInMonth)
        val monthWindow = PeriodWindow(
            period = BudgetPeriod.MONTH,
            startMs = monthStartMs,
            endMs = monthEndMs,
            title = "${monthNamesNominative[currentMonth.monthValue - 1]} ${currentMonth.year}",
            isCurrentPeriod = true,
            progressRatio = (currentDay.toFloat() / totalDaysInMonth.toFloat()).coerceIn(0f, 1f),
            totalDaysInPeriod = totalDaysInMonth,
            daysLeftInPeriod = (totalDaysInMonth - currentDay + 1).coerceAtLeast(1)
        )
        val report = AiBudgetAdvisor.buildInstantLocalReport(
            expenses = expenses,
            monthlyBudgetRub = monthlyBudgetRub,
            window = monthWindow,
            registry = registry,
            recurringPayments = recurringPayments,
            nowMs = nowMs,
            savingsGoals = savingsGoals
        )
        sb.appendLine()
        sb.appendLine("[Текущий месяц ${monthNamesNominative[currentMonth.monthValue - 1]} ${currentMonth.year}]")
        sb.appendLine(
            "Бюджет месяца: ${if (monthlyBudgetRub > 0.0) formatRub(monthlyBudgetRub) else "не задан"}; " +
                "потрачено ${formatRub(report.totalSpentThisMonthRub)}, доходы ${formatRub(report.totalIncomeThisMonthRub)}; " +
                "осталось дней: ${report.daysLeftInMonth}; безопасный ритм ${formatRub(report.safeDailySpendRemainingRub)}/день."
        )
        val catLines = report.allCategories
            .filter { it.spentRub > 0.0 || it.recommendedLimitRub > 0.0 }
            .sortedByDescending { it.spentRub }
        if (catLines.isEmpty()) {
            sb.appendLine("Расходов по категориям пока нет.")
        } else {
            catLines.forEach { d ->
                val limitText = describeLimit(d.category, d.recommendedLimitRub)
                val pctOfLimit = if (d.recommendedLimitRub > 0.0) {
                    " (${(d.spentRub / d.recommendedLimitRub * 100.0).roundToInt()}% лимита)"
                } else {
                    ""
                }
                val status = when (d.status) {
                    CategoryDiagnosis.Status.OVERBURNED -> "перерасход темпа"
                    CategoryDiagnosis.Status.CAN_SPLURGE -> "есть запас"
                    CategoryDiagnosis.Status.ON_TRACK -> "по плану"
                }
                sb.appendLine("- ${d.category.displayName}: ${formatRub(d.spentRub)} из $limitText$pctOfLimit, ${d.transactionCount} оп., $status")
            }
        }

        // 3. Регулярные платежи и резерв
        val activeRecurring = recurringPayments.filter { !it.isDeleted && it.isActive }
        sb.appendLine()
        sb.appendLine("[Регулярные платежи]")
        if (activeRecurring.isEmpty()) {
            sb.appendLine("Активных регулярных платежей нет.")
        } else {
            val monthKey = RecurringPaymentScheduler.periodKeyFor(nowMs, zoneId)
            val pendingIds = expenses.asSequence()
                .filter { !it.isDeleted && it.isPendingConfirmation }
                .map { it.id }
                .toHashSet()
            activeRecurring.sortedBy { it.dayOfMonth }.forEach { p ->
                val type = when (p.effectiveOperationType) {
                    OperationType.EXPENSE -> "расход"
                    OperationType.INCOME -> "доход"
                    OperationType.TRANSFER -> "перевод"
                }
                val deterministicId = RecurringPaymentScheduler.deterministicExpenseId(p.id, monthKey)
                val paid = when {
                    deterministicId in pendingIds -> "ожидает подтверждения (в резерве)"
                    p.isPaidInPeriod(monthKey) -> "в этом месяце учтён"
                    else -> "ожидается"
                }
                val cat = registry.resolve(p.categoryId).displayName
                sb.appendLine("- ${sanitizeFreeText(p.title).ifBlank { cat }}: ${formatRub(p.amountRub)}, $type, ${p.dayOfMonth}-го числа, $cat, $paid")
            }
            sb.appendLine("Резерв под ещё не списанные регулярные расходы этого месяца: ${formatRub(report.upcomingRecurringReserveRub)}.")
        }

        // 4. Копилки / цели
        val goals = SavingsGoalCalculator.buildGoalProgressList(savingsGoals, confirmed)
            .filter { !it.goal.isArchived }
        sb.appendLine()
        sb.appendLine("[Копилки и цели]")
        if (goals.isEmpty()) {
            sb.appendLine("Активных копилок нет.")
        } else {
            goals.forEach { g ->
                val target = if (g.goal.targetAmountRub > 0.0) {
                    " из ${formatRub(g.goal.targetAmountRub)} (${(g.progressRatio * 100).roundToInt()}%)"
                } else {
                    ""
                }
                sb.appendLine("- ${sanitizeFreeText(g.goal.title).ifBlank { "Цель" }}: накоплено ${formatRub(g.savedAmountRub)}$target")
            }
        }
        sb.appendLine(
            "Общий баланс за всё время: ${formatRub(report.allTimeBalanceRub, signed = true)}; " +
                "в копилках ${formatRub(report.totalSavedInGoalsRub)}; свободно ${formatRub(report.freeUnallocatedBalanceRub, signed = true)}."
        )

        // 5. Топ-15 частых мерчантов за 12 месяцев (только расходы)
        val startOf12m = currentMonth.minusMonths((SUMMARY_MONTHS - 1).toLong())
            .atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val merchantStats = confirmed.asSequence()
            .filter { it.createdAtEpochMs >= startOf12m && it.resolveOperationType(registry) == OperationType.EXPENSE }
            .mapNotNull { e ->
                val clean = sanitizeFreeText(e.merchant, 40)
                if (clean.isBlank()) null else clean to e
            }
            .groupBy({ normalizeKey(it.first) }, { it })
            .map { (_, items) ->
                val displayName = items.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key ?: items.first().first
                Triple(displayName, items.size, items.sumOf { it.second.amountRub })
            }
            .sortedWith(compareByDescending<Triple<String, Int, Double>> { it.second }.thenByDescending { it.third })
            .take(TOP_MERCHANTS_LIMIT)
        sb.appendLine()
        sb.appendLine("[Топ-$TOP_MERCHANTS_LIMIT частых мерчантов за 12 мес.]")
        if (merchantStats.isEmpty()) {
            sb.appendLine("Нет данных.")
        } else {
            merchantStats.forEach { (name, count, sum) ->
                sb.appendLine("- $name: $count оп., ${formatRub(sum)}")
            }
        }
        return sb.toString().trim()
    }

    private fun describeLimit(category: CategoryDescriptor, limitRub: Double): String = when {
        limitRub <= 0.0 -> "лимита (не задан)"
        category.limitMode == CategoryLimitMode.PERCENT ->
            "лимита ${formatRub(limitRub)} [${formatPlain(category.limitValue)}% бюджета]"
        else -> "лимита ${formatRub(limitRub)} [фикс.]"
    }

    // ---------------------------------------------------------------------------------------------
    // Relevance search
    // ---------------------------------------------------------------------------------------------

    private data class DateRange(val startMs: Long, val endExclusiveMs: Long, val label: String)

    private data class SearchResult(
        val records: List<ExpenseEntity>,
        val totalMatched: Int,
        val criteriaText: String,
        val aggregatesText: String
    )

    private data class ParsedQuestion(
        val keywordStems: List<String>,
        val synonymNeedles: List<String>,
        val dateRanges: List<DateRange>,
        val operationFilter: OperationType?
    )

    private fun searchRelevant(
        question: String,
        pool: List<ExpenseEntity>,
        registry: CategoryRegistry,
        nowMs: Long,
        zoneId: ZoneId,
        limit: Int
    ): SearchResult {
        val safeLimit = limit.coerceIn(0, MAX_RELEVANT_RECORDS)
        val parsed = parseQuestion(question, nowMs, zoneId)

        // Категории, чьи названия совпали с ключевыми словами
        val matchedCategoryIds = linkedMapOf<String, String>()
        val allCats = registry.allCategories + listOf(
            SavingsGoalCalculator.resolveDisplayCategory(ExpenseEntity(categoryName = SavingsGoalCalculator.CATEGORY_GOAL_DEPOSIT), registry),
            SavingsGoalCalculator.resolveDisplayCategory(ExpenseEntity(categoryName = SavingsGoalCalculator.CATEGORY_GOAL_WITHDRAW), registry)
        )
        for (cat in allCats) {
            val names = normalizeKey("${cat.displayName} ${cat.shortName}")
            val nameWords = names.split(' ').filter { it.isNotBlank() }
            if (parsed.keywordStems.any { stem -> nameWords.any { it.startsWith(stem) || (stem.length >= 5 && it.contains(stem)) } }) {
                matchedCategoryIds[cat.id] = cat.displayName
            }
        }

        val hasKeywordCriteria = parsed.keywordStems.isNotEmpty() || parsed.synonymNeedles.isNotEmpty()

        val inDate = pool.filter { e ->
            (parsed.dateRanges.isEmpty() || parsed.dateRanges.any { e.createdAtEpochMs >= it.startMs && e.createdAtEpochMs < it.endExclusiveMs }) &&
                (parsed.operationFilter == null || e.resolveOperationType(registry) == parsed.operationFilter)
        }

        val scored: List<Pair<ExpenseEntity, Int>> = if (hasKeywordCriteria) {
            inDate.mapNotNull { e ->
                val score = scoreRecord(e, parsed, matchedCategoryIds.keys, registry)
                if (score > 0) e to score else null
            }
        } else if (parsed.dateRanges.isNotEmpty() || parsed.operationFilter != null) {
            // Только период/тип: самые крупные операции периода наиболее информативны
            inDate.map { it to 1 }
        } else {
            emptyList()
        }

        val sorted = if (hasKeywordCriteria) {
            scored.sortedWith(
                compareByDescending<Pair<ExpenseEntity, Int>> { it.second }
                    .thenByDescending { it.first.createdAtEpochMs }
            )
        } else {
            scored.sortedWith(
                compareByDescending<Pair<ExpenseEntity, Int>> { abs(it.first.amountRub) }
                    .thenByDescending { it.first.createdAtEpochMs }
            )
        }
        val matched = sorted.map { it.first }
        val attached = matched.take(safeLimit).sortedByDescending { it.createdAtEpochMs }

        val criteria = buildString {
            val parts = mutableListOf<String>()
            if (parsed.keywordStems.isNotEmpty()) parts += "ключевые слова: ${parsed.keywordStems.joinToString(", ")}"
            if (matchedCategoryIds.isNotEmpty()) parts += "категории: ${matchedCategoryIds.values.joinToString(", ")}"
            if (parsed.dateRanges.isNotEmpty()) parts += "период: ${parsed.dateRanges.joinToString(", ") { it.label }}"
            parsed.operationFilter?.let {
                parts += "тип: ${if (it == OperationType.INCOME) "доходы" else if (it == OperationType.EXPENSE) "расходы" else "переводы"}"
            }
            if (parts.isEmpty()) {
                append("Конкретных критериев в вопросе не найдено — отвечай по сводке выше.")
            } else {
                append("Критерии: ${parts.joinToString("; ")}.")
            }
        }

        val aggregates = if (matched.isEmpty()) {
            if (hasKeywordCriteria || parsed.dateRanges.isNotEmpty()) "Совпадений в базе не найдено." else ""
        } else {
            buildAggregates(matched, attached.size, registry, zoneId)
        }

        return SearchResult(
            records = attached,
            totalMatched = matched.size,
            criteriaText = criteria,
            aggregatesText = aggregates
        )
    }

    private fun scoreRecord(
        e: ExpenseEntity,
        parsed: ParsedQuestion,
        matchedCategoryIds: Set<String>,
        registry: CategoryRegistry
    ): Int {
        // ВНИМАНИЕ: rawPushOrQrText намеренно не участвует в поиске.
        val merchant = normalizeKey(e.merchant)
        val note = normalizeKey(e.note)
        val categoryId = SavingsGoalCalculator.resolveDisplayCategory(e, registry).id
        var score = 0
        if (categoryId in matchedCategoryIds) score += 3
        for (stem in parsed.keywordStems) {
            if (merchant.isNotEmpty() && merchant.contains(stem)) score += 4
            if (note.isNotEmpty() && note.contains(stem)) score += 3
        }
        for (needle in parsed.synonymNeedles) {
            val n = needle.trim()
            val paddedMerchant = " $merchant "
            if (merchant.isNotEmpty() && (paddedMerchant.contains(if (needle.endsWith(" ")) " $n " else n))) {
                score += 4
                break
            }
        }
        for (needle in parsed.synonymNeedles) {
            if (note.isNotEmpty() && note.contains(needle.trim())) {
                score += 2
                break
            }
        }
        return score
    }

    private fun buildAggregates(
        matched: List<ExpenseEntity>,
        attachedCount: Int,
        registry: CategoryRegistry,
        zoneId: ZoneId
    ): String {
        val byType = matched.groupBy { it.resolveOperationType(registry) }
        val expSum = byType[OperationType.EXPENSE].orEmpty().sumOf { it.amountRub }
        val incSum = byType[OperationType.INCOME].orEmpty().sumOf { it.amountRub }
        val trSum = byType[OperationType.TRANSFER].orEmpty().sumOf { it.amountRub }
        val minDate = Instant.ofEpochMilli(matched.minOf { it.createdAtEpochMs }).atZone(zoneId).toLocalDate()
        val maxDate = Instant.ofEpochMilli(matched.maxOf { it.createdAtEpochMs }).atZone(zoneId).toLocalDate()

        val sb = StringBuilder()
        sb.append("Найдено записей: ${matched.size} (с ${minDate.format(dateFormatter)} по ${maxDate.format(dateFormatter)}). ")
        sb.append("Сумма по ВСЕМ найденным: расходы ${formatRub(expSum)} (${byType[OperationType.EXPENSE].orEmpty().size} оп.)")
        if (incSum > 0.0) sb.append(", доходы ${formatRub(incSum)} (${byType[OperationType.INCOME].orEmpty().size} оп.)")
        if (trSum > 0.0) sb.append(", переводы ${formatRub(trSum)}")
        sb.append(". Ниже приложено $attachedCount самых релевантных.")

        val expensesOnly = byType[OperationType.EXPENSE].orEmpty()
        if (expensesOnly.isNotEmpty()) {
            val byCategory = expensesOnly
                .groupBy { SavingsGoalCalculator.resolveDisplayCategory(it, registry).displayName }
                .mapValues { (_, list) -> list.sumOf { it.amountRub } }
                .entries
                .sortedByDescending { it.value }
            if (byCategory.size > 1) {
                val catLine = byCategory.take(8).joinToString(", ") { (cat, sum) ->
                    "$cat: ${formatRub(sum)}"
                }
                sb.appendLine()
                sb.append("По категориям найденных расходов: $catLine.")
            }

            val byMonth = expensesOnly
                .groupBy { YearMonth.from(Instant.ofEpochMilli(it.createdAtEpochMs).atZone(zoneId)) }
                .toSortedMap(compareByDescending { it })
            val monthLine = byMonth.entries.take(MAX_MONTH_BREAKDOWN_LINES).joinToString("; ") { (ym, list) ->
                "%04d-%02d: %s".format(Locale.US, ym.year, ym.monthValue, formatRub(list.sumOf { it.amountRub }))
            }
            sb.appendLine()
            sb.append("Расходы найденного по месяцам: $monthLine")
            if (byMonth.size > MAX_MONTH_BREAKDOWN_LINES) sb.append("; …ещё ${byMonth.size - MAX_MONTH_BREAKDOWN_LINES} мес.")
            sb.append('.')
        }
        return sb.toString()
    }

    private fun parseQuestion(question: String, nowMs: Long, zoneId: ZoneId): ParsedQuestion {
        val normalized = normalizeKey(question)
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val currentMonth = YearMonth.from(today)

        val years = tokens.mapNotNull { t -> t.toIntOrNull()?.takeIf { it in 2000..2100 } }.distinct()
        val months = mutableListOf<Int>()
        val keywordStems = mutableListOf<String>()
        val synonymNeedles = mutableListOf<String>()

        for (token in tokens) {
            if (token.toIntOrNull() != null) continue
            val monthIdx = monthIndexOf(token)
            if (monthIdx != null) {
                months += monthIdx
                continue
            }
            val shortNeedles = shortTokenSynonyms[token]
            if (shortNeedles != null) {
                synonymNeedles += shortNeedles
                if (token !in keywordStems) keywordStems += token
                continue
            }
            if (token.length < 3 || token in stopWords) continue
            if (stopPrefixes.any { token.startsWith(it) }) continue
            val stem = stemOf(token)
            synonymGroups.entries.firstOrNull { (key, _) -> token.startsWith(key) || stem.startsWith(key) || key.startsWith(stem) && stem.length >= 4 }
                ?.let { (_, needles) -> synonymNeedles += needles }
            if (stem !in keywordStems) keywordStems += stem
        }

        val ranges = mutableListOf<DateRange>()
        fun monthRange(ym: YearMonth) = DateRange(
            ym.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            ym.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            "%04d-%02d".format(Locale.US, ym.year, ym.monthValue)
        )
        fun dayRange(from: LocalDate, toExclusive: LocalDate, label: String) = DateRange(
            from.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            toExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            label
        )

        if (months.isNotEmpty()) {
            for (m in months.distinct()) {
                if (years.isNotEmpty()) {
                    years.forEach { y -> ranges += monthRange(YearMonth.of(y, m + 1)) }
                } else {
                    // Месяц без года — ближайший прошедший (или текущий)
                    val candidate = YearMonth.of(currentMonth.year, m + 1)
                    ranges += monthRange(if (candidate.isAfter(currentMonth)) candidate.minusYears(1) else candidate)
                }
            }
        } else if (years.isNotEmpty()) {
            years.forEach { y ->
                ranges += dayRange(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1), "$y год")
            }
        } else {
            val text = " $normalized "
            when {
                text.contains("сегодн") -> ranges += dayRange(today, today.plusDays(1), "сегодня")
                text.contains("вчера") -> ranges += dayRange(today.minusDays(1), today, "вчера")
                text.contains("недел") -> ranges += dayRange(today.minusDays(6), today.plusDays(1), "последние 7 дней")
                text.contains("полгод") || text.contains("полугод") || Regex("""\b6\s*мес""").containsMatchIn(text) ||
                    text.contains("шесть мес") ->
                    ranges += DateRange(
                        currentMonth.minusMonths(5).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        currentMonth.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        "последние 6 месяцев"
                    )
                Regex("""\b3\s*мес""").containsMatchIn(text) || text.contains("квартал") || text.contains("три мес") ->
                    ranges += DateRange(
                        currentMonth.minusMonths(2).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        currentMonth.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        "последние 3 месяца"
                    )
                text.contains("позапрошл") && text.contains("месяц") -> ranges += monthRange(currentMonth.minusMonths(2))
                text.contains("прошл") && text.contains("месяц") -> ranges += monthRange(currentMonth.minusMonths(1))
                (text.contains("этом месяц") || text.contains("текущ") || text.contains("этот месяц")) ->
                    ranges += monthRange(currentMonth)
                text.contains("прошл") && (text.contains(" год") || text.contains(" году")) -> {
                    val y = today.year - 1
                    ranges += dayRange(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1), "$y год")
                }
                text.contains(" этом году") || text.contains(" этот год") -> {
                    val y = today.year
                    ranges += dayRange(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1), "$y год")
                }
                text.contains(" год") || text.contains(" году") || Regex("""\b12\s*мес""").containsMatchIn(text) ->
                    ranges += DateRange(
                        currentMonth.minusMonths(11).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        currentMonth.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        "последние 12 месяцев"
                    )
            }
        }

        val mentionsIncome = tokens.any { it.startsWith("доход") || it.startsWith("зарплат") || it.startsWith("заработ") }
        val mentionsExpense = tokens.any { it.startsWith("расход") || it.startsWith("трат") || it.startsWith("потрат") }
        val opFilter = when {
            mentionsIncome && !mentionsExpense -> OperationType.INCOME
            else -> null
        }

        return ParsedQuestion(
            keywordStems = keywordStems,
            synonymNeedles = synonymNeedles.distinct(),
            dateRanges = ranges,
            operationFilter = opFilter
        )
    }

    private fun monthIndexOf(token: String): Int? {
        if (token == "май" || token == "мая" || token == "мае" || token == "маю") return 4
        monthStems.forEachIndexed { idx, stem ->
            if (idx == 4) return@forEachIndexed
            if (token.startsWith(stem)) return idx
        }
        return null
    }

    /** Очень простой «стеммер» для русских слов: отрезаем окончание, оставляя ≥ 4 символов. */
    private fun stemOf(token: String): String = when {
        token.length >= 7 -> token.dropLast(2)
        token.length >= 5 -> token.dropLast(1)
        else -> token
    }

    private fun normalizeKey(raw: String): String =
        raw.lowercase(ruLocale)
            .replace('ё', 'е')
            .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
            .trim()

    // ---------------------------------------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------------------------------------

    private val integerFormat = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' })
    private val decimalFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = ' '
        decimalSeparator = ','
    })

    fun formatRub(amount: Double, signed: Boolean = false): String {
        if (!amount.isFinite()) return "0 ₽"
        val kopecks = (amount * 100.0).roundToLong()
        val absKopecks = abs(kopecks)
        val body = if (absKopecks % 100L == 0L) {
            synchronized(integerFormat) { integerFormat.format(absKopecks / 100L) }
        } else {
            synchronized(decimalFormat) { decimalFormat.format(absKopecks / 100.0) }
        }
        val sign = when {
            kopecks < 0L -> "-"
            signed && kopecks > 0L -> "+"
            else -> ""
        }
        return "$sign$body ₽"
    }

    private fun formatPlain(value: Double): String =
        if (value == value.roundToLong().toDouble()) value.roundToLong().toString() else "%.1f".format(Locale.US, value)
}
