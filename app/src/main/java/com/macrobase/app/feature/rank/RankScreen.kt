package com.macrobase.app.feature.rank

import com.macrobase.app.core.designsystem.components.rememberIsScrolled
import com.macrobase.app.core.designsystem.components.MbDelayedProgress
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbHeader
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.navigation.LocalDockInset
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankSeason
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.model.rank.ratingToRankTier
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val scrolled = scrollState.rememberIsScrolled()
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

    Column(modifier = modifier.fillMaxSize()) {
        // Pinned header; the (i) opens the full rank ladder
        MbHeader(title = "Rank", scrolled = scrolled, applyStatusBarInset = false) {
            MbIconButton(
                icon = Ph.Info.light,
                contentDescription = "View all ranks",
                // The ladder marks your rank, so it opens once the rank is known
                onClick = { if (!uiState.isLoading) showLadderSheet = true },
                size = Dimensions.HeaderButton,
                outlined = true
            )
        }

        // The first visit works out the rank from the whole diary; until then nothing is shown
        // rather than a made-up starting rank
        if (uiState.isLoading) {
            MbDelayedProgress(modifier = Modifier.fillMaxSize())
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = AppSpacing.screenMargin)
        ) {
            val season = profile.season
            if (profile.showNewSeasonNotice && season != null) {
                RankNotice(
                    title = "New season",
                    text = newSeasonNoticeText(season, profile.lastSeasonBest),
                    onDismiss = viewModel::dismissSeasonNotice
                )
                Spacer(modifier = Modifier.height(AppSpacing.md))
            }

            if (profile.showRulesNotice) {
                RulesChangedNotice(onDismiss = viewModel::dismissRulesNotice)
                Spacer(modifier = Modifier.height(AppSpacing.md))
            }

            // The rank: its image, name, RR in the tier and the way to the next tier
            RankHero(profile)
            RankHints(profile)

            LifestyleScoreSection(profile)

            RecentHistoryCard(
                history = profile.recentHistory,
                calorieZone = profile.calorieZone,
                rankCountsFrom = profile.rankCountsFrom,
                onPageChanged = {
                    // Only back up: a card top already on screen stays where it is
                    if (scrollState.value > historyTop) scope.launch { scrollState.animateScrollTo(historyTop) }
                },
                modifier = Modifier
                    .padding(top = AppSpacing.sectionGap)
                    .onGloballyPositioned { historyTop = it.positionInParent().y.roundToInt() }
            )

            // Clears the bottom dock
            Spacer(modifier = Modifier.height(AppSpacing.lg + LocalDockInset.current))
        }
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

/** The tier after [current] on the ladder (Fat Bear I to GigaChad III), or null at the top. */
internal fun nextRankTier(current: RankTier): RankTier? {
    val ladder = Rank.entries.flatMap { rank -> Tier.entries.map { tier -> RankTier(rank, tier) } }
    val index = ladder.indexOf(current)
    return if (index < 0) null else ladder.getOrNull(index + 1)
}

/** A notice at the top of the screen that stays until the user taps "Got it". */
@Composable
private fun RankNotice(title: String, text: String, onDismiss: () -> Unit) {
    MbPanel(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = AppTypography.Header3, color = AppColors.TextPrimary)
        Text(
            text = text,
            style = AppTypography.Body2.copy(fontSize = 13.sp),
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Got it",
            style = AppTypography.Body1.copy(fontSize = 14.sp),
            color = AppColors.Primary,
            modifier = Modifier
                .padding(top = 6.dp)
                .mbClickable(onClick = onDismiss)
                .padding(vertical = 8.dp)
        )
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

/** The rank's picture in a rounded, outlined frame. */
@Composable
private fun RankImageHeader(
    rankTier: RankTier,
    modifier: Modifier = Modifier
) {
    val drawableRes = remember(rankTier.rank) {
        RankImageResolver.getRankDrawableRes(rankTier)
    }
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(AppColors.IconCircleFill)
            .border(1.dp, AppColors.Edge, shape),
        contentAlignment = Alignment.Center
    ) {
        if (drawableRes != 0) {
            RankPicture(
                id = drawableRes,
                contentDescription = rankTier.displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            )
        } else {
            // Safe fallback placeholder
            MbIcon(icon = Ph.Trophy.light, contentDescription = null, size = 56.dp, tint = AppColors.Primary)
        }
    }
}

/** The image beside the rank name, "<rr> / 100 RR", its bar and how far the next tier is. */
@Composable
private fun RankHero(profile: RankProfile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RankImageHeader(
            rankTier = profile.rankTier,
            modifier = Modifier
                .width(120.dp)
                .height(180.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.rankTier.displayName,
                style = AppTypography.Header1.copy(fontSize = 24.sp, lineHeight = 29.sp),
                color = AppColors.AccentStrong
            )
            if (profile.isProvisional) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(CircleShape)
                        .background(AppColors.MaxRankBadge)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "PROVISIONAL",
                        style = AppTypography.Caption.copy(fontSize = 10.5.sp),
                        color = AppColors.MaxRankInk
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 14.dp)) {
                Text(
                    text = "${profile.tierRR}",
                    style = AppTypography.Header1.copy(fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em),
                    color = AppColors.TextPrimary,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = " / 100 RR",
                    style = AppTypography.Body1.copy(fontSize = 14.sp),
                    color = AppColors.TextSecondary,
                    modifier = Modifier.alignByBaseline()
                )
            }
            ProgressLine(
                fraction = profile.tierRR / 100f,
                height = 8.dp,
                modifier = Modifier.padding(top = 8.dp)
            )
            nextRankTier(profile.rankTier)?.let { next ->
                Text(
                    text = "${(100 - profile.tierRR).coerceAtLeast(0)} RR to ${next.displayName}",
                    style = AppTypography.Body2.copy(fontSize = 12.5.sp),
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/** The lines under the rank: what a provisional rank still needs, the clean-week rule, the season best. */
@Composable
private fun RankHints(profile: RankProfile) {
    val lines = buildList {
        if (profile.isProvisional) {
            val needed = RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD
            // Days count once they end (BUG-032), so a day logged today is already on its way
            val todayLogged = profile.recentHistory.firstOrNull()?.let { it.isInProgress && it.isLogged } == true
            val remaining = (needed - profile.loggedDaysCount - (if (todayLogged) 1 else 0)).coerceAtLeast(0)
            add(
                if (remaining == 0) {
                    "Your rating starts when today ends (${profile.loggedDaysCount}/$needed days)"
                } else {
                    "Log $remaining more ${if (remaining == 1) "day" else "days"} to establish initial rating (${profile.loggedDaysCount}/$needed)"
                }
            )
        }
        profile.cleanWeekGate?.let { add(cleanWeekHint(it)) }
        profile.bestThisSeason?.let { add(bestRankLine(it)) }
    }
    if (lines.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { line ->
            Text(text = line, style = AppTypography.Body2.copy(fontSize = 13.sp), color = AppColors.TextSecondary)
        }
    }
}

/** A thin bar: the green fill on the faint track. */
@Composable
private fun ProgressLine(fraction: Float, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(AppColors.BarTrack)
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(AppColors.Primary)
            )
        }
    }
}

/** "Lifestyle score, last 30 days": the score, how a day scores, and its five parts. */
@Composable
private fun LifestyleScoreSection(profile: RankProfile) {
    val ls = profile.lifestyleScore
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.sectionGap)
    ) {
        Text(
            // The 30 days that have ended; today shows in the history below (BUG-032)
            text = "Lifestyle score, last 30 days",
            style = AppTypography.Header2,
            color = AppColors.TextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        Row(modifier = Modifier.padding(top = 10.dp)) {
            Text(
                text = "${ls.overallScore.roundToInt()}",
                style = AppTypography.BigStat,
                color = AppColors.TextPrimary,
                modifier = Modifier.alignByBaseline()
            )
            Text(
                text = " / 100",
                style = AppTypography.HeroUnit,
                modifier = Modifier.alignByBaseline()
            )
        }
        Text(
            text = "Day score = calories in zone \u00d7 food score.",
            style = AppTypography.Body2.copy(fontSize = 13.sp),
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
        Column(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ScoreRow("Calories in zone", ls.calorieScore.roundToInt())
            ScoreRow("Protein", ls.proteinScore.roundToInt())
            ScoreRow("Food quality", ls.qualityScore.roundToInt())
            ScoreRow("Carb & fat balance", ls.balanceScore.roundToInt())
            ScoreRow("Days logged", ls.consistencyScore.roundToInt())
        }
    }
}

/** One part of the score on two lines: the name and "<n> / 100", then its bar. */
@Composable
private fun ScoreRow(name: String, score: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$name: $score of 100" }
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = name, style = AppTypography.Body1.copy(fontSize = 14.sp), color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(text = "$score / 100", style = AppTypography.Body1.copy(fontSize = 14.sp), color = AppColors.TextPrimary)
        }
        ProgressLine(fraction = score / 100f, height = 6.dp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The full-credit calorie zone and where the credit runs out, e.g. "Zone: 1,500 \u2013 1,800 kcal". */
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

/** Days in a page of "Recent RR movement"; older pages are drawn when the user pages back. */
private const val HISTORY_PAGE_DAYS = 15

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

/** The history split into pages of [HISTORY_PAGE_DAYS] days, the newest page first. */
internal fun historyPages(history: List<RankHistoryEntry>): List<List<RankHistoryEntry>> = history.chunked(HISTORY_PAGE_DAYS)

/** "Oct 1 \u2013 Oct 15" for a page of [entries], the newest first. */
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
    // The first page holds today and the 14 days before it, each later page the 15 days before
    val pages = remember(history) { historyPages(history) }
    // Back on the first page whenever the screen opens again
    var page by remember { mutableIntStateOf(0) }
    val shownPage = page.coerceIn(0, maxOf(0, pages.lastIndex))

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Recent RR movement",
            style = AppTypography.Header2,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .semantics { heading() }
        )

        if (history.isEmpty()) {
            Text(
                text = "No recent ranking evaluations.",
                style = AppTypography.Body2,
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
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dateLabel,
                            style = AppTypography.Body1,
                            color = if (entry.isInProgress) AppColors.TextSecondary else AppColors.TextPrimary
                        )
                        Text(
                            text = "${entry.rankTier.displayName} \u2022 ${entry.tierRR} RR",
                            style = AppTypography.Body2.copy(fontSize = 12.5.sp),
                            color = AppColors.TextSecondary
                        )
                        if (reason != null) {
                            Text(
                                text = reason,
                                style = AppTypography.Caption,
                                color = AppColors.TextMuted
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
                        entry.rrDelta > 0 -> AppColors.Success
                        entry.rrDelta < 0 -> AppColors.Error
                        else -> AppColors.TextSecondary
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MbIcon(
                            icon = when {
                                stillFillingUp -> Ph.Minus.light
                                entry.rrDelta > 0 -> Ph.CaretUp.regular
                                entry.rrDelta < 0 -> Ph.CaretDown.regular
                                else -> Ph.Minus.light
                            },
                            contentDescription = null,
                            size = 14.dp,
                            tint = deltaColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = deltaText,
                            style = AppTypography.Body1.copy(fontSize = 14.sp),
                            color = deltaColor
                        )
                    }
                }
                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The list reads from the newest day down, so < goes to newer days and > to older ones
                val hasNewer = shownPage > 0
                val hasOlder = shownPage < pages.lastIndex
                MbIconButton(
                    icon = Ph.CaretLeft.light,
                    contentDescription = "Newer days",
                    onClick = {
                        page = shownPage - 1
                        onPageChanged()
                    },
                    enabled = hasNewer,
                    tint = AppColors.TextPrimary
                )
                Text(
                    text = historyPageRange(pages[shownPage]),
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary
                )
                MbIconButton(
                    icon = Ph.CaretRight.light,
                    contentDescription = "Older days",
                    onClick = {
                        page = shownPage + 1
                        onPageChanged()
                    },
                    enabled = hasOlder,
                    tint = AppColors.TextPrimary
                )
            }
        }

        // Under the oldest page, or alone when every logged day came before it
        if (rankCountsFrom != null && shownPage >= pages.lastIndex) {
            val countsFrom = rankCountsFrom.format(historyDateFormat) +
                if (rankCountsFrom.year == today.year) "" else ", ${rankCountsFrom.year}"
            Text(
                text = "Your rank counts from $countsFrom, the first day with a saved goal.",
                style = AppTypography.Body2.copy(fontSize = 12.5.sp),
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
