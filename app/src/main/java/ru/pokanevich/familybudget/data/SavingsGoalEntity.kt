package ru.pokanevich.familybudget.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.util.UUID

/**
 * Семейная копилка / финансовая цель.
 * Хранится локально в Room (таблица `savings_goals`) и синхронизируется
 * между телефонами в `families/<CODE>/config_v1.json`.
 */
@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val emoji: String = "🎯",
    val targetAmountRub: Double = 0.0,
    val initialAmountRub: Double = 0.0,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false,
    val updatedAtEpochMs: Long = 0L
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("emoji", emoji.ifBlank { "🎯" })
        put("targetAmountRub", targetAmountRub)
        put("initialAmountRub", initialAmountRub)
        put("isArchived", isArchived)
        put("isDeleted", isDeleted)
        put("updatedAtEpochMs", updatedAtEpochMs)
    }

    companion object {
        private fun JSONObject.optNonNullString(key: String): String {
            if (!has(key) || isNull(key)) return ""
            return optString(key, "")
        }

        fun fromJsonObject(obj: JSONObject): SavingsGoalEntity? {
            val id = obj.optNonNullString("id").trim().ifBlank { return null }
            val title = obj.optNonNullString("title").trim()
            val emoji = obj.optNonNullString("emoji").trim().ifBlank { "🎯" }

            val rawTarget = if (obj.has("targetAmountRub") && !obj.isNull("targetAmountRub")) {
                obj.optDouble("targetAmountRub", 0.0)
            } else {
                0.0
            }
            val targetAmountRub = if (rawTarget.isFinite() && rawTarget >= 0.0) rawTarget else 0.0

            val rawInitial = if (obj.has("initialAmountRub") && !obj.isNull("initialAmountRub")) {
                obj.optDouble("initialAmountRub", 0.0)
            } else {
                0.0
            }
            val initialAmountRub = if (rawInitial.isFinite() && rawInitial >= 0.0) rawInitial else 0.0

            val isArchived = if (obj.has("isArchived") && !obj.isNull("isArchived")) {
                obj.optBoolean("isArchived", false)
            } else {
                false
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

            return SavingsGoalEntity(
                id = id,
                title = title,
                emoji = emoji,
                targetAmountRub = targetAmountRub,
                initialAmountRub = initialAmountRub,
                isArchived = isArchived,
                isDeleted = isDeleted,
                updatedAtEpochMs = updatedAtEpochMs
            )
        }
    }
}
