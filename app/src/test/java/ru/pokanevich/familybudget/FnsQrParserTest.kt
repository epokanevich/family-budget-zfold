package ru.pokanevich.familybudget

import org.junit.Assert.*
import org.junit.Test
import ru.pokanevich.familybudget.scanner.FnsQrParser
import java.text.SimpleDateFormat
import java.util.Locale

class FnsQrParserTest {

    @Test
    fun parse_validStandardQr_success() {
        val qr = "t=20261116T1425&s=1845.50&fn=9960440300123456&i=45210&fp=1234567890&n=1"
        val result = FnsQrParser.parse(qr)

        assertNotNull(result)
        assertEquals(1845.50, result!!.amountRub, 0.001)
        assertEquals(qr, result.rawQrString)

        val expectedTime = SimpleDateFormat("yyyyMMdd'T'HHmm", Locale.US).parse("20261116T1425")!!.time
        assertEquals(expectedTime, result.timestampMs)
    }

    @Test
    fun parse_validQrWithSeconds_success() {
        val qr = "t=20240315T093045&s=420.00&fn=111&i=222&fp=333&n=1"
        val result = FnsQrParser.parse(qr)

        assertNotNull(result)
        assertEquals(420.00, result!!.amountRub, 0.001)

        val expectedTime = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse("20240315T093045")!!.time
        assertEquals(expectedTime, result.timestampMs)
    }

    @Test
    fun parse_validQrWithCommaSeparator_success() {
        val qr = "t=20260520T1800&s=150,75&fn=98765"
        val result = FnsQrParser.parse(qr)

        assertNotNull(result)
        assertEquals(150.75, result!!.amountRub, 0.001)
    }

    @Test
    fun parse_validQrWithAnyParameterOrder_success() {
        val qr = "s=321.10&t=20261116T1000&fn=12345&i=999"
        val result = FnsQrParser.parse(qr)

        assertNotNull(result)
        assertEquals(321.10, result!!.amountRub, 0.001)
    }

    @Test
    fun parse_invalidOrBrokenQr_returnsNull() {
        // Empty or whitespace
        assertNull(FnsQrParser.parse(""))
        assertNull(FnsQrParser.parse("   "))

        // Regular URL without FNS receipt format
        assertNull(FnsQrParser.parse("https://check.nalog.ru/receipt/123456"))

        // Missing s= (amount)
        assertNull(FnsQrParser.parse("t=20261116T1425&fn=9960440300123456"))

        // Missing t= (time)
        assertNull(FnsQrParser.parse("s=1845.50&fn=9960440300123456"))

        // Non-numeric amount
        assertNull(FnsQrParser.parse("t=20261116T1425&s=invalid&fn=123"))

        // Zero or negative amount
        assertNull(FnsQrParser.parse("t=20261116T1425&s=0.00&fn=123"))
        assertNull(FnsQrParser.parse("t=20261116T1425&s=-100.50&fn=123"))

        // Empty s parameter
        assertNull(FnsQrParser.parse("t=20261116T1425&s=&fn=123"))
    }
}
