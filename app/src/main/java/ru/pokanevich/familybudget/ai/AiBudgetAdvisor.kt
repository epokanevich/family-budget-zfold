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
import java.util.UUID
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

enum class AiChatRole {
    USER,
    MODEL
}

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: AiChatRole,
    val text: String,
    val modelUsed: String? = null,
    val contextSummary: String? = null,
    val isError: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class CloudAiChatReply(
    val text: String,
    val modelUsed: String,
    val attemptsCount: Int
)

internal class GeminiHttpException(
    val statusCode: Int,
    val errorBody: String
) : IllegalStateException("HTTP $statusCode ${errorBody.take(120)}")

object AiBudgetAdvisor {

    const val CHAT_HISTORY_WINDOW_SIZE = 8
    private const val MAX_DISCOVERED_FALLBACK_MODELS = 3

    internal val PRIORITIZED_FREE_MODELS = listOf(
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

    private val CHAT_SYSTEM_INSTRUCTION = """
        Ты — персональный семейный финансовый советник для пары (Евгений и Анжелика, общий бюджет без деления на личные счета).
        Отвечай на русском языке: дружелюбно, конкретно, с цифрами в ₽, без воды (обычно 3–7 предложений или короткий маркированный список).
        Правила работы с данными:
        1. Опирайся ТОЛЬКО на сводку семейной базы, агрегаты поиска и приложенные операции из текущего вопроса. Никогда не выдумывай числа, категории или операции.
        2. Переводы (TRANSFER), включая пополнение и снятие семейных копилок, не являются расходами или доходами месяца.
        3. Если к вопросу приложена выборка (до 50 операций), а в блоке поиска указана сумма по ВСЕМ найденным операциям, для итогов и сравнений используй сумму по всем найденным, а выборку используй для примеров и деталей.
        4. Если в переданном срезе не хватает данных для точного ответа, прямо напиши об этом и подскажи, как уточнить вопрос (например, назвать категорию, магазин или месяц).
    """.trimIndent()

    @Volatile
    private var lastSuccessfulModel: String? = null

    @Volatile
    internal var modelCaller: (apiKey: String, model: String, bodyJson: String) -> Result<String> =
        ::callGeminiModel

    @Volatile
    internal var availableModelsFetcher: (apiKey: String) -> List<String> =
        ::fetchAvailableTextModels

    fun lastUsedModel(): String? = lastSuccessfulModel

    internal fun resetForTest() {
        lastSuccessfulModel = null
        modelCaller = ::callGeminiModel
        availableModelsFetcher = ::fetchAvailableTextModels
    }

    internal fun orderedCandidateModels(preferred: String? = lastSuccessfulModel): List<String> {
        val cleanPreferred = preferred?.trim()?.takeIf { it.isNotBlank() }
        return if (cleanPreferred != null) {
            listOf(cleanPreferred) + PRIORITIZED_FREE_MODELS.filter { it != cleanPreferred }
        } else {
            PRIORITIZED_FREE_MODELS
        }
    }

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
            val confirmedRecurringIds = expenses.asSequence()
                .filter { !it.isDeleted && !it.isPendingConfirmation }
                .map { it.id }
                .toHashSet()
            val pendingRecurringIds = expenses.asSequence()
                .filter { !it.isDeleted && it.isPendingConfirmation }
                .map { it.id }
                .toHashSet()

            recurringPayments
                .asSequence()
                .filter { p ->
                    if (!p.isActive || p.isDeleted || p.effectiveOperationType != OperationType.EXPENSE) {
                        return@filter false
                    }
                    val deterministicId = RecurringPaymentScheduler.deterministicExpenseId(p.id, currentMonthKey)
                    // Пока авто-созданный платёж ждёт подтверждения в ленте, он не учтён в totalSpent — удерживаем в резерве.
                    deterministicId in pendingRecurringIds ||
                        (p.lastPaidPeriodKey != currentMonthKey && deterministicId !in confirmedRecurringIds)
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

            val res = executeWithModelFallback(cleanKey, bodyJson)
            CloudAiAdvice(
                text = res.text,
                modelUsed = res.modelUsed,
                attemptsCount = res.attemptsCount
            )
        }
    }

    /**
     * Диалоговый запрос к Gemini по семейной базе:
     * - передаёт системную инструкцию, компактный срез [contextSnapshot] и скользящее окно последних
     *   [CHAT_HISTORY_WINDOW_SIZE] сообщений диалога;
     * - первой пробует последнюю успешную модель;
     * - при HTTP 429 (или 401/403) сразу возвращает понятную ошибку без лишнего перебора моделей.
     */
    suspend fun askFinancialAdvisorChat(
        apiKey: String,
        history: List<AiChatMessage>,
        userQuestion: String,
        contextSnapshot: AiChatContextSnapshot
    ): Result<CloudAiChatReply> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            if (cleanKey.isBlank()) throw IllegalStateException("Не задан API-ключ Gemini")
            val cleanQuestion = userQuestion.trim()
            if (cleanQuestion.isBlank()) throw IllegalArgumentException("Введите вопрос для советника")

            val bodyJson = buildChatRequestJson(
                history = history,
                userQuestion = cleanQuestion,
                contextSnapshot = contextSnapshot,
                historyLimit = CHAT_HISTORY_WINDOW_SIZE
            )
            executeWithModelFallback(cleanKey, bodyJson)
        }
    }

    internal fun buildChatRequestJson(
        history: List<AiChatMessage>,
        userQuestion: String,
        contextSnapshot: AiChatContextSnapshot,
        historyLimit: Int = CHAT_HISTORY_WINDOW_SIZE
    ): String {
        val windowMessages = normalizeHistoryWindow(history, historyLimit)
        val contentsArray = JSONArray()

        for ((role, text) in windowMessages) {
            contentsArray.put(
                JSONObject().apply {
                    put("role", if (role == AiChatRole.USER) "user" else "model")
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }
            )
        }

        val currentTurnText = buildString {
            appendLine(contextSnapshot.toPromptText())
            appendLine()
            appendLine("Вопрос пользователя:")
            append(userQuestion.trim())
        }

        // Гарантируем, что текущий вопрос не нарушает чередование ролей Gemini API.
        if (windowMessages.lastOrNull()?.first == AiChatRole.USER) {
            val lastIdx = contentsArray.length() - 1
            val prevText = windowMessages.last().second
            contentsArray.put(
                lastIdx,
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", "$prevText\n\n$currentTurnText")))
                }
            )
        } else {
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", currentTurnText)))
                }
            )
        }

        return JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", CHAT_SYSTEM_INSTRUCTION))
                )
            )
            put("contents", contentsArray)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.35)
                    put("maxOutputTokens", 1200)
                }
            )
        }.toString()
    }

    private fun normalizeHistoryWindow(
        history: List<AiChatMessage>,
        historyLimit: Int
    ): List<Pair<AiChatRole, String>> {
        val recent = history
            .asSequence()
            .filter { !it.isError && it.text.isNotBlank() }
            .toList()
            .takeLast(historyLimit.coerceAtLeast(0))

        // Gemini contents всегда должен начинаться с реплики user.
        val firstUserIdx = recent.indexOfFirst { it.role == AiChatRole.USER }
        if (firstUserIdx < 0) return emptyList()

        val merged = mutableListOf<Pair<AiChatRole, String>>()
        for (msg in recent.drop(firstUserIdx)) {
            val cleanText = msg.text.trim()
            if (cleanText.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.first == msg.role) {
                merged[merged.lastIndex] = msg.role to "${last.second}\n\n$cleanText"
            } else {
                merged.add(msg.role to cleanText)
            }
        }
        return merged
    }

    private fun executeWithModelFallback(cleanKey: String, bodyJson: String): CloudAiChatReply {
        val candidates = orderedCandidateModels()
        val triedModels = linkedSetOf<String>()
        var attempts = 0
        var lastError = "Не удалось получить ответ ни от одной модели"

        fun tryModel(model: String): CloudAiChatReply? {
            triedModels.add(model)
            attempts++
            val attemptResult = modelCaller(cleanKey, model, bodyJson)
            if (attemptResult.isSuccess) {
                lastSuccessfulModel = model
                return CloudAiChatReply(
                    text = attemptResult.getOrThrow(),
                    modelUsed = model,
                    attemptsCount = attempts
                )
            }
            val err = attemptResult.exceptionOrNull()
            if (err is GeminiHttpException) {
                when (err.statusCode) {
                    429 -> throw IllegalStateException(
                        "Превышен лимит запросов Gemini (HTTP 429, модель $model). Подождите 30–60 секунд и повторите вопрос."
                    )
                    401, 403 -> throw IllegalStateException(
                        "Ключ Gemini API недействителен или нет доступа (HTTP ${err.statusCode}). Проверьте ключ в настройках."
                    )
                    404 -> if (lastSuccessfulModel == model) {
                        lastSuccessfulModel = null
                    }
                }
            }
            lastError = "$model: ${err?.message}"
            return null
        }

        // 1. Сначала пробуем запомненную рабочую модель, затем приоритетный список Free Tier.
        for (model in candidates) {
            tryModel(model)?.let { return it }
        }

        // 2. Только если все приоритетные модели недоступны (например, 404 после смены версий API),
        // подтягиваем актуальный список текстовых моделей и пробуем до MAX_DISCOVERED_FALLBACK_MODELS.
        val discoveredModels = availableModelsFetcher(cleanKey)
            .filter { it !in triedModels }
            .take(MAX_DISCOVERED_FALLBACK_MODELS)

        for (model in discoveredModels) {
            tryModel(model)?.let { return it }
        }

        throw IllegalStateException("Не удалось получить ответ от Gemini (попыток: $attempts). $lastError")
    }

    private fun callGeminiModel(apiKey: String, model: String, bodyJson: String): Result<String> {
        return runCatching {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 12_000
                readTimeout = 25_000
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
                    throw GeminiHttpException(code, err)
                }
                extractCandidateText(responseText)
            } finally {
                conn.disconnect()
            }
        }
    }

    internal fun extractCandidateText(responseText: String): String {
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini не вернул вариантов ответа")
        if (candidates.length() == 0) {
            throw IllegalStateException("Пустой ответ от Gemini")
        }
        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content")
            ?: throw IllegalStateException("Ответ заблокирован или пуст (${firstCandidate.optString("finishReason", "unknown")})")
        val parts = content.optJSONArray("parts")
            ?: throw IllegalStateException("Пустое содержимое ответа")

        val visibleParts = mutableListOf<String>()
        val fallbackParts = mutableListOf<String>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.optString("text", "").trim()
            if (text.isBlank()) continue
            if (part.optBoolean("thought", false)) {
                fallbackParts.add(text)
            } else {
                visibleParts.add(text)
            }
        }
        val result = (if (visibleParts.isNotEmpty()) visibleParts else fallbackParts)
            .joinToString("\n\n")
            .trim()
        if (result.isBlank()) throw IllegalStateException("Пустой ответ")
        return result
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
