package ru.pokanevich.familybudget.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.util.UUID

/**
 * Единая запись расхода общего семейного бюджета.
 * Каждая трата имеет свой глобальный UUIDv4 и флаг мягкого удаления (isDeleted),
 * благодаря чему два телефона никогда не перезаписывают и не теряют траты друг друга.
 */
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val amountRub: Double = 0.0,
    val merchant: String = "",
    val categoryName: String = ExpenseCategory.OTHER.name,
    val authorName: String = "Евгений", // "Евгений" или "Анжелика" (информативно, бюджет общий)
    val sourceType: String = "MANUAL",  // "MANUAL", "PUSH_SBER", "PUSH_MIRPAY", "QR_RECEIPT"
    val isPendingConfirmation: Boolean = false, // true = попало во "Входящие из пушей" на проверку в 1 тап
    val isDeleted: Boolean = false,     // Soft-delete для бесконфликтной синхронизации
    val isSynced: Boolean = false,      // Локальный маркер отправки в Firestore
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val rawPushOrQrText: String = "",
    val dedupSignature: String = ""     // Защита от дублей одного и того же пуша Сбера и Mir Pay
) {
    val category: ExpenseCategory
        get() = ExpenseCategory.fromStoredName(categoryName)

    val isIncome: Boolean
        get() = category.isIncome

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("amountRub", amountRub)
        put("merchant", merchant)
        put("categoryName", categoryName)
        if (authorName != "Евгений") put("authorName", authorName)
        if (sourceType != "MANUAL") put("sourceType", sourceType)
        if (isPendingConfirmation) put("isPendingConfirmation", true)
        if (isDeleted) put("isDeleted", true)
        put("createdAtEpochMs", createdAtEpochMs)
        if (updatedAtEpochMs != createdAtEpochMs) put("updatedAtEpochMs", updatedAtEpochMs)
        if (rawPushOrQrText.isNotEmpty()) put("rawPushOrQrText", rawPushOrQrText)
        if (dedupSignature.isNotEmpty()) put("dedupSignature", dedupSignature)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): ExpenseEntity? {
            val id = obj.optString("id", "").ifBlank { return null }
            val amount = obj.optDouble("amountRub", 0.0)
            val merchant = obj.optString("merchant", "")
            val categoryName = obj.optString("categoryName", ExpenseCategory.OTHER.name)
            val authorName = obj.optString("authorName", "Евгений")
            val sourceType = obj.optString("sourceType", "MANUAL")
            val isPending = obj.optBoolean("isPendingConfirmation", false)
            val isDeleted = obj.optBoolean("isDeleted", false)
            val createdAt = obj.optLong("createdAtEpochMs", System.currentTimeMillis())
            val updatedAt = obj.optLong("updatedAtEpochMs", createdAt)
            val rawText = obj.optString("rawPushOrQrText", "")
            val dedup = obj.optString("dedupSignature", "")

            return ExpenseEntity(
                id = id,
                amountRub = amount,
                merchant = merchant,
                categoryName = categoryName,
                authorName = authorName,
                sourceType = sourceType,
                isPendingConfirmation = isPending,
                isDeleted = isDeleted,
                isSynced = true,
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = updatedAt,
                rawPushOrQrText = rawText,
                dedupSignature = dedup
            )
        }
    }
}
