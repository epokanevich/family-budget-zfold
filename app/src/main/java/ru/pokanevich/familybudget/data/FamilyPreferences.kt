package ru.pokanevich.familybudget.data

import android.content.Context
import android.content.SharedPreferences
import ru.pokanevich.familybudget.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FamilyPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("family_budget_prefs", Context.MODE_PRIVATE)

    /** Безопасное чтение строки: при повреждённом/чужом типе значения возвращаем дефолт, а не падаем. */
    private fun safeGetString(key: String, default: String): String =
        runCatching { prefs.getString(key, default) }.getOrNull() ?: default

    var currentUserName: String
        get() = safeGetString("user_name", "Евгений")
        set(value) = prefs.edit().putString("user_name", value).apply()

    var familySpaceCode: String
        get() = safeGetString("family_space_code", "POKANE-FAMILY")
        set(value) = prefs.edit().putString("family_space_code", value.trim().uppercase()).apply()

    var monthlyBudgetRub: Double
        get() = runCatching { prefs.getFloat("monthly_budget_rub", 200000f).toDouble() }.getOrDefault(200000.0)
        set(value) = prefs.edit().putFloat("monthly_budget_rub", value.toFloat()).apply()

    var autoConfirmBankPush: Boolean
        get() = runCatching { prefs.getBoolean("auto_confirm_push", false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean("auto_confirm_push", value).apply()

    /**
     * Ключ Gemini. Пустое значение в "gemini_api_key" по-прежнему означает «использовать ключ из сборки».
     * Полностью отключить ключ (включая ключ из BuildConfig) можно только явно через [clearGeminiApiKey].
     * Формат существующих ключей SharedPreferences не меняется.
     */
    var geminiApiKey: String
        get() {
            val explicitlyCleared = runCatching { prefs.getBoolean(KEY_GEMINI_CLEARED, false) }.getOrDefault(false)
            if (explicitlyCleared) return ""
            return safeGetString("gemini_api_key", BuildConfig.DEFAULT_GEMINI_API_KEY)
                .trim()
                .ifBlank { BuildConfig.DEFAULT_GEMINI_API_KEY }
        }
        set(value) {
            val trimmed = value.trim()
            val editor = prefs.edit().putString("gemini_api_key", trimmed)
            if (trimmed.isNotEmpty()) {
                editor.remove(KEY_GEMINI_CLEARED)
            }
            editor.apply()
        }

    /** Явная очистка ключа Gemini: AI-советник отключается даже при наличии ключа в сборке. */
    fun clearGeminiApiKey() {
        prefs.edit()
            .putString("gemini_api_key", "")
            .putBoolean(KEY_GEMINI_CLEARED, true)
            .apply()
    }

    var themeMode: String
        get() = safeGetString("theme_mode", "SYSTEM")
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var githubSyncToken: String
        get() = safeGetString("github_sync_token", BuildConfig.DEFAULT_SYNC_TOKEN)
            .trim()
            .ifBlank { BuildConfig.DEFAULT_SYNC_TOKEN }
        set(value) = prefs.edit().putString("github_sync_token", value.trim()).apply()

    val pushDebugLog: String
        get() = safeGetString("push_debug_log", "")

    fun appendPushLog(entry: String) {
        val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale("ru")).format(Date())
        val line = "[$stamp] $entry"
        val existing = pushDebugLog.lines().filter { it.isNotBlank() }
        val updated = (listOf(line) + existing).take(10).joinToString("\n")
        prefs.edit().putString("push_debug_log", updated).apply()
    }

    /**
     * Последние [MAX_PERSISTED_PUSH_DEDUP_KEYS] ключей уже обработанных уведомлений
     * (`sbn.key|postTime`), чтобы перезапуск процесса не сбрасывал защиту от повторной обработки.
     * Порядок: от старых к новым.
     */
    var recentPushDedupKeys: List<String>
        get() = safeGetString(KEY_RECENT_PUSH_DEDUP_KEYS, "")
            .split('\n')
            .filter { it.isNotBlank() }
            .takeLast(MAX_PERSISTED_PUSH_DEDUP_KEYS)
        set(value) {
            val serialized = value
                .map { it.replace('\n', ' ').replace('\r', ' ') }
                .filter { it.isNotBlank() }
                .takeLast(MAX_PERSISTED_PUSH_DEDUP_KEYS)
                .joinToString("\n")
            prefs.edit().putString(KEY_RECENT_PUSH_DEDUP_KEYS, serialized).apply()
        }

    companion object {
        const val MAX_PERSISTED_PUSH_DEDUP_KEYS = 50
        private const val KEY_GEMINI_CLEARED = "gemini_api_key_cleared"
        private const val KEY_RECENT_PUSH_DEDUP_KEYS = "recent_push_dedup_keys"
    }
}
