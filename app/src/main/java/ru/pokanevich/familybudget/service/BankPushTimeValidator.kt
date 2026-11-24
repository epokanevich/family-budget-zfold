package ru.pokanevich.familybudget.service

import kotlin.math.abs

enum class PushTimeRejectReason {
    STALE_POST_TIME,
    STALE_NOTIFICATION_WHEN,
    STALE_OPERATION_TEXT_TIME
}

data class PushTimeValidationResult(
    val isValid: Boolean,
    val rejectReason: PushTimeRejectReason? = null,
    val maxDriftMs: Long = 0L,
    val detectedTimestampMs: Long? = null
)

/**
 * Проверяет актуальность банковского уведомления по правилу ±5 минут.
 *
 * MirPay при зависании часто доставляет уведомление спустя час и более, причём `sbn.postTime`
 * может равняться текущему времени, а реальная дата/время покупки написаны в теле пуша.
 * Поэтому проверяются ВСЕ доступные метки времени: `sbn.postTime`, `notification.when` (если задан)
 * и время операции, извлечённое из текста/заголовка пуша.
 */
object BankPushTimeValidator {

    const val MAX_ALLOWED_DRIFT_MS: Long = 5L * 60L * 1000L

    /** Нижняя граница валидного epoch ms (2020-01-01T00:00:00Z), чтобы игнорировать 0L / uptime. */
    const val MIN_VALID_EPOCH_MS: Long = 1_577_836_800_000L

    fun validate(
        nowMs: Long,
        postTimeMs: Long,
        notificationWhenMs: Long = 0L,
        operationTimeMs: Long? = null
    ): PushTimeValidationResult {
        var maxDrift = 0L

        if (postTimeMs > 0L) {
            val drift = abs(nowMs - postTimeMs)
            maxDrift = maxOf(maxDrift, drift)
            if (drift > MAX_ALLOWED_DRIFT_MS) {
                return PushTimeValidationResult(
                    isValid = false,
                    rejectReason = PushTimeRejectReason.STALE_POST_TIME,
                    maxDriftMs = drift,
                    detectedTimestampMs = postTimeMs
                )
            }
        }

        if (notificationWhenMs >= MIN_VALID_EPOCH_MS) {
            val drift = abs(nowMs - notificationWhenMs)
            maxDrift = maxOf(maxDrift, drift)
            if (drift > MAX_ALLOWED_DRIFT_MS) {
                return PushTimeValidationResult(
                    isValid = false,
                    rejectReason = PushTimeRejectReason.STALE_NOTIFICATION_WHEN,
                    maxDriftMs = drift,
                    detectedTimestampMs = notificationWhenMs
                )
            }
        }

        if (operationTimeMs != null && operationTimeMs >= MIN_VALID_EPOCH_MS) {
            val drift = abs(nowMs - operationTimeMs)
            maxDrift = maxOf(maxDrift, drift)
            if (drift > MAX_ALLOWED_DRIFT_MS) {
                return PushTimeValidationResult(
                    isValid = false,
                    rejectReason = PushTimeRejectReason.STALE_OPERATION_TEXT_TIME,
                    maxDriftMs = drift,
                    detectedTimestampMs = operationTimeMs
                )
            }
        }

        return PushTimeValidationResult(
            isValid = true,
            rejectReason = null,
            maxDriftMs = maxDrift,
            detectedTimestampMs = operationTimeMs ?: postTimeMs.takeIf { it > 0L }
        )
    }
}
