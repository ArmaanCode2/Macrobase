package com.macrobase.app

import androidx.compose.ui.geometry.Offset
import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.usecase.AddWeightEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWeightEntryUseCase
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightHistoryUseCase
import com.macrobase.app.feature.weight.WeightGraphHelper
import com.macrobase.app.feature.weight.WeightMetrics
import com.macrobase.app.feature.weight.WeightTimeInterval
import com.macrobase.app.feature.weight.WeightUiState
import com.macrobase.app.feature.weight.formatWeightValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit Test Suite for Phase 11: Weight Tracking & Graphs.
 */
class WeightTrackingUnitTests {

    private lateinit var fakeWeightRepository: FakeWeightRepository
    private lateinit var fakePreferencesRepository: FakeWeightPreferencesRepository

    private lateinit var getWeightHistoryUseCase: GetWeightHistoryUseCase
    private lateinit var getWeightForDateUseCase: GetWeightForDateUseCase
    private lateinit var addWeightEntryUseCase: AddWeightEntryUseCase
    private lateinit var deleteWeightEntryUseCase: DeleteWeightEntryUseCase

    @Before
    fun setUp() {
        fakeWeightRepository = FakeWeightRepository()
        fakePreferencesRepository = FakeWeightPreferencesRepository()

        getWeightHistoryUseCase = GetWeightHistoryUseCase(fakeWeightRepository)
        getWeightForDateUseCase = GetWeightForDateUseCase(fakeWeightRepository)
        addWeightEntryUseCase = AddWeightEntryUseCase(fakeWeightRepository)
        deleteWeightEntryUseCase = DeleteWeightEntryUseCase(fakeWeightRepository)
    }

    @Test
    fun addWeightEntry_persistsAndRetrievesByDate() = runBlocking {
        val date = LocalDate.of(2026, 8, 19)

        // Initial check: no weight
        val initial = getWeightForDateUseCase(date).first()
        assertNull(initial)

        // Log 82.5 kg
        addWeightEntryUseCase(date, 82.5, "Morning weigh-in")

        val entry = getWeightForDateUseCase(date).first()
        assertNotNull(entry)
        assertEquals(82.5, entry!!.weightKg, 0.001)
        assertEquals("Morning weigh-in", entry.note)
    }

    @Test
    fun updateWeightEntry_replacesSameDayWeight() = runBlocking {
        val date = LocalDate.of(2026, 8, 19)

        // Log 82.5 kg
        addWeightEntryUseCase(date, 82.5)
        assertEquals(82.5, getWeightForDateUseCase(date).first()!!.weightKg, 0.001)

        // Re-log / Edit to 81.8 kg on same date
        addWeightEntryUseCase(date, 81.8)
        val updated = getWeightForDateUseCase(date).first()!!
        assertEquals(81.8, updated.weightKg, 0.001)

        // Total count for all history should be 1
        val all = getWeightHistoryUseCase().first()
        assertEquals(1, all.size)
    }

    @Test
    fun deleteWeightEntry_removesEntry() = runBlocking {
        val date = LocalDate.of(2026, 8, 19)
        addWeightEntryUseCase(date, 80.0)
        assertNotNull(getWeightForDateUseCase(date).first())

        deleteWeightEntryUseCase(date)
        assertNull(getWeightForDateUseCase(date).first())
    }

    @Test
    fun historicalDateIsolation_keepsDaysIndependent() = runBlocking {
        val aug18 = LocalDate.of(2026, 8, 18)
        val aug19 = LocalDate.of(2026, 8, 19)

        addWeightEntryUseCase(aug18, 83.0)
        addWeightEntryUseCase(aug19, 82.4)

        assertEquals(83.0, getWeightForDateUseCase(aug18).first()!!.weightKg, 0.001)
        assertEquals(82.4, getWeightForDateUseCase(aug19).first()!!.weightKg, 0.001)
    }

    @Test
    fun deterministicGraphDataset_calculatesAccurateMetrics() = runBlocking {
        // Deterministic Prompt Test:
        // Day 1: 83.6 kg
        // Day 2: 82.9 kg
        // Day 3: 82.2 kg
        // Day 4: 81.7 kg
        // Day 5: 80.9 kg
        val day1 = LocalDate.of(2026, 8, 1)
        val day2 = LocalDate.of(2026, 8, 2)
        val day3 = LocalDate.of(2026, 8, 3)
        val day4 = LocalDate.of(2026, 8, 4)
        val day5 = LocalDate.of(2026, 8, 5)

        addWeightEntryUseCase(day1, 83.6)
        addWeightEntryUseCase(day2, 82.9)
        addWeightEntryUseCase(day3, 82.2)
        addWeightEntryUseCase(day4, 81.7)
        addWeightEntryUseCase(day5, 80.9)

        val entries = getWeightHistoryUseCase(day1, day5).first().sortedBy { it.date }
        assertEquals(5, entries.size)

        val start = entries.first().weightKg
        val current = entries.last().weightKg
        val delta = current - start
        val percent = (delta / start) * 100.0

        assertEquals(83.6, start, 0.001)
        assertEquals(80.9, current, 0.001)
        assertEquals(-2.7, delta, 0.001)
        assertEquals(-3.23, percent, 0.01)
    }

    @Test
    fun rangeFiltering_filtersCorrectDateRange() = runBlocking {
        val start = LocalDate.of(2026, 1, 1)
        val mid = LocalDate.of(2026, 6, 1)
        val end = LocalDate.of(2026, 12, 1)

        addWeightEntryUseCase(start, 90.0)
        addWeightEntryUseCase(mid, 85.0)
        addWeightEntryUseCase(end, 80.0)

        // Query only May to July (should only return mid)
        val midRange = getWeightHistoryUseCase(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 1)).first()
        assertEquals(1, midRange.size)
        assertEquals(85.0, midRange.first().weightKg, 0.001)
    }

    @Test
    fun emptyAndSingleState_handlesGraphThreshold() {
        val state0 = WeightUiState(entries = emptyList())
        assertFalse("0 entries should not have sufficient data for graph", state0.hasSufficientDataForGraph)

        val state1 = WeightUiState(entries = listOf(WeightEntry(date = LocalDate.now(), weightKg = 80.0)))
        assertFalse("1 entry should not have sufficient data for graph", state1.hasSufficientDataForGraph)

        val state2 = WeightUiState(entries = listOf(
            WeightEntry(date = LocalDate.now().minusDays(1), weightKg = 81.0),
            WeightEntry(date = LocalDate.now(), weightKg = 80.0)
        ))
        assertTrue("2 entries should have sufficient data for graph", state2.hasSufficientDataForGraph)
    }

    @Test
    fun unitConversions_preservesValueReversibility() {
        val kg = 82.5
        val lbs = UnitConversions.kgToLbs(kg)
        assertEquals(181.881, lbs, 0.01)

        val backToKg = UnitConversions.lbsToKg(lbs)
        assertEquals(kg, backToKg, 0.001)
    }

    @Test
    fun inputValidation_rejectsInvalidWeights() = runBlocking {
        val today = LocalDate.of(2026, 8, 19)

        var thrown = false
        try {
            addWeightEntryUseCase(today, 10.0) // Below 20 kg
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Should reject < 20 kg", thrown)

        thrown = false
        try {
            addWeightEntryUseCase(today, 600.0) // Above 500 kg
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Should reject > 500 kg", thrown)
    }

    // =========================================================================
    // WEIGHT GRAPH POINT TAP & TOOLTIP INTERACTION TESTS
    // =========================================================================

    private val sampleGraphEntries = listOf(
        WeightEntry(id = 1, date = LocalDate.of(2026, 6, 24), weightKg = 81.6),
        WeightEntry(id = 2, date = LocalDate.of(2026, 7, 1), weightKg = 81.0),
        WeightEntry(id = 3, date = LocalDate.of(2026, 7, 10), weightKg = 80.4)
    )

    private val sampleWeights = sampleGraphEntries.map { it.weightKg }
    private val sampleOffsets = WeightGraphHelper.calculatePointOffsets(
        entries = sampleGraphEntries,
        weights = sampleWeights,
        minW = 79.0,
        rangeW = 4.0,
        width = 400f,
        height = 200f,
        paddingLeft = 40f,
        paddingRight = 16f,
        paddingTop = 12f,
        paddingBottom = 24f
    )

    @Test
    fun weightGraph_tapFirstPoint_showsCorrectDateAndWeight() {
        val firstPoint = sampleOffsets[0]
        // Tap near first point (within 10px)
        val tapOffset = Offset(firstPoint.x + 5f, firstPoint.y - 5f)
        val selectedIdx = WeightGraphHelper.findNearestPointIndex(tapOffset, sampleOffsets, 32f)

        assertNotNull(selectedIdx)
        assertEquals(0, selectedIdx)

        val entry = sampleGraphEntries[selectedIdx!!]
        assertEquals("Jun 24", WeightGraphHelper.formatTooltipDate(entry.date))
        assertEquals("81.6 kg", formatWeightValue(entry.weightKg, isImperial = false))
    }

    @Test
    fun weightGraph_tapMiddlePoint_showsCorrectDateAndWeight() {
        val midPoint = sampleOffsets[1]
        val tapOffset = Offset(midPoint.x - 2f, midPoint.y + 3f)
        val selectedIdx = WeightGraphHelper.findNearestPointIndex(tapOffset, sampleOffsets, 32f)

        assertNotNull(selectedIdx)
        assertEquals(1, selectedIdx)

        val entry = sampleGraphEntries[selectedIdx!!]
        assertEquals("Jul 1", WeightGraphHelper.formatTooltipDate(entry.date))
        assertEquals("81.0 kg", formatWeightValue(entry.weightKg, isImperial = false))
    }

    @Test
    fun weightGraph_tapLastPoint_showsCorrectDateAndWeight() {
        val lastPoint = sampleOffsets[2]
        val tapOffset = Offset(lastPoint.x + 4f, lastPoint.y + 4f)
        val selectedIdx = WeightGraphHelper.findNearestPointIndex(tapOffset, sampleOffsets, 32f)

        assertNotNull(selectedIdx)
        assertEquals(2, selectedIdx)

        val entry = sampleGraphEntries[selectedIdx!!]
        assertEquals("Jul 10", WeightGraphHelper.formatTooltipDate(entry.date))
        assertEquals("80.4 kg", formatWeightValue(entry.weightKg, isImperial = false))
    }

    @Test
    fun weightGraph_tapOutsideThreshold_returnsNullAndDismissesTooltip() {
        // Tap far away in empty space
        val tapOffset = Offset(10f, 10f)
        val selectedIdx = WeightGraphHelper.findNearestPointIndex(tapOffset, sampleOffsets, 32f)

        assertNull("Tap far from any data point should return null", selectedIdx)
    }

    @Test
    fun weightGraph_multipleClosePoints_selectsNearestPoint() {
        val closePoints = listOf(
            Offset(100f, 100f),
            Offset(110f, 100f)
        )
        // Tap closer to first point
        val tap1 = Offset(102f, 100f)
        assertEquals(0, WeightGraphHelper.findNearestPointIndex(tap1, closePoints, 32f))

        // Tap closer to second point
        val tap2 = Offset(108f, 100f)
        assertEquals(1, WeightGraphHelper.findNearestPointIndex(tap2, closePoints, 32f))
    }

    @Test
    fun weightGraph_pointNearTop_positionsTooltipBelowPointWithoutClipping() {
        // Point very close to top of canvas (y = 5)
        val pointNearTop = Offset(200f, 5f)
        val bounds = WeightGraphHelper.calculateTooltipBounds(
            point = pointNearTop,
            tooltipWidth = 70f,
            tooltipHeight = 38f,
            canvasWidth = 400f,
            canvasHeight = 200f,
            marginPx = 4f
        )

        // Must flip below point (topY > pointNearTop.y) and stay within bounds
        assertTrue("Tooltip should flip below point when near top", bounds.topY >= pointNearTop.y)
        assertTrue("Tooltip top must be within canvas", bounds.topY >= 4f)
        assertTrue("Tooltip bottom must be within canvas", bounds.topY + bounds.height <= 200f)
    }

    @Test
    fun weightGraph_pointNearLeft_clampsTooltipInsideLeftMargin() {
        // Point on left edge (x = 5)
        val pointNearLeft = Offset(5f, 100f)
        val bounds = WeightGraphHelper.calculateTooltipBounds(
            point = pointNearLeft,
            tooltipWidth = 80f,
            tooltipHeight = 38f,
            canvasWidth = 400f,
            canvasHeight = 200f,
            marginPx = 4f
        )

        assertTrue("Tooltip left must not clip past left margin", bounds.leftX >= 4f)
    }

    @Test
    fun weightGraph_pointNearRight_clampsTooltipInsideRightMargin() {
        // Point on right edge (x = 395)
        val pointNearRight = Offset(395f, 100f)
        val bounds = WeightGraphHelper.calculateTooltipBounds(
            point = pointNearRight,
            tooltipWidth = 80f,
            tooltipHeight = 38f,
            canvasWidth = 400f,
            canvasHeight = 200f,
            marginPx = 4f
        )

        assertTrue("Tooltip right must not clip past right margin", bounds.leftX + bounds.width <= 396f)
    }

    @Test
    fun weightGraph_emptyOrSinglePoint_doesNotCrash() {
        val emptyOffsets = WeightGraphHelper.calculatePointOffsets(
            entries = emptyList(),
            weights = emptyList(),
            minW = 50.0,
            rangeW = 10.0,
            width = 400f,
            height = 200f,
            paddingLeft = 40f,
            paddingRight = 16f,
            paddingTop = 12f,
            paddingBottom = 24f
        )
        assertTrue(emptyOffsets.isEmpty())

        val result = WeightGraphHelper.findNearestPointIndex(Offset(100f, 100f), emptyOffsets, 32f)
        assertNull(result)
    }

    @Test
    fun weightGraph_imperialUnitFormatting_showsPoundsCorrectly() {
        val entry = WeightEntry(id = 1, date = LocalDate.of(2026, 6, 24), weightKg = 81.6)
        val formattedMetric = formatWeightValue(entry.weightKg, isImperial = false)
        val formattedImperial = formatWeightValue(entry.weightKg, isImperial = true)

        assertEquals("81.6 kg", formattedMetric)
        assertEquals("179.9 lb", formattedImperial)
    }
}

/**
 * In-memory fake implementation of WeightRepository for testing.
 */
private class FakeWeightRepository : WeightRepository {
    private val entriesFlow = MutableStateFlow<Map<Long, WeightEntry>>(emptyMap())

    override suspend fun addWeightEntry(entry: WeightEntry): Long {
        val id = if (entry.id > 0) entry.id else (entriesFlow.value.keys.maxOrNull() ?: 0L) + 1L
        val persisted = entry.copy(id = id)
        entriesFlow.update { it + (entry.date.toEpochDay() to persisted) }
        return id
    }

    override suspend fun updateWeightEntry(entry: WeightEntry) {
        addWeightEntry(entry)
    }

    override suspend fun deleteWeightEntry(entryId: Long) {
        entriesFlow.update { map -> map.filterValues { it.id != entryId } }
    }

    override suspend fun deleteWeightEntryForDate(date: LocalDate) {
        entriesFlow.update { it - date.toEpochDay() }
    }

    override fun observeWeightForDate(date: LocalDate): Flow<WeightEntry?> {
        return entriesFlow.asStateFlow().map { it[date.toEpochDay()] }
    }

    override suspend fun getWeightForDate(date: LocalDate): WeightEntry? {
        return observeWeightForDate(date).first()
    }

    override fun observeWeightHistory(): Flow<List<WeightEntry>> {
        return entriesFlow.asStateFlow().map { map ->
            map.values.sortedBy { it.date }
        }
    }

    override suspend fun getWeightHistory(): List<WeightEntry> {
        return observeWeightHistory().first()
    }

    override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>> {
        return entriesFlow.asStateFlow().map { map ->
            map.values.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }.sortedBy { it.date }
        }
    }

    override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate): List<WeightEntry> {
        return observeWeightRange(startDate, endDate).first()
    }
}

/**
 * In-memory fake implementation of PreferencesRepository for Weight tests.
 */
private class FakeWeightPreferencesRepository : PreferencesRepository {
    private val prefsFlow = MutableStateFlow(UserPreferences(targetWeightKg = 75.0))

    override suspend fun getPreferences(): UserPreferences = prefsFlow.value
    override fun observePreferences(): Flow<UserPreferences> = prefsFlow.asStateFlow()
    override suspend fun updatePreferences(preferences: UserPreferences) {
        prefsFlow.value = preferences
    }
}
