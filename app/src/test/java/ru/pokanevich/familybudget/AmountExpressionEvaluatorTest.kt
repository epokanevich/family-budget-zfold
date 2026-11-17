package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.pokanevich.familybudget.ui.util.AmountExpressionEvaluator

class AmountExpressionEvaluatorTest {

    @Test
    fun simpleNumbers_parsedAndRounded() {
        assertEquals(500.0, AmountExpressionEvaluator.evaluate("500")!!, 0.001)
        assertEquals(1540.5, AmountExpressionEvaluator.evaluate("1 540.50")!!, 0.001)
        assertEquals(33.33, AmountExpressionEvaluator.evaluate("100 / 3")!!, 0.001)
    }

    @Test
    fun operatorPrecedence_multiplicationAndDivisionBeforeAddAndSubtract() {
        assertEquals(700.0, AmountExpressionEvaluator.evaluate("100 + 200 * 3")!!, 0.001)
        assertEquals(250.0, AmountExpressionEvaluator.evaluate("300 - 100 / 2")!!, 0.001)
        assertEquals(14.0, AmountExpressionEvaluator.evaluate("2 + 3 * 4")!!, 0.001)
        assertEquals(10.0, AmountExpressionEvaluator.evaluate("20 / 4 * 2")!!, 0.001)
    }

    @Test
    fun commasAndUnicodeOperators_supported() {
        assertEquals(1540.5, AmountExpressionEvaluator.evaluate("1000,25 + 540,25")!!, 0.001)
        assertEquals(300.0, AmountExpressionEvaluator.evaluate("500 − 200")!!, 0.001)
        assertEquals(600.0, AmountExpressionEvaluator.evaluate("150 × 4")!!, 0.001)
        assertEquals(125.0, AmountExpressionEvaluator.evaluate("500 ÷ 4")!!, 0.001)
    }

    @Test
    fun parentheses_overridePrecedence() {
        assertEquals(900.0, AmountExpressionEvaluator.evaluate("(100 + 200) * 3")!!, 0.001)
        assertEquals(150.0, AmountExpressionEvaluator.evaluate("300 / (1 + 1)")!!, 0.001)
        assertEquals(21.0, AmountExpressionEvaluator.evaluate("((2 + 5) * (1 + 2))")!!, 0.001)
        assertEquals(15.0, AmountExpressionEvaluator.evaluate("-5 + 20")!!, 0.001)
    }

    @Test
    fun divisionByZero_returnsNull() {
        assertNull(AmountExpressionEvaluator.evaluate("100 / 0"))
        assertNull(AmountExpressionEvaluator.evaluate("100 / (5 - 5)"))
        assertNull(AmountExpressionEvaluator.evaluate("100 / 0.0"))
    }

    @Test
    fun nonPositiveResult_returnsNull() {
        assertNull(AmountExpressionEvaluator.evaluate("0"))
        assertNull(AmountExpressionEvaluator.evaluate("100 - 100"))
        assertNull(AmountExpressionEvaluator.evaluate("100 - 200"))
        assertNull(AmountExpressionEvaluator.evaluate("-50"))
        assertNull(AmountExpressionEvaluator.evaluate("0.001"))
    }

    @Test
    fun invalidExpressions_returnNull() {
        assertNull(AmountExpressionEvaluator.evaluate(""))
        assertNull(AmountExpressionEvaluator.evaluate("   "))
        assertNull(AmountExpressionEvaluator.evaluate("100+"))
        assertNull(AmountExpressionEvaluator.evaluate("*100"))
        assertNull(AmountExpressionEvaluator.evaluate("100..5"))
        assertNull(AmountExpressionEvaluator.evaluate("."))
        assertNull(AmountExpressionEvaluator.evaluate("(100 + 200"))
        assertNull(AmountExpressionEvaluator.evaluate("100 + 200)"))
        assertNull(AmountExpressionEvaluator.evaluate("()"))
        assertNull(AmountExpressionEvaluator.evaluate("(10)(20)"))
        assertNull(AmountExpressionEvaluator.evaluate("100 + abc"))
    }
}
