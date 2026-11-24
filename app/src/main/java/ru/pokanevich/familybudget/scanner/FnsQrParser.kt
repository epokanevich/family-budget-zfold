package ru.pokanevich.familybudget.scanner

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FnsReceiptQrData(
    val amountRub: Double,
    val timestampMs: Long,
    val rawQrString: String
)

object FnsQrParser {

    private val FNS_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US),
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm", Locale.US)
    )

    /**
     * Формат стандартного QR-кода кассового чека ФНС РФ и URL-обёрток ОФД:
     * `t=20261116T1425&s=1845.50&fn=9960440300123456&i=45210&fp=1234567890&n=1`
     * или `https://check.ofd.ru/?t=20261116T1425&s=1845.50&fn=...`
     */
    fun parse(qrRaw: String): FnsReceiptQrData? {
        val trimmed = qrRaw.trim()
        if (trimmed.isEmpty()) return null

        val queryCandidate = if ('?' in trimmed) {
            trimmed.substringAfter('?').substringBefore('#')
        } else {
            trimmed.substringBefore('#')
        }
        if (!queryCandidate.contains("s=", ignoreCase = true) ||
            !queryCandidate.contains("t=", ignoreCase = true)
        ) {
            return null
        }

        val params = queryCandidate.split("&").associate { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                part.substring(0, idx).trim().lowercase(Locale.US) to part.substring(idx + 1).trim()
            } else {
                "" to ""
            }
        }

        val sumStr = params["s"]?.replace(",", ".") ?: return null
        val amount = sumStr.toDoubleOrNull() ?: return null
        if (!amount.isFinite() || amount <= 0.0) return null

        val timeStr = params["t"].orEmpty()
        val parsedTimeMs = parseFnsDate(timeStr) ?: System.currentTimeMillis()

        return FnsReceiptQrData(
            amountRub = amount,
            timestampMs = parsedTimeMs,
            rawQrString = trimmed
        )
    }

    private fun parseFnsDate(raw: String): Long? {
        if (raw.isBlank()) return null
        val zone = ZoneId.systemDefault()
        for (formatter in FNS_FORMATTERS) {
            val epoch = runCatching {
                LocalDateTime.parse(raw, formatter).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
            if (epoch != null) return epoch
        }
        return null
    }
}
