package com.macrobase.app

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
import com.macrobase.app.feature.weight.WeightMetrics
import com.macrobase.app.feature.weight.WeightTimeInterval
import com.macrobase.app.feature.weight.WeightUiState
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
