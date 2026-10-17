package com.macrobase.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

// Every role is set, so no Material default (the baseline purple tones) shows in dialogs, sheets,
// menus or pickers.
private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Primary,
    onPrimary = AppColors.OnPrimary,
    primaryContainer = AppColors.Capsule,
    onPrimaryContainer = AppColors.ChipOnInk,
    inversePrimary = AppColors.PrimaryDark,
    secondary = AppColors.AccentStrong,
    onSecondary = AppColors.OnPrimary,
    secondaryContainer = AppColors.Capsule,
    onSecondaryContainer = AppColors.ChipOnInk,
    tertiary = AppColors.MacroFat,
    onTertiary = AppColors.OnPrimary,
    tertiaryContainer = AppColors.IconCircleFill,
    onTertiaryContainer = AppColors.IconCircleInk,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceAlt,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceTint = AppColors.Primary,
    inverseSurface = AppColors.TextPrimary,
    inverseOnSurface = AppColors.Background,
    error = AppColors.Error,
    onError = AppColors.OnPrimary,
    errorContainer = AppColors.DangerFill,
    onErrorContainer = AppColors.OnDanger,
    outline = AppColors.FieldBorder,
    outlineVariant = AppColors.Divider,
    scrim = AppColors.Scrim,
    surfaceBright = AppColors.SurfaceAlt,
    surfaceDim = AppColors.Background,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.Surface,
    surfaceContainerHighest = AppColors.SurfaceAlt,
    surfaceContainerLow = AppColors.Surface,
    surfaceContainerLowest = AppColors.Background
)

@Composable
fun MacroBaseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // MacroBase is dark only by design: one Mentor theme, no light or AMOLED mode
    CompositionLocalProvider(
        LocalAppColors provides AppColors,
        LocalAppTypography provides AppTypography,
        LocalAppSpacing provides AppSpacing,
        LocalAppShapes provides AppShapes
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = MaterialJuraTypography,
            content = content
        )
    }
}

object MacroBaseTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current

    val typography: AppTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTypography.current

    val spacing: AppSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalAppSpacing.current

    val shapes: AppShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalAppShapes.current
}
