package com.finvault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        FinancialTransactionEntity::class,
        JournalEntryEntity::class,
        JournalLineEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class FinVaultDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile
        private var instance: FinVaultDatabase? = null

        fun getInstance(context: Context): FinVaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinVaultDatabase::class.java,
                    "finvault.db"
                ).build().also { instance = it }
            }
    }
}
