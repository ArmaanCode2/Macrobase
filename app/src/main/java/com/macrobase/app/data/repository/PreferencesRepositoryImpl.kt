package com.macrobase.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(
    name = "user_preferences",
    corruptionHandler = preferencesCorruptionHandler
)

class PreferencesRepositoryImpl(
    private val context: Context,
    private val store: DataStore<Preferences> = context.dataStore
) : PreferencesRepository {

    /** Every read goes through here, so an unreadable file means default settings, not a crash (BUG-053). */
    private val data = store.data.emptyOnIoError("profile")

    private object Keys {
        val FIRST_NAME = stringPreferencesKey("first_name")
        val LAST_NAME = stringPreferencesKey("last_name")
        val TIME_ZONE = stringPreferencesKey("time_zone")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val CURRENT_WEIGHT_KG = doublePreferencesKey("current_weight_kg")
        val TARGET_WEIGHT_KG = doublePreferencesKey("target_weight_kg")
        val HEIGHT_CM = doublePreferencesKey("height_cm")
        val WATER_GOAL_ML = doublePreferencesKey("water_goal_ml")
        val SEEN_RANK_RULES_VERSION = intPreferencesKey("seen_rank_rules_version")
        val SEEN_RANK_SEASON = longPreferencesKey("seen_rank_season_start")
    }

    override suspend fun getPreferences(): UserPreferences {
        return observePreferences().first()
    }

    override fun observePreferences(): Flow<UserPreferences> = preferencesFrom(data)

    // A backup holds the stored profile or fails, never defaults standing in for it (BUG-053)
    override suspend fun getPreferencesForBackup(): UserPreferences = preferencesFrom(store.data).first()

    /** The profile as [source]'s preferences hold it. */
    private fun preferencesFrom(source: Flow<Preferences>): Flow<UserPreferences> {
        return source.map { prefs ->
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
        store.edit { prefs ->
            prefs[Keys.FIRST_NAME] = preferences.firstName
            prefs[Keys.LAST_NAME] = preferences.lastName
            prefs[Keys.TIME_ZONE] = preferences.timeZone
            prefs[Keys.UNIT_SYSTEM] = preferences.unitSystem.name
            prefs[Keys.WATER_GOAL_ML] = preferences.dailyWaterGoalMl
            // Null means the user cleared the field, so the stored value goes too (BUG-028)
            setOrRemove(prefs, Keys.HEIGHT_CM, preferences.heightCm)
            setOrRemove(prefs, Keys.CURRENT_WEIGHT_KG, preferences.currentWeightKg)
            setOrRemove(prefs, Keys.TARGET_WEIGHT_KG, preferences.targetWeightKg)
        }
    }

    private fun setOrRemove(prefs: MutablePreferences, key: Preferences.Key<Double>, value: Double?) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }

    // The rank notices are not part of the profile: a backup import must still restore a profile
    // on a phone that has only dismissed them
    override suspend fun hasSavedPreferences(): Boolean =
        data.first().asMap().keys.any { it != Keys.SEEN_RANK_RULES_VERSION && it != Keys.SEEN_RANK_SEASON }

    override fun observeSeenRankRulesVersion(): Flow<Int> = data.map { it[Keys.SEEN_RANK_RULES_VERSION] ?: 0 }

    override suspend fun setSeenRankRulesVersion(version: Int) {
        store.edit { it[Keys.SEEN_RANK_RULES_VERSION] = version }
    }

    override fun observeSeenRankSeason(): Flow<Long> = data.map { it[Keys.SEEN_RANK_SEASON] ?: Long.MIN_VALUE }

    override suspend fun setSeenRankSeason(seasonStartEpochDay: Long) {
        store.edit { it[Keys.SEEN_RANK_SEASON] = seasonStartEpochDay }
    }
}
