package com.macrobase.app.data.cache

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingCommand
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import java.time.LocalDate

/**
 * Statistics and calendar figures kept ready for the screens.
 *
 * Each range or month asked for is worked out once and shared by every screen that shows it. It is
 * kept up to date in the background until [KEEP_WARM_MS] after the last screen showing it closes:
 * a change to the diary, weight or goals works it out again on the calculation lane (the
 * repositories settle a burst of changes into one calculation). Going back to it shows it at once.
 *
 * The figures are also saved, so after the app starts they show at once while the live figures
 * are worked out and replace them. A saved copy is dropped as soon as the data behind it changes
 * (see [onDiaryChanged] and the others); figures kept up to date save themselves again, so a saved
 * copy lags a change only for the moment it takes to work the figures out again.
 */
class FiguresCache(
    private val scope: CoroutineScope,
    private val store: SnapshotStore?,
    /** How many ranges and months are kept at once; the least recently asked for one is let go. */
    private val maxLive: Int = 24
) {
    private class Live(val flow: SharedFlow<*>, val job: Job, val watching: Watching)

    /** WhileSubscribed, remembering how many screens watch the figures, so watched ones are never let go. */
    private class Watching(private val delegate: SharingStarted) : SharingStarted {
        @Volatile
        var count: StateFlow<Int>? = null

        override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> {
            count = subscriptionCount
            return delegate.command(subscriptionCount)
        }

        val isWatched: Boolean get() = (count?.value ?: 0) > 0
    }

    // Access order, so the least recently asked for range or month comes first
    private val live = LinkedHashMap<String, Live>(16, 0.75f, true)

    /**
     * Lets go of the least recently asked for figures no screen watches, until at most [maxLive]
     * are kept. Watched ones are kept even past the limit, until no screen watches them.
     */
    private fun trim() {
        val entries = live.entries.iterator()
        while (live.size > maxLive && entries.hasNext()) {
            val entry = entries.next()
            if (!entry.value.watching.isWatched) {
                entry.value.job.cancel()
                entries.remove()
            }
        }
    }

    /** The figures under [key]: the saved copy first, if any, then the live figures from [source]. */
    @Suppress("UNCHECKED_CAST")
    fun <T> figures(key: String, codec: SnapshotCodec<T>, source: () -> Flow<T>): Flow<T> = synchronized(live) {
        (live[key]?.flow as SharedFlow<T>?) ?: run {
            val job = SupervisorJob(scope.coroutineContext[Job])
            val watching = Watching(SharingStarted.WhileSubscribed(KEEP_WARM_MS))
            val shared = flow {
                store?.read(key, codec)?.let { emit(it.value) }
                emitAll(source().onEach { value -> store?.write(key, "", value, codec) })
            }
                .distinctUntilChanged()
                .shareIn(CoroutineScope(scope.coroutineContext + job), watching, replay = 1)
            live[key] = Live(shared, job, watching)
            trim()
            shared
        }
    }

    /**
     * The diary changed: the saved calendar months, consistency and averages are dropped. Figures
     * being kept up to date save themselves again once worked out; the others are worked out when
     * next shown.
     */
    fun onDiaryChanged() {
        store?.delete { key -> DIARY_KINDS.any { key.startsWith(it) } }
    }

    /** The goals changed: everything scored against them is dropped, as for a diary change. */
    fun onGoalsChanged() = onDiaryChanged()

    /** The weight log changed: the saved weight figures are dropped. */
    fun onWeightChanged() {
        store?.delete { key -> key.startsWith(WEIGHT_KIND) }
    }

    companion object {
        /** How long figures are kept up to date after the last screen showing them closes. */
        const val KEEP_WARM_MS = 10 * 60 * 1000L

        /** The rank's snapshot, kept apart: it carries its own check of the data it was worked out from. */
        const val RANK_SNAPSHOT_KEY = "rank"

        private const val WEIGHT_KIND = "weight_"
        private val DIARY_KINDS = listOf("consistency_", "averages_", "calendar_")

        fun consistencyKey(start: LocalDate, end: LocalDate) = "consistency_${start}_$end"
        fun averagesKey(start: LocalDate, end: LocalDate) = "averages_${start}_$end"
        fun weightKey(start: LocalDate, end: LocalDate) = "$WEIGHT_KIND${start}_$end"
        fun calendarKey(year: Int, month: Int) = "calendar_${year}_$month"
    }
}

/** The statistics, served from [cache]. Everything not cached goes straight to [inner]. */
class CachedStatisticsRepository(
    private val inner: StatisticsRepository,
    private val cache: FiguresCache
) : StatisticsRepository by inner {

    override fun observeNutritionConsistency(startDate: LocalDate, endDate: LocalDate): Flow<ConsistencyStatistics> =
        cache.figures(FiguresCache.consistencyKey(startDate, endDate), ConsistencyCodec) {
            inner.observeNutritionConsistency(startDate, endDate)
        }

    override fun observeMacroAverages(startDate: LocalDate, endDate: LocalDate): Flow<MacroAveragesStatistics> =
        cache.figures(FiguresCache.averagesKey(startDate, endDate), MacroAveragesCodec) {
            inner.observeMacroAverages(startDate, endDate)
        }

    override fun observeWeightStatistics(startDate: LocalDate, endDate: LocalDate): Flow<WeightStatistics> =
        cache.figures(FiguresCache.weightKey(startDate, endDate), WeightStatisticsCodec) {
            inner.observeWeightStatistics(startDate, endDate)
        }
}

/** The diary, with calendar months served from [cache]. Everything else goes straight to [inner]. */
class CachedDiaryRepository(
    private val inner: DiaryRepository,
    private val cache: FiguresCache
) : DiaryRepository by inner {

    override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> =
        cache.figures(FiguresCache.calendarKey(year, month), CalendarMonthCodec) {
            inner.observeMonthlyAdherence(year, month)
        }
}
