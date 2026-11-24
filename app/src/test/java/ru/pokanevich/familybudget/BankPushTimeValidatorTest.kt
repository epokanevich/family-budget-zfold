package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.service.BankPushParser
import ru.pokanevich.familybudget.service.BankPushTimeValidator
import ru.pokanevich.familybudget.service.PushTimeRejectReason
import java.time.LocalDateTime
import java.time.ZoneId

class BankPushTimeValidatorTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, min, sec)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `mirpay delayed push with postTime=now but old body timestamp is rejected`() {
        // Реальный кейс: выгрузка в 16:28:20, покупка совершена в 09:44
        val nowMs = epoch(2026, 11, 16, 16, 28, 20)
        val body = "Оплата покупки Сумма 294 ₽. 16.11.2026, 09:44. Карта Mir • • • • 3538"
        val extractedOpMs = BankPushParser.extractOperationTimestampMs(
            title = "Mir Pay",
            text = body,
            nowMs = nowMs,
            zoneId = zone
        )
        assertNotNull(extractedOpMs)
        assertEquals(epoch(2026, 11, 16, 9, 44, 0), extractedOpMs)

        val result = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs,
            notificationWhenMs = nowMs,
            operationTimeMs = extractedOpMs
        )
        assertFalse(result.isValid)
        assertEquals(PushTimeRejectReason.STALE_OPERATION_TEXT_TIME, result.rejectReason)
    }

    @Test
    fun `mirpay fresh push within 5 minutes passes`() {
        val nowMs = epoch(2026, 11, 17, 13, 27, 40)
        val body = "Оплата покупки Сумма 767 ₽. 17.11.2026, 13:25. Карта Mir • • • • 3538"
        val extractedOpMs = BankPushParser.extractOperationTimestampMs(
            title = "Mir Pay",
            text = body,
            nowMs = nowMs,
            zoneId = zone
        )
        assertNotNull(extractedOpMs)

        val result = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs - 10_000L,
            notificationWhenMs = nowMs - 10_000L,
            operationTimeMs = extractedOpMs
        )
        assertTrue(result.isValid)
        assertNull(result.rejectReason)
    }

    @Test
    fun `rejects when postTime deviates more than 5 minutes in past or future`() {
        val nowMs = epoch(2026, 11, 17, 14, 0, 0)

        val tooOld = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs - 300_001L
        )
        assertFalse(tooOld.isValid)
        assertEquals(PushTimeRejectReason.STALE_POST_TIME, tooOld.rejectReason)

        val tooFuture = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs + 300_001L
        )
        assertFalse(tooFuture.isValid)
        assertEquals(PushTimeRejectReason.STALE_POST_TIME, tooFuture.rejectReason)

        val boundaryOk = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs - 300_000L
        )
        assertTrue(boundaryOk.isValid)
    }

    @Test
    fun `rejects when notification when deviates more than 5 minutes while ignoring zero when`() {
        val nowMs = epoch(2026, 11, 17, 14, 0, 0)

        val zeroWhen = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs,
            notificationWhenMs = 0L
        )
        assertTrue(zeroWhen.isValid)

        val staleWhen = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs,
            notificationWhenMs = nowMs - 6L * 60_000L
        )
        assertFalse(staleWhen.isValid)
        assertEquals(PushTimeRejectReason.STALE_NOTIFICATION_WHEN, staleWhen.rejectReason)
    }

    @Test
    fun `extracts time-only SMS timestamp across midnight boundary`() {
        val nowMs = epoch(2026, 11, 17, 0, 2, 0)
        val opMs = BankPushParser.extractOperationTimestampMs(
            title = "900",
            text = "СберКарта ••1234 23:59 Покупка 450 ₽ Пятёрочка Баланс: 5000 ₽",
            nowMs = nowMs,
            zoneId = zone
        )
        assertEquals(epoch(2026, 11, 16, 23, 59, 0), opMs)

        val result = BankPushTimeValidator.validate(
            nowMs = nowMs,
            postTimeMs = nowMs,
            operationTimeMs = opMs
        )
        assertTrue(result.isValid)
    }

    @Test
    fun `extractOperationTimestampMs returns null when no timestamp present`() {
        val nowMs = epoch(2026, 11, 17, 12, 0, 0)
        val opMs = BankPushParser.extractOperationTimestampMs(
            title = "СберБанк Онлайн",
            text = "Покупка 1 540,50 ₽ ВкусВилл. Баланс: 12 345,67 ₽",
            nowMs = nowMs,
            zoneId = zone
        )
        assertNull(opMs)
    }
}
