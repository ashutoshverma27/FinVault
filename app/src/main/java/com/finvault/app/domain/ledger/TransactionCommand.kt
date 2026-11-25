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
    /**
     * REFUND is money coming back for an earlier purchase. It lands in an
     * asset or card account and reduces spending in its expense category,
     * so it is neither income nor a transfer.
     */
    enum class Type { EXPENSE, INCOME, TRANSFER, REFUND }
}
