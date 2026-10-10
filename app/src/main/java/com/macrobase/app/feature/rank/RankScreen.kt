package com.macrobase.app.feature.rank

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankTier
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankScreen(
    viewModel: RankViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val profile = uiState.rankProfile
    var showLadderSheet by remember { mutableStateOf(false) }

    if (showLadderSheet) {
        RankLadderSheet(
            currentRankTier = profile.rankTier,
            currentTierRR = profile.tierRR,
            onDismissRequest = { showLadderSheet = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
    ) {
        // 0. Header Row with Info Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MACROBASE RANK",
                style = AppTypography.Header3.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = AppColors.TextSecondary
            )
            IconButton(
                onClick = { showLadderSheet = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "View all ranks",
                    tint = AppColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // =========================================================================
        // SECTION 1: TOP SECTION (Fits in first viewport WITHOUT scrolling)
        // 1. Rank Image
        // 2. Centered Rank Name
        // 3. RR Progress (e.g. 4 / 100 RR) + Progress Bar
        // 4. Compact Lifestyle Score Breakdown
        // =========================================================================

        // 1. Rank Image (Substantially larger focal point, responsive fitting)
        RankImageHeader(
            rankTier = profile.rankTier,
            modifier = Modifier
                .fillMaxWidth()
                .height(185.dp)
        )

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        // 2. Centered Rank Name
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = profile.rankTier.displayName.uppercase(),
                style = AppTypography.Header1.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                ),
                color = AppColors.Primary
            )
            if (profile.isProvisional) {
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFFFA000))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "PROVISIONAL",
                        style = AppTypography.Caption.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = Color.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Active Strategy Badge & Pending Change
        StrategyBadge(
            fitnessGoal = profile.fitnessGoal,
            maintenanceCalories = profile.maintenanceCalories,
            pendingStrategyChange = profile.pendingStrategyChange
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 3. RR Progress Bar (0..100 RR within current tier)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${profile.tierRR} / 100 RR",
                style = AppTypography.Caption.copy(
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextSecondary
                )
            )
            Spacer(modifier = Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { (profile.tierRR / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = AppColors.Primary,
                trackColor = AppColors.SurfaceAlt
            )

            if (profile.isProvisional) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Log ${7 - profile.loggedDaysCount} more days to establish initial rating (${profile.loggedDaysCount}/7)",
                    style = AppTypography.Caption.copy(fontSize = 10.sp, color = AppColors.TextSecondary)
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        // 4. Compact Lifestyle Score Breakdown
        CompactScoreBreakdownCard(profile = profile)

        // =========================================================================
        // SECTION 2: RECENT RR MOVEMENT (Below the fold - requires scrolling)
        // =========================================================================
        Spacer(modifier = Modifier.height(AppSpacing.md))

        RecentHistoryCard(history = profile.recentHistory)

        Spacer(modifier = Modifier.height(AppSpacing.lg))
    }
}

@Composable
private fun RankImageHeader(
    rankTier: RankTier,
    modifier: Modifier = Modifier
) {
    val drawableRes = remember(rankTier.rank) {
        RankImageResolver.getRankDrawableRes(rankTier)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (drawableRes != 0) {
            Image(
                painter = painterResource(id = drawableRes),
                contentDescription = rankTier.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.75f)
            )
        } else {
            // Safe fallback placeholder
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.SurfaceAlt),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = AppColors.Primary,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

@Composable
private fun StrategyBadge(
    fitnessGoal: FitnessGoal,
    maintenanceCalories: Double,
    pendingStrategyChange: String? = null,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, borderColor) = when (fitnessGoal) {
        FitnessGoal.BULKING -> Triple(
            Color(0xFF4CAF50).copy(alpha = 0.15f),
            Color(0xFF4CAF50),
            Color(0xFF4CAF50).copy(alpha = 0.3f)
        )
        FitnessGoal.CUTTING -> Triple(
            Color(0xFF00BCD4).copy(alpha = 0.15f),
            Color(0xFF00ACC1),
            Color(0xFF00BCD4).copy(alpha = 0.3f)
        )
        FitnessGoal.MAINTAINING -> Triple(
            AppColors.SurfaceAlt,
            AppColors.TextSecondary,
            AppColors.Divider
        )
    }

    val formattedMaint = String.format(Locale.US, "%,d", maintenanceCalories.roundToInt())
    val badgeText = "${fitnessGoal.displayName.uppercase()} \u2022 $formattedMaint kcal Maintenance"

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .padding(horizontal = AppSpacing.sm, vertical = 4.dp)
        ) {
            Text(
                text = badgeText,
                style = AppTypography.Caption.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                color = textColor
            )
        }

        if (!pendingStrategyChange.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Pending: $pendingStrategyChange tomorrow",
                    style = AppTypography.Caption.copy(fontSize = 10.sp),
                    color = AppColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CompactScoreBreakdownCard(profile: RankProfile) {
    val ls = profile.lifestyleScore

    val maint = profile.maintenanceCalories.roundToInt()
    val calorieSubtitle = when (profile.fitnessGoal) {
        FitnessGoal.BULKING -> {
            val minStr = String.format(Locale.US, "%,d", maint)
            val maxStr = String.format(Locale.US, "%,d", maint + 500)
            "Target: $minStr \u2013 $maxStr kcal (Surplus)"
        }
        FitnessGoal.CUTTING -> {
            val minStr = String.format(Locale.US, "%,d", (maint - 500).coerceAtLeast(0))
            val maxStr = String.format(Locale.US, "%,d", maint)
            "Target: $minStr \u2013 $maxStr kcal (Deficit)"
        }
        FitnessGoal.MAINTAINING -> {
            val minStr = String.format(Locale.US, "%,d", (maint - 100).coerceAtLeast(0))
            val maxStr = String.format(Locale.US, "%,d", maint + 100)
            "Target: $minStr \u2013 $maxStr kcal (\u00b1100)"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Divider, RoundedCornerShape(4.dp))
            .padding(horizontal = AppSpacing.sm, vertical = 8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIFESTYLE SCORE BREAKDOWN",
                    style = AppTypography.Caption.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = AppColors.TextPrimary
                )
                Text(
                    text = "${ls.overallScore.roundToInt()} / 100",
                    style = AppTypography.Body1.copy(fontWeight = FontWeight.Bold),
                    color = AppColors.CalorieText
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 1. Consistency (35%)
            CompactComponentScoreRow(
                label = "Consistency (35%)",
                score = ls.consistencyScore.roundToInt(),
                color = Color(0xFF4CAF50)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 2. Calories (30%)
            CompactComponentScoreRow(
                label = "Calories (30%)",
                score = ls.calorieScore.roundToInt(),
                color = Color(0xFFFFA726),
                subtitle = calorieSubtitle
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 3. Macros (27%)
            CompactComponentScoreRow(
                label = "Macros (27%)",
                score = ls.macroScore.roundToInt(),
                color = Color(0xFF4DD0E1)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 4. Weight Trend (8%)
            CompactComponentScoreRow(
                label = "Weight Trend (8%)",
                score = ls.weightTrendScore.roundToInt(),
                color = Color(0xFFAB47BC)
            )
        }
    }
}

@Composable
private fun CompactComponentScoreRow(
    label: String,
    score: Int,
    color: Color,
    subtitle: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = label,
                    style = AppTypography.Caption.copy(fontSize = 11.sp),
                    color = AppColors.TextSecondary
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = AppTypography.Caption.copy(fontSize = 9.sp),
                        color = AppColors.TextSecondary.copy(alpha = 0.85f)
                    )
                }
            }
            Text(
                text = "$score",
                style = AppTypography.Caption.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                color = AppColors.TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { (score / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = AppColors.SurfaceAlt
        )
    }
}

@Composable
private fun RecentHistoryCard(history: List<RankHistoryEntry>) {
    val today = LocalDate.now()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Divider, RoundedCornerShape(4.dp))
            .padding(AppSpacing.md)
    ) {
        Column {
            Text(
                text = "RECENT RR MOVEMENT",
                style = AppTypography.Header3.copy(fontWeight = FontWeight.Bold),
                color = AppColors.TextPrimary
            )

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            if (history.isEmpty()) {
                Text(
                    text = "No recent ranking evaluations.",
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary
                )
            } else {
                history.take(14).forEach { entry ->
                    val dateLabel = when (entry.date) {
                        today -> "Today"
                        today.minusDays(1) -> "Yesterday"
                        else -> entry.date.format(DateTimeFormatter.ofPattern("MMM dd"))
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = dateLabel,
                                style = AppTypography.Body2.copy(fontWeight = FontWeight.Medium),
                                color = AppColors.TextPrimary
                            )
                            Text(
                                text = "${entry.rankTier.displayName} • ${entry.tierRR} RR",
                                style = AppTypography.Caption,
                                color = AppColors.TextSecondary
                            )
                        }

                        val deltaText = if (entry.rrDelta > 0) "+${entry.rrDelta} RR" else if (entry.rrDelta < 0) "${entry.rrDelta} RR" else "0 RR"
                        val deltaColor = when {
                            entry.rrDelta > 0 -> AppColors.CalorieText
                            entry.rrDelta < 0 -> Color(0xFFE53935)
                            else -> AppColors.TextSecondary
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when {
                                    entry.rrDelta > 0 -> Icons.Default.ArrowUpward
                                    entry.rrDelta < 0 -> Icons.Default.ArrowDownward
                                    else -> Icons.Default.Remove
                                },
                                contentDescription = null,
                                tint = deltaColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = deltaText,
                                style = AppTypography.Body2.copy(fontWeight = FontWeight.Bold),
                                color = deltaColor
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.Divider, thickness = 0.5.dp)
                }
            }
        }
    }
}

