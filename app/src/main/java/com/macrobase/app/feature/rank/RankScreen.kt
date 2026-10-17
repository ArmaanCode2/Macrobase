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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankSeason
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.ratingToRankTier
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankScreen(
    viewModel: RankViewModel,
    modifier: Modifier = Modifier
) {
    // Collected only while visible, so coming back after midnight reads the new day (BUG-032)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = uiState.rankProfile
    var showLadderSheet by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Where the history card starts in the scrolled column, so a new page opens at its top
    var historyTop by remember { mutableIntStateOf(0) }

    if (showLadderSheet) {
        RankLadderSheet(
            currentRankTier = profile.rankTier,
            currentTierRR = profile.tierRR,
            nextRankReset = profile.season?.nextReset,
            onDismissRequest = { showLadderSheet = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .verticalScroll(scrollState)
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

        val season = profile.season
        if (profile.showNewSeasonNotice && season != null) {
            RankNotice(
                title = "New season",
                text = newSeasonNoticeText(season, profile.lastSeasonBest),
                onDismiss = viewModel::dismissSeasonNotice
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs))
        }

        if (profile.showRulesNotice) {
            RulesChangedNotice(onDismiss = viewModel::dismissRulesNotice)
            Spacer(modifier = Modifier.height(AppSpacing.xs))
        }

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
                val needed = RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD
                // Days count once they end (BUG-032), so a day logged today is already on its way
                val todayLogged = profile.recentHistory.firstOrNull()?.let { it.isInProgress && it.isLogged } == true
                val remaining = (needed - profile.loggedDaysCount - (if (todayLogged) 1 else 0)).coerceAtLeast(0)
                Text(
                    text = if (remaining == 0) {
                        "Your rating starts when today ends (${profile.loggedDaysCount}/$needed days)"
                    } else {
                        "Log $remaining more ${if (remaining == 1) "day" else "days"} to establish initial rating (${profile.loggedDaysCount}/$needed)"
                    },
                    style = AppTypography.Caption.copy(fontSize = 10.sp, color = AppColors.TextSecondary)
                )
            }

            val gate = profile.cleanWeekGate
            if (gate != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = cleanWeekHint(gate),
                    style = AppTypography.Caption.copy(fontSize = 10.sp, color = AppColors.TextSecondary)
                )
            }

            val best = profile.bestThisSeason
            if (best != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = bestRankLine(best),
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

        RecentHistoryCard(
            history = profile.recentHistory,
            calorieZone = profile.calorieZone,
            rankCountsFrom = profile.rankCountsFrom,
            onPageChanged = {
                // Only back up: a card top already on screen stays where it is
                if (scrollState.value > historyTop) scope.launch { scrollState.animateScrollTo(historyTop) }
            },
            modifier = Modifier.onGloballyPositioned { historyTop = it.positionInParent().y.roundToInt() }
        )

        Spacer(modifier = Modifier.height(AppSpacing.lg))
    }
}

/** Why the rating stops below [gate], the first rating of a rank that needs a clean week. */
internal fun cleanWeekHint(gate: Int): String =
    "To reach ${ratingToRankTier(gate).rank.displayName}: a clean week, 7 days in a row in your calorie zone " +
        "(one day off or missed is allowed)"

/** "Best this season: Gym Cat II \u2022 Sep 3". */
internal fun bestRankLine(best: BestRank): String =
    "Best this season: ${best.rankTier.displayName} \u2022 ${best.date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))}"

/** What the "New season" notice says about the reset that started [season]. */
internal fun newSeasonNoticeText(season: RankSeason, lastSeasonBest: BestRank?): String {
    val resetOn = season.start.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    val best = lastSeasonBest?.let { " Last season's best: ${it.rankTier.displayName}." } ?: ""
    return "A new season started on $resetOn, so your rank starts again from Fat Bear.$best"
}

/** A notice at the top of the screen that stays until the user taps "Got it". */
@Composable
private fun RankNotice(title: String, text: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.Primary.copy(alpha = 0.12f))
            .border(1.dp, AppColors.Primary.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = AppSpacing.sm, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = title,
                style = AppTypography.Body2.copy(fontWeight = FontWeight.Bold),
                color = AppColors.TextPrimary
            )
            Text(
                text = text,
                style = AppTypography.Caption,
                color = AppColors.TextSecondary
            )
            TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = "Got it",
                    style = AppTypography.Caption.copy(fontWeight = FontWeight.Bold),
                    color = AppColors.Primary
                )
            }
        }
    }
}

/**
 * Shown once after the rank rules change. Every rank is worked out from the diary with the
 * current rules, so the text is true both for a new user and for one whose rank changed.
 */
@Composable
private fun RulesChangedNotice(onDismiss: () -> Unit) {
    RankNotice(
        title = "New rank rules",
        text = "Your rank is worked out with these rules. It counts only days with a saved goal, and starts again " +
            "from Fat Bear every year on the date you first logged food. Days earn RR " +
            "for eating in your calorie zone and for good food: protein, fiber, and less saturated fat, " +
            "sodium and sugar. Eating near maintenance while cutting or bulking loses RR. Your logged " +
            "food is never changed.",
        onDismiss = onDismiss
    )
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
    val calorieSubtitle = calorieZoneSubtitle(profile.fitnessGoal, profile.calorieZone)

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
                    // The 30 days that have ended; today shows in the history below (BUG-032)
                    text = "LIFESTYLE SCORE (LAST 30 DAYS)",
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

            Text(
                text = "Day score = calories in zone \u00d7 food score",
                style = AppTypography.Caption.copy(fontSize = 9.sp),
                color = AppColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 1. Calorie zone: how much of the food score the days kept
            CompactComponentScoreRow(
                label = "Calories in zone",
                score = ls.calorieScore.roundToInt(),
                color = Color(0xFFFFA726),
                subtitle = calorieSubtitle
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 2-4. The food score: protein 40%, food quality 45%, carb & fat balance 15%
            CompactComponentScoreRow(
                label = "Protein (40% of food score)",
                score = ls.proteinScore.roundToInt(),
                color = Color(0xFF4DD0E1)
            )

            Spacer(modifier = Modifier.height(4.dp))

            CompactComponentScoreRow(
                label = "Food quality (45% of food score)",
                score = ls.qualityScore.roundToInt(),
                color = Color(0xFF4CAF50),
                subtitle = "Fiber, saturated fat, sodium, sugar"
            )

            Spacer(modifier = Modifier.height(4.dp))

            CompactComponentScoreRow(
                label = "Carb & fat balance (15% of food score)",
                score = ls.balanceScore.roundToInt(),
                color = Color(0xFFAB47BC)
            )

            Spacer(modifier = Modifier.height(4.dp))

            CompactComponentScoreRow(
                label = "Days logged",
                score = ls.consistencyScore.roundToInt(),
                color = Color(0xFF90A4AE),
                subtitle = if (profile.isProvisional) {
                    "${ls.loggedDays} of ${ls.eligibleDays} days"
                } else {
                    "${ls.loggedDays} of ${ls.eligibleDays} days \u2022 a missed day is \u221220 RR"
                }
            )
        }
    }
}

/** The full-credit calorie zone and where the credit runs out, e.g. "Zone: 1,500 – 1,800 kcal". */
internal fun calorieZoneSubtitle(strategy: FitnessGoal, zone: ClosedFloatingPointRange<Double>): String {
    val low = String.format(Locale.US, "%,d", zone.start.roundToInt())
    val high = String.format(Locale.US, "%,d", zone.endInclusive.roundToInt())
    val rule = when (strategy) {
        FitnessGoal.CUTTING -> "near or above maintenance earns nothing"
        FitnessGoal.BULKING -> "near or below maintenance earns nothing"
        FitnessGoal.MAINTAINING -> "10% off maintenance earns nothing"
    }
    return "Zone: $low \u2013 $high kcal \u2022 $rule"
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

/** Days per history page. */
private const val HISTORY_PAGE_DAYS = 30

/** "Oct 05", as each history row names a day; English like the rest of the screen. */
private val historyDateFormat = DateTimeFormatter.ofPattern("MMM dd", Locale.US)

/**
 * Why a day lost RR, in a few words, or null when it did not. A day at the bottom of the ladder
 * that would have lost RR gets its reason too, though the rank could not fall further. Today gets
 * one only when its preview is a loss and it is not [stillEating], below the calorie zone.
 */
internal fun historyReasonLine(entry: RankHistoryEntry, stillEating: Boolean = false): String? {
    if (entry.heldByCleanWeek) return "Next rank needs a clean week"
    if (entry.isInProgress && (!entry.isLogged || stillEating || entry.rrDelta >= 0)) return null
    val dayRR = if (entry.isLogged) RankScoringConfig.rrForDay(entry.overallScore, entry.ratingBefore) else RankScoringConfig.MISSED_DAY_RR
    return when {
        dayRR >= 0 -> null
        !entry.isLogged -> "Missed day"
        entry.calorieScore == 0.0 -> "Calories outside your zone"
        else -> {
            // The part that cost the day the most points
            val credit = entry.calorieScore / 100.0
            val food = RankScoringConfig.foodScore(entry.proteinScore, entry.qualityScore, entry.balanceScore)
            listOf(
                "Calories off target" to (1.0 - credit) * food,
                "Protein off target" to RankScoringConfig.PROTEIN_SHARE * (100.0 - entry.proteinScore) * credit,
                "Food quality low" to RankScoringConfig.QUALITY_SHARE * (100.0 - entry.qualityScore) * credit,
                "Carbs and fat off target" to RankScoringConfig.BALANCE_SHARE * (100.0 - entry.balanceScore) * credit
            ).maxBy { it.second }.first
        }
    }
}

/** "Sep 15 \u2013 Oct 14" for a page of [entries], the newest first. */
internal fun historyPageRange(entries: List<RankHistoryEntry>): String {
    val newest = entries.first().date.format(historyDateFormat)
    val oldest = entries.last().date.format(historyDateFormat)
    return if (entries.size == 1) newest else "$oldest \u2013 $newest"
}

@Composable
private fun RecentHistoryCard(
    history: List<RankHistoryEntry>,
    calorieZone: ClosedFloatingPointRange<Double>,
    rankCountsFrom: LocalDate?,
    onPageChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    // The first page holds today and the 29 days before it, each later page the 30 days before
    val pages = remember(history) { history.chunked(HISTORY_PAGE_DAYS) }
    // Back on the first page whenever the screen opens again
    var page by remember { mutableIntStateOf(0) }
    val shownPage = page.coerceIn(0, maxOf(0, pages.lastIndex))

    Box(
        modifier = modifier
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
                pages[shownPage].forEach { entry ->
                    val dateLabel = when {
                        // Not in the rating until the day ends (BUG-032); the RR side says so
                        entry.isInProgress || entry.date == today -> "Today"
                        entry.date == today.minusDays(1) -> "Yesterday"
                        else -> entry.date.format(historyDateFormat)
                    }
                    // Below the zone, today has simply not been eaten yet: no -15 preview each morning
                    val stillFillingUp = entry.isInProgress && entry.calorieScore == 0.0 && entry.calories < calorieZone.start
                    val reason = historyReasonLine(entry, stillEating = entry.isInProgress && entry.calories < calorieZone.start)

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
                                color = if (entry.isInProgress) AppColors.TextSecondary else AppColors.TextPrimary
                            )
                            Text(
                                text = "${entry.rankTier.displayName} \u2022 ${entry.tierRR} RR",
                                style = AppTypography.Caption,
                                color = AppColors.TextSecondary
                            )
                            if (reason != null) {
                                Text(
                                    text = reason,
                                    style = AppTypography.Caption.copy(fontSize = 10.sp),
                                    color = AppColors.TextSecondary.copy(alpha = 0.85f)
                                )
                            }
                        }

                        val signedDelta = if (entry.rrDelta > 0) "+${entry.rrDelta} RR" else if (entry.rrDelta < 0) "${entry.rrDelta} RR" else "0 RR"
                        val deltaText = when {
                            !entry.isInProgress -> signedDelta
                            !entry.isLogged -> "Not logged yet"
                            stillFillingUp -> "In progress"
                            else -> "$signedDelta preview"
                        }
                        val deltaColor = when {
                            entry.isInProgress -> AppColors.TextSecondary
                            entry.rrDelta > 0 -> AppColors.CalorieText
                            entry.rrDelta < 0 -> Color(0xFFE53935)
                            else -> AppColors.TextSecondary
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when {
                                    stillFillingUp -> Icons.Default.Remove
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The list reads from the newest day down, so < goes to newer days and > to older ones
                    val hasNewer = shownPage > 0
                    val hasOlder = shownPage < pages.lastIndex
                    IconButton(
                        onClick = {
                            page = shownPage - 1
                            onPageChanged()
                        },
                        enabled = hasNewer
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Newer days",
                            tint = if (hasNewer) AppColors.Primary else AppColors.TextSecondary.copy(alpha = 0.4f)
                        )
                    }
                    Text(
                        text = historyPageRange(pages[shownPage]),
                        style = AppTypography.Caption.copy(fontWeight = FontWeight.Bold),
                        color = AppColors.TextSecondary
                    )
                    IconButton(
                        onClick = {
                            page = shownPage + 1
                            onPageChanged()
                        },
                        enabled = hasOlder
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Older days",
                            tint = if (hasOlder) AppColors.Primary else AppColors.TextSecondary.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Under the oldest page, or alone when every logged day came before it
            if (rankCountsFrom != null && shownPage >= pages.lastIndex) {
                val countsFrom = rankCountsFrom.format(historyDateFormat) +
                    if (rankCountsFrom.year == today.year) "" else ", ${rankCountsFrom.year}"
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Your rank counts from $countsFrom, the first day with a saved goal.",
                    style = AppTypography.Caption.copy(fontSize = 10.sp),
                    color = AppColors.TextSecondary
                )
            }
        }
    }
}
