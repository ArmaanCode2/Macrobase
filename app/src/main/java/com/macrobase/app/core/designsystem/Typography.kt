package com.macrobase.app.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.macrobase.app.R

/**
 * Jura, bundled in res/font (SIL Open Font License, assets/licenses/Jura-OFL.txt). Only Light (300)
 * and Medium (500) exist, so every weight maps to one of the two files and Compose never draws a
 * synthetic bold: W100-W400 (Normal included) use Light, W500-W900 use Medium.
 */
val JuraFontFamily = FontFamily(
    Font(R.font.jura_light, FontWeight.W100),
    Font(R.font.jura_light, FontWeight.W200),
    Font(R.font.jura_light, FontWeight.W300),
    Font(R.font.jura_light, FontWeight.W400),
    Font(R.font.jura_medium, FontWeight.W500),
    Font(R.font.jura_medium, FontWeight.W600),
    Font(R.font.jura_medium, FontWeight.W700),
    Font(R.font.jura_medium, FontWeight.W800),
    Font(R.font.jura_medium, FontWeight.W900)
)

private val BaseTracking = 0.005.em
private val NumberTracking = (-0.03).em

/**
 * Centralized Design System Typography (designs/design.txt section 3). Sentence case everywhere.
 */
object AppTypography {
    /** Screen title in a header. */
    val Header1 = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = BaseTracking,
        color = AppColors.TextPrimary
    )

    /** Section heading ("Breakfast", "When"). */
    val Header2 = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = BaseTracking,
        color = AppColors.TextPrimary
    )

    /** Row title. */
    val Header3 = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = BaseTracking,
        color = AppColors.TextPrimary
    )

    val Body1 = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = BaseTracking,
        color = AppColors.TextPrimary
    )

    /** Secondary line, notes, subtitles. */
    val Body2 = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        letterSpacing = BaseTracking,
        color = AppColors.TextSecondary
    )

    /** Labels and captions. */
    val Caption = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = BaseTracking,
        color = AppColors.TextSecondary
    )

    val ValueLg = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = NumberTracking,
        color = AppColors.TextPrimary
    )

    val ValueMd = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = BaseTracking,
        color = AppColors.CalorieText
    )

    /** No colour of its own: it takes the button's content colour (dark ink on green fills). */
    val Button = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
        letterSpacing = BaseTracking,
        color = Color.Unspecified
    )

    /** "776" in "776 / 2,000 kcal". */
    val Hero = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 46.sp,
        letterSpacing = NumberTracking,
        color = AppColors.TextPrimary
    )

    /** Lifestyle score, weight. */
    val BigStat = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 42.sp,
        letterSpacing = NumberTracking,
        color = AppColors.TextPrimary
    )

    /** The unit after a hero number ("/ 2,000 kcal"). */
    val HeroUnit = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = BaseTracking,
        color = AppColors.TextSecondary
    )

    /** Chips and view-switch items. */
    val Chip = TextStyle(
        fontFamily = JuraFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        letterSpacing = BaseTracking,
        color = Color.Unspecified
    )
}

/** Material3 type scale in Jura, so dialogs, text fields and date pickers use it too. */
val MaterialJuraTypography: Typography = Typography().let { base ->
    fun TextStyle.jura() = copy(fontFamily = JuraFontFamily, letterSpacing = BaseTracking)
    base.copy(
        displayLarge = base.displayLarge.jura(),
        displayMedium = base.displayMedium.jura(),
        displaySmall = base.displaySmall.jura(),
        headlineLarge = base.headlineLarge.jura(),
        headlineMedium = base.headlineMedium.jura(),
        headlineSmall = base.headlineSmall.jura(),
        titleLarge = base.titleLarge.jura(),
        titleMedium = base.titleMedium.jura(),
        titleSmall = base.titleSmall.jura(),
        bodyLarge = base.bodyLarge.jura().copy(fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.jura().copy(fontWeight = FontWeight.Medium),
        bodySmall = base.bodySmall.jura(),
        labelLarge = base.labelLarge.jura(),
        labelMedium = base.labelMedium.jura(),
        labelSmall = base.labelSmall.jura()
    )
}

val LocalAppTypography = staticCompositionLocalOf { AppTypography }
