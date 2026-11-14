package com.macrobase.app.core.config

/**
 * The thresholds of the Stats Patterns view (domain/model/Patterns.kt). A finding shows only when
 * its condition holds on enough data; a measure the foods do not state well enough is skipped,
 * never guessed.
 */
object PatternsConfig {
    /** The window: this many days, ending yesterday, so only days that have ended count. */
    const val WINDOW_DAYS = 28L

    /** Counted days the window needs before any finding shows. */
    const val MIN_COUNTED_DAYS = 10

    /** At most this many findings, the largest effect first. */
    const val MAX_FINDINGS = 4

    /** A meal counts towards a meal finding once it was logged on this many counted days. */
    const val MIN_MEAL_DAYS = 5

    /** The lowest-protein meal shows when its average is under this share of the highest meal's. */
    const val LOW_PROTEIN_MEAL_SHARE = 0.5

    /** A weekday counts towards a weekday finding once it has this many counted days. */
    const val MIN_WEEKDAY_DAYS = 2

    /** The highest-calorie weekday shows when it is at least this much above the daily average. */
    const val HIGH_CALORIE_WEEKDAY_EXCESS = 0.15

    /** Sugar and sodium are judged only where the foods stating them cover this share of the calories. */
    const val MIN_STATED_COVERAGE = 0.60

    /** A sugar or sodium peak shows when its rate per 1,000 kcal is at least this much above the overall rate. */
    const val NUTRIENT_PEAK_EXCESS = 0.25
}
