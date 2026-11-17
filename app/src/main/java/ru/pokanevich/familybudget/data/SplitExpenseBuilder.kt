package ru.pokanevich.familybudget.data

import java.util.UUID
import kotlin.math.abs

data class SplitPartInput(
    val amountRub: Double,
    val category: ExpenseCategory,
    val note: String = ""
)

object SplitExpenseBuilder {

    const val AMOUNT_TOLERANCE_RUB = 0.01

    /**
     * Строит N обычных [ExpenseEntity] с общим [splitGroupId] для разделённого чека (Split).
     *
     * Правила:
     * - минимум 2 части, каждая сумма > 0, сумма частей равна [totalAmountRub] с допуском 0.01 ₽;
     * - если [original] != null, первая часть сохраняет id, createdAtEpochMs, sourceType,
     *   rawPushOrQrText и исходную dedupSignature (чтобы при позднем прилёте парного пуша
     *   Сбера/Mir Pay кросс-девайс дедупликация продолжала узнавать исходную сумму чека);
     * - остальные части получают новые UUID и пустой dedupSignature;
     * - при делении с нуля все части получают общий splitGroupId и одинаковый createdAtEpochMs.
     */
    fun build(
        original: ExpenseEntity?,
        parts: List<SplitPartInput>,
        totalAmountRub: Double,
        merchant: String,
        authorName: String,
        note: String,
        sourceType: String = "MANUAL",
        nowEpochMs: Long = System.currentTimeMillis(),
        splitGroupId: String = UUID.randomUUID().toString()
    ): List<ExpenseEntity> {
        require(parts.size >= 2) { "Для разделения чека нужно минимум 2 части" }
        require(totalAmountRub.isFinite() && totalAmountRub > 0.0) {
            "Общая сумма чека должна быть больше 0"
        }
        parts.forEachIndexed { index, part ->
            require(part.amountRub.isFinite() && part.amountRub > 0.0) {
                "Сумма части #${index + 1} должна быть больше 0"
            }
        }
        val partsSum = parts.sumOf { it.amountRub }
        require(abs(partsSum - totalAmountRub) <= AMOUNT_TOLERANCE_RUB + 1e-6) {
            "Сумма частей ($partsSum) не совпадает с общей суммой ($totalAmountRub)"
        }

        val cleanMerchant = merchant.trim().ifBlank { "Покупка по карте" }
        val cleanCommonNote = note.trim()
        val baseCreatedAt = original?.createdAtEpochMs ?: nowEpochMs
        val effectiveSourceType = original?.sourceType ?: sourceType

        return parts.mapIndexed { index, part ->
            val partNote = buildPartNote(cleanCommonNote, part.note.trim())
            val isFirstOfOriginal = original != null && index == 0
            ExpenseEntity(
                id = if (isFirstOfOriginal) original!!.id else UUID.randomUUID().toString(),
                amountRub = part.amountRub,
                merchant = cleanMerchant,
                categoryName = part.category.name,
                authorName = authorName,
                sourceType = effectiveSourceType,
                isPendingConfirmation = false,
                isDeleted = false,
                isSynced = false,
                createdAtEpochMs = baseCreatedAt,
                updatedAtEpochMs = nowEpochMs,
                rawPushOrQrText = if (isFirstOfOriginal) original!!.rawPushOrQrText else "",
                dedupSignature = if (isFirstOfOriginal) original!!.dedupSignature else "",
                note = partNote,
                splitGroupId = splitGroupId,
                operationType = OperationType.EXPENSE.name
            )
        }
    }

    private fun buildPartNote(commonNote: String, partNote: String): String = when {
        partNote.isNotEmpty() && commonNote.isNotEmpty() -> "$partNote • $commonNote"
        partNote.isNotEmpty() -> partNote
        else -> commonNote
    }
}
