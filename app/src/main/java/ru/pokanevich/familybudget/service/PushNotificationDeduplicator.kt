package ru.pokanevich.familybudget.service

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Устойчивая к обновлениям шторки и перезапускам сервиса дедупликация системных уведомлений.
 *
 * Решает две причины двойных записей Сбера:
 * 1. Обновление того же уведомления в шторке (смена `sbn.postTime` при сохранении `sbn.key`/`tag`/`id`
 *    и того же содержимого) -> отсекается по `(slotHash, contentHash)` в окне [SLOT_CONTENT_TTL_MS].
 * 2. Перезапуск `BankPushListenerService` и повторная выдача `activeNotifications` ->
 *    ключи сериализуются в `SharedPreferences` (до [MAX_ENTRIES] записей) и восстанавливаются.
 */
class PushNotificationDeduplicator(
    private val maxEntries: Int = MAX_ENTRIES,
    private val slotContentTtlMs: Long = SLOT_CONTENT_TTL_MS
) {
    private data class Entry(
        val deliveryKey: String,
        val slotHash: String,
        val contentHash: String,
        val seenAtMs: Long
    ) {
        fun serialize(): String = "v2|$seenAtMs|$slotHash|$contentHash|$deliveryKey"

        companion object {
            fun parse(raw: String): Entry? {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return null
                if (trimmed.startsWith("v2|")) {
                    val parts = trimmed.split('|', limit = 5)
                    if (parts.size == 5) {
                        val ts = parts[1].toLongOrNull() ?: return null
                        return Entry(
                            deliveryKey = parts[4],
                            slotHash = parts[2],
                            contentHash = parts[3],
                            seenAtMs = ts
                        )
                    }
                }
                // Обратная совместимость со старыми ключами вида `sbnKey|postTime`
                return Entry(
                    deliveryKey = trimmed,
                    slotHash = "",
                    contentHash = "",
                    seenAtMs = 0L
                )
            }
        }
    }

    private val entries = ArrayDeque<Entry>()
    private var loaded = false

    @Synchronized
    fun ensureLoaded(persistedLines: List<String>) {
        if (loaded) return
        loaded = true
        for (line in persistedLines) {
            Entry.parse(line)?.let { entries.addLast(it) }
        }
        trimToMax()
    }

    /**
     * Проверяет, является ли уведомление новым. Если да — регистрирует его и возвращает `true`.
     * Возвращает `false`, если совпадает точный `deliveryKey` ИЛИ тот же notification slot
     * уже доставлял идентичное содержимое в пределах [slotContentTtlMs].
     */
    @Synchronized
    fun markIfNew(
        slotKey: String,
        postTimeMs: Long,
        contentFingerprint: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val deliveryKey = "$slotKey|$postTimeMs"
        val slotHash = sha256Short(slotKey)
        val contentHash = sha256Short(contentFingerprint)

        val duplicate = entries.any { existing ->
            existing.deliveryKey == deliveryKey ||
                (existing.slotHash.isNotEmpty() &&
                    existing.slotHash == slotHash &&
                    existing.contentHash == contentHash &&
                    abs(nowMs - existing.seenAtMs) <= slotContentTtlMs)
        }
        if (duplicate) return false

        entries.addLast(
            Entry(
                deliveryKey = deliveryKey,
                slotHash = slotHash,
                contentHash = contentHash,
                seenAtMs = nowMs
            )
        )
        trimToMax()
        return true
    }

    @Synchronized
    fun exportPersistedLines(): List<String> = entries.map { it.serialize() }

    @Synchronized
    fun resetForTest() {
        entries.clear()
        loaded = false
    }

    private fun trimToMax() {
        while (entries.size > maxEntries) {
            entries.removeFirst()
        }
    }

    companion object {
        const val MAX_ENTRIES = 100
        const val SLOT_CONTENT_TTL_MS = 10L * 60L * 1000L

        fun buildContentFingerprint(parsed: ParsedBankExpense): String {
            val kopecks = (parsed.amountRub * 100.0).roundToLong()
            val normMerchant = parsed.merchant.lowercase().replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
            val normRaw = parsed.rawText.lowercase().replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
            return "${parsed.sourceType}|$kopecks|$normMerchant|$normRaw"
        }

        private fun sha256Short(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(16)
            for (i in 0 until 8) {
                val v = bytes[i].toInt() and 0xFF
                sb.append("0123456789abcdef"[v ushr 4])
                sb.append("0123456789abcdef"[v and 0x0F])
            }
            return sb.toString()
        }
    }
}
