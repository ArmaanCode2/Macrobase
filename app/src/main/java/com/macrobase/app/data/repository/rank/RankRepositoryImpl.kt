package com.macrobase.app.data.repository.rank

import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.util.DeviceClock
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.ratingToRankTier
import com.macrobase.app.domain.model.rank.scoreToRankBreakdown
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.repository.rank.RankRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

class RankRepositoryImpl(
    private val statisticsRepository: StatisticsRepository,
    private val goalsRepository: GoalsRepository,
    private val weightRepository: WeightRepository,
    private val preferencesRepository: PreferencesRepository,
    private val diaryRepository: DiaryRepository,
    private val clock: Clock = DeviceClock
) : RankRepository {

    private data class StatsAndGoals(
        val consistencyStats: com.macrobase.app.domain.model.ConsistencyStatistics,
        val macroStats: com.macrobase.app.domain.model.MacroAveragesStatistics,
        val weightStats: com.macrobase.app.domain.model.WeightStatistics,
        val goals: Goal
    )

    override fun observeRankProfile(): Flow<RankProfile> {
        val today = LocalDate.now(clock)
        // The lifestyle breakdown and the history list cover the last 30 days
        val startDate = today.minusDays((RankScoringConfig.ROLLING_WINDOW_DAYS - 1).toLong())

        val statsAndGoalsFlow = combine(
            statisticsRepository.observeNutritionConsistency(startDate, today),
            statisticsRepository.observeMacroAverages(startDate, today),
            statisticsRepository.observeWeightStatistics(startDate, today),
            goalsRepository.observeGoals()
        ) { consistencyStats, macroStats, weightStats, goals ->
            StatsAndGoals(consistencyStats, macroStats, weightStats, goals)
        }

        return combine(
            statsAndGoalsFlow,
            preferencesRepository.observePreferences(),
            // The rating itself is replayed over every logged day (BUG-023)
            diaryRepository.observeNutritionAggregations(HISTORY_START, today),
            weightRepository.observeWeightHistory()
        ) { statsAndGoals, preferences, aggregations, weights ->
            buildRankProfile(
                today = today,
                startDate = startDate,
                consistencyStats = statsAndGoals.consistencyStats,
                macroStats = statsAndGoals.macroStats,
                weightStats = statsAndGoals.weightStats,
                goals = statsAndGoals.goals,
                preferences = preferences,
                aggregations = aggregations,
                weights = weights
            )
        }
            // Replaying years of history is real work: keep it off the main thread
            .flowOn(Dispatchers.Default)
    }

    override suspend fun getRankProfile(): RankProfile {
        return observeRankProfile().first()
    }

    override suspend fun calculateLifestyleScoreForPeriod(startDate: LocalDate, endDate: LocalDate): LifestyleScore {
        val consistencyStats = statisticsRepository.getNutritionConsistency(startDate, endDate)
        val macroStats = statisticsRepository.getMacroAverages(startDate, endDate)
        val weightStats = statisticsRepository.getWeightStatistics(startDate, endDate)
        val goals = goalsRepository.getGoals()
        val preferences = preferencesRepository.getPreferences()

        return computeLifestyleScore(
            consistencyStats = consistencyStats,
            macroStats = macroStats,
            weightStats = weightStats,
            goals = goals,
            preferences = preferences
        )
    }

    private fun computeLifestyleScore(
        consistencyStats: com.macrobase.app.domain.model.ConsistencyStatistics,
        macroStats: com.macrobase.app.domain.model.MacroAveragesStatistics,
        weightStats: com.macrobase.app.domain.model.WeightStatistics,
        goals: Goal,
        preferences: UserPreferences
    ): LifestyleScore {
        val consistencyScore = consistencyStats.consistencyScorePercentage.toDouble()

        val calorieScore = RankScoringConfig.evaluateCalorieScore(
            actualCalories = macroStats.averageCalories,
            maintenanceCalories = goals.maintenanceCalories,
            goal = goals.fitnessGoal
        )

        val macroScore = RankScoringConfig.evaluateMacroBalanceScore(
            actualP = macroStats.averageProteinGrams,
            targetP = goals.proteinGrams,
            actualC = macroStats.averageCarbsGrams,
            targetC = goals.carbGrams,
            actualF = macroStats.averageFatGrams,
            targetF = goals.fatGrams
        )

        val weightScore = RankScoringConfig.evaluateWeightTrendScore(
            deltaWeightKg = weightStats.deltaWeightKg,
            entryCount = weightStats.entries.size,
            goal = goals.fitnessGoal
        )

        val overallScore = RankScoringConfig.calculateLifestyleScore(
            consistencyScore = consistencyScore,
            calorieScore = calorieScore,
            macroScore = macroScore,
            weightTrendScore = weightScore
        )

        return LifestyleScore(
            consistencyScore = consistencyScore,
            calorieScore = calorieScore,
            macroScore = macroScore,
            weightTrendScore = weightScore,
            overallScore = overallScore
        )
    }

    private suspend fun buildRankProfile(
        today: LocalDate,
        startDate: LocalDate,
        consistencyStats: com.macrobase.app.domain.model.ConsistencyStatistics,
        macroStats: com.macrobase.app.domain.model.MacroAveragesStatistics,
        weightStats: com.macrobase.app.domain.model.WeightStatistics,
        goals: Goal,
        preferences: UserPreferences,
        aggregations: List<com.macrobase.app.domain.repository.DailyMacroAggregation>,
        weights: List<com.macrobase.app.domain.model.WeightEntry>
    ): RankProfile {
        val lifestyleScore = computeLifestyleScore(
            consistencyStats = consistencyStats,
            macroStats = macroStats,
            weightStats = weightStats,
            goals = goals,
            preferences = preferences
        )

        // The rating is replayed over the whole history from the first logged day. A 30-day
        // window rebuilt from 0 capped it at 30 x 20 = 600 (rank 3 of 8) and dropped it as good
        // days left the window (BUG-023).
        val aggMap = aggregations.associateBy { it.date }
        val loggedDates = aggregations.filter { it.calories > 0.0 && !it.date.isAfter(today) }.map { it.date }.sorted()
        val loggedDaysCount = loggedDates.size
        val isProvisional = loggedDaysCount < RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD
        val days = loggedDates.firstOrNull()
            ?.let { first -> generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList() }
            ?: emptyList()
        // Each day is scored against the goal in force that day (BUG-024), read in one pass
        val dayGoals = goalsRepository.getGoalsForDates(days)
        val sortedWeights = weights.sortedBy { it.date }

        val historyEntries = mutableListOf<RankHistoryEntry>()

        var runningRating = RankScoringConfig.MIN_RATING
        var evaluatedLoggedDays = 0
        // Weigh-ins in the 30 days up to the current day: [windowFirst, windowEnd) of sortedWeights,
        // moved forward with the days so the replay stays linear over long histories
        var windowFirst = 0
        var windowEnd = 0

        for ((dayIndex, curr) in days.withIndex()) {
            val dayAgg = aggMap[curr]
            val hasLogged = dayAgg != null && dayAgg.calories > 0.0
            if (hasLogged) {
                evaluatedLoggedDays++
            }

            val dayGoal = dayGoals[dayIndex]

            val dayCalScore = if (dayAgg != null && dayAgg.calories > 0.0) {
                RankScoringConfig.evaluateCalorieScore(
                    actualCalories = dayAgg.calories,
                    maintenanceCalories = dayGoal.maintenanceCalories,
                    goal = dayGoal.fitnessGoal
                )
            } else {
                0.0
            }

            val dayMacroScore = if (dayAgg != null && dayAgg.calories > 0.0) {
                RankScoringConfig.evaluateMacroBalanceScore(
                    actualP = dayAgg.proteinGrams,
                    targetP = dayGoal.proteinGrams,
                    actualC = dayAgg.carbsGrams,
                    targetC = dayGoal.carbGrams,
                    actualF = dayAgg.fatGrams,
                    targetF = dayGoal.fatGrams
                )
            } else {
                0.0
            }

            val dayConsistencyScore = if (hasLogged) 100.0 else 0.0
            // Weight trend over the 30 days up to this day, not the last 30 days for every day
            val windowStart = curr.minusDays((RankScoringConfig.ROLLING_WINDOW_DAYS - 1).toLong())
            while (windowEnd < sortedWeights.size && !sortedWeights[windowEnd].date.isAfter(curr)) windowEnd++
            while (windowFirst < windowEnd && sortedWeights[windowFirst].date.isBefore(windowStart)) windowFirst++
            val weighIns = windowEnd - windowFirst
            val dayWeightScore = RankScoringConfig.evaluateWeightTrendScore(
                deltaWeightKg = if (weighIns == 0) null else sortedWeights[windowEnd - 1].weightKg - sortedWeights[windowFirst].weightKg,
                entryCount = weighIns,
                goal = dayGoal.fitnessGoal
            )

            val dayOverallScore = RankScoringConfig.calculateLifestyleScore(
                consistencyScore = dayConsistencyScore,
                calorieScore = dayCalScore,
                macroScore = dayMacroScore,
                weightTrendScore = dayWeightScore
            )

            val rrDelta = RankScoringConfig.calculateRRDelta(
                lifestyleScore = dayOverallScore,
                loggedDaysCount = if (isProvisional) evaluatedLoggedDays else loggedDaysCount,
                macroScore = dayMacroScore
            )

            val ratingBefore = runningRating
            runningRating = (runningRating + rrDelta).coerceIn(RankScoringConfig.MIN_RATING, RankScoringConfig.MAX_RATING)

            // The screen lists the last 30 days; older days still count toward the rating
            if (!curr.isBefore(startDate)) {
                historyEntries.add(
                    RankHistoryEntry(
                        date = curr,
                        rankTier = ratingToRankTier(runningRating),
                        tierRR = runningRating % 100,
                        overallRating = runningRating,
                        rrDelta = rrDelta,
                        overallScore = dayOverallScore,
                        ratingBefore = ratingBefore,
                        rankTierBefore = ratingToRankTier(ratingBefore),
                        calorieScore = dayCalScore,
                        consistencyScore = dayConsistencyScore,
                        macroScore = dayMacroScore,
                        weightTrendScore = dayWeightScore
                    )
                )
            }
        }

        val breakdown = scoreToRankBreakdown(runningRating)

        // Today's active score for display on the Breakdown Card (or 30-day aggregate fallback)
        val todayAgg = aggMap[today]
        val displayLifestyleScore = if (todayAgg != null && todayAgg.calories > 0.0) {
            val todayHistory = historyEntries.lastOrNull { it.date == today }
            if (todayHistory != null) {
                LifestyleScore(
                    consistencyScore = todayHistory.consistencyScore,
                    calorieScore = todayHistory.calorieScore,
                    macroScore = todayHistory.macroScore,
                    weightTrendScore = todayHistory.weightTrendScore,
                    overallScore = todayHistory.overallScore
                )
            } else {
                lifestyleScore
            }
        } else {
            lifestyleScore
        }

        val pendingChange = if (goals.scheduledFitnessGoal != null && (goals.scheduledFitnessGoal != goals.fitnessGoal || goals.scheduledMaintenanceCalories != goals.maintenanceCalories)) {
            val pendingMaintStr = goals.scheduledMaintenanceCalories?.let { String.format(Locale.US, "%,d", it.roundToInt()) }
            if (pendingMaintStr != null) {
                "${goals.scheduledFitnessGoal.displayName} ($pendingMaintStr kcal)"
            } else {
                goals.scheduledFitnessGoal.displayName
            }
        } else null

        return RankProfile(
            currentRating = runningRating,
            rankTier = breakdown.rankTier,
            tierRR = breakdown.tierRR,
            isProvisional = isProvisional,
            loggedDaysCount = loggedDaysCount,
            lifestyleScore = displayLifestyleScore,
            recentHistory = historyEntries.reversed(), // Most recent first for UI
            fitnessGoal = goals.fitnessGoal,
            maintenanceCalories = goals.maintenanceCalories,
            pendingStrategyChange = pendingChange
        )
    }

    private companion object {
        /** Earlier than any diary entry, so the aggregation query returns every logged day. */
        val HISTORY_START: LocalDate = LocalDate.of(1970, 1, 1)
    }
}
