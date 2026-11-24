package ru.pokanevich.familybudget.data

import kotlin.math.roundToLong

/**
 * Точная целочисленная арифметика в копейках для устранения погрешности накопления IEEE-754 Double.
 */
object MoneyMath {

    fun toKopecks(amountRub: Double): Long {
        if (!amountRub.isFinite()) return 0L
        return (amountRub * 100.0).roundToLong()
    }

    fun fromKopecks(kopecks: Long): Double = kopecks / 100.0

    fun sumRub(values: Iterable<Double>): Double {
        var totalKopecks = 0L
        for (value in values) {
            totalKopecks += toKopecks(value)
        }
        return fromKopecks(totalKopecks)
    }

    fun sumRub(values: Sequence<Double>): Double {
        var totalKopecks = 0L
        for (value in values) {
            totalKopecks += toKopecks(value)
        }
        return fromKopecks(totalKopecks)
    }
}

inline fun <T> Iterable<T>.sumRubOf(selector: (T) -> Double): Double {
    var totalKopecks = 0L
    for (item in this) {
        totalKopecks += MoneyMath.toKopecks(selector(item))
    }
    return MoneyMath.fromKopecks(totalKopecks)
}

inline fun <T> Sequence<T>.sumRubOf(selector: (T) -> Double): Double {
    var totalKopecks = 0L
    for (item in this) {
        totalKopecks += MoneyMath.toKopecks(selector(item))
    }
    return MoneyMath.fromKopecks(totalKopecks)
}
