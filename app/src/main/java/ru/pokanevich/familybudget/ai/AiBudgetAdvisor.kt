package ru.pokanevich.familybudget.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.pokanevich.familybudget.data.ExpenseCategory
import ru.pokanevich.familybudget.data.ExpenseEntity
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
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

data class AiBudgetReport(
    val totalSpentThisMonthRub: Double,
    val monthlyBudgetRub: Double,
    val expectedSpentByTodayRub: Double,
    val safeDailySpendRemainingRub: Double,
    val daysLeftInMonth: Int,
    val monthProgressRatio: Float,
    val isCurrentMonth: Boolean,
    val totalTransactions: Int,
    val eugeneSpentRub: Double,
    val anzhelikaSpentRub: Double,
    val eugeneCount: Int,
    val anzhelikaCount: Int,
    val headlineVerdict: String,
    val overburnedCategories: List<CategoryDiagnosis>,
    val splurgeCategories: List<CategoryDiagnosis>,
    val allCategories: List<CategoryDiagnosis>
)

object AiBudgetAdvisor {

    fun buildInstantLocalReport(
        expenses: List<ExpenseEntity>,
        monthlyBudgetRub: Double,
        targetYear: Int? = null,
        targetMonth: Int? = null
    ): AiBudgetReport {
        val nowCal = Calendar.getInstance()
        val nowYear = nowCal.get(Calendar.YEAR)
        val nowMonth = nowCal.get(Calendar.MONTH)

        val currentYear = targetYear ?: nowYear
        val currentMonth = targetMonth ?: nowMonth
        val isCurrentMonth = (currentYear == nowYear && currentMonth == nowMonth)
        val isPastMonth = (currentYear < nowYear) || (currentYear == nowYear && currentMonth < nowMonth)

        val targetCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, currentYear)
            set(Calendar.MONTH, currentMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val totalDaysInMonth = targetCal.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtLeast(28)

        val currentDay = when {
            isCurrentMonth -> nowCal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
            isPastMonth -> totalDaysInMonth
            else -> 1
        }
        val daysLeft = when {
            isCurrentMonth -> (totalDaysInMonth - currentDay + 1).coerceAtLeast(1)
            isPastMonth -> 0
            else -> totalDaysInMonth
        }
        val monthProgressRatio = when {
            isCurrentMonth -> (currentDay.toDouble() / totalDaysInMonth.toDouble()).coerceIn(0.0, 1.0)
            isPastMonth -> 1.0
            else -> 0.0
        }

        val monthExpenses = expenses.filter { exp ->
            val c = Calendar.getInstance().apply { timeInMillis = exp.createdAtEpochMs }
            c.get(Calendar.MONTH) == currentMonth &&
                c.get(Calendar.YEAR) == currentYear &&
                !exp.isDeleted &&
                !exp.isPendingConfirmation
        }

        val totalSpent = monthExpenses.sumOf { it.amountRub }
        val expectedByToday = monthlyBudgetRub * monthProgressRatio
        val remainingBudget = (monthlyBudgetRub - totalSpent).coerceAtLeast(0.0)
        val safeDaily = if (daysLeft > 0) remainingBudget / daysLeft.toDouble() else 0.0

        val eugeneExpenses = monthExpenses.filter { it.authorName.equals("Евгений", ignoreCase = true) }
        val anzhelikaExpenses = monthExpenses.filter { it.authorName.equals("Анжелика", ignoreCase = true) }

        val diagnoses = ExpenseCategory.entries.map { cat ->
            val catExpenses = monthExpenses.filter { it.category == cat }
            val catSpent = catExpenses.sumOf { it.amountRub }
            val catLimit = monthlyBudgetRub * (cat.recommendedSharePercent / 100.0)
            val catExpectedToday = catLimit * monthProgressRatio
            val diff = catSpent - catExpectedToday

            val status = when {
                catSpent > catLimit -> CategoryDiagnosis.Status.OVERBURNED
                isCurrentMonth && diff > catLimit * 0.18 -> CategoryDiagnosis.Status.OVERBURNED
                catExpectedToday - catSpent > catLimit * 0.20 -> CategoryDiagnosis.Status.CAN_SPLURGE
                else -> CategoryDiagnosis.Status.ON_TRACK
            }

            val msg = when (status) {
                CategoryDiagnosis.Status.OVERBURNED -> {
                    val overRub = (catSpent - catExpectedToday).roundToInt().coerceAtLeast(0)
                    if (isCurrentMonth) {
                        "Перебор темпа на $overRub ₽"
                    } else {
                        "Превышение лимита на ${(catSpent - catLimit).roundToInt().coerceAtLeast(0)} ₽"
                    }
                }
                CategoryDiagnosis.Status.CAN_SPLURGE -> {
                    val freeRub = (catExpectedToday - catSpent).roundToInt().coerceAtLeast(0)
                    if (isCurrentMonth) {
                        "Свободный запас +$freeRub ₽"
                    } else {
                        "Экономия за месяц +$freeRub ₽"
                    }
                }
                CategoryDiagnosis.Status.ON_TRACK -> {
                    "Идёт ровно по плану"
                }
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

        val overburned = diagnoses.filter { it.status == CategoryDiagnosis.Status.OVERBURNED }
            .sortedByDescending { it.spentRub - it.expectedByTodayRub }
        val splurge = diagnoses.filter { it.status == CategoryDiagnosis.Status.CAN_SPLURGE && it.recommendedLimitRub >= 3000 }
            .sortedByDescending { it.expectedByTodayRub - it.spentRub }

        val headline = when {
            totalSpent > monthlyBudgetRub ->
                "Месячный бюджет превышен на ${(totalSpent - monthlyBudgetRub).roundToInt()} ₽."
            !isCurrentMonth ->
                if (isPastMonth) {
                    "Итог месяца: сэкономлено ${(monthlyBudgetRub - totalSpent).roundToInt()} ₽ из бюджета."
                } else {
                    "Плановый период ещё не начался."
                }
            totalSpent > expectedByToday * 1.12 ->
                "Темп выше плана на ${(totalSpent - expectedByToday).roundToInt()} ₽. Комфортный ритм до конца месяца — ${safeDaily.roundToInt()} ₽/день."
            totalSpent < expectedByToday * 0.85 ->
                "Отличный запас! Вы ниже графика на ${(expectedByToday - totalSpent).roundToInt()} ₽ — можно спокойно позволить приятные траты."
            else ->
                "Ровный темп по графику. Безопасный дневной ритм: ${safeDaily.roundToInt()} ₽/день."
        }

        return AiBudgetReport(
            totalSpentThisMonthRub = totalSpent,
            monthlyBudgetRub = monthlyBudgetRub,
            expectedSpentByTodayRub = expectedByToday,
            safeDailySpendRemainingRub = safeDaily,
            daysLeftInMonth = daysLeft,
            monthProgressRatio = monthProgressRatio.toFloat(),
            isCurrentMonth = isCurrentMonth,
            totalTransactions = monthExpenses.size,
            eugeneSpentRub = eugeneExpenses.sumOf { it.amountRub },
            anzhelikaSpentRub = anzhelikaExpenses.sumOf { it.amountRub },
            eugeneCount = eugeneExpenses.size,
            anzhelikaCount = anzhelikaExpenses.size,
            headlineVerdict = headline,
            overburnedCategories = overburned,
            splurgeCategories = splurge,
            allCategories = diagnoses
        )
    }

    /**
     * Опциональный глубокий разбор через бесплатный ключ Gemini API (0 ₽).
     * Отправляет только обезличенные суммы по категориям.
     */
    suspend fun fetchDeepCloudAiAdvice(
        apiKey: String,
        report: AiBudgetReport
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val summaryLines = report.allCategories.joinToString("\n") {
                "- ${it.category.displayName}: потрачено ${it.spentRub.roundToInt()} ₽ (норма на месяц ${it.recommendedLimitRub.roundToInt()} ₽)"
            }
            val prompt = listOf(
                "Ты — семейный финансовый аналитик для пары (общий бюджет без деления на личные счета).",
                "Месячный бюджет: ${report.monthlyBudgetRub.roundToInt()} ₽.",
                "Потрачено с начала месяца: ${report.totalSpentThisMonthRub.roundToInt()} ₽.",
                "Осталось дней в месяце: ${report.daysLeftInMonth}.",
                "Разбивка по категориям:",
                summaryLines,
                "Дай короткий, тёплый и конкретный разбор на русском языке (до 120 слов):",
                "1) Где сейчас перебор и как мягко притормозить.",
                "2) В каких категориях есть запас и на что приятное пара может спокойно потратиться на этой неделе."
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
