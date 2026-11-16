package ru.pokanevich.familybudget.ui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class BudgetPeriod(val label: String) {
    DAY("День"),
    WEEK("Неделя"),
    MONTH("Месяц"),
    YEAR("Год"),
    ALL("Всё")
}

data class PeriodWindow(
    val period: BudgetPeriod,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val isCurrentPeriod: Boolean,
    val progressRatio: Float,
    val totalDaysInPeriod: Int,
    val daysLeftInPeriod: Int
)

object PeriodCalculator {

    private val ruLocale = Locale("ru", "RU")
    private val shortDayMonth = SimpleDateFormat("d MMM", ruLocale)
    private val fullDay = SimpleDateFormat("d MMMM yyyy", ruLocale)
    private val dayMonthOnly = SimpleDateFormat("d MMMM", ruLocale)

    fun buildWindow(period: BudgetPeriod, anchorEpochMs: Long): PeriodWindow {
        val nowMs = System.currentTimeMillis()
        val nowCal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = nowMs
        }
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = anchorEpochMs
        }

        return when (period) {
            BudgetPeriod.DAY -> {
                val start = (cal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val end = (cal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis

                val isToday = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
                val title = if (cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)) {
                    dayMonthOnly.format(Date(start))
                } else {
                    fullDay.format(Date(start))
                }

                PeriodWindow(
                    period = period,
                    startMs = start,
                    endMs = end,
                    title = title,
                    isCurrentPeriod = isToday,
                    progressRatio = if (isToday) 1f else if (end < nowMs) 1f else 0f,
                    totalDaysInPeriod = 1,
                    daysLeftInPeriod = if (isToday) 1 else 0
                )
            }

            BudgetPeriod.WEEK -> {
                val weekStartCal = (cal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // Если anchor оказался до понедельника (например, воскресенье в некоторых локалях)
                if (weekStartCal.timeInMillis > anchorEpochMs) {
                    weekStartCal.add(Calendar.DAY_OF_YEAR, -7)
                }
                val weekEndCal = (weekStartCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, 6)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }

                val start = weekStartCal.timeInMillis
                val end = weekEndCal.timeInMillis
                val isCurrentWeek = nowMs in start..end

                val startStr = shortDayMonth.format(Date(start)).replace(".", "")
                val endStr = shortDayMonth.format(Date(end)).replace(".", "")
                val title = "$startStr – $endStr"

                val dayIndex = if (isCurrentWeek) {
                    ((nowMs - start) / 86_400_000L).toInt().coerceIn(0, 6) + 1
                } else if (end < nowMs) 7 else 1

                PeriodWindow(
                    period = period,
                    startMs = start,
                    endMs = end,
                    title = title,
                    isCurrentPeriod = isCurrentWeek,
                    progressRatio = (dayIndex / 7f).coerceIn(0f, 1f),
                    totalDaysInPeriod = 7,
                    daysLeftInPeriod = if (isCurrentWeek) (7 - dayIndex + 1).coerceAtLeast(1) else 0
                )
            }

            BudgetPeriod.MONTH -> {
                val startCal = (cal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val totalDays = startCal.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtLeast(28)
                val endCal = (startCal.clone() as Calendar).apply {
                    set(Calendar.DAY_OF_MONTH, totalDays)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                val start = startCal.timeInMillis
                val end = endCal.timeInMillis
                val isCurrentMonth = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                    cal.get(Calendar.MONTH) == nowCal.get(Calendar.MONTH)
                val isPast = end < nowMs

                val currentDay = when {
                    isCurrentMonth -> nowCal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
                    isPast -> totalDays
                    else -> 1
                }
                val daysLeft = if (isCurrentMonth) (totalDays - currentDay + 1).coerceAtLeast(1) else 0
                val ratio = when {
                    isCurrentMonth -> (currentDay.toFloat() / totalDays.toFloat()).coerceIn(0f, 1f)
                    isPast -> 1f
                    else -> 0f
                }

                PeriodWindow(
                    period = period,
                    startMs = start,
                    endMs = end,
                    title = Formatters.formatMonthTitle(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)),
                    isCurrentPeriod = isCurrentMonth,
                    progressRatio = ratio,
                    totalDaysInPeriod = totalDays,
                    daysLeftInPeriod = daysLeft
                )
            }

            BudgetPeriod.YEAR -> {
                val startCal = (cal.clone() as Calendar).apply {
                    set(Calendar.MONTH, Calendar.JANUARY)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endCal = (cal.clone() as Calendar).apply {
                    set(Calendar.MONTH, Calendar.DECEMBER)
                    set(Calendar.DAY_OF_MONTH, 31)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
                val start = startCal.timeInMillis
                val end = endCal.timeInMillis
                val isCurrentYear = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                val totalDays = startCal.getActualMaximum(Calendar.DAY_OF_YEAR).coerceAtLeast(365)
                val dayOfYear = if (isCurrentYear) nowCal.get(Calendar.DAY_OF_YEAR) else if (end < nowMs) totalDays else 1

                PeriodWindow(
                    period = period,
                    startMs = start,
                    endMs = end,
                    title = "${cal.get(Calendar.YEAR)} год",
                    isCurrentPeriod = isCurrentYear,
                    progressRatio = (dayOfYear.toFloat() / totalDays.toFloat()).coerceIn(0f, 1f),
                    totalDaysInPeriod = totalDays,
                    daysLeftInPeriod = if (isCurrentYear) (totalDays - dayOfYear + 1).coerceAtLeast(1) else 0
                )
            }

            BudgetPeriod.ALL -> {
                PeriodWindow(
                    period = period,
                    startMs = Long.MIN_VALUE,
                    endMs = Long.MAX_VALUE,
                    title = "За всё время",
                    isCurrentPeriod = true,
                    progressRatio = 1f,
                    totalDaysInPeriod = 365,
                    daysLeftInPeriod = 0
                )
            }
        }
    }

    fun stepAnchor(period: BudgetPeriod, anchorEpochMs: Long, delta: Int): Long {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = anchorEpochMs
        }
        when (period) {
            BudgetPeriod.DAY -> cal.add(Calendar.DAY_OF_YEAR, delta)
            BudgetPeriod.WEEK -> cal.add(Calendar.DAY_OF_YEAR, delta * 7)
            BudgetPeriod.MONTH -> cal.add(Calendar.MONTH, delta)
            BudgetPeriod.YEAR -> cal.add(Calendar.YEAR, delta)
            BudgetPeriod.ALL -> {}
        }
        return cal.timeInMillis
    }
}
