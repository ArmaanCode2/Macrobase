package com.macrobase.app.feature.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.PastSeason
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RatingPoint
import com.macrobase.app.domain.model.rank.ratingToRankTier
import com.macrobase.app.domain.model.windowStart
import com.macrobase.app.feature.rank.bestRankLine
import com.macrobase.app.feature.weight.WeightGraphHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** The spans the Rank History tab can show. */
enum class RankHistoryInterval(val label: String) {
    DAYS_30("30 Days"),
    DAYS_90("90 Days"),
    SEASON("This Season"),
    ALL("All")
}

/** The Rank History tab: the rating after each day that ended, and each season's best rank. */
data class RankHistoryUiState(
    val interval: RankHistoryInterval = RankHistoryInterval.DAYS_30,
    val points: List<RatingPoint> = emptyList(),
    val bestThisSeason: BestRank? = null,
    val pastSeasons: List<PastSeason> = emptyList(),
    /** The day each season after the first started, where the graph draws a dashed line. */
    val resetDates: List<LocalDate> = emptyList(),
    /** False until a first rank was established in any season. */
    val hasRank: Boolean = false,
    /** This season has fewer than 7 logged days, so no rank of its own yet. */
    val seasonIsProvisional: Boolean = false,
    /** Dates from another year than this one name their year. */
    val currentYear: Int = LocalDate.now().year,
    val isLoading: Boolean = true
)

/**
 * [profile]'s ratings within [interval]. The day ranges count back from the last day that ended,
 * as the other averages do, since today is not in the rating until it ends.
 */
internal fun rankHistoryPoints(profile: RankProfile, interval: RankHistoryInterval, today: LocalDate): List<RatingPoint> {
    val lastEndedDay = today.minusDays(1)
    val from = when (interval) {
        RankHistoryInterval.DAYS_30 -> windowStart(lastEndedDay, 30)
        RankHistoryInterval.DAYS_90 -> windowStart(lastEndedDay, 90)
        RankHistoryInterval.SEASON -> profile.season?.start ?: LocalDate.MIN
        RankHistoryInterval.ALL -> LocalDate.MIN
    }
    return profile.ratingHistory.filter { !it.date.isBefore(from) }
}

internal fun rankHistoryState(profile: RankProfile, interval: RankHistoryInterval, today: LocalDate) = RankHistoryUiState(
    interval = interval,
    points = rankHistoryPoints(profile, interval, today),
    bestThisSeason = profile.bestThisSeason,
    pastSeasons = profile.pastSeasons,
    resetDates = (profile.pastSeasons.map { it.season } + listOfNotNull(profile.season))
        .filter { it.number > 1 }
        .map { it.start },
    hasRank = profile.ratingHistory.isNotEmpty(),
    seasonIsProvisional = profile.isProvisional,
    currentYear = today.year,
    isLoading = false
)

/**
 * The ratings the graph's height covers: from the start of the lowest rank in [points] to the
 * start of the rank above the highest, so a low rank is not a flat line along the bottom.
 */
internal fun rankAxisRange(points: List<RatingPoint>): IntRange {
    val perRank = RankScoringConfig.RR_PER_RANK
    val low = (points.minOfOrNull { it.rating } ?: 0) / perRank * perRank
    val high = ((points.maxOfOrNull { it.rating } ?: 0) / perRank + 1) * perRank
    return low..high.coerceAtMost(perRank * RankScoringConfig.TOTAL_RANKS)
}

/** "Oct 12 \u2022 Fat Bear I \u2022 83 RR", as the rank screen names a day; another year's day names its year. */
internal fun rankPointLabel(point: RatingPoint, currentYear: Int): String =
    "${graphDate(point.date, currentYear)} \u2022 " +
        "${ratingToRankTier(point.rating).displayName} \u2022 ${point.rating % RankScoringConfig.RR_PER_TIER} RR"

/** "Oct 12", or "Oct 12, 2026" for a day in another year than [currentYear]. */
internal fun graphDate(date: LocalDate, currentYear: Int): String =
    date.format(DateTimeFormatter.ofPattern(if (date.year == currentYear) "MMM d" else "MMM d, yyyy", Locale.US))

/** "Aug 29, 2026 \u2013 Aug 28, 2027 \u2022 best Gym Cat II". */
internal fun pastSeasonLine(past: PastSeason): String {
    val format = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    val span = "${past.season.start.format(format)} \u2013 ${past.season.nextReset.minusDays(1).format(format)}"
    val best = past.best
    return if (best == null) "$span \u2022 no rank" else "$span \u2022 best ${best.rankTier.displayName}"
}

/** Collects the rank only while this tab is on screen: building it replays the whole diary. */
@Composable
internal fun RankHistoryTabRoute(viewModel: StatisticsViewModel) {
    val state by viewModel.rankHistory.collectAsStateWithLifecycle()
    RankHistoryTab(state = state, onSelectInterval = viewModel::onSelectRankInterval)
}

@Composable
internal fun RankHistoryTab(state: RankHistoryUiState, onSelectInterval: (RankHistoryInterval) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            RankHistoryInterval.entries.forEach { interval ->
                FilterChip(
                    selected = state.interval == interval,
                    onClick = { onSelectInterval(interval) },
                    label = { Text(interval.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.Primary,
                        selectedLabelColor = AppColors.Background,
                        containerColor = AppColors.SurfaceAlt,
                        labelColor = AppColors.TextSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        if (state.isLoading) {
            MessageCard("Working out your rank history...")
            return@Column
        }

        if (!state.hasRank) {
            MessageCard("Log 7 days to get your first rank.")
            return@Column
        }

        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.lg)) {
                Text(text = "Rank Over Time", style = AppTypography.Header2)
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Your rank after each day that ended. Tap the line for a day. A dashed line marks a yearly reset.",
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(AppSpacing.md))
                if (state.points.isEmpty()) {
                    Text(
                        text = if (state.seasonIsProvisional && state.interval == RankHistoryInterval.SEASON) {
                            "This season's rank starts after 7 logged days."
                        } else {
                            "No ranked days in this range."
                        },
                        style = AppTypography.Body2,
                        color = AppColors.TextMuted
                    )
                } else {
                    RankHistoryGraph(
                        points = state.points,
                        resetDates = state.resetDates,
                        currentYear = state.currentYear,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
                val best = state.bestThisSeason
                if (best != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(text = bestRankLine(best), style = AppTypography.Body2, color = AppColors.TextPrimary)
                }
            }
        }

        if (state.pastSeasons.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.md))
            Card(
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.lg)) {
                    Text(text = "Past Seasons", style = AppTypography.Header2)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    // The latest first
                    state.pastSeasons.asReversed().forEachIndexed { index, past ->
                        if (index > 0) HorizontalDivider(color = AppColors.Divider, thickness = 0.5.dp)
                        Text(
                            text = pastSeasonLine(past),
                            style = AppTypography.Body2,
                            color = AppColors.TextSecondary,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(text: String) {
    Card(
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.xl),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, style = AppTypography.Body2, color = AppColors.TextMuted)
        }
    }
}

/**
 * The rating after each day in [points], the oldest first, with a line at the start of each rank
 * the graph covers. Days are placed by date; each season is its own line, and a dashed line
 * marks the day a new season started, from [resetDates].
 */
@Composable
private fun RankHistoryGraph(points: List<RatingPoint>, resetDates: List<LocalDate>, currentYear: Int, modifier: Modifier = Modifier) {
    val axis = remember(points) { rankAxisRange(points) }
    val firstDay = points.first().date.toEpochDay()
    val lastDay = points.last().date.toEpochDay()
    val lineColor = AppColors.Primary
    val textGray = android.graphics.Color.parseColor("#888888")

    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    // Where the last draw put each point, for the tap; not state, so drawing does not recompose
    val pointOffsets = remember { mutableListOf<Offset>() }

    Canvas(
        modifier = modifier.pointerInput(points) {
            detectTapGestures { tap ->
                // The day under the finger, by x alone, so a flat line is easy to hit
                selectedIndex = pointOffsets.indices.minByOrNull { abs(pointOffsets[it].x - tap.x) }
            }
        }
    ) {
        val paddingLeft = 8.dp.toPx()
        val paddingRight = 8.dp.toPx()
        val paddingTop = 12.dp.toPx()
        val paddingBottom = 22.dp.toPx()
        val plotWidth = size.width - paddingLeft - paddingRight
        val plotHeight = size.height - paddingTop - paddingBottom
        val axisSpan = (axis.last - axis.first).coerceAtLeast(1)

        fun yOf(rating: Int) = paddingTop + plotHeight - (rating - axis.first).toFloat() / axisSpan * plotHeight
        fun xOf(date: LocalDate) = if (lastDay == firstDay) {
            paddingLeft + plotWidth / 2f
        } else {
            paddingLeft + (date.toEpochDay() - firstDay).toFloat() / (lastDay - firstDay) * plotWidth
        }

        // 1. A line where each rank the graph covers starts, named above it
        val rankLabelPaint = android.graphics.Paint().apply {
            color = textGray
            textSize = 9.sp.toPx()
            isAntiAlias = true
        }
        for (rating in axis step RankScoringConfig.RR_PER_RANK) {
            val y = yOf(rating)
            drawLine(Color(0xFF333333), Offset(paddingLeft, y), Offset(size.width - paddingRight, y), strokeWidth = 1.dp.toPx())
            if (rating < axis.last) {
                drawContext.canvas.nativeCanvas.drawText(
                    ratingToRankTier(rating).rank.displayName,
                    paddingLeft + 2.dp.toPx(),
                    y - 3.dp.toPx(),
                    rankLabelPaint
                )
            }
        }

        // 2. The day a new season started
        val offsets = points.map { Offset(xOf(it.date), yOf(it.rating)) }
        pointOffsets.clear()
        pointOffsets.addAll(offsets)
        val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        for (reset in resetDates) {
            if (reset.toEpochDay() in firstDay..lastDay) {
                val x = xOf(reset)
                drawLine(
                    color = AppColors.TextSecondary,
                    start = Offset(x, paddingTop),
                    end = Offset(x, paddingTop + plotHeight),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashes
                )
                drawContext.canvas.nativeCanvas.drawText("Reset", x + 3.dp.toPx(), paddingTop + 9.sp.toPx(), rankLabelPaint)
            }
        }

        // 3. The rating, one line per season
        val path = Path()
        points.forEachIndexed { i, point ->
            val o = offsets[i]
            if (i == 0 || point.seasonNumber != points[i - 1].seasonNumber) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lineColor, radius = 3.5.dp.toPx(), center = offsets.last())

        // 4. The first and last dates
        val datePaint = android.graphics.Paint().apply {
            color = textGray
            textSize = 9.sp.toPx()
            isAntiAlias = true
        }
        datePaint.textAlign = android.graphics.Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(graphDate(points.first().date, currentYear), paddingLeft, size.height - 4.dp.toPx(), datePaint)
        if (points.size > 1) {
            datePaint.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(graphDate(points.last().date, currentYear), size.width - paddingRight, size.height - 4.dp.toPx(), datePaint)
        }

        // 5. The tapped day
        val selected = selectedIndex
        if (selected != null && selected in points.indices && selected in offsets.indices) {
            val at = offsets[selected]
            drawLine(lineColor.copy(alpha = 0.4f), Offset(at.x, paddingTop), Offset(at.x, paddingTop + plotHeight), strokeWidth = 1.dp.toPx())
            drawCircle(lineColor, radius = 5.dp.toPx(), center = at)
            drawCircle(Color.White, radius = 2.5.dp.toPx(), center = at)

            val label = rankPointLabel(points[selected], currentYear)
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 11.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val bounds = WeightGraphHelper.calculateTooltipBounds(
                point = at,
                tooltipWidth = labelPaint.measureText(label) + 16.dp.toPx(),
                tooltipHeight = 24.dp.toPx(),
                canvasWidth = size.width,
                canvasHeight = size.height,
                marginPx = 4.dp.toPx()
            )
            drawRoundRect(
                color = Color(0xFF1E1E1E),
                topLeft = Offset(bounds.leftX, bounds.topY),
                size = Size(bounds.width, bounds.height),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            drawRoundRect(
                color = Color(0xFF424242),
                topLeft = Offset(bounds.leftX, bounds.topY),
                size = Size(bounds.width, bounds.height),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                bounds.leftX + bounds.width / 2f,
                bounds.topY + bounds.height / 2f + 4.dp.toPx(),
                labelPaint
            )
        }
    }
}
