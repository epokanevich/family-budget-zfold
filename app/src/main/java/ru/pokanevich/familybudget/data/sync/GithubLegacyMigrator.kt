package ru.pokanevich.familybudget.data.sync

import org.json.JSONArray
import org.json.JSONObject
import ru.pokanevich.familybudget.data.ExpenseEntity
import ru.pokanevich.familybudget.data.FamilyConfig
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class LegacyMigrationPayload(
    val config: FamilyConfig?,
    val expenses: List<ExpenseEntity>,
    val sourceDescription: String
)

/**
 * Строго read-only мигратор данных из исторического GitHub-бэкапа (`epokanevich/family-budget-sync-db`)
 * и локальных JSON-экспортов. Никогда не выполняет PUT/POST/DELETE к GitHub API.
 */
object GithubLegacyMigrator {

    private const val REPO_BASE_API = "https://api.github.com/repos/epokanevich/family-budget-sync-db"

    fun fetchFromGithubReadOnly(
        familyCode: String,
        token: String,
        httpGet: (url: String, token: String) -> String? = ::defaultHttpGet
    ): LegacyMigrationPayload? {
        val cleanToken = token.trim()
        val cleanCode = familyCode.trim()
        if (cleanToken.isEmpty() || cleanCode.isEmpty()) return null

        val configRaw = fetchGithubFileDecoded("$REPO_BASE_API/contents/families/$cleanCode/config_v1.json", cleanToken, httpGet)
        val budgetRaw = fetchGithubFileDecoded("$REPO_BASE_API/contents/families/$cleanCode/budget_v3.json", cleanToken, httpGet)

        val config = configRaw?.takeIf { it.isNotBlank() }?.let { FamilyConfig.fromJsonString(it) }
        val expenses = budgetRaw?.takeIf { it.isNotBlank() }?.let { parseExpensesJsonArray(it) }.orEmpty()

        if (config == null && expenses.isEmpty()) return null
        return LegacyMigrationPayload(
            config = config,
            expenses = expenses,
            sourceDescription = "github:$cleanCode"
        )
    }

    fun parseFromJsonStream(input: InputStream, sourceDescription: String = "local-json"): LegacyMigrationPayload {
        val rawText = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseFromJsonText(rawText, sourceDescription)
    }

    fun parseFromJsonText(rawText: String, sourceDescription: String = "local-json"): LegacyMigrationPayload {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return LegacyMigrationPayload(config = null, expenses = emptyList(), sourceDescription = sourceDescription)
        }
        return if (trimmed.startsWith("[")) {
            LegacyMigrationPayload(
                config = null,
                expenses = parseExpensesJsonArray(trimmed),
                sourceDescription = sourceDescription
            )
        } else {
            val root = JSONObject(trimmed)
            val configObj = root.optJSONObject("config")
            val parsedConfig = when {
                configObj != null -> FamilyConfig.fromJsonString(configObj.toString())
                root.has("monthlyBudgetRub") && root.has("categories") -> FamilyConfig.fromJsonString(trimmed)
                else -> null
            }
            val arr = root.optJSONArray("expenses") ?: root.optJSONArray("items")
            val parsedExpenses = if (arr != null) parseExpensesJsonArray(arr) else emptyList()
            LegacyMigrationPayload(
                config = parsedConfig,
                expenses = parsedExpenses,
                sourceDescription = sourceDescription
            )
        }
    }

    internal fun parseExpensesJsonArray(rawJsonArray: String): List<ExpenseEntity> {
        val arr = JSONArray(rawJsonArray)
        return parseExpensesJsonArray(arr)
    }

    private fun parseExpensesJsonArray(arr: JSONArray): List<ExpenseEntity> {
        val result = ArrayList<ExpenseEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val parsed = ExpenseEntity.fromJsonObject(obj) ?: continue
            result.add(parsed)
        }
        return result
    }

    private fun fetchGithubFileDecoded(
        contentsApiUrl: String,
        token: String,
        httpGet: (url: String, token: String) -> String?
    ): String? {
        val body = httpGet(contentsApiUrl, token) ?: return null
        val fileObj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val sha = fileObj.optString("sha", "").ifBlank { null }
        var contentB64 = fileObj.optString("content", "")
            .replace("\n", "")
            .replace("\r", "")

        if (contentB64.isBlank() && sha != null) {
            val blobBody = httpGet("$REPO_BASE_API/git/blobs/$sha", token) ?: return null
            val blobObj = runCatching { JSONObject(blobBody) }.getOrNull() ?: return null
            contentB64 = blobObj.optString("content", "")
                .replace("\n", "")
                .replace("\r", "")
        }
        if (contentB64.isBlank()) return null
        return runCatching {
            val cleanedB64 = contentB64.replace(Regex("\\s+"), "")
            String(Base64.getDecoder().decode(cleanedB64), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun defaultHttpGet(url: String, token: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FamilyBudgetZFold")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        return try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                runCatching { conn.errorStream?.use { it.readBytes() } }
                null
            } else if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                runCatching { conn.errorStream?.use { it.readBytes() } }
                throw IllegalStateException("GitHub Read-Only GET HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
