package com.macrobase.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

class PreferencesRepositoryImpl(
    private val context: Context
) : PreferencesRepository {

    private object Keys {
        val FIRST_NAME = stringPreferencesKey("first_name")
        val LAST_NAME = stringPreferencesKey("last_name")
        val TIME_ZONE = stringPreferencesKey("time_zone")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val CURRENT_WEIGHT_KG = doublePreferencesKey("current_weight_kg")
        val TARGET_WEIGHT_KG = doublePreferencesKey("target_weight_kg")
        val HEIGHT_CM = doublePreferencesKey("height_cm")
        val WATER_GOAL_ML = doublePreferencesKey("water_goal_ml")
    }

    override suspend fun getPreferences(): UserPreferences {
        return observePreferences().first()
    }

    override fun observePreferences(): Flow<UserPreferences> {
        return context.dataStore.data.map { prefs ->
            val unitStr = prefs[Keys.UNIT_SYSTEM] ?: UnitSystem.METRIC.name
            UserPreferences(
                firstName = prefs[Keys.FIRST_NAME] ?: "",
                lastName = prefs[Keys.LAST_NAME] ?: "",
                timeZone = prefs[Keys.TIME_ZONE] ?: "UTC",
                unitSystem = try { UnitSystem.valueOf(unitStr) } catch (e: Exception) { UnitSystem.METRIC },
                dailyWaterGoalMl = prefs[Keys.WATER_GOAL_ML] ?: 2500.0,
                heightCm = prefs[Keys.HEIGHT_CM],
                currentWeightKg = prefs[Keys.CURRENT_WEIGHT_KG],
                targetWeightKg = prefs[Keys.TARGET_WEIGHT_KG]
            )
        }
    }

    override suspend fun updatePreferences(preferences: UserPreferences) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FIRST_NAME] = preferences.firstName
            prefs[Keys.LAST_NAME] = preferences.lastName
            prefs[Keys.TIME_ZONE] = preferences.timeZone
            prefs[Keys.UNIT_SYSTEM] = preferences.unitSystem.name
            prefs[Keys.WATER_GOAL_ML] = preferences.dailyWaterGoalMl
            preferences.heightCm?.let { prefs[Keys.HEIGHT_CM] = it }
            preferences.currentWeightKg?.let { prefs[Keys.CURRENT_WEIGHT_KG] = it }
            preferences.targetWeightKg?.let { prefs[Keys.TARGET_WEIGHT_KG] = it }
        }
    }
}
