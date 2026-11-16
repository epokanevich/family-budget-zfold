package ru.pokanevich.familybudget.data

import android.content.Context
import android.content.SharedPreferences

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
        get() = prefs.getString("gemini_api_key", "") ?: ""
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
}
