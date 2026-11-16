package ru.pokanevich.familybudget.scanner

import java.text.SimpleDateFormat
import java.util.Locale

data class FnsReceiptQrData(
    val amountRub: Double,
    val timestampMs: Long,
    val rawQrString: String
)

object FnsQrParser {
    /**
     * Формат стандартного QR-кода кассового чека ФНС РФ:
     * t=20261116T1425&s=1845.50&fn=9960440300123456&i=45210&fp=1234567890&n=1
     */
    fun parse(qrRaw: String): FnsReceiptQrData? {
        if (!qrRaw.contains("s=") || !qrRaw.contains("t=")) return null

        val params = qrRaw.split("&").associate { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                part.substring(0, idx).lowercase() to part.substring(idx + 1)
            } else {
                "" to ""
            }
        }

        val sumStr = params["s"]?.replace(",", ".") ?: return null
        val amount = sumStr.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        val timeStr = params["t"] ?: ""
        val parsedTimeMs = parseFnsDate(timeStr) ?: System.currentTimeMillis()

        return FnsReceiptQrData(
            amountRub = amount,
            timestampMs = parsedTimeMs,
            rawQrString = qrRaw
        )
    }

    private fun parseFnsDate(raw: String): Long? {
        val formats = listOf("yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HHmm")
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val date = sdf.parse(raw)
                if (date != null) return date.time
            }
        }
        return null
    }
}
