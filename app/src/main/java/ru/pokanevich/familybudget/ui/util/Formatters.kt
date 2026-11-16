package ru.pokanevich.familybudget.ui.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object Formatters {

    private val ruLocale = Locale("ru", "RU")

    private val integerFormatter = DecimalFormat("#,##0", DecimalFormatSymbols(ruLocale).apply {
        groupingSeparator = '\u00A0'
    })

    private val decimalFormatter = DecimalFormat("#,##0.00", DecimalFormatSymbols(ruLocale).apply {
        groupingSeparator = '\u00A0'
        decimalSeparator = ','
    })

    private val timeFormat = SimpleDateFormat("HH:mm", ruLocale)
    private val dayMonthFormat = SimpleDateFormat("d MMMM", ruLocale)
    private val dayMonthYearFormat = SimpleDateFormat("d MMMM yyyy", ruLocale)

    private val monthNamesNominative = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    )

    private val monthNamesGenitive = listOf(
        "январе", "феврале", "марте", "апреле", "мае", "июне",
        "июле", "августе", "сентябре", "октябре", "ноябре", "декабре"
    )

    fun formatRub(amount: Double, showKopecksIfAny: Boolean = false): String {
        val kopecks = (amount * 100.0).roundToLong()
        if (kopecks == 0L) return "0\u00A0₽"
        val rounded = kopecks / 100.0
        val hasFraction = showKopecksIfAny && (kopecks % 100L != 0L)
        val numStr = if (hasFraction) {
            decimalFormatter.format(rounded)
        } else {
            integerFormatter.format(amount.roundToLong())
        }
        return "$numStr\u00A0₽"
    }

    fun formatMonthTitle(year: Int, monthZeroBased: Int): String {
        val name = monthNamesNominative.getOrElse(monthZeroBased) { "Месяц" }
        return "$name $year"
    }

    fun formatMonthPrepositional(monthZeroBased: Int): String {
        return monthNamesGenitive.getOrElse(monthZeroBased) { "месяце" }
    }

    fun formatTimeOnly(epochMs: Long): String {
        return timeFormat.format(Date(epochMs))
    }

    fun formatFullDateTime(epochMs: Long): String {
        return "${formatDayHeader(epochMs)}, ${formatTimeOnly(epochMs)}"
    }

    fun formatDayHeader(epochMs: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = epochMs }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        return when {
            isSameDay(target, today) -> "Сегодня"
            isSameDay(target, yesterday) -> "Вчера"
            target.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> dayMonthFormat.format(Date(epochMs))
            else -> dayMonthYearFormat.format(Date(epochMs))
        }
    }

    fun dayKey(epochMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
