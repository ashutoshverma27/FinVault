package com.finvault.app.domain.ledger

data class TransactionCommand(
    val title: String,
    val amountMinor: Long,
    val type: Type,
    val fromAccountId: String? = null,
    val toAccountId: String? = null,
    val categoryAccountId: String? = null,
    val note: String = "",
    val occurredAt: Long = System.currentTimeMillis()
) {
    enum class Type { EXPENSE, INCOME, TRANSFER }
}
