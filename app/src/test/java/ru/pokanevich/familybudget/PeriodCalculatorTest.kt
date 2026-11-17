package ru.pokanevich.familybudget

import org.junit.Assert.*
import org.junit.Test
import ru.pokanevich.familybudget.ui.util.BudgetPeriod
import ru.pokanevich.familybudget.ui.util.PeriodCalculator
import java.util.Calendar

class PeriodCalculatorTest {

    private fun createCalendar(year: Int, monthZeroIndexed: Int, day: Int, hour: Int = 12): Calendar {
        return Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroIndexed)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun buildWindow_dayBounds() {
        // Wednesday, June 10, 2026 15:30:00
        val cal = createCalendar(2026, Calendar.JUNE, 10, 15)
        val window = PeriodCalculator.buildWindow(BudgetPeriod.DAY, cal.timeInMillis)

        assertEquals(BudgetPeriod.DAY, window.period)
        assertEquals(1, window.totalDaysInPeriod)

        val startCal = Calendar.getInstance().apply { timeInMillis = window.startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.endMs }

        assertEquals(2026, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, startCal.get(Calendar.MONTH))
        assertEquals(10, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(Calendar.MINUTE))
        assertEquals(0, startCal.get(Calendar.SECOND))
        assertEquals(0, startCal.get(Calendar.MILLISECOND))

        assertEquals(2026, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, endCal.get(Calendar.MONTH))
        assertEquals(10, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, endCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, endCal.get(Calendar.MINUTE))
        assertEquals(59, endCal.get(Calendar.SECOND))
        assertEquals(999, endCal.get(Calendar.MILLISECOND))

        assertEquals(86400000L - 1, window.endMs - window.startMs)
    }

    @Test
    fun buildWindow_weekBounds_mondayToSunday() {
        // Test anchors across the same week (June 8, 2026 Monday to June 14, 2026 Sunday)
        val mondayCal = createCalendar(2026, Calendar.JUNE, 8, 9)
        val wednesdayCal = createCalendar(2026, Calendar.JUNE, 10, 14)
        val sundayCal = createCalendar(2026, Calendar.JUNE, 14, 20)

        val windowMonday = PeriodCalculator.buildWindow(BudgetPeriod.WEEK, mondayCal.timeInMillis)
        val windowWednesday = PeriodCalculator.buildWindow(BudgetPeriod.WEEK, wednesdayCal.timeInMillis)
        val windowSunday = PeriodCalculator.buildWindow(BudgetPeriod.WEEK, sundayCal.timeInMillis)

        // All 3 anchors must produce the exact same start (Monday 00:00) and end (Sunday 23:59:59.999)
        assertEquals(windowMonday.startMs, windowWednesday.startMs)
        assertEquals(windowMonday.endMs, windowWednesday.endMs)
        assertEquals(windowMonday.startMs, windowSunday.startMs)
        assertEquals(windowMonday.endMs, windowSunday.endMs)

        assertEquals(BudgetPeriod.WEEK, windowWednesday.period)
        assertEquals(7, windowWednesday.totalDaysInPeriod)

        val startCal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = windowWednesday.startMs
        }
        val endCal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = windowWednesday.endMs
        }

        // Start is Monday 00:00:00.000
        assertEquals(Calendar.MONDAY, startCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, startCal.get(Calendar.MINUTE))
        assertEquals(0, startCal.get(Calendar.SECOND))
        assertEquals(0, startCal.get(Calendar.MILLISECOND))

        // End is Sunday 23:59:59.999
        assertEquals(Calendar.SUNDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(14, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, endCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, endCal.get(Calendar.MINUTE))
        assertEquals(59, endCal.get(Calendar.SECOND))
        assertEquals(999, endCal.get(Calendar.MILLISECOND))

        // Exactly 7 days
        assertEquals(7 * 86400000L - 1, windowWednesday.endMs - windowWednesday.startMs)
    }

    @Test
    fun buildWindow_monthBounds() {
        // June has 30 days
        val juneCal = createCalendar(2026, Calendar.JUNE, 15)
        val juneWindow = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, juneCal.timeInMillis)

        assertEquals(BudgetPeriod.MONTH, juneWindow.period)
        assertEquals(30, juneWindow.totalDaysInPeriod)

        val startCal = Calendar.getInstance().apply { timeInMillis = juneWindow.startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = juneWindow.endMs }

        assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, endCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, endCal.get(Calendar.MINUTE))
        assertEquals(59, endCal.get(Calendar.SECOND))
        assertEquals(999, endCal.get(Calendar.MILLISECOND))

        // February leap year 2024 has 29 days
        val febLeapCal = createCalendar(2024, Calendar.FEBRUARY, 10)
        val febLeapWindow = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, febLeapCal.timeInMillis)
        assertEquals(29, febLeapWindow.totalDaysInPeriod)

        // February non-leap year 2025 has 28 days
        val febNonLeapCal = createCalendar(2025, Calendar.FEBRUARY, 10)
        val febNonLeapWindow = PeriodCalculator.buildWindow(BudgetPeriod.MONTH, febNonLeapCal.timeInMillis)
        assertEquals(28, febNonLeapWindow.totalDaysInPeriod)
    }

    @Test
    fun buildWindow_yearBounds() {
        val cal = createCalendar(2026, Calendar.AUGUST, 20)
        val window = PeriodCalculator.buildWindow(BudgetPeriod.YEAR, cal.timeInMillis)

        assertEquals(BudgetPeriod.YEAR, window.period)
        assertEquals("2026 год", window.title)
        assertEquals(365, window.totalDaysInPeriod)

        val startCal = Calendar.getInstance().apply { timeInMillis = window.startMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = window.endMs }

        assertEquals(2026, startCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, startCal.get(Calendar.MONTH))
        assertEquals(1, startCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, startCal.get(Calendar.HOUR_OF_DAY))

        assertEquals(2026, endCal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, endCal.get(Calendar.MONTH))
        assertEquals(31, endCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, endCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, endCal.get(Calendar.MINUTE))
        assertEquals(59, endCal.get(Calendar.SECOND))
        assertEquals(999, endCal.get(Calendar.MILLISECOND))
    }

    @Test
    fun buildWindow_allBounds() {
        val window = PeriodCalculator.buildWindow(BudgetPeriod.ALL, System.currentTimeMillis())

        assertEquals(BudgetPeriod.ALL, window.period)
        assertEquals(Long.MIN_VALUE, window.startMs)
        assertEquals(Long.MAX_VALUE, window.endMs)
        assertEquals("За всё время", window.title)
        assertTrue(window.isCurrentPeriod)
    }

    @Test
    fun stepAnchor_navigation() {
        val cal = createCalendar(2026, Calendar.JUNE, 10)
        val anchor = cal.timeInMillis

        // DAY
        val nextDay = PeriodCalculator.stepAnchor(BudgetPeriod.DAY, anchor, 1)
        val prevDay = PeriodCalculator.stepAnchor(BudgetPeriod.DAY, anchor, -1)
        assertEquals(11, Calendar.getInstance().apply { timeInMillis = nextDay }.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, Calendar.getInstance().apply { timeInMillis = prevDay }.get(Calendar.DAY_OF_MONTH))

        // WEEK
        val nextWeek = PeriodCalculator.stepAnchor(BudgetPeriod.WEEK, anchor, 1)
        assertEquals(17, Calendar.getInstance().apply { timeInMillis = nextWeek }.get(Calendar.DAY_OF_MONTH))

        // MONTH
        val nextMonth = PeriodCalculator.stepAnchor(BudgetPeriod.MONTH, anchor, 1)
        assertEquals(Calendar.JULY, Calendar.getInstance().apply { timeInMillis = nextMonth }.get(Calendar.MONTH))

        // YEAR
        val nextYear = PeriodCalculator.stepAnchor(BudgetPeriod.YEAR, anchor, 1)
        assertEquals(2027, Calendar.getInstance().apply { timeInMillis = nextYear }.get(Calendar.YEAR))

        // ALL
        val allSame = PeriodCalculator.stepAnchor(BudgetPeriod.ALL, anchor, 1)
        assertEquals(anchor, allSame)
    }
}
