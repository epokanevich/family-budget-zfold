package ru.pokanevich.familybudget.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.pokanevich.familybudget.data.CategoryDescriptor
import ru.pokanevich.familybudget.data.CategoryLimitMode
import ru.pokanevich.familybudget.data.CategoryRegistry
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.OperationType
import ru.pokanevich.familybudget.data.RecurringPaymentEntity
import ru.pokanevich.familybudget.data.RecurringPaymentScheduler
import ru.pokanevich.familybudget.data.SavingsGoalCalculator
import ru.pokanevich.familybudget.data.SavingsGoalEntity
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.Formatters
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import ru.pokanevich.familybudget.ui.util.PeriodWindow
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class CategoryDiagnosis(
    val category: CategoryDescriptor,
    val spentRub: Double,
    val recommendedLimitRub: Double,
    val expectedByTodayRub: Double,
    val status: Status,
    val message: String,
    val transactionCount: Int
) {
    enum class Status { OVERBURNED, ON_TRACK, CAN_SPLURGE }
}

data class IncomeCategoryBreakdown(
    val category: CategoryDescriptor,
    val amountRub: Double,
    val transactionCount: Int
)

data class AiBudgetReport(
    val window: PeriodWindow,
    val totalSpentThisMonthRub: Double,
    val totalIncomeThisMonthRub: Double,
    val allTimeIncomeRub: Double,
    val allTimeExpenseRub: Double,
    val allTimeBalanceRub: Double,
    val totalSavedInGoalsRub: Double = 0.0,
    val freeUnallocatedBalanceRub: Double = 0.0,
    val monthlyBudgetRub: Double,
    val expectedSpentByTodayRub: Double,
    val safeDailySpendRemainingRub: Double,
    val upcomingRecurringReserveRub: Double = 0.0,
    val freeBudgetAfterReserveRub: Double = 0.0,
    val daysLeftInMonth: Int,
    val monthProgressRatio: Float,
    val isCurrentMonth: Boolean,
    val totalExpenseTransactions: Int,
    val totalIncomeTransactions: Int,
    val eugeneSpentRub: Double,
    val anzhelikaSpentRub: Double,
    val eugeneCount: Int,
    val anzhelikaCount: Int,
    val headlineVerdict: String,
    val overburnedCategories: List<CategoryDiagnosis>,
    val splurgeCategories: List<CategoryDiagnosis>,
    val allCategories: List<CategoryDiagnosis>,
    val incomeBreakdown: List<IncomeCategoryBreakdown>
)

data class CloudAiAdvice(
    val text: String,
    val modelUsed: String,
    val attemptsCount: Int
)

object AiBudgetAdvisor {

    private val PRIORITIZED_FREE_MODELS = listOf(
        "gemini-flash-latest",
        "gemini-flash-lite-latest",
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.6-flash",
        "gemini-3.8-flash",
        "gemini-3.1-flash-lite",
        "gemini-3-flash-preview",
        "gemini-3.1-flash-lite-preview",
        "gemini-3.7-flash"
    )

    private val EXCLUDED_MODEL_TOKENS = listOf(
        "tts", "image", "lyria", "banana", "transcribe", "robotics",
        "computer-use", "deep-research", "antigravity", "embedding"
    )

    fun buildInstantLocalReport(
        expenses: List<ExpenseEntity>,
        monthlyBudgetRub: Double,
        window: PeriodWindow = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, System.currentTimeMillis()),
        registry: CategoryRegistry = CategoryRegistry.DEFAULT,
        recurringPayments: List<RecurringPaymentEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        savingsGoals: List<SavingsGoalEntity> = emptyList()
    ): AiBudgetReport {
        val activeAll = expenses.filter {
            !it.isDeleted && !it.isPendingConfirmation && !it.isTransfer(registry)
        }

        // Общий баланс за всё время (как "Итого" в Money Manager)
        val allTimeIncome = activeAll.filter { it.isIncome(registry) }.sumOf { it.amountRub }
        val allTimeExpense = activeAll.filter { !it.isIncome(registry) }.sumOf { it.amountRub }
        val allTimeBalance = allTimeIncome - allTimeExpense
        val totalSavedInGoalsRub = SavingsGoalCalculator.calculateTotalSavedInGoals(savingsGoals, expenses)
        val freeUnallocatedBalanceRub = allTimeBalance - totalSavedInGoalsRub

        val periodItems = if (window.period == BudgetPeriod.ALL) {
            activeAll
        } else {
            activeAll.filter { it.createdAtEpochMs in window.startMs..window.endMs }
        }

        val periodExpenses = periodItems.filter { !it.isIncome(registry) }
        val periodIncomes = periodItems.filter { it.isIncome(registry) }

        val totalSpent = periodExpenses.sumOf { it.amountRub }
        val totalIncome = periodIncomes.sumOf { it.amountRub }

        val periodBudget = when (window.period) {
            BudgetPeriod.DAY -> monthlyBudgetRub / 30.0
            BudgetPeriod.WEEK -> monthlyBudgetRub * 7.0 / 30.0
            BudgetPeriod.MONTH -> monthlyBudgetRub
            BudgetPeriod.YEAR -> monthlyBudgetRub * 12.0
            BudgetPeriod.ALL -> allTimeIncome.coerceAtLeast(monthlyBudgetRub)
        }

        val expectedByToday = periodBudget * window.progressRatio.toDouble()
        val upcomingRecurringReserveRub = if (
            window.period == BudgetPeriod.MONTH &&
            window.isCurrentPeriod &&
            recurringPayments.isNotEmpty()
        ) {
            val currentMonthKey = RecurringPaymentScheduler.periodKeyFor(nowMs)
            val confirmedCurrentMonthIds = expenses.asSequence()
                .filter {
                    !it.isDeleted &&
                        !it.isPendingConfirmation &&
                        it.createdAtEpochMs in window.startMs..window.endMs
                }
                .map { it.id }
                .toHashSet()

            recurringPayments
                .asSequence()
                .filter { p ->
                    p.isActive &&
                        !p.isDeleted &&
                        p.effectiveOperationType == OperationType.EXPENSE &&
                        p.lastPaidPeriodKey != currentMonthKey &&
                        RecurringPaymentScheduler.deterministicExpenseId(p.id, currentMonthKey) !in confirmedCurrentMonthIds
                }
                .sumOf { it.amountRub.coerceAtLeast(0.0) }
        } else {
            0.0
        }

        val freeBudgetBeforeReserve = periodBudget - totalSpent
        val freeBudgetAfterReserve = (freeBudgetBeforeReserve - upcomingRecurringReserveRub).coerceAtLeast(0.0)
        val safeDaily = if (window.daysLeftInPeriod > 0) {
            freeBudgetAfterReserve / window.daysLeftInPeriod.toDouble()
        } else {
            0.0
        }

        val eugeneExpenses = periodExpenses.filter { it.authorName.equals("Евгений", ignoreCase = true) }
        val anzhelikaExpenses = periodExpenses.filter { it.authorName.equals("Анжелика", ignoreCase = true) }

        val expensesByCategoryId = periodExpenses.groupBy { registry.resolve(it.categoryName).id }
        val activeExpenseCats = registry.expenseCategories
        val activeExpenseIds = activeExpenseCats.map { it.id }.toSet()
        // Архивные или пока неизвестные категории с операциями за период сохраняем в отчёте, чтобы история не терялась
        val extraExpenseCats = periodExpenses
            .map { registry.resolve(it.categoryName) }
            .filter { it.id !in activeExpenseIds }
            .distinctBy { it.id }

        val diagnoses = (activeExpenseCats + extraExpenseCats).map { cat ->
            val catExpenses = expensesByCategoryId[cat.id].orEmpty()
            val catSpent = catExpenses.sumOf { it.amountRub }
            val catLimit = when (cat.limitMode) {
                CategoryLimitMode.PERCENT -> periodBudget * (cat.limitValue / 100.0)
                CategoryLimitMode.FIXED_RUB -> when (window.period) {
                    BudgetPeriod.DAY -> cat.limitValue / 30.0
                    BudgetPeriod.WEEK -> cat.limitValue * 7.0 / 30.0
                    BudgetPeriod.MONTH -> cat.limitValue
                    BudgetPeriod.YEAR -> cat.limitValue * 12.0
                    BudgetPeriod.ALL -> 0.0
                }
            }
            val catExpectedToday = catLimit * window.progressRatio.toDouble()
            val diff = catSpent - catExpectedToday

            val status = when {
                window.period == BudgetPeriod.ALL -> CategoryDiagnosis.Status.ON_TRACK
                catLimit <= 0.0 -> CategoryDiagnosis.Status.ON_TRACK
                catSpent > catLimit -> CategoryDiagnosis.Status.OVERBURNED
                window.isCurrentPeriod && diff > catLimit * 0.18 -> CategoryDiagnosis.Status.OVERBURNED
                catExpectedToday - catSpent > catLimit * 0.20 -> CategoryDiagnosis.Status.CAN_SPLURGE
                else -> CategoryDiagnosis.Status.ON_TRACK
            }

            val msg = when {
                window.period == BudgetPeriod.ALL || catLimit <= 0.0 ->
                    "${catExpenses.size} оп."
                status == CategoryDiagnosis.Status.OVERBURNED -> {
                    val overRub = (catSpent - catExpectedToday).roundToInt().coerceAtLeast(0)
                    if (window.isCurrentPeriod) {
                        "Перебор темпа на $overRub ₽"
                    } else {
                        "Выше ориентира на ${(catSpent - catLimit).roundToInt().coerceAtLeast(0)} ₽"
                    }
                }
                status == CategoryDiagnosis.Status.CAN_SPLURGE -> {
                    val freeRub = (catExpectedToday - catSpent).roundToInt().coerceAtLeast(0)
                    if (window.isCurrentPeriod) "Запас +$freeRub ₽" else "Экономия +$freeRub ₽"
                }
                else -> "Идёт ровно по плану"
            }

            CategoryDiagnosis(
                category = cat,
                spentRub = catSpent,
                recommendedLimitRub = catLimit,
                expectedByTodayRub = catExpectedToday,
                status = status,
                message = msg,
                transactionCount = catExpenses.size
            )
        }

        val incomesByCategoryId = periodIncomes.groupBy { registry.resolve(it.categoryName).id }
        val activeIncomeCats = registry.incomeCategories
        val activeIncomeIds = activeIncomeCats.map { it.id }.toSet()
        val extraIncomeCats = periodIncomes
            .map { registry.resolve(it.categoryName) }
            .filter { it.id !in activeIncomeIds }
            .distinctBy { it.id }

        val incomeBreakdown = (activeIncomeCats + extraIncomeCats).map { cat ->
            val catIncomes = incomesByCategoryId[cat.id].orEmpty()
            IncomeCategoryBreakdown(
                category = cat,
                amountRub = catIncomes.sumOf { it.amountRub },
                transactionCount = catIncomes.size
            )
        }

        val overburned = diagnoses.filter { it.status == CategoryDiagnosis.Status.OVERBURNED }
            .sortedByDescending { it.spentRub - it.expectedByTodayRub }
        val splurge = diagnoses.filter { it.status == CategoryDiagnosis.Status.CAN_SPLURGE && it.recommendedLimitRub >= 3000 }
            .sortedByDescending { it.expectedByTodayRub - it.spentRub }

        val netPeriod = totalIncome - totalSpent
        val reserveSuffix = if (upcomingRecurringReserveRub > 0.5) {
            " (учтён резерв ${Formatters.formatRub(upcomingRecurringReserveRub)} под регулярные списания)"
        } else {
            ""
        }
        val headline = when {
            window.period == BudgetPeriod.ALL ->
                "За всё время: доходы ${Formatters.formatRub(allTimeIncome, true)}, расходы ${Formatters.formatRub(allTimeExpense, true)}, итог ${Formatters.formatRub(allTimeBalance, true)}."
            window.period != BudgetPeriod.MONTH ->
                "За период (${window.title}): расходы ${Formatters.formatRub(totalSpent, true)}, доходы ${Formatters.formatRub(totalIncome, true)} (сальдо ${Formatters.formatRub(netPeriod, true)})."
            totalSpent > periodBudget ->
                "Месячный бюджет превышен на ${(totalSpent - periodBudget).roundToInt()} ₽."
            !window.isCurrentPeriod ->
                if (netPeriod >= 0) {
                    "Итог месяца: в плюсе на ${Formatters.formatRub(netPeriod, true)}."
                } else {
                    "Итог месяца: расходы превысили доходы на ${Formatters.formatRub(-netPeriod, true)}."
                }
            totalSpent > expectedByToday * 1.12 ->
                "Темп выше плана на ${(totalSpent - expectedByToday).roundToInt()} ₽. Комфортный ритм — ${safeDaily.roundToInt()} ₽/день$reserveSuffix."
            totalSpent < expectedByToday * 0.85 ->
                if (upcomingRecurringReserveRub > 0.5) {
                    "Отличный запас! Вы ниже графика на ${(expectedByToday - totalSpent).roundToInt()} ₽. С учётом резерва ${Formatters.formatRub(upcomingRecurringReserveRub)} под регулярные списания — ритм ${safeDaily.roundToInt()} ₽/день."
                } else {
                    "Отличный запас! Вы ниже графика на ${(expectedByToday - totalSpent).roundToInt()} ₽ — можно спокойно позволить приятные покупки."
                }
            else ->
                "Ровный темп по графику. Безопасный дневной ритм: ${safeDaily.roundToInt()} ₽/день$reserveSuffix."
        }

        return AiBudgetReport(
            window = window,
            totalSpentThisMonthRub = totalSpent,
            totalIncomeThisMonthRub = totalIncome,
            allTimeIncomeRub = allTimeIncome,
            allTimeExpenseRub = allTimeExpense,
            allTimeBalanceRub = allTimeBalance,
            totalSavedInGoalsRub = totalSavedInGoalsRub,
            freeUnallocatedBalanceRub = freeUnallocatedBalanceRub,
            monthlyBudgetRub = periodBudget,
            expectedSpentByTodayRub = expectedByToday,
            safeDailySpendRemainingRub = safeDaily,
            upcomingRecurringReserveRub = upcomingRecurringReserveRub,
            freeBudgetAfterReserveRub = freeBudgetAfterReserve,
            daysLeftInMonth = window.daysLeftInPeriod,
            monthProgressRatio = window.progressRatio,
            isCurrentMonth = window.isCurrentPeriod,
            totalExpenseTransactions = periodExpenses.size,
            totalIncomeTransactions = periodIncomes.size,
            eugeneSpentRub = eugeneExpenses.sumOf { it.amountRub },
            anzhelikaSpentRub = anzhelikaExpenses.sumOf { it.amountRub },
            eugeneCount = eugeneExpenses.size,
            anzhelikaCount = anzhelikaExpenses.size,
            headlineVerdict = headline,
            overburnedCategories = overburned,
            splurgeCategories = splurge,
            allCategories = diagnoses,
            incomeBreakdown = incomeBreakdown
        )
    }

    suspend fun fetchDeepCloudAiAdvice(
        apiKey: String,
        report: AiBudgetReport
    ): Result<CloudAiAdvice> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) throw IllegalStateException("Не задан API-ключ Gemini")

            val summaryLines = report.allCategories
                .filter { it.spentRub > 0.0 }
                .joinToString("\n") {
                    "- ${it.category.displayName}: потрачено ${it.spentRub.roundToInt()} ₽ (ориентир ${it.recommendedLimitRub.roundToInt()} ₽)"
                }
            val prompt = listOf(
                "Ты — семейный финансовый аналитик для пары (общий бюджет без деления на личные счета).",
                "Период: ${report.window.title}.",
                "Общий баланс кошелька за всё время: ${Formatters.formatRub(report.allTimeBalanceRub, true)}.",
                "Доходы за период: ${report.totalIncomeThisMonthRub.roundToInt()} ₽.",
                "Расходы за период: ${report.totalSpentThisMonthRub.roundToInt()} ₽.",
                "Разбивка расходов по категориям:",
                summaryLines,
                "Дай короткий, тёплый и конкретный разбор на русском языке (до 120 слов):",
                "1) Где сейчас перебор и как мягко притормозить.",
                "2) В каких категориях есть запас и на что приятное пара может спокойно потратиться."
            ).joinToString("\n")

            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
            }.toString()

            val triedModels = linkedSetOf<String>()
            var attempts = 0
            var lastError = "Не удалось получить ответ ни от одной модели"

            // 1. Сначала перебираем приоритетные бесплатные быстрые модели
            for (model in PRIORITIZED_FREE_MODELS) {
                triedModels.add(model)
                attempts++
                val attemptResult = callGeminiModel(cleanKey, model, bodyJson)
                if (attemptResult.isSuccess) {
                    return@runCatching CloudAiAdvice(
                        text = attemptResult.getOrThrow(),
                        modelUsed = model,
                        attemptsCount = attempts
                    )
                }
                lastError = "$model: ${attemptResult.exceptionOrNull()?.message}"
            }

            // 2. Если все приоритетные перегружены/недоступны — динамически запрашиваем полный список моделей API и перебираем все текстовые
            val discoveredModels = fetchAvailableTextModels(cleanKey)
                .filter { it !in triedModels }

            for (model in discoveredModels) {
                triedModels.add(model)
                attempts++
                val attemptResult = callGeminiModel(cleanKey, model, bodyJson)
                if (attemptResult.isSuccess) {
                    return@runCatching CloudAiAdvice(
                        text = attemptResult.getOrThrow(),
                        modelUsed = model,
                        attemptsCount = attempts
                    )
                }
                lastError = "$model: ${attemptResult.exceptionOrNull()?.message}"
            }

            throw IllegalStateException("Перебрано моделей: $attempts. Последняя ошибка: $lastError")
        }
    }

    private fun callGeminiModel(apiKey: String, model: String, bodyJson: String): Result<String> {
        return runCatching {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
            }
            try {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(bodyJson)
                }
                val code = conn.responseCode
                val responseText = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    throw IllegalStateException("HTTP $code ${err.take(120)}")
                }
                val root = JSONObject(responseText)
                val candidates = root.getJSONArray("candidates")
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val text = parts.getJSONObject(0).getString("text").trim()
                if (text.isBlank()) throw IllegalStateException("Пустой ответ")
                text
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun fetchAvailableTextModels(apiKey: String): List<String> {
        return runCatching {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching emptyList()
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val arr = json.optJSONArray("models") ?: return@runCatching emptyList()
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawName = obj.optString("name", "").removePrefix("models/")
                    if (rawName.isBlank()) continue
                    val methods = obj.optJSONArray("supportedGenerationMethods") ?: continue
                    var supportsGen = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "generateContent") {
                            supportsGen = true
                            break
                        }
                    }
                    if (!supportsGen) continue
                    val lower = rawName.lowercase()
                    if (EXCLUDED_MODEL_TOKENS.any { lower.contains(it) }) continue
                    list.add(rawName)
                }
                // Сортируем: flash-lite -> flash -> остальные
                list.sortedBy { name ->
                    val l = name.lowercase()
                    when {
                        l.contains("flash-lite") -> 0
                        l.contains("flash") -> 1
                        l.contains("gemini") -> 2
                        else -> 3
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }
}
