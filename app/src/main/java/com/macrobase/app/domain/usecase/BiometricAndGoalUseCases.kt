package com.macrobase.app.domain.usecase

import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import com.macrobase.app.domain.repository.WaterRepository
import com.macrobase.app.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class GetWeightHistoryUseCase(
    private val weightRepository: WeightRepository
) {
    operator fun invoke(): Flow<List<WeightEntry>> {
        return weightRepository.observeWeightHistory()
    }

    operator fun invoke(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>> {
        return weightRepository.observeWeightRange(startDate, endDate)
    }

    suspend fun logWeight(date: LocalDate, weightKg: Double, note: String? = null): Long {
        require(weightKg in 20.0..500.0) { "Weight must be between 20.0 kg and 500.0 kg" }
        return weightRepository.addWeightEntry(WeightEntry(date = date, weightKg = weightKg, note = note))
    }
}

class GetWeightForDateUseCase(
    private val weightRepository: WeightRepository
) {
    operator fun invoke(date: LocalDate): Flow<WeightEntry?> {
        return weightRepository.observeWeightForDate(date)
    }

    suspend fun snapshot(date: LocalDate): WeightEntry? {
        return weightRepository.getWeightForDate(date)
    }
}

class AddWeightEntryUseCase(
    private val weightRepository: WeightRepository
) {
    suspend operator fun invoke(date: LocalDate, weightKg: Double, note: String? = null): Long {
        require(weightKg in 20.0..500.0) { "Weight must be between 20.0 kg and 500.0 kg" }
        return weightRepository.addWeightEntry(WeightEntry(date = date, weightKg = weightKg, note = note))
    }
}

class DeleteWeightEntryUseCase(
    private val weightRepository: WeightRepository
) {
    suspend operator fun invoke(entryId: Long) {
        weightRepository.deleteWeightEntry(entryId)
    }

    suspend operator fun invoke(date: LocalDate) {
        weightRepository.deleteWeightEntryForDate(date)
    }
}

class GetWaterForDateUseCase(
    private val waterRepository: WaterRepository
) {
    operator fun invoke(date: LocalDate): Flow<Double> {
        return waterRepository.observeWaterTotalForDate(date)
    }

    suspend fun addWater(date: LocalDate, amountMl: Double): Long {
        require(amountMl > 0) { "Water amount must be greater than 0" }
        return waterRepository.addWaterEntry(WaterEntry(date = date, amountMl = amountMl))
    }
}

class GetWaterEntriesUseCase(
    private val waterRepository: WaterRepository
) {
    operator fun invoke(date: LocalDate): Flow<List<WaterEntry>> {
        return waterRepository.observeWaterEntriesForDate(date)
    }
}

class AddWaterEntryUseCase(
    private val waterRepository: WaterRepository
) {
    suspend operator fun invoke(date: LocalDate, amountMl: Double): Long {
        require(amountMl > 0) { "Water amount must be greater than 0" }
        return waterRepository.addWaterEntry(WaterEntry(date = date, amountMl = amountMl))
    }
}

class UpdateWaterEntryUseCase(
    private val waterRepository: WaterRepository
) {
    suspend operator fun invoke(entry: WaterEntry) {
        require(entry.amountMl > 0) { "Water amount must be greater than 0" }
        waterRepository.updateWaterEntry(entry)
    }
}

class DeleteWaterEntryUseCase(
    private val waterRepository: WaterRepository
) {
    suspend operator fun invoke(entryId: Long) {
        waterRepository.deleteWaterEntry(entryId)
    }
}

class GetWaterHistoryUseCase(
    private val waterRepository: WaterRepository
) {
    operator fun invoke(startDate: LocalDate, endDate: LocalDate): Flow<List<WaterEntry>> {
        return waterRepository.observeWaterHistory(startDate, endDate)
    }
}

class GetGoalsUseCase(
    private val goalsRepository: GoalsRepository
) {
    operator fun invoke(): Flow<Goal> {
        return goalsRepository.observeGoals()
    }

    suspend fun snapshot(): Goal {
        return goalsRepository.getGoals()
    }
}

class UpdateGoalsUseCase(
    private val goalsRepository: GoalsRepository
) {
    suspend operator fun invoke(goal: Goal): Result<Unit> {
        if (!goal.isValidPercentageSum) {
            return Result.failure(IllegalArgumentException("Macro percentages must total exactly 100% (currently ${goal.totalPercentage}%)"))
        }
        if (goal.dailyCalorieGoal <= 0.0) {
            return Result.failure(IllegalArgumentException("Calorie goal must be greater than 0"))
        }
        goalsRepository.updateGoals(goal)
        return Result.success(Unit)
    }
}

class GetStatisticsUseCase(
    private val statisticsRepository: StatisticsRepository
) {
    fun observeNutritionConsistency(startDate: LocalDate, endDate: LocalDate): Flow<com.macrobase.app.domain.model.ConsistencyStatistics> {
        return statisticsRepository.observeNutritionConsistency(startDate, endDate)
    }

    suspend fun getNutritionConsistency(startDate: LocalDate, endDate: LocalDate): com.macrobase.app.domain.model.ConsistencyStatistics {
        return statisticsRepository.getNutritionConsistency(startDate, endDate)
    }

    fun observeMacroAverages(startDate: LocalDate, endDate: LocalDate): Flow<com.macrobase.app.domain.model.MacroAveragesStatistics> {
        return statisticsRepository.observeMacroAverages(startDate, endDate)
    }

    suspend fun getMacroAverages(startDate: LocalDate, endDate: LocalDate): com.macrobase.app.domain.model.MacroAveragesStatistics {
        return statisticsRepository.getMacroAverages(startDate, endDate)
    }

    fun observeWeightStatistics(startDate: LocalDate, endDate: LocalDate): Flow<WeightStatistics> {
        return statisticsRepository.observeWeightStatistics(startDate, endDate)
    }

    suspend fun getWeightStatistics(startDate: LocalDate, endDate: LocalDate): WeightStatistics {
        return statisticsRepository.getWeightStatistics(startDate, endDate)
    }
}
