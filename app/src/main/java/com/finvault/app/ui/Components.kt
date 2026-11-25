package com.finvault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.finvault.app.data.local.TransactionListItem
import com.finvault.app.data.repository.FinVaultRepository
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Visual(val icon: ImageVector, val color: Color)

fun categoryVisual(categoryId: String?): Visual = when (categoryId) {
    FinVaultRepository.FOOD_EXPENSE -> Visual(Icons.Rounded.Restaurant, FvColors.Orange)
    FinVaultRepository.TRANSPORT_EXPENSE -> Visual(Icons.Rounded.DirectionsCar, FvColors.Sky)
    FinVaultRepository.SHOPPING_EXPENSE -> Visual(Icons.Rounded.ShoppingBag, FvColors.Pink)
    FinVaultRepository.HOUSING_EXPENSE -> Visual(Icons.Rounded.Home, FvColors.Purple)
    FinVaultRepository.BILLS_EXPENSE -> Visual(Icons.Rounded.Bolt, FvColors.Amber)
    FinVaultRepository.HEALTH_EXPENSE -> Visual(Icons.Rounded.LocalHospital, FvColors.Coral)
    FinVaultRepository.ENTERTAINMENT_EXPENSE -> Visual(Icons.Rounded.Movie, FvColors.Blue)
    FinVaultRepository.SALARY_INCOME -> Visual(Icons.Rounded.Work, FvColors.Mint)
    FinVaultRepository.OTHER_INCOME -> Visual(Icons.Rounded.Savings, FvColors.Mint)
    else -> Visual(Icons.Rounded.Receipt, FvColors.Slate)
}

fun accountVisual(accountId: String?): Visual = when {
    accountId?.startsWith("bank_") == true -> Visual(Icons.Rounded.AccountBalance, FvColors.Blue)
    accountId?.startsWith("card_") == true -> Visual(Icons.Rounded.CreditCard, FvColors.Coral)
    else -> genericAccountVisual(accountId)
}

private fun genericAccountVisual(accountId: String?): Visual = when (accountId) {
    FinVaultRepository.CASH -> Visual(Icons.Rounded.Payments, FvColors.Amber)
    FinVaultRepository.BANK -> Visual(Icons.Rounded.AccountBalance, FvColors.Blue)
    FinVaultRepository.CREDIT_CARD -> Visual(Icons.Rounded.CreditCard, FvColors.Coral)
    FinVaultRepository.INVESTMENTS -> Visual(Icons.AutoMirrored.Rounded.TrendingUp, FvColors.Purple)
    else -> Visual(Icons.Rounded.AccountBalanceWallet, FvColors.Slate)
}

fun transactionVisual(item: TransactionListItem): Visual = when (item.type) {
    "TRANSFER" ->
        if (item.toAccountId == FinVaultRepository.INVESTMENTS) {
            Visual(Icons.AutoMirrored.Rounded.TrendingUp, FvColors.Invest)
        } else {
            Visual(Icons.Rounded.SwapHoriz, FvColors.Transfer)
        }
    else -> categoryVisual(item.categoryId)
}

fun typeColor(type: String): Color = when (type) {
    "INCOME", "REFUND" -> FvColors.Income
    "EXPENSE" -> FvColors.Expense
    else -> FvColors.Transfer
}

@Composable
fun FvCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            title.uppercase(),
            style = EyebrowStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) {
            if (onTrailingClick != null) {
                TextButton(onClick = onTrailingClick) { Text(trailing) }
            } else {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun IconBadge(
    visual: Visual,
    size: Dp = 42.dp,
    iconSize: Dp = 22.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(visual.color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            visual.icon,
            contentDescription = null,
            tint = visual.color,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun TransactionRow(
    item: TransactionListItem,
    onClick: () -> Unit,
    showDate: Boolean = true
) {
    val (sign, color) = when (item.type) {
        "INCOME", "REFUND" -> "+" to FvColors.Income
        "EXPENSE" -> "−" to MaterialTheme.colorScheme.onSurface
        else -> "" to FvColors.Transfer
    }
    val subtitle = buildList {
        when (item.type) {
            "TRANSFER" -> add("${item.fromName ?: "?"} → ${item.toName ?: "?"}")
            "INCOME" -> {
                item.categoryName?.let(::add)
                item.toName?.let(::add)
            }
            "REFUND" -> {
                add("Refund")
                item.categoryName?.let(::add)
                item.toName?.let(::add)
            }
            else -> {
                item.categoryName?.let(::add)
                item.fromName?.let(::add)
            }
        }
        if (showDate) add(shortDate(item.occurredAt))
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(transactionVisual(item))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.sourceObservationId != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Sms,
                        contentDescription = "From SMS",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "$sign${money(item.amountMinor)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
fun EmptyState(
    visual: Visual,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(visual, size = 64.dp, iconSize = 30.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun MonthSwitcher(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canGoNext = month < YearMonth.now()
    val label = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    val buttonColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        FilledTonalIconButton(onClick = onPrevious, colors = buttonColors) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (month == YearMonth.now()) {
                Text(
                    "This month",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        FilledTonalIconButton(onClick = onNext, enabled = canGoNext, colors = buttonColors) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month")
        }
    }
}

@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun ProgressTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(color)
        )
    }
}
