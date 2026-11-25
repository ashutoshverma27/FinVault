package com.finvault.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object FvColors {
    val Background = Color(0xFF0A0F14)
    val Surface = Color(0xFF111920)
    val SurfaceContainer = Color(0xFF16212A)
    val SurfaceContainerHigh = Color(0xFF1C2833)
    val SurfaceVariant = Color(0xFF223140)
    val Outline = Color(0xFF3A4A58)
    val OutlineVariant = Color(0xFF26343F)
    val OnSurface = Color(0xFFE8EEF2)
    val OnSurfaceVariant = Color(0xFF93A4B2)

    val Mint = Color(0xFF5FE0B0)
    val MintContainer = Color(0xFF0E3A2E)
    val Coral = Color(0xFFFF8A80)
    val CoralContainer = Color(0xFF45201E)
    val Blue = Color(0xFF8FB4FF)
    val BlueContainer = Color(0xFF1B2C4B)
    val Purple = Color(0xFFC7A6FF)
    val PurpleContainer = Color(0xFF30244B)
    val Amber = Color(0xFFFFC870)
    val AmberContainer = Color(0xFF3F3018)
    val Orange = Color(0xFFFFB36B)
    val Pink = Color(0xFFFF8FC8)
    val Sky = Color(0xFF7FD0FF)
    val Slate = Color(0xFFB0BEC5)

    val Income = Mint
    val Expense = Coral
    val Transfer = Blue
    val Invest = Purple
}

private val colorScheme = darkColorScheme(
    primary = FvColors.Mint,
    onPrimary = Color(0xFF003828),
    primaryContainer = FvColors.MintContainer,
    onPrimaryContainer = Color(0xFFA8F7D8),
    secondary = FvColors.Blue,
    onSecondary = Color(0xFF0B2350),
    secondaryContainer = FvColors.BlueContainer,
    onSecondaryContainer = Color(0xFFD8E3FF),
    tertiary = FvColors.Purple,
    onTertiary = Color(0xFF2A1458),
    tertiaryContainer = FvColors.PurpleContainer,
    onTertiaryContainer = Color(0xFFEBDDFF),
    error = FvColors.Coral,
    onError = Color(0xFF4A0E0A),
    errorContainer = FvColors.CoralContainer,
    onErrorContainer = Color(0xFFFFDAD5),
    background = FvColors.Background,
    onBackground = FvColors.OnSurface,
    surface = FvColors.Background,
    onSurface = FvColors.OnSurface,
    surfaceVariant = FvColors.SurfaceVariant,
    onSurfaceVariant = FvColors.OnSurfaceVariant,
    surfaceContainerLowest = FvColors.Background,
    surfaceContainerLow = FvColors.Surface,
    surfaceContainer = FvColors.SurfaceContainer,
    surfaceContainerHigh = FvColors.SurfaceContainerHigh,
    surfaceContainerHighest = FvColors.SurfaceVariant,
    outline = FvColors.Outline,
    outlineVariant = FvColors.OutlineVariant,
    inverseSurface = FvColors.OnSurface,
    inverseOnSurface = FvColors.Background,
    scrim = Color(0xCC000000)
)

private val base = Typography()

private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall
)

/** Section "eyebrow" style used for small uppercase headings. */
val EyebrowStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
    )

/** When true, monetary amounts are masked (privacy mode). */
val LocalHideAmounts = staticCompositionLocalOf { false }

@Composable
fun FinVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
