package com.finvault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        FinancialTransactionEntity::class,
        JournalEntryEntity::class,
        JournalLineEntity::class,
        SmsObservationEntity::class,
        MerchantRuleEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class FinVaultDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun smsObservationDao(): SmsObservationDao

    abstract fun monthlyOverviewDao(): MonthlyOverviewDao

    abstract fun merchantRuleDao(): MerchantRuleDao

    companion object {

        @Volatile
        private var instance: FinVaultDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // Add SMS provenance to existing transactions.
                db.execSQL(
                    """
                    ALTER TABLE financial_transactions
                    ADD COLUMN sourceObservationId TEXT
                    """
                )

                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_financial_transactions_sourceObservationId
                    ON financial_transactions(sourceObservationId)
                    """
                )

                // Create the SMS review table.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sms_observations (
                        id TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        body TEXT NOT NULL,
                        receivedAt INTEGER NOT NULL,
                        suggestedAmountMinor INTEGER NOT NULL,
                        suggestedType TEXT NOT NULL,
                        suggestedTitle TEXT NOT NULL,
                        confidence INTEGER NOT NULL,
                        reviewNote TEXT NOT NULL,
                        manualOnly INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_sms_observations_status
                    ON sms_observations(status)
                    """
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_sms_observations_receivedAt
                    ON sms_observations(receivedAt)
                    """
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS merchant_rules (
                        merchantKey TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        targetId TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(merchantKey)
                    )
                    """
                )
            }
        }

        fun getInstance(context: Context): FinVaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinVaultDatabase::class.java,
                    "finvault.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}