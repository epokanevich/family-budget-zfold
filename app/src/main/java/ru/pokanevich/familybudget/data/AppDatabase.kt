package ru.pokanevich.familybudget.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Локальная БД (offline-first). ВАЖНО: destructive migration запрещена —
 * при любом изменении схемы поднимайте version и добавляйте Migration
 * (схемы экспортируются в app/schemas и коммитятся в репозиторий).
 */
@Database(
    entities = [ExpenseEntity::class, RecurringPaymentEntity::class, SavingsGoalEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun recurringPaymentDao(): RecurringPaymentDao
    abstract fun savingsGoalDao(): SavingsGoalDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE expenses ADD COLUMN splitGroupId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE expenses ADD COLUMN operationType TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recurring_payments` (" +
                        "`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`amountRub` REAL NOT NULL, " +
                        "`categoryId` TEXT NOT NULL, " +
                        "`operationType` TEXT NOT NULL, " +
                        "`dayOfMonth` INTEGER NOT NULL, " +
                        "`autoCreateInFeed` INTEGER NOT NULL, " +
                        "`lastPaidPeriodKey` TEXT NOT NULL, " +
                        "`isActive` INTEGER NOT NULL, " +
                        "`isDeleted` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `savings_goals` (" +
                        "`id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`emoji` TEXT NOT NULL, " +
                        "`targetAmountRub` REAL NOT NULL, " +
                        "`initialAmountRub` REAL NOT NULL, " +
                        "`isArchived` INTEGER NOT NULL, " +
                        "`isDeleted` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("ALTER TABLE expenses ADD COLUMN goalId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN originDeviceId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_expenses_isDeleted_isPendingConfirmation_createdAtEpochMs` " +
                        "ON `expenses` (`isDeleted`, `isPendingConfirmation`, `createdAtEpochMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_expenses_isSynced` ON `expenses` (`isSynced`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_expenses_dedupSignature_createdAtEpochMs` " +
                        "ON `expenses` (`dedupSignature`, `createdAtEpochMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_expenses_splitGroupId` ON `expenses` (`splitGroupId`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "family_budget_zfold.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
