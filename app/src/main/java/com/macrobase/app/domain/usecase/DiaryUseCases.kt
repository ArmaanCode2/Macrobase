package com.macrobase.app.domain.usecase

import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.repository.DiaryRepository
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Use case to observe real-time updates for a selected date's daily meal diary.
 */
class GetDailyDiaryUseCase(
    private val diaryRepository: DiaryRepository
) {
    operator fun invoke(date: LocalDate): Flow<DailyNutritionSummary> {
        return diaryRepository.observeDiaryForDate(date)
    }
}

/**
 * Use case to retrieve a snapshot of the daily nutrition summary for a date.
 */
class GetDailyNutritionSummaryUseCase(
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(date: LocalDate): DailyNutritionSummary {
        return diaryRepository.getDiaryForDate(date)
    }
}

/**
 * Use case to log a food item into the user diary for a specific meal and date.
 */
class LogFoodUseCase(
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(entry: DiaryEntry): Long {
        return diaryRepository.addEntry(entry)
    }
}

/**
 * Use case to update an existing diary entry (e.g. modify quantity or serving size).
 */
class UpdateDiaryEntryUseCase(
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(entry: DiaryEntry) {
        diaryRepository.updateEntry(entry)
    }
}

/**
 * Use case to retrieve a single diary entry by ID.
 */
class GetDiaryEntryUseCase(
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(entryId: Long): DiaryEntry? {
        return diaryRepository.getEntryById(entryId)
    }
}

/**
 * Use case to delete a diary entry by ID.
 */
class DeleteDiaryEntryUseCase(
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(entryId: Long) {
        diaryRepository.deleteEntry(entryId)
    }
}

/**
 * Use case to retrieve calendar adherence days for a given month and year.
 */
class GetCalendarAdherenceUseCase(
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(year: Int, month: Int): List<CalendarDaySummary> {
        return diaryRepository.getMonthlyAdherence(year, month)
    }

    fun observe(year: Int, month: Int): Flow<List<CalendarDaySummary>> {
        return diaryRepository.observeMonthlyAdherence(year, month)
    }
}
