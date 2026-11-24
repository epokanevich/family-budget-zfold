package ru.pokanevich.familybudget

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pokanevich.familybudget.data.AppDatabase
import java.io.File
import java.lang.reflect.Proxy

class AppDatabaseMigrationTest {

    private fun recordingSupportDb(executedSql: MutableList<String>): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args?.firstOrNull() as? String
                    if (sql != null) executedSql.add(sql)
                    null
                }
                "getVersion" -> 4
                "isOpen", "isDatabaseIntegrityOk", "needUpgrade" -> true
                "getMaximumSize", "getPageSize", "insert" -> 0L
                "delete", "update" -> 0
                "isReadOnly", "isWriteAheadLoggingEnabled", "isDbLockedByCurrentThread",
                "enableWriteAheadLogging", "inTransaction", "yieldIfContendedSafely" -> false
                "setMaximumSize" -> (args?.firstOrNull() as? Long) ?: 0L
                else -> null
            }
        } as SupportSQLiteDatabase

    @Test
    fun `migration 4 to 5 matches exported Room v5 schema columns and indices`() {
        val executedSql = mutableListOf<String>()
        val db = recordingSupportDb(executedSql)
        AppDatabase.MIGRATION_4_5.migrate(db)

        assertTrue(
            "MIGRATION_4_5 must add originDeviceId column with NOT NULL DEFAULT ''",
            executedSql.any {
                it.contains("ALTER TABLE expenses ADD COLUMN originDeviceId TEXT NOT NULL DEFAULT ''")
            }
        )

        val schemaFile = listOf(
            File("app/schemas/ru.pokanevich.familybudget.data.AppDatabase/5.json"),
            File("schemas/ru.pokanevich.familybudget.data.AppDatabase/5.json")
        ).firstOrNull { it.isFile }
        requireNotNull(schemaFile) { "Exported Room schema 5.json not found" }

        val root = JSONObject(schemaFile.readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals(5, root.getInt("version"))

        val entities = root.getJSONArray("entities")
        val expensesTable = (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == "expenses" }

        val fields = expensesTable.getJSONArray("fields")
        val fieldNames = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        assertTrue("originDeviceId must exist in 5.json", "originDeviceId" in fieldNames)

        val indices = expensesTable.getJSONArray("indices")
        assertEquals(4, indices.length())
        for (i in 0 until indices.length()) {
            val idxName = indices.getJSONObject(i).getString("name")
            assertTrue(
                "MIGRATION_4_5 must create index $idxName",
                executedSql.any { sql -> sql.contains(idxName) && sql.startsWith("CREATE INDEX") }
            )
        }
    }
}
