package com.macrobase.app.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Where the app's heavier calculations run: the rank replay, statistics, calendar months and the
 * diary totals behind Home. Off the main thread, so drawing never waits for them, and on at most
 * two threads, so a burst of work queues up instead of loading every core at once.
 */
object CalculationLane {
    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /**
     * The rank replay, the heaviest job, on a thread of its own: two replays at once (the Rank tab
     * and Stats' rank history) wait for each other instead of taking the lane from Home's totals
     * and the widget.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val replay: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** How long the data must stay unchanged before figures are worked out again. */
    const val SETTLE_MS = 400L
}

/**
 * The input of a calculation on [dispatcher], settled: the first value at once, then a burst of
 * changes (several foods logged or deleted, a meal moved) as one value once it settles, so the
 * calculation after it runs once per burst. Unsettled where collected when [dispatcher] is null,
 * as in tests.
 */
fun <T> Flow<T>.settledOnLane(dispatcher: CoroutineDispatcher?): Flow<T> =
    if (dispatcher == null) this else settleAfterFirst(CalculationLane.SETTLE_MS)

/** This flow's work on [dispatcher]; where it is collected when [dispatcher] is null, as in tests. */
fun <T> Flow<T>.onLane(dispatcher: CoroutineDispatcher?): Flow<T> =
    if (dispatcher == null) this else flowOn(dispatcher)

/**
 * The first value at once, then a later value only once no newer one has come for [quietMs]. A
 * burst of changes (several foods logged or deleted, a meal moved, goals edited) then costs one
 * recalculation instead of one each.
 */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.settleAfterFirst(quietMs: Long): Flow<T> = flow {
    var first = true
    emitAll(
        this@settleAfterFirst.debounce {
            if (first) {
                first = false
                0L
            } else {
                quietMs
            }
        }
    )
}
