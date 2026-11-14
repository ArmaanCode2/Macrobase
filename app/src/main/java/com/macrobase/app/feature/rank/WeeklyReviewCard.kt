package com.macrobase.app.feature.rank

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.config.WeeklyReviewConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.domain.model.rank.BalanceMacro
import com.macrobase.app.domain.model.rank.QualityMeasure
import com.macrobase.app.domain.model.rank.WeekScores
import com.macrobase.app.domain.model.rank.WeeklyReview
import com.macrobase.app.domain.model.rank.WeeklyTip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** "+34 RR", "-12 RR" or "0 RR". */
internal fun rrChangeText(rr: Int): String = if (rr > 0) "+$rr RR" else "$rr RR"

/**
 * The change from last week between the two shown whole numbers, as "+4", "-3" or "0"; null when
 * either week has no value.
 */
internal fun weekChange(now: Double?, before: Double?): String? {
    if (now == null || before == null) return null
    val change = now.roundToInt() - before.roundToInt()
    return if (change > 0) "+$change" else "$change"
}

private fun thousands(value: Double): String = String.format(Locale.US, "%,d", value.roundToInt())

private fun wholePercent(value: Double): String = "${value.roundToInt()}%"

/** "5 of 7 days logged": of the days the rank counted, 7 unless the season began during the week. */
internal fun loggedDaysText(scores: WeekScores): String = "${scores.loggedDays} of ${scores.countedDays} days logged"

/** A tip in a sentence or two: the user's number, the rule's target, and what to try. */
internal fun weeklyTipText(tip: WeeklyTip): String = when (tip) {
    is WeeklyTip.Calories -> {
        val days = "${tip.daysOutside} of ${tip.loggedDays} logged days"
        val zone = tip.zone
        if (zone == null) {
            "$days fell outside their calorie zone, which changed during the week. A day inside its zone keeps its full food score."
        } else {
            "$days fell outside your calorie zone of ${thousands(zone.start)} \u2013 ${thousands(zone.endInclusive)} kcal. " +
                "A day inside it keeps its full food score."
        }
    }
    is WeeklyTip.Protein ->
        "Protein averaged ${tip.averageGrams.roundToInt()} g a day. Full marks start at your goal of " +
            "${tip.goalGrams.roundToInt()} g: add dal, paneer, eggs or curd to a meal."
    is WeeklyTip.Quality -> qualityTipText(tip.measure, tip.value)
    is WeeklyTip.Balance -> {
        val name = if (tip.macro == BalanceMacro.CARBS) "Carbs" else "Fat"
        val off = tip.averageGrams / tip.goalGrams - 1.0
        val side = if (off > 0) "above" else "below"
        "$name averaged ${wholePercent(abs(off) * 100.0)} $side your goal, ${tip.averageGrams.roundToInt()} g against " +
            "${tip.goalGrams.roundToInt()} g a day. Full marks are within " +
            "${wholePercent(RankScoringConfig.BALANCE_FULL_MARKS_WITHIN * 100.0)} of the goal each day."
    }
    is WeeklyTip.Logging -> {
        val days = if (tip.missedDays == 1) "1 day" else "${tip.missedDays} days"
        "$days went unlogged this week. A missed day costs ${abs(RankScoringConfig.MISSED_DAY_RR)} RR, " +
            "more than a logged day can lose, so log even a rough day."
    }
}

private fun qualityTipText(measure: QualityMeasure, value: Double?): String {
    if (value == null) {
        val name = when (measure) {
            QualityMeasure.FIBER -> "fiber"
            QualityMeasure.SATURATED_FAT -> "saturated fat"
            QualityMeasure.SODIUM -> "sodium"
            QualityMeasure.SUGAR -> "sugar"
        }
        return "There isn't enough data to judge $name this week. Under half of your calories came from foods that state it."
    }
    return when (measure) {
        QualityMeasure.FIBER ->
            "Fiber averaged ${value.roundToInt()} g per 1,000 kcal. Full marks start at " +
                "${RankScoringConfig.FIBER_FULL_MARKS_PER_1000_KCAL.roundToInt()} g: add dal, fruit or whole grains."
        QualityMeasure.SATURATED_FAT ->
            "Saturated fat was ${wholePercent(value)} of your calories. Full marks are at " +
                "${wholePercent(RankScoringConfig.SATURATED_FAT_FULL_MARKS_PERCENT)} or less: use less ghee, butter and cream."
        QualityMeasure.SODIUM ->
            "Sodium averaged ${thousands(value)} mg a day. Full marks are at " +
                "${thousands(RankScoringConfig.SODIUM_FULL_MARKS_MG)} mg or less: go lighter on salt, pickles and packaged snacks."
        QualityMeasure.SUGAR ->
            "Sugar was ${wholePercent(value)} of your calories. Full marks are at " +
                "${wholePercent(RankScoringConfig.SUGAR_FULL_MARKS_PERCENT)} or less: cut back on sweets and sweet drinks."
    }
}

/**
 * "This week": the RR and the logged days of the 7 days up to yesterday, each part's average with
 * its change from the 7 before, and up to two tips. Without last week to compare, the changes are
 * left out and the note says when they start.
 */
@Composable
internal fun WeeklyReviewCard(review: WeeklyReview, modifier: Modifier = Modifier) {
    val now = review.thisWeek
    val before = review.lastWeek
    val week = WeeklyReviewConfig.WEEK_DAYS
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "This week",
            style = AppTypography.Header2,
            color = AppColors.TextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = if (before != null) {
                "The $week days up to yesterday, against the $week before."
            } else {
                "The $week days up to yesterday. Comparisons start once last week has " +
                    "${WeeklyReviewConfig.MIN_COMPARABLE_LOGGED_DAYS} logged days."
            },
            style = AppTypography.Body2.copy(fontSize = 13.sp),
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )

        MbPanel(modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = rrChangeText(now.rrChange), style = AppTypography.Header1, color = AppColors.TextPrimary)
                    if (before != null) {
                        Text(text = "Last week ${rrChangeText(before.rrChange)}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = loggedDaysText(now),
                        style = AppTypography.Body1.copy(fontSize = 14.sp),
                        color = AppColors.TextPrimary
                    )
                    if (before != null) {
                        Text(text = "Last week ${before.loggedDays} of ${before.countedDays}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }
                }
            }

            HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(vertical = 12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WeekPartRow("Day score", now.dayScore, before?.dayScore)
                WeekPartRow("Calories in zone", now.calorieScore, before?.calorieScore)
                WeekPartRow("Protein", now.proteinScore, before?.proteinScore)
                WeekPartRow("Food quality", now.qualityScore, before?.qualityScore)
                WeekPartRow("Carb & fat balance", now.balanceScore, before?.balanceScore)
            }

            if (review.tips.isNotEmpty()) {
                HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(vertical = 12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    review.tips.forEach { tip ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.Accent)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = weeklyTipText(tip),
                                style = AppTypography.Body2.copy(fontSize = 13.sp),
                                color = AppColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A part's week average out of 100, or "-" with nothing logged, and its change from last week. */
@Composable
private fun WeekPartRow(name: String, now: Double?, before: Double?) {
    val value = now?.roundToInt()?.toString() ?: "-"
    val change = weekChange(now, before)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "$name: $value" + (change?.let { ", $it from last week" } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, style = AppTypography.Body1.copy(fontSize = 14.sp), color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(text = value, style = AppTypography.Body1.copy(fontSize = 14.sp), color = AppColors.TextPrimary)
        if (change != null) {
            Text(
                text = change,
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp)
            )
        }
    }
}
