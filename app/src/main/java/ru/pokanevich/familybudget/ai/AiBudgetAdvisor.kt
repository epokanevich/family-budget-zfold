package ru.pokanevich.familybudget.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.Formatters
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import ru.pokanevich.familybudget.ui.util.PeriodWindow
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class CategoryDiagnosis(
    val category: ExpenseCategory,
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
    val category: ExpenseCategory,
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
    val monthlyBudgetRub: Double,
    val expectedSpentByTodayRub: Double,
    val safeDailySpendRemainingRub: Double,
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

object AiBudgetAdvisor {

    fun buildInstantLocalReport(
        expenses: List<ExpenseEntity>,
        monthlyBudgetRub: Double,
        window: PeriodWindow = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, System.currentTimeMillis())
    ): AiBudgetReport {
        val activeAll = expenses.filter { !it.isDeleted && !it.isPendingConfirmation }

        // Общий баланс за всё время (как "Итого" в Money Manager)
        val allTimeIncome = activeAll.filter { it.isIncome }.sumOf { it.amountRub }
        val allTimeExpense = activeAll.filter { !it.isIncome }.sumOf { it.amountRub }
        val allTimeBalance = allTimeIncome - allTimeExpense

        val periodItems = if (window.period == BudgetPeriod.ALL) {
            activeAll
        } else {
            activeAll.filter { it.createdAtEpochMs in window.startMs..window.endMs }
        }

        val periodExpenses = periodItems.filter { !it.isIncome }
        val periodIncomes = periodItems.filter { it.isIncome }

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
        val remainingBudget = (periodBudget - totalSpent).coerceAtLeast(0.0)
        val safeDaily = if (window.daysLeftInPeriod > 0) {
            remainingBudget / window.daysLeftInPeriod.toDouble()
        } else {
            0.0
        }

        val eugeneExpenses = periodExpenses.filter { it.authorName.equals("Евгений", ignoreCase = true) }
        val anzhelikaExpenses = periodExpenses.filter { it.authorName.equals("Анжелика", ignoreCase = true) }

        val diagnoses = ExpenseCategory.expenseCategories.map { cat ->
            val catExpenses = periodExpenses.filter { it.category == cat }
            val catSpent = catExpenses.sumOf { it.amountRub }
            val catLimit = periodBudget * (cat.recommendedSharePercent / 100.0)
            val catExpectedToday = catLimit * window.progressRatio.toDouble()
            val diff = catSpent - catExpectedToday

            val status = when {
                window.period == BudgetPeriod.ALL -> CategoryDiagnosis.Status.ON_TRACK
                catSpent > catLimit -> CategoryDiagnosis.Status.OVERBURNED
                window.isCurrentPeriod && diff > catLimit * 0.18 -> CategoryDiagnosis.Status.OVERBURNED
                catExpectedToday - catSpent > catLimit * 0.20 -> CategoryDiagnosis.Status.CAN_SPLURGE
                else -> CategoryDiagnosis.Status.ON_TRACK
            }

            val msg = when {
                window.period == BudgetPeriod.ALL ->
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

        val incomeBreakdown = ExpenseCategory.incomeCategories.map { cat ->
            val catIncomes = periodIncomes.filter { it.category == cat }
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
                "Темп выше плана на ${(totalSpent - expectedByToday).roundToInt()} ₽. Комфортный ритм — ${safeDaily.roundToInt()} ₽/день."
            totalSpent < expectedByToday * 0.85 ->
                "Отличный запас! Вы ниже графика на ${(expectedByToday - totalSpent).roundToInt()} ₽ — можно спокойно позволить приятные покупки."
            else ->
                "Ровный темп по графику. Безопасный дневной ритм: ${safeDaily.roundToInt()} ₽/день."
        }

        return AiBudgetReport(
            window = window,
            totalSpentThisMonthRub = totalSpent,
            totalIncomeThisMonthRub = totalIncome,
            allTimeIncomeRub = allTimeIncome,
            allTimeExpenseRub = allTimeExpense,
            allTimeBalanceRub = allTimeBalance,
            monthlyBudgetRub = periodBudget,
            expectedSpentByTodayRub = expectedByToday,
            safeDailySpendRemainingRub = safeDaily,
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
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
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

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 12000
                readTimeout = 15000
                doOutput = true
            }

            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(bodyJson.toString())
            }

            val responseText = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("HTTP ${conn.responseCode}: $err")
            }

            val root = JSONObject(responseText)
            val candidates = root.getJSONArray("candidates")
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            parts.getJSONObject(0).getString("text").trim()
        }
    }
}
