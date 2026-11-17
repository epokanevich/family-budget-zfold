package ru.pokanevich.familybudget.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Локальная БД (offline-first). ВАЖНО: destructive migration запрещена —
 * при любом изменении схемы поднимайте version и добавляйте Migration
 * (схемы экспортируются в app/schemas и коммитятся в репозиторий).
 */
@Database(entities = [ExpenseEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "family_budget_zfold.db"
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
