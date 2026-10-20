package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig.PerformanceCategory
import com.macrobase.app.data.cache.CalendarMonthCodec
import com.macrobase.app.data.cache.ConsistencyCodec
import com.macrobase.app.data.cache.FiguresCache
import com.macrobase.app.data.cache.MacroAveragesCodec
import com.macrobase.app.data.cache.RankProfileCodec
import com.macrobase.app.data.cache.SnapshotCodec
import com.macrobase.app.data.cache.SnapshotStore
import com.macrobase.app.data.cache.WeightStatisticsCodec
import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.PastSeason
import com.macrobase.app.domain.model.rank.Rank
import com.macrobase.app.domain.model.rank.RankSeason
import com.macrobase.app.domain.model.rank.RankTier
import com.macrobase.app.domain.model.rank.Tier
import com.macrobase.app.domain.model.windowStart
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The saved figures (design follow-up, phase 2): a snapshot must read back exactly as saved, a
 * damaged or older one is worked out again, and the rank is reused only for exactly the same data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotCacheTests {

    @get:Rule
    val folder = TemporaryFolder()

    private val today = LocalDate.of(2026, 10, 19)
    private val clock = MutableTestClock(today, LocalTime.of(9, 0))
    private val goal = Goal(
        dailyCalorieGoal = 2000.0,
        carbPercentage = 50.0,
        proteinPercentage = 25.0,
        fatPercentage = 25.0,
        fitnessGoal = FitnessGoal.MAINTAINING,
        maintenanceCalories = 2000.0
    )

    private fun store(dir: File = folder.root, version: Long = 17L) = SnapshotStore(dir, version)

    private fun <T> roundTrip(value: T, codec: SnapshotCodec<T>): T {
        val store = store()
        store.write("check", "tag", value, codec)
        val saved = store.read("check", codec)
        assertNotNull(saved)
        assertEquals("tag", saved!!.tag)
        return saved.value
    }

    // Logged days with a little variety, so every part of the figures has something in it
    private fun days(count: Int): List<DailyMacroAggregation> = (1..count).map { back ->
        val wobble = (back % 5) * 90.0
        DailyMacroAggregation(
            date = today.minusDays(back.toLong()),
            calories = 1700.0 + wobble,
            proteinGrams = 110.0 + back % 7,
            carbsGrams = 220.0,
            fatGrams = 60.0
        )
    }

    private class Goals(
        private val current: Goal,
        var history: Map<LocalDate, Goal> = emptyMap(),
        var historyStart: LocalDate? = null
    ) : GoalsRepository {
        override suspend fun getGoals() = current
        override fun observeGoals(): Flow<Goal> = flowOf(current)
        override suspend fun updateGoals(goals: Goal) {}
        override suspend fun getGoalsForDates(dates: List<LocalDate>) = dates.map { history[it] ?: current }
        override suspend fun getGoalHistoryStart() = historyStart
    }

    private fun rank(aggregations: List<DailyMacroAggregation>, goals: Goals, store: SnapshotStore?) = RankRepositoryImpl(
        goalsRepository = goals,
        diaryRepository = P2PhaseFourRankReplayTests.Diary(aggregations),
        clock = clock,
        dispatcher = Dispatchers.Unconfined,
        snapshotStore = store
    )

    private fun statistics(aggregations: List<DailyMacroAggregation>, weights: List<WeightEntry> = emptyList()) = StatisticsRepositoryImpl(
        diaryRepository = P2PhaseFourRankReplayTests.Diary(aggregations),
        goalsRepository = Goals(goal),
        weightRepository = P2PhaseFourRankReplayTests.Weights(weights),
        clock = clock
    )

    @Test
    fun aRankWorkedOutFromADiaryReadsBackTheSame() = runBlocking {
        val profile = rank(days(60), Goals(goal), null).observeRankProfile().first()
        assertTrue(profile.recentHistory.size > 30)
        assertEquals(profile, roundTrip(profile, RankProfileCodec))
    }

    @Test
    fun everyOptionalRankPartReadsBackTheSame() = runBlocking {
        val base = rank(days(20), Goals(goal), null).observeRankProfile().first()
        val tier = RankTier(Rank.GYM_CAT, Tier.II)
        val season = RankSeason(2, today.minusDays(40), today.plusDays(325))
        val full = base.copy(
            pendingStrategyChange = "Cutting tomorrow",
            season = season,
            bestThisSeason = BestRank(tier, 1234, today.minusDays(3)),
            lastSeasonBest = BestRank(RankTier(Rank.AVERAGE, Tier.III), 870, today.minusDays(400)),
            showNewSeasonNotice = true,
            pastSeasons = listOf(PastSeason(RankSeason(1, today.minusDays(405), today.minusDays(40)), null)),
            cleanWeekGate = 4,
            rankCountsFrom = today.minusDays(12),
            calorieZone = 1810.5..2190.25
        )
        assertEquals(full, roundTrip(full, RankProfileCodec))
    }

    @Test
    fun statisticsAndCalendarReadBackTheSame() = runBlocking {
        val stats = statistics(
            days(40),
            listOf(
                WeightEntry(id = 1, date = today.minusDays(9), weightKg = 81.4, note = "after run", loggedAt = Instant.ofEpochSecond(1_790_000_000, 123)),
                WeightEntry(id = 2, date = today.minusDays(2), weightKg = 80.9, loggedAt = Instant.ofEpochSecond(1_790_600_000))
            )
        )
        val consistency = stats.getNutritionConsistency(windowStart(today, 30), today)
        assertEquals(consistency, roundTrip(consistency, ConsistencyCodec))
        val averages = stats.getMacroAverages(windowStart(today.minusDays(1), 30), today.minusDays(1))
        assertEquals(averages, roundTrip(averages, MacroAveragesCodec))
        val weight = stats.getWeightStatistics(windowStart(today, 30), today)
        assertEquals(2, weight.entries.size)
        assertEquals(weight, roundTrip(weight, WeightStatisticsCodec))
        val month = listOf(
            CalendarDaySummary(today.minusDays(1), 1980.0, 2000.0),
            CalendarDaySummary(today, 0.0, 2000.0, hasLogs = false, performanceCategory = PerformanceCategory.EMPTY_MISSED)
        )
        assertEquals(month, roundTrip(month, CalendarMonthCodec))
    }

    @Test
    fun aDamagedOrOlderSnapshotIsWorkedOutAgain() {
        val store = store()
        store.write("rank", "x", listOf(CalendarDaySummary(today, 100.0, 2000.0)), CalendarMonthCodec)
        val file = File(folder.root, "rank.snap")
        // Cut short, as a full disk or a crash mid-write could leave it
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 6) }
        assertNull(store.read("rank", CalendarMonthCodec))
        assertTrue(!file.exists())

        // Saved by another app version: its figures may have been worked out by other rules
        store.write("month", "", listOf(CalendarDaySummary(today, 100.0, 2000.0)), CalendarMonthCodec)
        assertNull(store(version = 18L).read("month", CalendarMonthCodec))
    }

    @Test
    fun aLayoutChangeIsWorkedOutAgain() {
        val store = store()
        store.write("month", "", listOf(CalendarDaySummary(today, 100.0, 2000.0)), CalendarMonthCodec)
        val newer = object : SnapshotCodec<List<CalendarDaySummary>> {
            override val version = CalendarMonthCodec.version + 1
            override fun write(out: DataOutputStream, value: List<CalendarDaySummary>) = CalendarMonthCodec.write(out, value)
            override fun read(input: DataInputStream) = CalendarMonthCodec.read(input)
        }
        assertNull(store.read("month", newer))
    }

    @Test
    fun onlyTheNewestSnapshotsAreKept() {
        val store = SnapshotStore(folder.root, 17L, maxFiles = 3)
        (1..5).forEach { day ->
            store.write("month$day", "", listOf(CalendarDaySummary(today.minusDays(day.toLong()), 1.0, 2000.0)), CalendarMonthCodec)
            File(folder.root, "month$day.snap").setLastModified(1_000_000L * day)
        }
        store.write("month6", "", emptyList(), CalendarMonthCodec)
        assertEquals(3, folder.root.listFiles { f -> f.name.endsWith(".snap") }!!.size)
        assertNull(store.read("month1", CalendarMonthCodec))
        assertNotNull(store.read("month6", CalendarMonthCodec))
    }

    // The point of the rank's snapshot: starting again on the same data shows the saved rank without replaying
    @Test
    fun theSameDataReusesTheSavedRankWithoutReplaying() = runBlocking {
        val diary = days(90)
        val first = rank(diary, Goals(goal), store())
        val worked = first.observeRankProfile().first()
        assertEquals(1, first.replayCount.get())

        // As the app starting again: a new repository, the same saved snapshot
        val again = rank(diary, Goals(goal), store())
        assertEquals(worked, again.observeRankProfile().first())
        assertEquals(0, again.replayCount.get())
        // And the same as working it out with nothing saved
        assertEquals(worked, rank(diary, Goals(goal), null).observeRankProfile().first())
    }

    @Test
    fun aChangedDiaryIsReplayed() = runBlocking {
        rank(days(90), Goals(goal), store()).observeRankProfile().first()
        val changed = days(90).toMutableList().also { it[10] = it[10].copy(calories = 3100.0) }
        val again = rank(changed, Goals(goal), store())
        val profile = again.observeRankProfile().first()
        assertEquals(1, again.replayCount.get())
        assertEquals(rank(changed, Goals(goal), null).observeRankProfile().first(), profile)
    }

    // The goal history is read inside the replay, not watched: a restore can change an old day's
    // goal while today's goal stays the same, and that must not reuse the saved rank
    @Test
    fun aChangedPastGoalIsReplayed() = runBlocking {
        rank(days(90), Goals(goal), store()).observeRankProfile().first()
        val pastGoal = goal.copy(dailyCalorieGoal = 2600.0)
        val goals = Goals(goal, history = mapOf(today.minusDays(30) to pastGoal))
        val again = rank(days(90), goals, store())
        val profile = again.observeRankProfile().first()
        assertEquals(1, again.replayCount.get())
        assertEquals(rank(days(90), Goals(goal, history = mapOf(today.minusDays(30) to pastGoal)), null).observeRankProfile().first(), profile)

        // Where the goal history starts is part of it too
        val later = rank(days(90), Goals(goal, history = goals.history, historyStart = today.minusDays(20)), store())
        later.observeRankProfile().first()
        assertEquals(1, later.replayCount.get())
    }

    @Test
    fun aNewDayIsReplayed() = runBlocking {
        rank(days(90), Goals(goal), store()).observeRankProfile().first()
        clock.advanceBy(java.time.Duration.ofDays(1))
        val nextDay = rank(days(90), Goals(goal), store())
        nextDay.observeRankProfile().first()
        assertEquals(1, nextDay.replayCount.get())
    }

    @Test
    fun aRangeIsSharedAndShowsItsSavedFiguresFirst() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store()
        val saved = listOf(CalendarDaySummary(today, 500.0, 2000.0))
        store.write(FiguresCache.calendarKey(2026, 10), "", saved, CalendarMonthCodec)
        val cache = FiguresCache(backgroundScope + dispatcher, store)
        val live = MutableStateFlow(listOf(CalendarDaySummary(today, 900.0, 2000.0)))
        var sources = 0

        val first = cache.figures(FiguresCache.calendarKey(2026, 10), CalendarMonthCodec) { sources++; live }
        val second = cache.figures(FiguresCache.calendarKey(2026, 10), CalendarMonthCodec) { sources++; live }
        assertSame(first, second)

        val seen = mutableListOf<List<CalendarDaySummary>>()
        val job = launch(dispatcher) { first.take(2).toList(seen) }
        runCurrent()
        job.join()
        assertEquals(1, sources)
        // The saved figures at once, then the live ones, which are saved in turn
        assertEquals(listOf(saved, live.value), seen)
        assertEquals(live.value, store.read(FiguresCache.calendarKey(2026, 10), CalendarMonthCodec)!!.value)
    }

    // The calculation itself runs once per burst: the inputs settle before it, not only the saving after
    @Test
    fun aBurstOfChangesIsWorkedOutOnceItSettles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val diary = MutableStateFlow(days(10))
        val stats = StatisticsRepositoryImpl(
            diaryRepository = LiveDiary(diary),
            goalsRepository = Goals(goal),
            weightRepository = P2PhaseFourRankReplayTests.Weights(emptyList()),
            clock = clock,
            calculationDispatcher = dispatcher
        )
        val worked = mutableListOf<Int>()
        backgroundScope.launch(dispatcher) {
            stats.observeNutritionConsistency(windowStart(today, 30), today).collect { worked += it.loggedDaysCount }
        }
        runCurrent()
        assertEquals(listOf(10), worked)

        // Five changes in quick succession, then quiet
        for (count in 11..15) {
            diary.value = days(count)
            advanceTimeBy(100)
        }
        assertEquals(listOf(10), worked)
        advanceTimeBy(com.macrobase.app.core.util.CalculationLane.SETTLE_MS + 1)
        runCurrent()
        assertEquals(listOf(10, 15), worked)
    }

    @Test
    fun aDataChangeDropsOnlyTheFiguresWorkedOutFromIt() {
        val store = store()
        val cache = FiguresCache(kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined), store)
        val calendar = FiguresCache.calendarKey(2026, 10)
        val consistency = FiguresCache.consistencyKey(today.minusDays(29), today)
        val averages = FiguresCache.averagesKey(today.minusDays(30), today.minusDays(1))
        val weight = FiguresCache.weightKey(today.minusDays(29), today)
        fun saveAll() = listOf(calendar, consistency, averages, weight, FiguresCache.RANK_SNAPSHOT_KEY)
            .forEach { store.write(it, "print", emptyList(), CalendarMonthCodec) }
        fun saved(key: String) = store.read(key, CalendarMonthCodec) != null

        saveAll()
        cache.onWeightChanged()
        assertTrue(saved(calendar) && saved(consistency) && saved(averages))
        assertTrue(!saved(weight))

        saveAll()
        cache.onDiaryChanged()
        assertTrue(!saved(calendar) && !saved(consistency) && !saved(averages))
        assertTrue(saved(weight))

        saveAll()
        cache.onGoalsChanged()
        assertTrue(!saved(calendar) && !saved(consistency) && !saved(averages))
        // The rank carries its own fingerprint of the data it was worked out from
        assertTrue(saved(FiguresCache.RANK_SNAPSHOT_KEY))
    }

    // A screen still showing a range keeps it, however many other ranges were asked for since
    @Test
    fun watchedFiguresAreNeverLetGo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FiguresCache(backgroundScope + dispatcher, null, maxLive = 2)
        val month = { m: Int -> listOf(CalendarDaySummary(today.withMonth(m).withDayOfMonth(1), m * 100.0, 2000.0)) }
        val watched = cache.figures(FiguresCache.calendarKey(2026, 1), CalendarMonthCodec) { flowOf(month(1)) }
        backgroundScope.launch(dispatcher) { watched.collect {} }
        runCurrent()
        val unwatched = cache.figures(FiguresCache.calendarKey(2026, 2), CalendarMonthCodec) { flowOf(month(2)) }
        runCurrent()

        for (m in 3..8) {
            cache.figures(FiguresCache.calendarKey(2026, m), CalendarMonthCodec) { flowOf(month(m)) }
            runCurrent()
        }

        assertSame(watched, cache.figures(FiguresCache.calendarKey(2026, 1), CalendarMonthCodec) { flowOf(month(1)) })
        // One no screen watches is let go and worked out again when asked for
        assertTrue(unwatched !== cache.figures(FiguresCache.calendarKey(2026, 2), CalendarMonthCodec) { flowOf(month(2)) })
    }

    // The midnight wait counts only time the phone is awake: a screen opening after a night asleep
    // must not get yesterday's rank
    @Test
    fun theSharedRankReadsTheDayAgainWhenAScreenOpens() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val shared = RankRepositoryImpl(
            goalsRepository = Goals(goal),
            diaryRepository = P2PhaseFourRankReplayTests.Diary(days(60)),
            clock = clock,
            dispatcher = dispatcher,
            cacheScope = backgroundScope + dispatcher
        )
        val yesterday = shared.observeRankProfile().first()

        // A night passes on the wall clock while no time passes for the coroutines
        clock.advanceBy(java.time.Duration.ofDays(1))
        val morning = shared.observeRankProfile().first()

        val fresh = rank(days(60), Goals(goal), null).observeRankProfile().first()
        assertEquals(fresh, morning)
        assertTrue(fresh != yesterday)
    }

    // A saved rank is never shown for another day, even before today's is worked out
    @Test
    fun aSavedRankFromAnotherDayIsNotUsed() = runBlocking {
        rank(days(60), Goals(goal), store()).observeRankProfile().first()
        clock.advanceBy(java.time.Duration.ofDays(1))
        val again = rank(days(60), Goals(goal), store())
        assertEquals(rank(days(60), Goals(goal), null).observeRankProfile().first(), again.observeRankProfile().first())
        assertEquals(1, again.replayCount.get())
    }

    /** The diary with its daily totals changing over time. */
    private class LiveDiary(private val days: MutableStateFlow<List<DailyMacroAggregation>>) :
        com.macrobase.app.domain.repository.DiaryRepository by P2PhaseFourRankReplayTests.Diary(emptyList()) {
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> =
            days.map { list -> list.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) } }

        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) =
            observeNutritionAggregations(startDate, endDate).first()
    }
}
