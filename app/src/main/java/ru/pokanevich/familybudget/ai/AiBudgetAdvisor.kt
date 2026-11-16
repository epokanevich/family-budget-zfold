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
    val message: String
) {
    enum class Status { OVERBURNED, ON_TRACK, CAN_SPLURGE }
}

data class AiBudgetReport(
    val totalSpentThisMonthRub: Double,
    val monthlyBudgetRub: Double,
    val expectedSpentByTodayRub: Double,
    val safeDailySpendRemainingRub: Double,
    val daysLeftInMonth: Int,
    val headlineVerdict: String,
    val overburnedCategories: List<CategoryDiagnosis>,
    val splurgeCategories: List<CategoryDiagnosis>,
    val allCategories: List<CategoryDiagnosis>
)

object AiBudgetAdvisor {

    fun buildInstantLocalReport(
        expenses: List<ExpenseEntity>,
        monthlyBudgetRub: Double
    ): AiBudgetReport {
        val cal = Calendar.getInstance()
        val currentMonth = cal.get(Calendar.MONTH)
        val currentYear = cal.get(Calendar.YEAR)
        val currentDay = cal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
        val totalDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtLeast(28)
        val daysLeft = (totalDaysInMonth - currentDay + 1).coerceAtLeast(1)
        val monthProgressRatio = currentDay.toDouble() / totalDaysInMonth.toDouble()

        val monthExpenses = expenses.filter { exp ->
            val c = Calendar.getInstance().apply { timeInMillis = exp.createdAtEpochMs }
            c.get(Calendar.MONTH) == currentMonth && c.get(Calendar.YEAR) == currentYear && !exp.isDeleted && !exp.isPendingConfirmation
        }

        val totalSpent = monthExpenses.sumOf { it.amountRub }
        val expectedByToday = monthlyBudgetRub * monthProgressRatio
        val remainingBudget = (monthlyBudgetRub - totalSpent).coerceAtLeast(0.0)
        val safeDaily = remainingBudget / daysLeft.toDouble()

        val diagnoses = ExpenseCategory.entries.map { cat ->
            val catSpent = monthExpenses.filter { it.category == cat }.sumOf { it.amountRub }
            val catLimit = monthlyBudgetRub * (cat.recommendedSharePercent / 100.0)
            val catExpectedToday = catLimit * monthProgressRatio
            val diff = catSpent - catExpectedToday

            val status = when {
                catSpent > catLimit -> CategoryDiagnosis.Status.OVERBURNED
                diff > catLimit * 0.18 -> CategoryDiagnosis.Status.OVERBURNED
                catExpectedToday - catSpent > catLimit * 0.20 -> CategoryDiagnosis.Status.CAN_SPLURGE
                else -> CategoryDiagnosis.Status.ON_TRACK
            }

            val msg = when (status) {
                CategoryDiagnosis.Status.OVERBURNED -> {
                    val overRub = (catSpent - catExpectedToday).roundToInt()
                    "Перебор темпа на $overRub ₽ (потрачено ${catSpent.roundToInt()} ₽ из рекомендуемых ${catLimit.roundToInt()} ₽ на месяц)."
                }
                CategoryDiagnosis.Status.CAN_SPLURGE -> {
                    val freeRub = (catExpectedToday - catSpent).roundToInt()
                    "Свободный запас +$freeRub ₽ к текущему дню — можно смело порадовать себя!"
                }
                CategoryDiagnosis.Status.ON_TRACK -> {
                    "В рамках нормы: ${catSpent.roundToInt()} ₽ из ${catLimit.roundToInt()} ₽."
                }
            }

            CategoryDiagnosis(
                category = cat,
                spentRub = catSpent,
                recommendedLimitRub = catLimit,
                expectedByTodayRub = catExpectedToday,
                status = status,
                message = msg
            )
        }

        val overburned = diagnoses.filter { it.status == CategoryDiagnosis.Status.OVERBURNED }
            .sortedByDescending { it.spentRub - it.expectedByTodayRub }
        val splurge = diagnoses.filter { it.status == CategoryDiagnosis.Status.CAN_SPLURGE && it.recommendedLimitRub >= 3000 }
            .sortedByDescending { it.expectedByTodayRub - it.spentRub }

        val headline = when {
            totalSpent > monthlyBudgetRub ->
                "Внимание: общий месячный бюджет превышен на ${(totalSpent - monthlyBudgetRub).roundToInt()} ₽."
            totalSpent > expectedByToday * 1.12 ->
                "Темп выше плана на ${(totalSpent - expectedByToday).roundToInt()} ₽. Чтобы уложиться в бюджет, держите ритм около ${safeDaily.roundToInt()} ₽/день."
            totalSpent < expectedByToday * 0.85 ->
                "Отличный запас! Вы сэкономили ${(expectedByToday - totalSpent).roundToInt()} ₽ относительно графика — можно позволить себе приятные покупки."
            else ->
                "Идёте точно по графику! Комфортный дневной лимит до конца месяца: ${safeDaily.roundToInt()} ₽/день."
        }

        return AiBudgetReport(
            totalSpentThisMonthRub = totalSpent,
            monthlyBudgetRub = monthlyBudgetRub,
            expectedSpentByTodayRub = expectedByToday,
            safeDailySpendRemainingRub = safeDaily,
            daysLeftInMonth = daysLeft,
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
