package com.macrobase.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.GoalTransitionBackupDto
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import com.macrobase.app.domain.repository.WeightRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** BUG-023: the rating is replayed over the whole diary, not rebuilt from 0 over 30 days. */
class P2PhaseFourRankReplayTests {

    private val today = LocalDate.now()

    private val maintaining = Goal(
        dailyCalorieGoal = 2000.0,
        carbPercentage = 50.0,
        proteinPercentage = 25.0,
        fatPercentage = 25.0,
        fitnessGoal = FitnessGoal.MAINTAINING,
        maintenanceCalories = 2000.0
    )

    /** A day eaten exactly to [goal]. */
    private fun perfectDay(date: LocalDate, goal: Goal = maintaining) = DailyMacroAggregation(
        date = date,
        calories = goal.dailyCalorieGoal,
        proteinGrams = goal.proteinGrams,
        carbsGrams = goal.carbGrams,
        fatGrams = goal.fatGrams
    )

    private fun lastDays(count: Int, endingDaysAgo: Int = 0) =
        (count - 1 downTo 0).map { today.minusDays((it + endingDaysAgo).toLong()) }

    private fun rank(
        aggregations: List<DailyMacroAggregation>,
        goals: GoalsRepository = DatedGoals(mapOf(LocalDate.MIN to maintaining)),
        weights: List<WeightEntry> = emptyList()
    ) = RankRepositoryImpl(
        statisticsRepository = WindowStats(),
        goalsRepository = goals,
        weightRepository = Weights(weights),
        preferencesRepository = Prefs(),
        diaryRepository = Diary(aggregations)
    )

    @Test
    fun oneHundredTwentyPerfectDaysGoPast600() = runBlocking {
        val profile = rank(lastDays(120).map { perfectDay(it) }).getRankProfile()

        // 30 days rebuilt from 0 could never pass 30 x 20 = 600, the third of eight ranks
        assertTrue("rating ${profile.currentRating}", profile.currentRating > 600)
        assertTrue(profile.rankTier.rank.order >= 3)
        assertFalse(profile.isProvisional)
        assertEquals(120, profile.loggedDaysCount)
        // The screen still lists the last 30 days
        assertEquals(30, profile.recentHistory.size)
        assertEquals(today, profile.recentHistory.first().date)
    }

    @Test
    fun goodDaysOlderThan30DaysStillCount() = runBlocking {
        val recent = rank(lastDays(30).map { perfectDay(it) }).getRankProfile()
        val longer = rank(lastDays(45).map { perfectDay(it) }).getRankProfile()

        assertTrue("${longer.currentRating} vs ${recent.currentRating}", longer.currentRating > recent.currentRating)
    }

    @Test
    fun aRatingIsKeptAcrossDaysAndOnlyMovesByEachDaysDelta() = runBlocking {
        val profile = rank(lastDays(60).map { perfectDay(it) }).getRankProfile()

        val history = profile.recentHistory.reversed() // oldest first
        for (i in 1 until history.size) {
            assertEquals(history[i - 1].overallRating, history[i].ratingBefore)
            assertEquals(
                (history[i].ratingBefore + history[i].rrDelta).coerceIn(RankScoringConfig.MIN_RATING, RankScoringConfig.MAX_RATING),
                history[i].overallRating
            )
        }
        assertEquals(profile.currentRating, history.last().overallRating)
    }

    @Test
    fun aBreakFromLoggingLowersTheRatingButKeepsItEstablished() = runBlocking {
        // 40 logged days, then 20 days without logging, then today
        val logged = lastDays(40, endingDaysAgo = 21).map { perfectDay(it) } + perfectDay(today)
        val profile = rank(logged).getRankProfile()

        assertFalse("the rating was established; a break does not make it provisional again", profile.isProvisional)
        assertEquals(41, profile.loggedDaysCount)
        val gapDay = profile.recentHistory.first { it.date == today.minusDays(5) }
        assertTrue(gapDay.rrDelta < 0)
    }

    @Test
    fun fewerThanSevenLoggedDaysAreProvisional() = runBlocking {
        val profile = rank(lastDays(5).map { perfectDay(it) }).getRankProfile()

        assertTrue(profile.isProvisional)
        assertEquals(5, profile.loggedDaysCount)
        assertEquals(0, profile.currentRating)
    }

    @Test
    fun eachDayIsScoredAgainstTheGoalInForceThatDay() = runBlocking {
        // 20 days on 2,000 kcal 50/25/25, then 20 days on 2,400 kcal 30/40/30, each eaten exactly
        val highProtein = maintaining.copy(dailyCalorieGoal = 2400.0, carbPercentage = 30.0, proteinPercentage = 40.0, fatPercentage = 30.0, maintenanceCalories = 2400.0)
        val switchDay = today.minusDays(19)
        val goals = DatedGoals(mapOf(LocalDate.MIN to maintaining, switchDay to highProtein))
        val diary = lastDays(40).map { perfectDay(it, if (it.isBefore(switchDay)) maintaining else highProtein) }

        val profile = rank(diary, goals).getRankProfile()

        assertTrue(profile.recentHistory.all { it.macroScore == 100.0 })
        assertTrue(profile.recentHistory.all { it.rrDelta > 0 })
        assertEquals(40, goals.requestedDates.size)
    }

    @Test
    fun weightTrendUsesThe30DaysBeforeEachDay() = runBlocking {
        val cutting = maintaining.copy(fitnessGoal = FitnessGoal.CUTTING, dailyCalorieGoal = 1800.0, maintenanceCalories = 2100.0)
        val weights = listOf(
            WeightEntry(id = 1, date = today.minusDays(100), weightKg = 80.0),
            WeightEntry(id = 2, date = today.minusDays(5), weightKg = 85.0),
            WeightEntry(id = 3, date = today, weightKg = 87.0)
        )
        val profile = rank(lastDays(40).map { perfectDay(it, cutting) }, DatedGoals(mapOf(LocalDate.MIN to cutting)), weights).getRankProfile()

        // Today: +2 kg over its 30 days while cutting
        assertEquals(20.0, profile.recentHistory.first { it.date == today }.weightTrendScore, 0.0)
        // Ten days ago: no weigh-in in its 30 days, so the neutral score
        assertEquals(50.0, profile.recentHistory.first { it.date == today.minusDays(10) }.weightTrendScore, 0.0)
    }

    @Test
    fun weightWindowCoversExactlyThe30DaysUpToTheDay() = runBlocking {
        val cutting = maintaining.copy(fitnessGoal = FitnessGoal.CUTTING, dailyCalorieGoal = 1800.0, maintenanceCalories = 2100.0)
        val weights = listOf(
            WeightEntry(id = 1, date = today.minusDays(30), weightKg = 95.0), // one day too old
            WeightEntry(id = 2, date = today.minusDays(29), weightKg = 90.0), // first day of the window
            WeightEntry(id = 3, date = today.minusDays(5), weightKg = 85.0),
            WeightEntry(id = 4, date = today, weightKg = 87.0)
        )
        val profile = rank(lastDays(40).map { perfectDay(it, cutting) }, DatedGoals(mapOf(LocalDate.MIN to cutting)), weights).getRankProfile()

        // 87 - 90 = -3 kg while cutting scores 70; counting the 95 kg weigh-in would give 40, missing the 90 kg one 20
        assertEquals(70.0, profile.recentHistory.first { it.date == today }.weightTrendScore, 0.0)
    }

    // --- fakes ---

    /** Goals by effective date; records which days the rank asked for. */
    private class DatedGoals(private val byDate: Map<LocalDate, Goal>) : GoalsRepository {
        val requestedDates = mutableListOf<LocalDate>()
        private val current = byDate.maxByOrNull { it.key }!!.value
        override suspend fun getGoals() = current
        override fun observeGoals(): Flow<Goal> = flowOf(current)
        override suspend fun updateGoals(goals: Goal) {}
        override suspend fun getGoalForDate(date: LocalDate): Goal {
            requestedDates += date
            return byDate.filterKeys { !it.isAfter(date) }.maxByOrNull { it.key }!!.value
        }
    }

    // Shared with the goal-history tests below
    class WindowStats : StatisticsRepository {
        private fun consistency(s: LocalDate, e: LocalDate) = ConsistencyStatistics(s, e, 30, 30, 100)
        private fun macros(s: LocalDate, e: LocalDate) = MacroAveragesStatistics(
            startDate = s, endDate = e, loggedDaysCount = 30, averageCalories = 2000.0, averageProteinGrams = 125.0,
            averageCarbsGrams = 250.0, averageFatGrams = 55.0, actualCarbsPercent = 50.0, actualProteinPercent = 25.0,
            actualFatPercent = 25.0, targetCarbsPercent = 50.0, targetProteinPercent = 25.0, targetFatPercent = 25.0,
            targetCalories = 2000.0
        )
        private fun weight(s: LocalDate, e: LocalDate) = WeightStatistics(startDate = s, endDate = e)
        override fun observeNutritionConsistency(startDate: LocalDate, endDate: LocalDate) = flowOf(consistency(startDate, endDate))
        override suspend fun getNutritionConsistency(startDate: LocalDate, endDate: LocalDate) = consistency(startDate, endDate)
        override fun observeMacroAverages(startDate: LocalDate, endDate: LocalDate) = flowOf(macros(startDate, endDate))
        override suspend fun getMacroAverages(startDate: LocalDate, endDate: LocalDate) = macros(startDate, endDate)
        override fun observeWeightStatistics(startDate: LocalDate, endDate: LocalDate) = flowOf(weight(startDate, endDate))
        override suspend fun getWeightStatistics(startDate: LocalDate, endDate: LocalDate) = weight(startDate, endDate)
    }

    class Weights(private val entries: List<WeightEntry>) : WeightRepository {
        override suspend fun addWeightEntry(entry: WeightEntry): Long = 1L
        override suspend fun updateWeightEntry(entry: WeightEntry) {}
        override suspend fun deleteWeightEntry(entryId: Long) {}
        override suspend fun deleteWeightEntryForDate(date: LocalDate) {}
        override fun observeWeightForDate(date: LocalDate): Flow<WeightEntry?> = flowOf(null)
        override suspend fun getWeightForDate(date: LocalDate): WeightEntry? = null
        override fun observeWeightHistory(): Flow<List<WeightEntry>> = flowOf(entries)
        override suspend fun getWeightHistory(): List<WeightEntry> = entries
        override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>> = flowOf(entries)
        override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate): List<WeightEntry> = entries
    }

    class Prefs : PreferencesRepository {
        override suspend fun getPreferences() = UserPreferences()
        override fun observePreferences(): Flow<UserPreferences> = flowOf(UserPreferences())
        override suspend fun updatePreferences(preferences: UserPreferences) {}
    }

    class Diary(private val aggregations: List<DailyMacroAggregation>) : DiaryRepository {
        override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
        override suspend fun addEntry(entry: DiaryEntry): Long = 1L
        override suspend fun addEntries(entries: List<DiaryEntry>) {}
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {}
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> =
            flowOf(aggregations.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) })
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) =
            observeNutritionAggregations(startDate, endDate).first()
    }
}

/** BUG-024: every goal change is kept, so each day is scored against the goal it had. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P2PhaseFourGoalHistoryTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val today = LocalDate.now()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** The repository's "today"; tests move it forward to see how past days resolve. */
    private var clockDay = today

    private fun goals(name: String): Pair<GoalsRepositoryImpl, DataStore<Preferences>> {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name.preferences_pb") }
        return GoalsRepositoryImpl(RuntimeEnvironment.getApplication(), store) { clockDay } to store
    }

    private fun goal(calories: Double, carbs: Double, protein: Double, fat: Double, strategy: FitnessGoal, maintenance: Double) =
        Goal(calories, carbs, protein, fat, strategy, maintenance)

    @Test
    fun macroChangeAfterAStrategySwitchIsWhatTodayIsScoredAgainst() = runBlocking {
        val (repo, store) = goals("a")
        // Switched to Cutting at 2,000 kcal 50/25/25 ten days ago
        store.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 2000.0
            it[doublePreferencesKey("carb_percentage")] = 50.0
            it[doublePreferencesKey("protein_percentage")] = 25.0
            it[doublePreferencesKey("fat_percentage")] = 25.0
            it[stringPreferencesKey("fitness_goal")] = "CUTTING"
            it[doublePreferencesKey("maintenance_calories")] = 2200.0
            it[stringPreferencesKey("transitions_history")] =
                "2000-01-01|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0\n${today.minusDays(10)}|CUTTING|2200.0|2000.0|50.0|25.0|25.0"
        }

        // Today: only calories and macros change, to 2,200 kcal 30/40/30
        repo.updateGoals(goal(2200.0, 30.0, 40.0, 30.0, FitnessGoal.CUTTING, 2200.0))

        assertEquals(repo.observeGoals().first().proteinGrams, repo.getGoalForDate(today).proteinGrams, 0.0)
        assertEquals(220.0, repo.getGoalForDate(today).proteinGrams, 0.001)
        // Before today the old targets were in force
        assertEquals(125.0, repo.getGoalForDate(today.minusDays(1)).proteinGrams, 0.001)
        // A day later, today is in the past and keeps the targets it had
        clockDay = today.plusDays(1)
        assertEquals(220.0, repo.getGoalForDate(today).proteinGrams, 0.001)
        assertEquals(FitnessGoal.CUTTING, repo.getGoalForDate(today.minusDays(5)).fitnessGoal)
        assertEquals(FitnessGoal.MAINTAINING, repo.getGoalForDate(today.minusDays(20)).fitnessGoal)
    }

    @Test
    fun historyLeftIncompleteByAnOlderVersionStillGivesTodaysTargets() = runBlocking {
        val (repo, store) = goals("g")
        // Older versions changed the targets to 2,200 kcal 30/40/30 without recording it
        store.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 2200.0
            it[doublePreferencesKey("carb_percentage")] = 30.0
            it[doublePreferencesKey("protein_percentage")] = 40.0
            it[doublePreferencesKey("fat_percentage")] = 30.0
            it[stringPreferencesKey("fitness_goal")] = "CUTTING"
            it[doublePreferencesKey("maintenance_calories")] = 2200.0
            it[stringPreferencesKey("transitions_history")] =
                "2000-01-01|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0\n${today.minusDays(10)}|CUTTING|2200.0|2000.0|50.0|25.0|25.0"
        }

        assertEquals(repo.observeGoals().first().proteinGrams, repo.getGoalForDate(today).proteinGrams, 0.0)
        assertEquals(220.0, repo.getGoalForDate(today).proteinGrams, 0.001)

        // The first read recorded the live goal, so once today is past it still has 220 g
        clockDay = today.plusDays(1)
        assertEquals(220.0, repo.getGoalForDate(today).proteinGrams, 0.001)
        assertEquals(FitnessGoal.CUTTING, repo.getGoalForDate(today).fitnessGoal)
    }

    @Test
    fun firstChangeKeepsThePreviousTargetsForEarlierDays() = runBlocking {
        // Target-only change on a fresh install (defaults 2,000 kcal 50/25/25)
        val (targetsOnly, _) = goals("b")
        targetsOnly.updateGoals(goal(1800.0, 40.0, 35.0, 25.0, FitnessGoal.MAINTAINING, 2000.0))
        assertEquals(2000.0, targetsOnly.getGoalForDate(today.minusDays(1)).dailyCalorieGoal, 0.0)
        assertEquals(1800.0, targetsOnly.getGoalForDate(today).dailyCalorieGoal, 0.0)

        // Strategy change: the baseline used to be built after the new values were written
        val (withStrategy, _) = goals("c")
        withStrategy.updateGoals(goal(1800.0, 40.0, 35.0, 25.0, FitnessGoal.CUTTING, 2200.0))
        val yesterday = withStrategy.getGoalForDate(today.minusDays(1))
        assertEquals(2000.0, yesterday.dailyCalorieGoal, 0.0)
        assertEquals(25.0, yesterday.proteinPercentage, 0.0)
        assertEquals(FitnessGoal.MAINTAINING, yesterday.fitnessGoal)
    }

    @Test
    fun strategyAndTargetsChangedTogether() = runBlocking {
        val (repo, _) = goals("d")
        repo.updateGoals(goal(2000.0, 50.0, 25.0, 25.0, FitnessGoal.MAINTAINING, 2000.0))

        repo.updateGoals(goal(2600.0, 45.0, 30.0, 25.0, FitnessGoal.BULKING, 2300.0))

        // Today: the new targets, still on today's strategy; the strategy switches tomorrow
        val todayGoal = repo.getGoalForDate(today)
        assertEquals(2600.0, todayGoal.dailyCalorieGoal, 0.0)
        assertEquals(FitnessGoal.MAINTAINING, todayGoal.fitnessGoal)
        val tomorrowGoal = repo.getGoalForDate(today.plusDays(1))
        assertEquals(FitnessGoal.BULKING, tomorrowGoal.fitnessGoal)
        assertEquals(2600.0, tomorrowGoal.dailyCalorieGoal, 0.0)
    }

    @Test
    fun aLaterScheduledChangeTakesTheNewTargets() = runBlocking {
        val (repo, _) = goals("e")
        val later = today.plusDays(10)
        repo.restoreGoalBackup(
            GoalBackupDto(
                dailyCalorieGoal = 2000.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0,
                fitnessGoal = "CUTTING", maintenanceCalories = 2200.0,
                scheduledFitnessGoal = "BULKING", scheduledMaintenanceCalories = 2500.0, scheduledEffectiveDate = later.toString(),
                transitions = listOf(
                    GoalTransitionBackupDto("2000-01-01", "CUTTING", 2200.0, 2000.0, 50.0, 25.0, 25.0),
                    GoalTransitionBackupDto(later.toString(), "BULKING", 2500.0, 2000.0, 50.0, 25.0, 25.0)
                )
            )
        )

        repo.updateGoals(goal(2100.0, 40.0, 35.0, 25.0, FitnessGoal.CUTTING, 2200.0))

        val scheduledRecord = repo.exportGoalBackup().transitions!!.single { it.effectiveDate == later.toString() }
        assertEquals("BULKING", scheduledRecord.fitnessGoal)
        assertEquals(2100.0, scheduledRecord.dailyCalorieGoal, 0.0)
        assertEquals(35.0, scheduledRecord.proteinPercentage, 0.0)
    }

    @Test
    fun restoringAnOlderBackupGivesItsTargetsToThePhonesHistory() = runBlocking {
        val (repo, store) = goals("f")
        // This phone: Cutting since ten days ago, recorded with 2,000 kcal 50/25/25
        store.edit {
            it[stringPreferencesKey("fitness_goal")] = "CUTTING"
            it[doublePreferencesKey("maintenance_calories")] = 2200.0
            it[stringPreferencesKey("transitions_history")] =
                "2000-01-01|MAINTAINING|2000.0|2000.0|50.0|25.0|25.0\n${today.minusDays(10)}|CUTTING|2200.0|2000.0|50.0|25.0|25.0"
        }

        // A backup from before format 1.2.0 carries only targets: 1,900 kcal 45/30/25
        repo.restoreGoalBackup(GoalBackupDto(1900.0, 45.0, 30.0, 25.0))

        // The restored diary's past days are scored against the restored targets
        assertEquals(142.5, repo.getGoalForDate(today.minusDays(5)).proteinGrams, 0.001)
        assertEquals(142.5, repo.getGoalForDate(today.minusDays(20)).proteinGrams, 0.001)
        // and no strategy change appears or disappears
        assertEquals(FitnessGoal.CUTTING, repo.getGoalForDate(today.minusDays(5)).fitnessGoal)
        assertEquals(FitnessGoal.MAINTAINING, repo.getGoalForDate(today.minusDays(20)).fitnessGoal)
    }

    @Test
    fun rankScoresEachDayAgainstTheTargetsSavedThatDay() = runBlocking {
        // The bug report's example, through the real goal storage: Cutting at 2,000 kcal 50/25/25
        // from ten days ago, then only the targets changed to 2,200 kcal 30/40/30 five days ago
        val (repo, _) = goals("h")
        clockDay = today.minusDays(11)
        repo.updateGoals(goal(2000.0, 50.0, 25.0, 25.0, FitnessGoal.CUTTING, 2200.0))
        clockDay = today.minusDays(5)
        repo.updateGoals(goal(2200.0, 30.0, 40.0, 30.0, FitnessGoal.CUTTING, 2200.0))
        clockDay = today

        val old = goal(2000.0, 50.0, 25.0, 25.0, FitnessGoal.CUTTING, 2200.0)
        val new = goal(2200.0, 30.0, 40.0, 30.0, FitnessGoal.CUTTING, 2200.0)
        val diary = (10 downTo 0).map { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            val eaten = if (daysAgo > 5) old else new
            DailyMacroAggregation(
                date = date,
                calories = eaten.dailyCalorieGoal,
                proteinGrams = eaten.proteinGrams,
                carbsGrams = eaten.carbGrams,
                fatGrams = eaten.fatGrams
            )
        }
        val rank = RankRepositoryImpl(
            statisticsRepository = P2PhaseFourRankReplayTests.WindowStats(),
            goalsRepository = repo,
            weightRepository = P2PhaseFourRankReplayTests.Weights(emptyList()),
            preferencesRepository = P2PhaseFourRankReplayTests.Prefs(),
            diaryRepository = P2PhaseFourRankReplayTests.Diary(diary)
        )

        val history = rank.getRankProfile().recentHistory

        // Following the targets shown on screen scores full marks every day, before and after the change
        assertEquals(11, history.size)
        assertTrue(history.joinToString { "${it.date}=${it.macroScore}" }, history.all { it.macroScore == 100.0 })
    }
}
