package com.macrobase.app.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macrobase.app.core.config.PatternsConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.components.MbChip
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.PatternFinding
import com.macrobase.app.domain.model.PatternsReport
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The Patterns view: what stands out in the 28 days up to yesterday. */
data class PatternsUiState(
    /** True when only the days marked "Done logging" count, as on the Averages view. */
    val finishedDaysOnly: Boolean = false,
    val report: PatternsReport? = null,
    val isLoading: Boolean = true
)

/** One finding as a plain sentence, such as "Saturdays run 22% above your average: 2,450 vs 2,010 kcal." */
internal fun patternText(finding: PatternFinding): String = when (finding) {
    is PatternFinding.LowProteinMeal ->
        "${mealLabel(finding.meal)} gives you the least protein: ${finding.averageGrams.roundToInt()} g on average " +
            "(${mealInSentence(finding.highestMeal)} ${finding.highestGrams.roundToInt()} g)."
    is PatternFinding.HighCalorieWeekday -> {
        val percent = ((finding.averageCalories / finding.overallCalories - 1.0) * 100.0).roundToInt()
        "${weekdays(finding.weekday)} run $percent% above your average: " +
            "${kcal(finding.averageCalories)} vs ${kcal(finding.overallCalories)} kcal."
    }
    is PatternFinding.SugarPeakWeekday -> "Sugar peaks on ${weekdays(finding.weekday)}."
    is PatternFinding.SodiumPeakMeal -> "Sodium peaks at ${mealInSentence(finding.meal)}."
}

/** What the view says below the minimum: the days still to log, or to mark done in finished mode. */
internal fun patternsNeedMoreText(daysNeeded: Int, finishedDaysOnly: Boolean): String {
    val days = if (daysNeeded == 1) "1 more day" else "$daysNeeded more days"
    return if (finishedDaysOnly) {
        "Mark $days as done on Home to see your patterns."
    } else {
        "Log $days to see your patterns."
    }
}

/** A meal's name inside a sentence: "lunch", "late snack", but "AM snack" keeps its capitals. */
private fun mealInSentence(meal: MealType): String {
    val label = mealLabel(meal)
    return if (label.length > 1 && label[1].isLowerCase()) label.replaceFirstChar { it.lowercase() } else label
}

/** "Saturdays". */
private fun weekdays(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.US) + "s"

/** Whole calories with a thousands separator, as "2,450". */
private fun kcal(value: Double): String = String.format(Locale.US, "%,d", value.roundToInt())

/** Collects the patterns only while this view is on screen. */
@Composable
internal fun PatternsTabRoute(viewModel: StatisticsViewModel) {
    val state by viewModel.patterns.collectAsStateWithLifecycle()
    PatternsTab(state = state, onSetFinishedDaysOnly = viewModel::onSetFinishedDaysOnly)
}

/**
 * Patterns: the Logged days / Finished days chips (the Averages switch), then up to four findings,
 * or how many more days it needs, or that nothing stands out.
 */
@Composable
internal fun PatternsTab(state: PatternsUiState, onSetFinishedDaysOnly: (Boolean) -> Unit) {
    StatsPage(
        chips = {
            MbChip(
                text = "Logged days",
                selected = !state.finishedDaysOnly,
                onClick = { onSetFinishedDaysOnly(false) }
            )
            MbChip(
                text = "Finished days",
                selected = state.finishedDaysOnly,
                onClick = { onSetFinishedDaysOnly(true) }
            )
        }
    ) {
        val report = state.report
        when {
            state.isLoading || report == null -> StatsMessagePanel(text = "Looking for patterns...")
            report.daysNeeded > 0 -> StatsMessagePanel(text = patternsNeedMoreText(report.daysNeeded, state.finishedDaysOnly))
            report.findings.isEmpty() -> StatsMessagePanel(text = "Nothing stands out in the last ${PatternsConfig.WINDOW_DAYS} days.")
            else -> PatternsContent(report = report, finishedDaysOnly = state.finishedDaysOnly)
        }
    }
}

@Composable
private fun PatternsContent(report: PatternsReport, finishedDaysOnly: Boolean) {
    val dayKind = if (finishedDaysOnly) "finished" else "logged"
    StatsSectionHeading(text = "What stands out")
    Text(
        text = "From the ${PatternsConfig.WINDOW_DAYS} days up to yesterday: ${report.countedDays} $dayKind days",
        style = StatsNoteStyle,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(modifier = Modifier.height(10.dp))

    MbPanel(modifier = Modifier.fillMaxWidth()) {
        report.findings.forEachIndexed { index, finding ->
            if (index > 0) {
                HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(vertical = 12.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AppColors.Accent)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = patternText(finding), style = AppTypography.Body1, color = AppColors.TextPrimary)
            }
        }
    }
}
