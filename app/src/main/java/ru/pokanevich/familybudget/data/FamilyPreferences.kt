package ru.pokanevich.familybudget.data

import android.content.Context
import android.content.SharedPreferences
import ru.pokanevich.familybudget.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальные источники банковских push- и SMS-уведомлений (настраиваются отдельно на каждом телефоне,
 * НЕ синхронизируются в общий семейный config_v1.json).
 */
enum class BankPushSource(
    val id: String,
    val title: String,
    val packages: Set<String>,
    val smsSenders: Set<String>,
    val sourceTypeTag: String
) {
    SBER(
        id = "SBER",
        title = "СберБанк + 900",
        packages = setOf(
            "ru.sberbankmobile",
            "ru.sberbank.sberbankir",
            "ru.sberbank.spasibo",
            "ru.sberbank.sberpay"
        ),
        smsSenders = setOf("900", "sberbank", "сбербанк"),
        sourceTypeTag = "PUSH_SBER"
    ),
    MIR_PAY(
        id = "MIR_PAY",
        title = "Mir Pay",
        packages = setOf("ru.nspk.mirpay"),
        smsSenders = emptySet(),
        sourceTypeTag = "PUSH_MIRPAY"
    ),
    TBANK(
        id = "TBANK",
        title = "Т-Банк",
        packages = setOf(
            "com.idamob.tinkoff.android",
            "com.tbank.app",
            "ru.tbank.mobile",
            "ru.tbank.android",
            "ru.tbank.app",
            "ru.tbank.pay",
            "ru.tinkoff.android",
            "com.tinkoff.android"
        ),
        smsSenders = setOf("tinkoff", "t-bank", "tbank", "тинькофф", "т-банк"),
        sourceTypeTag = "PUSH_TBANK"
    ),
    ALFA(
        id = "ALFA",
        title = "Альфа-Банк",
        packages = setOf(
            "ru.alfabank.mobile.android",
            "ru.alfabank.android",
            "ru.alfabank.app"
        ),
        smsSenders = setOf("alfa-bank", "alfabank", "альфа-банк", "альфабанк", "alfa"),
        sourceTypeTag = "PUSH_ALFA"
    ),
    VTB(
        id = "VTB",
        title = "ВТБ",
        packages = setOf(
            "ru.vtb24.mobilebanking.android",
            "ru.vtb.mobilebank",
            "ru.vtb.android",
            "ru.vtb.app"
        ),
        smsSenders = setOf("vtb", "втб", "vtb24", "втб24", "vtb-online", "vtb online", "втб онлайн"),
        sourceTypeTag = "PUSH_VTB"
    ),
    OZON(
        id = "OZON",
        title = "Ozon Банк",
        packages = setOf(
            "ru.ozon.fintech.finance"
        ),
        smsSenders = setOf("ozonbank", "ozon bank", "ozon банк", "озон банк", "ozon-bank", "ozoncard"),
        sourceTypeTag = "PUSH_OZON"
    );

    companion object {
        val DEFAULT_ENABLED: Set<BankPushSource> = setOf(SBER, MIR_PAY)
        val DEFAULT_ENABLED_IDS: Set<String> = setOf("SBER", "MIR_PAY")

        fun fromId(rawId: String): BankPushSource? {
            val normalized = rawId.trim().uppercase()
            return entries.firstOrNull { it.id == normalized }
        }

        fun fromSourceTypeTag(sourceType: String): BankPushSource? =
            entries.firstOrNull { it.sourceTypeTag == sourceType }
    }
}

class FamilyPreferences(context: Context) : PinLockStorage {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("family_budget_prefs", Context.MODE_PRIVATE)

    /** Безопасное чтение строки: при повреждённом/чужом типе значения возвращаем дефолт, а не падаем. */
    private fun safeGetString(key: String, default: String): String =
        runCatching { prefs.getString(key, default) }.getOrNull() ?: default

    var currentUserName: String
        get() = safeGetString("user_name", defaultUserName)
        set(value) = prefs.edit().putString("user_name", value).apply()

    /** Устойчивый локальный идентификатор установки устройства для дедупликации общей карты. */
    val deviceInstallationId: String
        get() {
            val existing = safeGetString(KEY_DEVICE_INSTALLATION_ID, "").trim()
            if (existing.isNotEmpty()) return existing
            val generated = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_INSTALLATION_ID, generated).apply()
            return generated
        }

    var familySpaceCode: String
        get() = if (isSpaceCodeLocked) {
            normalizeFamilyCode(defaultFamilySpaceCode)
        } else {
            safeGetString("family_space_code", defaultFamilySpaceCode)
        }
        set(value) {
            val target = if (isSpaceCodeLocked) {
                normalizeFamilyCode(defaultFamilySpaceCode)
            } else {
                value.trim().uppercase()
            }
            prefs.edit().putString("family_space_code", target).apply()
        }

    var monthlyBudgetRub: Double
        get() = runCatching {
            prefs.getFloat("monthly_budget_rub", defaultMonthlyBudgetRub.toFloat()).toDouble()
        }.getOrDefault(defaultMonthlyBudgetRub)
        set(value) = prefs.edit().putFloat("monthly_budget_rub", value.toFloat()).apply()

    var autoConfirmBankPush: Boolean
        get() = runCatching { prefs.getBoolean("auto_confirm_push", false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean("auto_confirm_push", value).apply()

    /**
     * Локальное множество включённых ID банков для перехвата пушей/SMS (только для этого телефона).
     * По умолчанию включены только SBER и MIR_PAY (Т-Банк и остальные банки выключены).
     */
    var enabledBankPushSourceIds: Set<String>
        get() {
            if (!prefs.contains(KEY_ENABLED_BANK_PUSH_SOURCES)) {
                return BankPushSource.DEFAULT_ENABLED_IDS
            }
            val raw = runCatching {
                prefs.getStringSet(KEY_ENABLED_BANK_PUSH_SOURCES, null)
            }.getOrNull() ?: return BankPushSource.DEFAULT_ENABLED_IDS
            return raw.mapNotNull { BankPushSource.fromId(it)?.id }.toSet()
        }
        set(value) {
            val sanitized = value.mapNotNull { BankPushSource.fromId(it)?.id }.toHashSet()
            prefs.edit().putStringSet(KEY_ENABLED_BANK_PUSH_SOURCES, sanitized).apply()
        }

    var enabledBankPushSources: Set<BankPushSource>
        get() = enabledBankPushSourceIds.mapNotNull { BankPushSource.fromId(it) }.toSet()
        set(value) {
            enabledBankPushSourceIds = value.map { it.id }.toSet()
        }

    /**
     * Ключ Gemini. Пустое значение в "gemini_api_key" по-прежнему означает «использовать ключ из сборки».
     * Полностью отключить ключ (включая ключ из BuildConfig) можно только явно через [clearGeminiApiKey].
     * Формат существующих ключей SharedPreferences не меняется.
     */
    var geminiApiKey: String
        get() {
            val explicitlyCleared = runCatching { prefs.getBoolean(KEY_GEMINI_CLEARED, false) }.getOrDefault(false)
            if (explicitlyCleared) return ""
            val raw = safeGetString("gemini_api_key", "")
            val decrypted = SecretVault.readAndMigrateIfPlaintext(raw) { encrypted ->
                prefs.edit().putString("gemini_api_key", encrypted).apply()
            }
            return decrypted.trim().ifBlank { BuildConfig.DEFAULT_GEMINI_API_KEY }
        }
        set(value) {
            val trimmed = value.trim()
            val stored = SecretVault.encrypt(trimmed)
            val editor = prefs.edit().putString("gemini_api_key", stored)
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
        get() {
            val raw = safeGetString("github_sync_token", "")
            val decrypted = SecretVault.readAndMigrateIfPlaintext(raw) { encrypted ->
                prefs.edit().putString("github_sync_token", encrypted).apply()
            }
            return decrypted.trim().ifBlank { BuildConfig.DEFAULT_SYNC_TOKEN }
        }
        set(value) {
            val stored = SecretVault.encrypt(value)
            prefs.edit().putString("github_sync_token", stored).apply()
        }

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
     * Флаг наличия локальных изменений семейного конфига (`monthlyBudgetRub` / категорий),
     * которые ещё не отправлены в `config_v1.json`.
     */
    var hasUnsyncedConfig: Boolean
        get() = runCatching { prefs.getBoolean(KEY_HAS_UNSYNCED_CONFIG, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_UNSYNCED_CONFIG, value).apply()

    fun loadFamilyConfig(): FamilyConfig {
        val raw = safeGetString(KEY_FAMILY_CONFIG_JSON, "")
        if (raw.isNotBlank()) {
            FamilyConfig.fromJsonString(raw)?.let { return it }
        }
        return FamilyConfig(
            schemaVersion = FamilyConfig.CURRENT_SCHEMA_VERSION,
            monthlyBudgetRub = monthlyBudgetRub,
            budgetUpdatedAtEpochMs = 0L,
            categories = emptyList()
        )
    }

    fun saveFamilyConfig(config: FamilyConfig, hasUnsynced: Boolean) {
        prefs.edit()
            .putString(KEY_FAMILY_CONFIG_JSON, config.toJsonObject().toString())
            .putBoolean(KEY_HAS_UNSYNCED_CONFIG, hasUnsynced)
            .putFloat("monthly_budget_rub", config.monthlyBudgetRub.toFloat())
            .apply()
    }

    fun hasSyncedConfigForFamily(familyCode: String): Boolean {
        val normalized = normalizeFamilyCode(familyCode)
        return normalized in syncedConfigFamilies
    }

    fun markConfigSyncedForFamily(familyCode: String) {
        val normalized = normalizeFamilyCode(familyCode)
        val current = syncedConfigFamilies
        if (normalized !in current) {
            val serialized = (current + normalized).joinToString("\n")
            prefs.edit().putString(KEY_SYNCED_CONFIG_FAMILIES, serialized).apply()
        }
    }

    private val syncedConfigFamilies: Set<String>
        get() = safeGetString(KEY_SYNCED_CONFIG_FAMILIES, "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

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

    /**
     * Локальные настройки защиты приложения PIN-кодом и биометрией.
     * Строго локальные — НЕ синхронизируются в облачный config_v1.json / budget_v3.json.
     */
    override var isAppLockEnabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY_APP_LOCK_ENABLED, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()

    override var isBiometricEnabled: Boolean
        get() = runCatching { prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    override var pinSaltHex: String
        get() = safeGetString(KEY_PIN_SALT_HEX, "")
        set(value) = prefs.edit().putString(KEY_PIN_SALT_HEX, value).apply()

    override var pinHashHex: String
        get() = safeGetString(KEY_PIN_HASH_HEX, "")
        set(value) = prefs.edit().putString(KEY_PIN_HASH_HEX, value).apply()

    override var pinAutoLockTimeoutMs: Long
        get() = runCatching {
            prefs.getLong(KEY_PIN_AUTO_LOCK_TIMEOUT_MS, PinSecurityManager.DEFAULT_AUTO_LOCK_TIMEOUT_MS)
        }.getOrDefault(PinSecurityManager.DEFAULT_AUTO_LOCK_TIMEOUT_MS).coerceAtLeast(0L)
        set(value) = prefs.edit().putLong(KEY_PIN_AUTO_LOCK_TIMEOUT_MS, value.coerceAtLeast(0L)).apply()

    /**
     * Локальный флаг приватности виджета: маскирует суммы на рабочем столе как «•••• ₽».
     */
    var hideAmountsOnWidget: Boolean
        get() = runCatching { prefs.getBoolean(KEY_HIDE_AMOUNTS_ON_WIDGET, false) }.getOrDefault(false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_AMOUNTS_ON_WIDGET, value).apply()

    override fun clearPinCredentials() {
        prefs.edit()
            .putBoolean(KEY_APP_LOCK_ENABLED, false)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .putBoolean(KEY_HIDE_AMOUNTS_ON_WIDGET, false)
            .remove(KEY_PIN_SALT_HEX)
            .remove(KEY_PIN_HASH_HEX)
            .apply()
    }

    companion object {
        const val MAX_PERSISTED_PUSH_DEDUP_KEYS = 100
        private const val KEY_GEMINI_CLEARED = "gemini_api_key_cleared"
        private const val KEY_RECENT_PUSH_DEDUP_KEYS = "recent_push_dedup_keys"
        private const val KEY_ENABLED_BANK_PUSH_SOURCES = "enabled_bank_push_sources"
        private const val KEY_FAMILY_CONFIG_JSON = "family_config_v1_json"
        private const val KEY_HAS_UNSYNCED_CONFIG = "has_unsynced_config"
        private const val KEY_SYNCED_CONFIG_FAMILIES = "synced_config_families"
        private const val KEY_APP_LOCK_ENABLED = "pin_app_lock_enabled"
        private const val KEY_BIOMETRIC_ENABLED = "pin_biometric_enabled"
        private const val KEY_PIN_SALT_HEX = "pin_salt_hex"
        private const val KEY_PIN_HASH_HEX = "pin_hash_hex"
        private const val KEY_PIN_AUTO_LOCK_TIMEOUT_MS = "pin_auto_lock_timeout_ms"
        private const val KEY_HIDE_AMOUNTS_ON_WIDGET = "hide_amounts_on_widget"
        private const val KEY_DEVICE_INSTALLATION_ID = "device_installation_id"

        val defaultFamilySpaceCode: String
            get() = BuildConfig.DEFAULT_FAMILY_SPACE_CODE.trim().ifBlank { "POKANE-FAMILY" }

        val defaultUserName: String
            get() = BuildConfig.DEFAULT_USER_NAME.trim().ifBlank { "Евгений" }

        val isSoloProfile: Boolean
            get() = BuildConfig.IS_SOLO_BUDGET_PROFILE

        val isSpaceCodeLocked: Boolean
            get() = BuildConfig.IS_SPACE_CODE_LOCKED

        val defaultMonthlyBudgetRub: Double
            get() = BuildConfig.DEFAULT_MONTHLY_BUDGET_RUB.takeIf { it.isFinite() && it > 0.0 } ?: 200000.0

        fun normalizeFamilyCode(raw: String): String =
            raw.trim().ifBlank { defaultFamilySpaceCode }.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
