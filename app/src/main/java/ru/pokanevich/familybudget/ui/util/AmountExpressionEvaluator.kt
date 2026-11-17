package ru.pokanevich.familybudget.ui.util

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Безопасный парсер и вычислитель арифметических выражений для поля суммы.
 * Поддерживает +, -, *, /, скобки (), десятичную точку и запятую, игнорирует пробелы.
 */
object AmountExpressionEvaluator {

    private const val ZERO_EPSILON = 1e-12

    fun hasOperators(raw: String): Boolean {
        return raw.any {
            it == '+' || it == '-' || it == '−' ||
                it == '*' || it == '×' ||
                it == '/' || it == '÷' ||
                it == '(' || it == ')'
        }
    }

    fun formatForInput(amount: Double): String {
        val kopecks = (amount * 100.0).roundToLong()
        return if (kopecks % 100L == 0L) {
            (kopecks / 100L).toString()
        } else {
            val whole = kopecks / 100L
            val frac = abs(kopecks % 100L)
            val fracStr = if (frac % 10L == 0L) {
                (frac / 10L).toString()
            } else {
                frac.toString().padStart(2, '0')
            }
            "$whole.$fracStr"
        }
    }

    fun evaluate(raw: String): Double? {
        val normalized = normalize(raw) ?: return null
        if (normalized.isEmpty()) return null

        val parser = Parser(normalized)
        val value = parser.parseExpression() ?: return null
        if (!parser.isAtEnd()) return null
        if (value.isNaN() || value.isInfinite()) return null

        val kopecks = (value * 100.0).roundToLong()
        val rounded = kopecks / 100.0
        if (rounded <= 0.0) return null
        return rounded
    }

    private fun normalize(raw: String): String? {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch.isWhitespace() -> Unit
                ch in '0'..'9' -> sb.append(ch)
                ch == '.' || ch == ',' -> sb.append('.')
                ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '(' || ch == ')' -> sb.append(ch)
                ch == '−' -> sb.append('-')
                ch == '×' -> sb.append('*')
                ch == '÷' -> sb.append('/')
                else -> return null
            }
        }
        return sb.toString()
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos == input.length

        fun parseExpression(): Double? {
            var left = parseTerm() ?: return null
            while (pos < input.length) {
                when (input[pos]) {
                    '+' -> {
                        pos++
                        val right = parseTerm() ?: return null
                        left += right
                    }
                    '-' -> {
                        pos++
                        val right = parseTerm() ?: return null
                        left -= right
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parseTerm(): Double? {
            var left = parseFactor() ?: return null
            while (pos < input.length) {
                when (input[pos]) {
                    '*' -> {
                        pos++
                        val right = parseFactor() ?: return null
                        left *= right
                    }
                    '/' -> {
                        pos++
                        val right = parseFactor() ?: return null
                        if (abs(right) < ZERO_EPSILON) return null
                        left /= right
                    }
                    else -> break
                }
            }
            return left
        }

        private fun parseFactor(): Double? {
            if (pos >= input.length) return null
            return when (input[pos]) {
                '+' -> {
                    pos++
                    parseFactor()
                }
                '-' -> {
                    pos++
                    parseFactor()?.let { -it }
                }
                '(' -> {
                    pos++
                    val inner = parseExpression() ?: return null
                    if (pos >= input.length || input[pos] != ')') return null
                    pos++
                    inner
                }
                else -> parseNumber()
            }
        }

        private fun parseNumber(): Double? {
            val start = pos
            var hasDigit = false
            var hasDot = false
            while (pos < input.length) {
                val ch = input[pos]
                when {
                    ch in '0'..'9' -> {
                        hasDigit = true
                        pos++
                    }
                    ch == '.' -> {
                        if (hasDot) return null
                        hasDot = true
                        pos++
                    }
                    else -> break
                }
            }
            if (!hasDigit) return null
            return input.substring(start, pos).toDoubleOrNull()
        }
    }
}
