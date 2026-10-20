package com.macrobase.app.data.repository.rank

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import java.io.OutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import com.macrobase.app.data.cache.SnapshotStore
import com.macrobase.app.data.cache.RankProfileCodec
import com.macrobase.app.data.cache.FiguresCache.Companion.RANK_SNAPSHOT_KEY
import com.macrobase.app.core.util.settleAfterFirst
import com.macrobase.app.core.config.RankScoringConfig
import com.macrobase.app.core.util.DeviceClock
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.rank.BestRank
import com.macrobase.app.domain.model.rank.LifestyleScore
import com.macrobase.app.domain.model.rank.PastSeason
import com.macrobase.app.domain.model.rank.RankDayNutrients
import com.macrobase.app.domain.model.rank.RankDayNutrients.KnownAmount
import com.macrobase.app.domain.model.rank.RankHistoryEntry
import com.macrobase.app.domain.model.rank.RankProfile
import com.macrobase.app.domain.model.rank.RankSeason
import com.macrobase.app.domain.model.rank.RatingPoint
import com.macrobase.app.domain.model.rank.ratingToRankTier
import com.macrobase.app.domain.model.rank.scoreToRankBreakdown
import com.macrobase.app.domain.model.windowStart
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.LoggedFoodNutrients
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.rank.RankRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class RankRepositoryImpl(
    private val goalsRepository: GoalsRepository,
    private val diaryRepository: DiaryRepository,
    /**
     * Built-in foods' nutrients for entries logged before the diary kept fiber, sugar, saturated
     * fat and sodium. Only read; the entries never change (AGENTS.md 1.4). Null skips the lookup.
     */
    private val foodRepository: FoodRepository? = null,
    /** Where the "rank recalculated" notice is remembered; null never shows it. */
    private val preferencesRepository: PreferencesRepository? = null,
    private val clock: Clock = DeviceClock,
    /** Where the replay runs; tests pass their own to drive the midnight check in virtual time. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Where the last profile is saved, so it shows at once after the app starts; null saves nothing. */
    private val snapshotStore: SnapshotStore? = null,
    /**
     * Where one shared replay runs for every screen and stays up to date in the background; null
     * gives each caller its own replay, as tests expect.
     */
    private val cacheScope: CoroutineScope? = null
) : RankRepository {

    /** A built-in food's nutrients by food id and logged name; null when it is not that food. */
    private data class CatalogNutrition(val nutrition: Nutrition?, val foodMissing: Boolean = false)

    private val catalogCache = ConcurrentHashMap<Pair<Long, String>, CatalogNutrition>()

    /** Catalog lookups that failed: a profile worked out with one is not kept, so the next replay asks again. */
    private val failedCatalogLookups = AtomicInteger()

    /**
     * Catalog foods not found. The built-in database reports an error as "no food" too, so a
     * profile worked out with one is kept for this run, as the lookup itself is, but never saved.
     */
    private val missingCatalogFoods = AtomicInteger()

    /** Whether the saved profile was looked at this run; after that the one in memory is current. */
    @Volatile
    private var snapshotChecked = false

    /** A screen started showing the rank: "today" is read again, in case the phone slept past midnight. */
    private val todayChecks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The last profile, with the fingerprint of everything it was worked out from. */
    @Volatile
    private var remembered: Pair<String, RankProfile>? = null

    /** How many times the whole diary was replayed; tests read it to see a replay was skipped. */
    internal val replayCount = AtomicInteger()

    /**
     * The replay shared by the Rank tab and Stats' rank history. Once started it stays up to date
     * in the background: a change to the diary or goals is replayed after it settles, so the Rank
     * tab opens on a profile that is already worked out.
     */
    private val sharedProfile: Flow<Pair<LocalDate, RankProfile>>? by lazy {
        cacheScope?.let { scope ->
            liveDatedProfile()
                // A replay that fails is logged and tried again a little later, so the shared
                // replay never stops for good and never takes the app down
                .retryWhen { cause, attempt ->
                    Log.w(TAG, "Rank replay failed, trying again: ${cause.message}")
                    delay(RETRY_DELAY_MS * (attempt + 1))
                    true
                }
                .shareIn(scope, SharingStarted.Lazily, replay = 1)
        }
    }

    /**
     * The profile for the current day. "Today" is read again after each midnight, so a profile
     * kept open overnight counts the day that just ended (BUG-032), and each time a screen starts
     * showing it: the midnight wait does not count time the phone sleeps.
     */
    override fun observeRankProfile(): Flow<RankProfile> =
        sharedProfile
            ?.onStart { todayChecks.tryEmit(Unit) }
            // A profile kept from an earlier day is not shown; the one for today follows
            ?.filter { (day, _) -> day == ZonedDateTime.now(clock).toLocalDate() }
            ?.map { (_, profile) -> profile }
            ?: liveProfile()

    private fun liveProfile(): Flow<RankProfile> = liveDatedProfile().map { (_, profile) -> profile }

    /** Each profile with the day it was worked out for. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun liveDatedProfile(): Flow<Pair<LocalDate, RankProfile>> =
        observeToday()
            .flatMapLatest { today -> observeRankProfile(today).map { today to it } }
            // Replaying years of history is real work: keep it off the main thread
            .flowOn(dispatcher)

    /**
     * Today, then each new day as it starts, checked at least hourly in case the clock or zone
     * changes, and again whenever a screen starts showing the rank.
     */
    private fun observeToday(): Flow<LocalDate> = merge(
        flow {
            while (true) {
                val now = ZonedDateTime.now(clock)
                emit(now.toLocalDate())
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                delay(Duration.between(now, nextMidnight).toMillis().coerceIn(MIN_DAY_CHECK_MS, MAX_DAY_CHECK_MS))
            }
        },
        todayChecks.map { ZonedDateTime.now(clock).toLocalDate() }
    ).distinctUntilChanged()

    private fun observeRankProfile(today: LocalDate): Flow<RankProfile> = combine(
        // A replay covers the whole diary: only a real change to these starts another
        goalsRepository.observeGoals().distinctUntilChanged(),
        // The rating is replayed over every logged day (BUG-023). A burst of diary changes (several
        // foods logged, deleted or moved) is replayed once, after it settles
        diaryRepository.observeLoggedFoodNutrients(HISTORY_START, today).settleAfterFirst(DIARY_SETTLE_MS),
        (preferencesRepository?.observeSeenRankRulesVersion() ?: flowOf(RankScoringConfig.RULES_VERSION)).distinctUntilChanged(),
        (preferencesRepository?.observeSeenRankSeason() ?: flowOf(Long.MAX_VALUE)).distinctUntilChanged()
    ) { goals, foods, seenRulesVersion, seenSeasonOn ->
        profileFor(today, goals, foods, seenRulesVersion, seenSeasonOn)
    }

    /**
     * The profile for these inputs. Replaying the whole diary is the costly part, so its result is
     * kept with a fingerprint of everything the replay reads. The same data again (coming back to
     * the Rank tab, or the app starting again) reuses that result instead of replaying.
     */
    private suspend fun profileFor(
        today: LocalDate,
        goals: Goal,
        foods: List<LoggedFoodNutrients>,
        seenRulesVersion: Int,
        seenSeasonOn: Long
    ): RankProfile {
        val inputs = replayInputs(today, goals, foods, seenRulesVersion, seenSeasonOn)
        val fingerprint = inputs.fingerprint
        remembered?.let { (print, profile) -> if (print == fingerprint) return profile }
        // The saved profile is looked at once per run; after that the one in memory is the newest
        if (!snapshotChecked) {
            snapshotChecked = true
            snapshotStore?.read(RANK_SNAPSHOT_KEY, RankProfileCodec)?.takeIf { it.tag == fingerprint }?.let { saved ->
                remembered = fingerprint to saved.value
                return saved.value
            }
        }
        val failedBefore = failedCatalogLookups.get()
        val missingBefore = missingCatalogFoods.get()
        val profile = buildRankProfile(today, goals, foods, seenRulesVersion, seenSeasonOn, inputs.historyStart, inputs.dayGoals)
        replayCount.incrementAndGet()
        // A failed lookup is tried again on the next replay, so this profile is not kept at all
        if (failedCatalogLookups.get() == failedBefore) {
            remembered = fingerprint to profile
            // Saved only when every catalog food was found: "not found" can be a database error
            if (missingCatalogFoods.get() == missingBefore) {
                snapshotStore?.write(RANK_SNAPSHOT_KEY, fingerprint, profile, RankProfileCodec)
            }
        }
        return profile
    }

    /**
     * A fingerprint of everything the replay reads: the day, the goals in force on every day it
     * scores and where the goal history starts, every logged food, and the notices already seen.
     * The built-in catalog and the scoring rules belong to the app version, which the snapshot
     * store checks itself. Any change gives another fingerprint, so a kept profile is never used
     * for different data.
     */
    private suspend fun replayInputs(
        today: LocalDate,
        goals: Goal,
        foods: List<LoggedFoodNutrients>,
        seenRulesVersion: Int,
        seenSeasonOn: Long
    ): ReplayInputs {
        val historyStart = goalsRepository.getGoalHistoryStart()
        // The goal of every day from the first logged one: as wide as any day the replay scores,
        // read in one pass and handed to the replay, so a goal change on any of those days
        // changes the fingerprint and nothing is read twice
        val first = foods.minOfOrNull { it.date }
        val dayGoals = if (first != null && !first.isAfter(today)) {
            val dates = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
            dates.zip(goalsRepository.getGoalsForDates(dates)).toMap()
        } else {
            emptyMap()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        // Written straight into the digest (OutputStream.nullOutputStream needs Android 13)
        val intoDigest = object : OutputStream() {
            override fun write(b: Int) = digest.update(b.toByte())
            override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
        }
        DataOutputStream(intoDigest).use { out ->
            out.writeInt(RankScoringConfig.RULES_VERSION)
            out.writeLong(today.toEpochDay())
            out.writeUTF(goals.toString())
            out.writeUTF(historyStart?.toString() ?: "")
            dayGoals.forEach { (date, goal) ->
                out.writeLong(date.toEpochDay())
                out.writeUTF(goal.toString())
            }
            out.writeInt(foods.size)
            foods.forEach { food ->
                out.writeLong(food.date.toEpochDay())
                out.writeLong(food.foodId)
                // As bytes: writeUTF cannot take a name longer than 64 KB
                val name = food.foodName.toByteArray(Charsets.UTF_8)
                out.writeInt(name.size)
                out.write(name)
                out.writeDouble(food.calories)
                out.writeDouble(food.proteinGrams)
                out.writeDouble(food.carbsGrams)
                out.writeDouble(food.fatGrams)
                listOf(food.fiberGrams, food.sugarGrams, food.saturatedFatGrams, food.sodiumMg).forEach { amount ->
                    out.writeBoolean(amount != null)
                    if (amount != null) out.writeDouble(amount)
                }
            }
            out.writeInt(seenRulesVersion)
            out.writeLong(seenSeasonOn)
        }
        val fingerprint = digest.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
        return ReplayInputs(fingerprint, historyStart, dayGoals)
    }

    /** What the replay reads besides the diary, with the fingerprint of all of it. */
    private class ReplayInputs(val fingerprint: String, val historyStart: LocalDate?, val dayGoals: Map<LocalDate, Goal>)

    override suspend fun getRankProfile(): RankProfile {
        return observeRankProfile().first()
    }

    override suspend fun calculateLifestyleScoreForPeriod(startDate: LocalDate, endDate: LocalDate): LifestyleScore {
        val countFrom = goalsRepository.getGoalHistoryStart()
        val foods = diaryRepository.observeLoggedFoodNutrients(startDate, endDate).first()
            .filter { countFrom == null || !it.date.isBefore(countFrom) }
        return summarize(scoreDays(foods, endDate), startDate, endDate)
    }

    override suspend fun dismissRulesNotice() {
        preferencesRepository?.setSeenRankRulesVersion(RankScoringConfig.RULES_VERSION)
    }

    override suspend fun dismissSeasonNotice(seasonStart: LocalDate) {
        preferencesRepository?.setSeenRankSeason(seasonStart.toEpochDay())
    }

    private suspend fun buildRankProfile(
        today: LocalDate,
        goals: Goal,
        foods: List<LoggedFoodNutrients>,
        seenRulesVersion: Int,
        seenSeasonOn: Long,
        historyStart: LocalDate?,
        dayGoals: Map<LocalDate, Goal>
    ): RankProfile {
        // A day before the first saved goal would be scored against a guessed goal: left out
        val countFrom = historyStart
        // Every day the rank may count, across seasons; the score card reads these
        val countedDays = scoreDays(if (countFrom == null) foods else foods.filter { !it.date.isBefore(countFrom) }, today, dayGoals)
        // The rank starts again from the bottom on each anniversary of the first logged day
        val firstLogged = foods.filter { it.calories > 0.0 }.minOfOrNull { it.date }
        val season = firstLogged?.let { RankSeason.containing(it, today) }
        val days = if (season == null) countedDays else seasonDays(countedDays, season)
        // Each earlier season replayed on its own, for its best rank and the rank history graph
        // A year with nothing logged is left out
        val pastSeasonRatings = if (firstLogged != null && season != null) {
            (1 until season.number).mapNotNull { number ->
                val past = RankSeason.containing(firstLogged, firstLogged.plusYears(number - 1L))
                val pastDays = seasonDays(countedDays, past)
                if (pastDays.isEmpty()) null else past to seasonRatings(pastDays, number)
            }
        } else {
            emptyList()
        }
        val pastSeasons = pastSeasonRatings.map { (past, ratings) -> PastSeason(past, bestOf(ratings)) }
        // Named under the oldest history page, when this season's logged days were left out
        val rankCountsFrom = countFrom?.takeIf { start ->
            foods.any { it.calories > 0.0 && it.date.isBefore(start) && (season == null || !it.date.isBefore(season.start)) }
        }
        // Today is still in progress (owner decision D3): it changes the rating only once it
        // ends, so an empty or half-logged morning costs nothing (BUG-032)
        val loggedDaysCount = days.count { it.isLogged && it.date.isBefore(today) }

        // Ended days make the rating. Today, when listed, is the last day and only a preview.
        val endedDays = if (days.lastOrNull()?.date == today) days.size - 1 else days.size
        // Every counted day; the screen shows them 30 days to a page
        val historyEntries = mutableListOf<RankHistoryEntry>()
        val currentRating = replayRating(days, endedDays, loggedDaysCount) { day, ratingBefore, rrDelta, rating, heldByCleanWeek ->
            historyEntries.add(day.toHistoryEntry(ratingBefore, rrDelta, rating, isInProgress = false, heldByCleanWeek = heldByCleanWeek))
        }
        // Before 7 logged days the rating is not a rank yet, only a 0 waiting to be replayed
        val thisSeasonRatings = if (loggedDaysCount < RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD) {
            emptyList()
        } else {
            historyEntries.map { RatingPoint(it.date, it.overallRating, season?.number ?: 1) }
        }
        if (endedDays < days.size) {
            val todayScores = days.last()
            // The rating once today ends, minus the rating now. Ending a day can change earlier
            // days too, for example on the day the rating is established, so this is a replay
            // rather than today's own delta. Nothing logged yet: no preview.
            val preview = if (todayScores.isLogged) {
                replayRating(days, days.size, loggedDaysCount + 1) - currentRating
            } else {
                0
            }
            historyEntries.add(todayScores.toHistoryEntry(currentRating, preview, currentRating, isInProgress = true))
        }

        // The score card covers the 30 days that have ended, so a half-logged morning does not
        // lower it (BUG-032). It judges the food, not the rank, so a new season does not empty it.
        val lastEndedDay = today.minusDays(1)
        val lifestyleScore = summarize(countedDays, windowStart(lastEndedDay, RankScoringConfig.ROLLING_WINDOW_DAYS.toLong()), lastEndedDay)
        val breakdown = scoreToRankBreakdown(currentRating)
        // The next rank that needs a clean week, once the rating is in the tier just below it
        val nextGate = RankScoringConfig.CLEAN_WEEK_GATES.firstOrNull { it > currentRating }
        val cleanWeekGate = nextGate?.takeIf {
            loggedDaysCount >= RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD &&
                currentRating >= it - RankScoringConfig.RR_PER_TIER &&
                !hasRecentCleanWeek(days, endedDays)
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
            currentRating = currentRating,
            rankTier = breakdown.rankTier,
            tierRR = breakdown.tierRR,
            isProvisional = loggedDaysCount < RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD,
            loggedDaysCount = loggedDaysCount,
            lifestyleScore = lifestyleScore,
            recentHistory = historyEntries.reversed(), // Most recent first for UI
            fitnessGoal = goals.fitnessGoal,
            maintenanceCalories = goals.maintenanceCalories,
            pendingStrategyChange = pendingChange,
            calorieZone = RankScoringConfig.calorieZone(goals.fitnessGoal, goals.maintenanceCalories),
            // Also when every logged day was left out: that rank was just recalculated to nothing
            // Anyone with a day logged before today had a rank to recalculate, even one now left out
            showRulesNotice = seenRulesVersion < RankScoringConfig.RULES_VERSION && firstLogged?.isBefore(today) == true,
            season = season,
            bestThisSeason = bestOf(thisSeasonRatings),
            // Only the season just before: a year with nothing logged has no best to name
            lastSeasonBest = pastSeasons.lastOrNull()?.takeIf { it.season.number == (season?.number ?: 0) - 1 }?.best,
            showNewSeasonNotice = season != null && season.number > 1 && season.start.toEpochDay() > seenSeasonOn,
            cleanWeekGate = cleanWeekGate,
            rankCountsFrom = rankCountsFrom,
            ratingHistory = pastSeasonRatings.flatMap { it.second } + thisSeasonRatings,
            pastSeasons = pastSeasons
        )
    }

    /**
     * Every day from the first logged day to [lastDay], each scored against the goal in force that
     * day (BUG-024). A day with no calories logged is a missed day.
     */
    private suspend fun scoreDays(
        foods: List<LoggedFoodNutrients>,
        lastDay: LocalDate,
        /** Goals already read for these days; the rest are read here. */
        knownGoals: Map<LocalDate, Goal> = emptyMap()
    ): List<ScoredDay> {
        val byDate = withCatalogNutrients(foods.filter { !it.date.isAfter(lastDay) }).groupBy { it.date }
        val dayNutrients = byDate.mapValues { (_, entries) -> entries.toDayNutrients() }
        val firstLogged = dayNutrients.filterValues { it.calories > 0.0 }.keys.minOrNull() ?: return emptyList()
        val dates = generateSequence(firstLogged) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDay) }.toList()
        // Read in one pass, unless they were read already
        val dayGoals = if (dates.all { it in knownGoals }) dates.map { knownGoals.getValue(it) } else goalsRepository.getGoalsForDates(dates)
        return dates.mapIndexed { index, date ->
            val nutrients = dayNutrients[date]?.takeIf { it.calories > 0.0 }
            ScoredDay(
                date = date,
                calories = nutrients?.calories ?: 0.0,
                scores = nutrients?.let { RankScoringConfig.scoreDay(it, dayGoals[index]) }
            )
        }
    }

    private fun List<LoggedFoodNutrients>.toDayNutrients() = RankDayNutrients(
        calories = sumOf { it.calories },
        proteinGrams = sumOf { it.proteinGrams },
        carbsGrams = sumOf { it.carbsGrams },
        fatGrams = sumOf { it.fatGrams },
        fiber = fold(KnownAmount.NONE) { sum, it -> sum + KnownAmount.of(it.fiberGrams, it.calories) },
        sugar = fold(KnownAmount.NONE) { sum, it -> sum + KnownAmount.of(it.sugarGrams, it.calories) },
        saturatedFat = fold(KnownAmount.NONE) { sum, it -> sum + KnownAmount.of(it.saturatedFatGrams, it.calories) },
        sodium = fold(KnownAmount.NONE) { sum, it -> sum + KnownAmount.of(it.sodiumMg, it.calories) }
    )

    /**
     * Fills the secondary nutrients an entry left unknown from the built-in food it logged, scaled
     * by the entry's calories. A logged 0.0 is a real zero and stays. Only for the score: nothing
     * is written back.
     */
    private suspend fun withCatalogNutrients(foods: List<LoggedFoodNutrients>): List<LoggedFoodNutrients> {
        val repository = foodRepository ?: return foods
        return foods.map { food ->
            val missesAValue = food.fiberGrams == null || food.sugarGrams == null || food.saturatedFatGrams == null || food.sodiumMg == null
            if (!missesAValue || !isCatalogFoodId(food.foodId) || food.calories <= 0.0) return@map food
            val catalog = catalogNutrition(repository, food.foodId, food.foodName) ?: return@map food
            val scale = food.calories / catalog.calories
            food.copy(
                fiberGrams = food.fiberGrams ?: catalog.fiberGrams?.times(scale),
                sugarGrams = food.sugarGrams ?: catalog.sugarGrams?.times(scale),
                saturatedFatGrams = food.saturatedFatGrams ?: catalog.saturatedFatGrams?.times(scale),
                sodiumMg = food.sodiumMg ?: catalog.sodiumMg?.times(scale)
            )
        }
    }

    /**
     * The built-in food [foodId], when it is the food the entry logged: ids from older versions
     * can point at a different food, so the name must match (as Food.isSameFoodAs). A food with
     * no calories cannot be scaled to an entry and gives nothing.
     */
    private suspend fun catalogNutrition(repository: FoodRepository, foodId: Long, foodName: String): Nutrition? {
        val key = foodId to foodName.trim().lowercase(Locale.US)
        catalogCache[key]?.let { cached ->
            if (cached.foodMissing) missingCatalogFoods.incrementAndGet()
            return cached.nutrition
        }
        val food = try {
            repository.getFoodById(foodId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not remembered: the next replay asks again
            failedCatalogLookups.incrementAndGet()
            return null
        }
        if (food == null) missingCatalogFoods.incrementAndGet()
        val nutrition = food
            ?.takeIf { it.name.trim().equals(foodName.trim(), ignoreCase = true) }
            ?.nutrition
            ?.takeIf { it.calories > 0.0 }
        catalogCache[key] = CatalogNutrition(nutrition, foodMissing = food == null)
        return nutrition
    }

    /** The days of [season] in [days], from its first logged day, as a new diary would start. */
    private fun seasonDays(days: List<ScoredDay>, season: RankSeason): List<ScoredDay> =
        days.filter { !it.date.isBefore(season.start) && it.date.isBefore(season.nextReset) }.dropWhile { !it.isLogged }

    /**
     * The rating after each of [endedDays], replayed as season [seasonNumber] on its own; none when
     * the season never had 7 logged days, so never had a rank.
     */
    private fun seasonRatings(endedDays: List<ScoredDay>, seasonNumber: Int): List<RatingPoint> {
        val loggedDays = endedDays.count { it.isLogged }
        if (loggedDays < RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD) return emptyList()
        val ratings = mutableListOf<RatingPoint>()
        replayRating(endedDays, endedDays.size, loggedDays) { day, _, _, rating, _ ->
            ratings.add(RatingPoint(day.date, rating, seasonNumber))
        }
        return ratings
    }

    /**
     * The highest of [ratings], with the first day its rank and tier were reached; null while the
     * rating is not established or never rose above 0.
     */
    private fun bestOf(ratings: List<RatingPoint>): BestRank? {
        var best: BestRank? = null
        for (point in ratings) {
            val before = best
            if (point.rating > (before?.rating ?: 0)) {
                val tier = ratingToRankTier(point.rating)
                // More RR in the same tier keeps the day the tier was first reached
                best = BestRank(tier, point.rating, if (before?.rankTier == tier) before.date else point.date)
            }
        }
        return best
    }

    /**
     * The card over [from]..[to], from the first logged day: each logged day's parts averaged, and
     * the day score averaged over every day, a missed day scoring 0.
     */
    private fun summarize(days: List<ScoredDay>, from: LocalDate, to: LocalDate): LifestyleScore {
        val window = days.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        if (window.isEmpty()) return LifestyleScore.ZERO
        val logged = window.mapNotNull { it.scores }
        fun average(score: (RankScoringConfig.DayScores) -> Double) =
            if (logged.isEmpty()) 0.0 else logged.sumOf(score) / logged.size
        return LifestyleScore(
            consistencyScore = logged.size * 100.0 / window.size,
            calorieScore = average { it.calorieCredit * 100.0 },
            proteinScore = average { it.protein },
            qualityScore = average { it.quality },
            balanceScore = average { it.balance },
            overallScore = logged.sumOf { it.dayScore } / window.size,
            loggedDays = logged.size,
            eligibleDays = window.size
        )
    }

    /** One day's scores, before any rating is applied; [scores] is null for a missed day. */
    private data class ScoredDay(
        val date: LocalDate,
        val calories: Double,
        val scores: RankScoringConfig.DayScores?
    ) {
        val isLogged: Boolean get() = scores != null

        /** Counts toward a clean week: logged with at least half the calorie credit. */
        val isCleanDay: Boolean get() = scores != null && scores.calorieCredit >= RankScoringConfig.CLEAN_DAY_MIN_CALORIE_CREDIT

        fun toHistoryEntry(ratingBefore: Int, rrDelta: Int, rating: Int, isInProgress: Boolean, heldByCleanWeek: Boolean = false) = RankHistoryEntry(
            date = date,
            rankTier = ratingToRankTier(rating),
            tierRR = rating % 100,
            overallRating = rating,
            rrDelta = rrDelta,
            overallScore = scores?.dayScore ?: 0.0,
            ratingBefore = ratingBefore,
            rankTierBefore = ratingToRankTier(ratingBefore),
            calorieScore = (scores?.calorieCredit ?: 0.0) * 100.0,
            consistencyScore = if (isLogged) 100.0 else 0.0,
            proteinScore = scores?.protein ?: 0.0,
            qualityScore = scores?.quality ?: 0.0,
            balanceScore = scores?.balance ?: 0.0,
            expectedScore = RankScoringConfig.expectedScore(ratingBefore),
            calories = calories,
            isLogged = isLogged,
            isInProgress = isInProgress,
            heldByCleanWeek = heldByCleanWeek
        )
    }

    /**
     * True when [days] ends a clean week at [index]: 7 days with at most one off day. A missed day
     * and a day outside the zone are both off days, so skipping a bad day never beats logging it.
     */
    private fun endsCleanWeek(days: List<ScoredDay>, index: Int): Boolean {
        if (index < RankScoringConfig.CLEAN_WEEK_DAYS - 1) return false
        val week = days.subList(index - RankScoringConfig.CLEAN_WEEK_DAYS + 1, index + 1)
        return week.count { !it.isCleanDay } <= RankScoringConfig.CLEAN_WEEK_OFF_DAYS_ALLOWED
    }

    /** A clean week ended within the lookback before the day at [index]. */
    private fun cleanWeekWithin(lastCleanWeekEnd: Int, index: Int) =
        index - lastCleanWeekEnd < RankScoringConfig.CLEAN_WEEK_LOOKBACK_DAYS

    /** Whether a clean week ended within the lookback of the last of the first [count] days. */
    private fun hasRecentCleanWeek(days: List<ScoredDay>, count: Int): Boolean =
        (maxOf(0, count - RankScoringConfig.CLEAN_WEEK_LOOKBACK_DAYS + 1) until count).any { endsCleanWeek(days, it) }

    /**
     * The rating after the first [count] of [days], from the minimum. Nothing moves until
     * [loggedDaysCount] reaches the provisional threshold; then every day counts, the earliest
     * included (BUG-023). [onDay] gets the change each day actually made, and whether the
     * clean-week rule held it back.
     */
    private fun replayRating(
        days: List<ScoredDay>,
        count: Int,
        loggedDaysCount: Int,
        onDay: (day: ScoredDay, ratingBefore: Int, rrDelta: Int, rating: Int, heldByCleanWeek: Boolean) -> Unit = { _, _, _, _, _ -> }
    ): Int {
        val isProvisional = loggedDaysCount < RankScoringConfig.PROVISIONAL_DAYS_THRESHOLD
        var rating = RankScoringConfig.MIN_RATING
        var lastCleanWeekEnd = Int.MIN_VALUE / 2
        for (index in 0 until count) {
            val day = days[index]
            if (endsCleanWeek(days, index)) lastCleanWeekEnd = index
            val ratingBefore = rating
            var held = false
            if (!isProvisional) {
                val rrDelta = if (day.isLogged) RankScoringConfig.rrForDay(day.scores!!.dayScore, rating) else RankScoringConfig.MISSED_DAY_RR
                rating = RankScoringConfig.applyDelta(rating, rrDelta, hasRecentCleanWeek = cleanWeekWithin(lastCleanWeekEnd, index))
                held = rating < (ratingBefore + rrDelta).coerceIn(RankScoringConfig.MIN_RATING, RankScoringConfig.MAX_RATING)
            }
            onDay(day, ratingBefore, rating - ratingBefore, rating, held)
        }
        return rating
    }

    private companion object {
        /** Earlier than any diary entry, so the query returns every logged day. */
        val HISTORY_START: LocalDate = LocalDate.of(1970, 1, 1)

        /** How long the diary must stay unchanged before a change is replayed. */
        const val DIARY_SETTLE_MS = 400L

        private const val TAG = "RankRepository"
        private const val RETRY_DELAY_MS = 5_000L

        /**
         * A food from the built-in catalog: any positive id outside the custom food and recipe
         * ranges. Catalog ids are large hashes, not the 1-99,999,999 of older versions.
         */
        fun isCatalogFoodId(foodId: Long): Boolean =
            foodId > 0 && !(foodId >= FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET && foodId < Recipe.FOOD_ID_RANGE_END)

        const val MIN_DAY_CHECK_MS = 1_000L
        const val MAX_DAY_CHECK_MS = 60 * 60 * 1_000L
    }
}
