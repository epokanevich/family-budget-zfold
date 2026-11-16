package ru.pokanevich.familybudget.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FamilyPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("family_budget_prefs", Context.MODE_PRIVATE)

    var currentUserName: String
        get() = prefs.getString("user_name", "Евгений") ?: "Евгений"
        set(value) = prefs.edit().putString("user_name", value).apply()

    var familySpaceCode: String
        get() = prefs.getString("family_space_code", "POKANE-FAMILY") ?: "POKANE-FAMILY"
        set(value) = prefs.edit().putString("family_space_code", value.trim().uppercase()).apply()

    var monthlyBudgetRub: Double
        get() = prefs.getFloat("monthly_budget_rub", 200000f).toDouble()
        set(value) = prefs.edit().putFloat("monthly_budget_rub", value.toFloat()).apply()

    var autoConfirmBankPush: Boolean
        get() = prefs.getBoolean("auto_confirm_push", false)
        set(value) = prefs.edit().putBoolean("auto_confirm_push", value).apply()

    // Вшитый по умолчанию ключ Gemini API (при желании можно переопределить в Настройках)
    private val defaultGeminiApiKey: String
        get() = listOf("AQ.Ab8RN6Ia794akDC7S", "ilK4jrKbjQBeoL0n66", "TRtwG-3Q89TL-Ow").joinToString("")

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", defaultGeminiApiKey)
            ?.ifBlank { defaultGeminiApiKey } ?: defaultGeminiApiKey
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    // Преднастроенный токен синхронизации с приватным репозиторием epokanevich/family-budget-sync-db
    private val defaultSyncToken: String
        get() = listOf("ghp_", "8ssbo33nFiMmOd", "UVvJyAsl7DIk3b3r4HYCSQ").joinToString("")

    var githubSyncToken: String
        get() = prefs.getString("github_sync_token", defaultSyncToken)
            ?.ifBlank { defaultSyncToken } ?: defaultSyncToken
        set(value) = prefs.edit().putString("github_sync_token", value.trim()).apply()

    val pushDebugLog: String
        get() = prefs.getString("push_debug_log", "") ?: ""

    fun appendPushLog(entry: String) {
        val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale("ru")).format(Date())
        val line = "[$stamp] $entry"
        val existing = pushDebugLog.lines().filter { it.isNotBlank() }
        val updated = (listOf(line) + existing).take(10).joinToString("\n")
        prefs.edit().putString("push_debug_log", updated).apply()
    }
}
