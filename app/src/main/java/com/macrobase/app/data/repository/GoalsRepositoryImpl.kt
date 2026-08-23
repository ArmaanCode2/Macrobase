package com.macrobase.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.repository.GoalsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.goalsDataStore by preferencesDataStore(name = "user_goals")

/**
 * Concrete implementation of GoalsRepository managing user nutritional targets in DataStore.
 */
class GoalsRepositoryImpl(
    private val context: Context
) : GoalsRepository {

    private object Keys {
        val DAILY_CALORIE_GOAL = doublePreferencesKey("daily_calorie_goal")
        val CARB_PERCENTAGE = doublePreferencesKey("carb_percentage")
        val PROTEIN_PERCENTAGE = doublePreferencesKey("protein_percentage")
        val FAT_PERCENTAGE = doublePreferencesKey("fat_percentage")
    }

    override suspend fun getGoals(): Goal {
        return observeGoals().first()
    }

    override fun observeGoals(): Flow<Goal> {
        return context.goalsDataStore.data.map { prefs ->
            Goal(
                dailyCalorieGoal = prefs[Keys.DAILY_CALORIE_GOAL] ?: 2000.0,
                carbPercentage = prefs[Keys.CARB_PERCENTAGE] ?: 50.0,
                proteinPercentage = prefs[Keys.PROTEIN_PERCENTAGE] ?: 25.0,
                fatPercentage = prefs[Keys.FAT_PERCENTAGE] ?: 25.0
            )
        }
    }

    override suspend fun updateGoals(goals: Goal) {
        context.goalsDataStore.edit { prefs ->
            prefs[Keys.DAILY_CALORIE_GOAL] = goals.dailyCalorieGoal
            prefs[Keys.CARB_PERCENTAGE] = goals.carbPercentage
            prefs[Keys.PROTEIN_PERCENTAGE] = goals.proteinPercentage
            prefs[Keys.FAT_PERCENTAGE] = goals.fatPercentage
        }
    }
}
