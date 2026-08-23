package com.macrobase.app

import com.macrobase.app.domain.model.UnitConversions
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.WaterRepository
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWaterEntryUseCase
import com.macrobase.app.domain.usecase.GetWaterEntriesUseCase
import com.macrobase.app.domain.usecase.GetWaterForDateUseCase
import com.macrobase.app.domain.usecase.GetWaterHistoryUseCase
import com.macrobase.app.domain.usecase.UpdateWaterEntryUseCase
import com.macrobase.app.feature.water.WaterUiState
import com.macrobase.app.feature.water.WaterViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Comprehensive Unit Test Suite for Phase 10: Water Hydration Tracking.
 */
class WaterTrackingUnitTests {

    private lateinit var fakeWaterRepository: FakeWaterRepository
    private lateinit var fakePreferencesRepository: FakeWaterPreferencesRepository

    private lateinit var getWaterForDateUseCase: GetWaterForDateUseCase
    private lateinit var getWaterEntriesUseCase: GetWaterEntriesUseCase
    private lateinit var addWaterEntryUseCase: AddWaterEntryUseCase
    private lateinit var updateWaterEntryUseCase: UpdateWaterEntryUseCase
    private lateinit var deleteWaterEntryUseCase: DeleteWaterEntryUseCase
    private lateinit var getWaterHistoryUseCase: GetWaterHistoryUseCase

    @Before
    fun setUp() {
        fakeWaterRepository = FakeWaterRepository()
        fakePreferencesRepository = FakeWaterPreferencesRepository()

        getWaterForDateUseCase = GetWaterForDateUseCase(fakeWaterRepository)
        getWaterEntriesUseCase = GetWaterEntriesUseCase(fakeWaterRepository)
        addWaterEntryUseCase = AddWaterEntryUseCase(fakeWaterRepository)
        updateWaterEntryUseCase = UpdateWaterEntryUseCase(fakeWaterRepository)
        deleteWaterEntryUseCase = DeleteWaterEntryUseCase(fakeWaterRepository)
        getWaterHistoryUseCase = GetWaterHistoryUseCase(fakeWaterRepository)
    }

    @Test
    fun addWaterEntry_persistsAndCalculatesDailyTotal() = runBlocking {
        val today = LocalDate.of(2026, 8, 19)

        // Initial total is 0.0
        val initialTotal = getWaterForDateUseCase(today).first()
        assertEquals(0.0, initialTotal, 0.001)

        // Add 250 mL
        addWaterEntryUseCase(today, 250.0)
        val totalAfter250 = getWaterForDateUseCase(today).first()
        assertEquals(250.0, totalAfter250, 0.001)

        // Add 500 mL
        addWaterEntryUseCase(today, 500.0)
        val totalAfter750 = getWaterForDateUseCase(today).first()
        assertEquals(750.0, totalAfter750, 0.001)

        // Verify entries list
        val entries = getWaterEntriesUseCase(today).first()
        assertEquals(2, entries.size)
        assertEquals(250.0, entries[0].amountMl, 0.001)
        assertEquals(500.0, entries[1].amountMl, 0.001)
    }

    @Test
    fun updateWaterEntry_modifiesExistingEntryAmount() = runBlocking {
        val today = LocalDate.of(2026, 8, 19)
        val id = addWaterEntryUseCase(today, 750.0)

        val entriesBefore = getWaterEntriesUseCase(today).first()
        val entry = entriesBefore.first { it.id == id }

        // Update from 750 to 500 mL
        updateWaterEntryUseCase(entry.copy(amountMl = 500.0))

        val entriesAfter = getWaterEntriesUseCase(today).first()
        assertEquals(1, entriesAfter.size)
        assertEquals(500.0, entriesAfter.first().amountMl, 0.001)

        val total = getWaterForDateUseCase(today).first()
        assertEquals(500.0, total, 0.001)
    }

    @Test
    fun deleteWaterEntry_removesEntryAndUpdatesDailyTotal() = runBlocking {
        val today = LocalDate.of(2026, 8, 19)
        val id1 = addWaterEntryUseCase(today, 250.0)
        val id2 = addWaterEntryUseCase(today, 500.0)

        assertEquals(750.0, getWaterForDateUseCase(today).first(), 0.001)

        // Delete the 250 mL entry
        deleteWaterEntryUseCase(id1)

        val entriesAfter = getWaterEntriesUseCase(today).first()
        assertEquals(1, entriesAfter.size)
        assertEquals(id2, entriesAfter.first().id)
        assertEquals(500.0, getWaterForDateUseCase(today).first(), 0.001)
    }

    @Test
    fun historicalDateIsolation_keepsEachDateIndependent() = runBlocking {
        val dateAug18 = LocalDate.of(2026, 8, 18)
        val dateAug19 = LocalDate.of(2026, 8, 19)

        // Log 500 mL on August 18
        addWaterEntryUseCase(dateAug18, 500.0)

        // August 18 should be 500 mL, August 19 should be 0 mL
        assertEquals(500.0, getWaterForDateUseCase(dateAug18).first(), 0.001)
        assertEquals(0.0, getWaterForDateUseCase(dateAug19).first(), 0.001)

        // Log 750 mL on August 19
        addWaterEntryUseCase(dateAug19, 750.0)

        // August 18 remains 500 mL, August 19 becomes 750 mL
        assertEquals(500.0, getWaterForDateUseCase(dateAug18).first(), 0.001)
        assertEquals(750.0, getWaterForDateUseCase(dateAug19).first(), 0.001)
    }

    @Test
    fun waterGoalCalculations_andProgressRatio() {
        // Under target: 1750 / 2500 = 70%
        val stateUnder = WaterUiState(
            totalIntakeMl = 1750.0,
            dailyGoalMl = 2500.0
        )
        assertEquals(0.70f, stateUnder.progressRatio, 0.001f)
        assertEquals(70, stateUnder.progressPercentage)
        assertEquals(750.0, stateUnder.remainingMl, 0.001)
        assertFalse(stateUnder.isGoalReached)

        // Exact target: 2500 / 2500 = 100%
        val stateExact = WaterUiState(
            totalIntakeMl = 2500.0,
            dailyGoalMl = 2500.0
        )
        assertEquals(1.0f, stateExact.progressRatio, 0.001f)
        assertEquals(100, stateExact.progressPercentage)
        assertEquals(0.0, stateExact.remainingMl, 0.001)
        assertTrue(stateExact.isGoalReached)

        // Over target: 3000 / 2500 = 120%
        val stateOver = WaterUiState(
            totalIntakeMl = 3000.0,
            dailyGoalMl = 2500.0
        )
        assertEquals(1.0f, stateOver.progressRatio, 0.001f) // Clamped to 1.0 for UI progress bar
        assertEquals(120, stateOver.progressPercentage)     // Unclamped for badge text
        assertEquals(0.0, stateOver.remainingMl, 0.001)
        assertTrue(stateOver.isGoalReached)

        // Zero goal / invalid edge case
        val stateZero = WaterUiState(
            totalIntakeMl = 500.0,
            dailyGoalMl = 0.0
        )
        assertEquals(0f, stateZero.progressRatio, 0.001f)
        assertEquals(0, stateZero.progressPercentage)
        assertFalse(stateZero.isGoalReached)
    }

    @Test
    fun goalReactivity_updatesCalculationsWhenGoalChanges() = runBlocking {
        val today = LocalDate.of(2026, 8, 19)
        addWaterEntryUseCase(today, 1000.0)

        // Initial goal: 2500 mL -> 1000 / 2500 = 40%
        val initialPrefs = fakePreferencesRepository.getPreferences()
        assertEquals(2500.0, initialPrefs.dailyWaterGoalMl, 0.001)
        val initialPercentage = ((1000.0 / initialPrefs.dailyWaterGoalMl) * 100.0).toInt()
        assertEquals(40, initialPercentage)

        // User changes goal to 2000 mL -> 1000 / 2000 = 50%
        fakePreferencesRepository.updatePreferences(initialPrefs.copy(dailyWaterGoalMl = 2000.0))
        val updatedPrefs = fakePreferencesRepository.getPreferences()
        assertEquals(2000.0, updatedPrefs.dailyWaterGoalMl, 0.001)
        val updatedPercentage = ((1000.0 / updatedPrefs.dailyWaterGoalMl) * 100.0).toInt()
        assertEquals(50, updatedPercentage)
    }

    @Test
    fun unitConversions_reversibleBetweenMetricAndImperial() {
        val ml = 500.0
        val flOz = UnitConversions.mlToFlOz(ml)
        assertEquals(16.907, flOz, 0.01)

        val backToMl = UnitConversions.flOzToMl(flOz)
        assertEquals(ml, backToMl, 0.01)

        val standardGoalFlOz = UnitConversions.mlToFlOz(2500.0)
        assertEquals(84.535, standardGoalFlOz, 0.01)
    }

    @Test
    fun inputValidation_rejectsNonPositiveWaterAmounts() = runBlocking {
        val today = LocalDate.of(2026, 8, 19)

        var thrown = false
        try {
            addWaterEntryUseCase(today, 0.0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Should throw on 0 mL", thrown)

        thrown = false
        try {
            addWaterEntryUseCase(today, -100.0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Should throw on negative mL", thrown)
    }
}

/**
 * In-memory fake implementation of WaterRepository for unit tests.
 */
private class FakeWaterRepository : WaterRepository {
    private val entriesFlow = MutableStateFlow<Map<Long, WaterEntry>>(emptyMap())

    override suspend fun addWaterEntry(entry: WaterEntry): Long {
        val id = if (entry.id > 0) entry.id else (entriesFlow.value.keys.maxOrNull() ?: 0L) + 1L
        val persisted = entry.copy(id = id)
        entriesFlow.update { it + (id to persisted) }
        return id
    }

    override suspend fun updateWaterEntry(entry: WaterEntry) {
        entriesFlow.update { it + (entry.id to entry) }
    }

    override suspend fun deleteWaterEntry(entryId: Long) {
        entriesFlow.update { it - entryId }
    }

    override fun observeWaterTotalForDate(date: LocalDate): Flow<Double> {
        return entriesFlow.asStateFlow().map { map ->
            map.values.filter { it.date == date }.sumOf { it.amountMl }
        }
    }

    override suspend fun getWaterTotalForDate(date: LocalDate): Double {
        return observeWaterTotalForDate(date).first()
    }

    override fun observeWaterEntriesForDate(date: LocalDate): Flow<List<WaterEntry>> {
        return entriesFlow.asStateFlow().map { map ->
            map.values.filter { it.date == date }.sortedBy { it.loggedAt }
        }
    }

    override suspend fun getWaterEntriesForDate(date: LocalDate): List<WaterEntry> {
        return observeWaterEntriesForDate(date).first()
    }

    override fun observeWaterHistory(startDate: LocalDate, endDate: LocalDate): Flow<List<WaterEntry>> {
        return entriesFlow.asStateFlow().map { map ->
            map.values.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }
        }
    }

    override suspend fun getWaterHistory(startDate: LocalDate, endDate: LocalDate): List<WaterEntry> {
        return observeWaterHistory(startDate, endDate).first()
    }
}

/**
 * In-memory fake implementation of PreferencesRepository for water goal unit tests.
 */
private class FakeWaterPreferencesRepository : PreferencesRepository {
    private val prefsFlow = MutableStateFlow(UserPreferences(dailyWaterGoalMl = 2500.0))

    override suspend fun getPreferences(): UserPreferences = prefsFlow.value
    override fun observePreferences(): Flow<UserPreferences> = prefsFlow.asStateFlow()
    override suspend fun updatePreferences(preferences: UserPreferences) {
        prefsFlow.value = preferences
    }
}
