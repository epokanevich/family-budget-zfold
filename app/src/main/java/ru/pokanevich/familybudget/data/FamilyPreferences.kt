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

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", BuildConfig.DEFAULT_GEMINI_API_KEY)
            ?.ifBlank { BuildConfig.DEFAULT_GEMINI_API_KEY } ?: BuildConfig.DEFAULT_GEMINI_API_KEY
        set(value) = prefs.edit().putString("gemini_api_key", value.trim()).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var githubSyncToken: String
        get() = prefs.getString("github_sync_token", BuildConfig.DEFAULT_SYNC_TOKEN)
            ?.ifBlank { BuildConfig.DEFAULT_SYNC_TOKEN } ?: BuildConfig.DEFAULT_SYNC_TOKEN
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
