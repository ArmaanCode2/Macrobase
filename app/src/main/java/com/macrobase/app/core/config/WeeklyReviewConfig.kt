package com.macrobase.app.core.config

/**
 * The thresholds of the Rank screen's weekly review (domain/model/rank/WeeklyReview.kt). The review
 * reads the rank rules in [RankScoringConfig] and changes nothing in them.
 */
object WeeklyReviewConfig {
    /** A week: the 7 days up to yesterday, against the 7 before them. */
    const val WEEK_DAYS = 7

    /** Last week needs this many logged days before this week is compared with it. */
    const val MIN_COMPARABLE_LOGGED_DAYS = 3

    /** A part of the week's score gets a tip when it averages below this. */
    const val TIP_SCORE_BELOW = 70.0

    /** At most this many tips, the lowest part first. */
    const val MAX_TIPS = 2

    /** A food quality measure is judged only when the foods stating it cover this share of the calories. */
    const val MIN_QUALITY_COVERAGE = 0.5
}
