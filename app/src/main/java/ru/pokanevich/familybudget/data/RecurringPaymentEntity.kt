package ru.pokanevich.familybudget.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.util.UUID

/**
 * Шаблон регулярного платежа или подписки.
 * Хранится локально в Room (таблица `recurring_payments`) и синхронизируется
 * между телефонами в `families/<CODE>/config_v1.json`.
 */
@Entity(tableName = "recurring_payments")
data class RecurringPaymentEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val amountRub: Double = 0.0,
    val categoryId: String = ExpenseCategory.OTHER.name,
    val operationType: String = OperationType.EXPENSE.name,
    val dayOfMonth: Int = 1,
    val autoCreateInFeed: Boolean = true,
    val lastPaidPeriodKey: String = "",
    val isActive: Boolean = true,
    val isDeleted: Boolean = false,
    val updatedAtEpochMs: Long = 0L
) {
    val effectiveOperationType: OperationType
        get() = runCatching { OperationType.valueOf(operationType.trim().uppercase()) }
            .getOrDefault(OperationType.EXPENSE)

    fun isPaidInPeriod(periodKey: String): Boolean =
        periodKey.isNotBlank() && lastPaidPeriodKey == periodKey

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("amountRub", amountRub)
        put("categoryId", categoryId)
        put("operationType", effectiveOperationType.name)
        put("dayOfMonth", dayOfMonth.coerceIn(1, 31))
        put("autoCreateInFeed", autoCreateInFeed)
        put("lastPaidPeriodKey", lastPaidPeriodKey)
        put("isActive", isActive)
        put("isDeleted", isDeleted)
        put("updatedAtEpochMs", updatedAtEpochMs)
    }

    companion object {
        private fun JSONObject.optNonNullString(key: String): String {
            if (!has(key) || isNull(key)) return ""
            return optString(key, "")
        }

        fun fromJsonObject(obj: JSONObject): RecurringPaymentEntity? {
            val id = obj.optNonNullString("id").trim().ifBlank { return null }
            val title = obj.optNonNullString("title").trim()
            val rawAmount = if (obj.has("amountRub") && !obj.isNull("amountRub")) {
                obj.optDouble("amountRub", 0.0)
            } else {
                0.0
            }
            val amountRub = if (rawAmount.isFinite() && rawAmount >= 0.0) rawAmount else 0.0
            val categoryId = obj.optNonNullString("categoryId").trim()
                .ifBlank { ExpenseCategory.OTHER.name }
            val rawOp = obj.optNonNullString("operationType").trim().uppercase()
            val operationType = runCatching { OperationType.valueOf(rawOp).name }
                .getOrDefault(OperationType.EXPENSE.name)
            val dayOfMonth = if (obj.has("dayOfMonth") && !obj.isNull("dayOfMonth")) {
                obj.optInt("dayOfMonth", 1).coerceIn(1, 31)
            } else {
                1
            }
            val autoCreateInFeed = if (obj.has("autoCreateInFeed") && !obj.isNull("autoCreateInFeed")) {
                obj.optBoolean("autoCreateInFeed", true)
            } else {
                true
            }
            val lastPaidPeriodKey = obj.optNonNullString("lastPaidPeriodKey").trim()
            val isActive = if (obj.has("isActive") && !obj.isNull("isActive")) {
                obj.optBoolean("isActive", true)
            } else {
                true
            }
            val isDeleted = if (obj.has("isDeleted") && !obj.isNull("isDeleted")) {
                obj.optBoolean("isDeleted", false)
            } else {
                false
            }
            val updatedAtEpochMs = if (obj.has("updatedAtEpochMs") && !obj.isNull("updatedAtEpochMs")) {
                obj.optLong("updatedAtEpochMs", 0L).coerceAtLeast(0L)
            } else {
                0L
            }

            return RecurringPaymentEntity(
                id = id,
                title = title,
                amountRub = amountRub,
                categoryId = categoryId,
                operationType = operationType,
                dayOfMonth = dayOfMonth,
                autoCreateInFeed = autoCreateInFeed,
                lastPaidPeriodKey = lastPaidPeriodKey,
                isActive = isActive,
                isDeleted = isDeleted,
                updatedAtEpochMs = updatedAtEpochMs
            )
        }
    }
}
