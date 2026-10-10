package com.macrobase.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.GoalTransitionBackupDto
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.feature.widget.MacroBaseWidgetProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.goalsDataStore by preferencesDataStore(name = "user_goals")

/**
 * Concrete implementation of GoalsRepository managing user nutritional targets in DataStore.
 */
class GoalsRepositoryImpl(
    private val context: Context,
    private val store: DataStore<Preferences> = context.goalsDataStore,
    /** Today's date; tests move it to check how past days resolve. */
    private val clock: () -> LocalDate = { LocalDate.now() }
) : GoalsRepository {

    private object Keys {
        val DAILY_CALORIE_GOAL = doublePreferencesKey("daily_calorie_goal")
        val CARB_PERCENTAGE = doublePreferencesKey("carb_percentage")
        val PROTEIN_PERCENTAGE = doublePreferencesKey("protein_percentage")
        val FAT_PERCENTAGE = doublePreferencesKey("fat_percentage")

        // New Strategy Keys
        val FITNESS_GOAL = stringPreferencesKey("fitness_goal")
        val MAINTENANCE_CALORIES = doublePreferencesKey("maintenance_calories")
        val SCHEDULED_FITNESS_GOAL = stringPreferencesKey("scheduled_fitness_goal")
        val SCHEDULED_MAINTENANCE_CALORIES = doublePreferencesKey("scheduled_maintenance_calories")
        val SCHEDULED_EFFECTIVE_DATE = stringPreferencesKey("scheduled_effective_date")
        val TRANSITIONS_HISTORY = stringPreferencesKey("transitions_history")
    }

    private data class GoalTransitionRecord(
        val effectiveDate: String,
        val fitnessGoal: String,
        val maintenanceCalories: Double,
        val dailyCalorieGoal: Double,
        val carbPercentage: Double,
        val proteinPercentage: Double,
        val fatPercentage: Double
    )

    private fun serializeTransitions(records: List<GoalTransitionRecord>): String {
        return records.joinToString("\n") { r ->
            "${r.effectiveDate}|${r.fitnessGoal}|${r.maintenanceCalories}|${r.dailyCalorieGoal}|${r.carbPercentage}|${r.proteinPercentage}|${r.fatPercentage}"
        }
    }

    private fun parseTransitions(raw: String?): List<GoalTransitionRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.trim().split("|")
            if (parts.size >= 7) {
                try {
                    GoalTransitionRecord(
                        effectiveDate = parts[0],
                        fitnessGoal = parts[1],
                        maintenanceCalories = parts[2].toDouble(),
                        dailyCalorieGoal = parts[3].toDouble(),
                        carbPercentage = parts[4].toDouble(),
                        proteinPercentage = parts[5].toDouble(),
                        fatPercentage = parts[6].toDouble()
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    private suspend fun checkAndPromoteScheduledGoal() {
        val today = clock()
        store.edit { prefs ->
            promoteScheduledGoal(prefs, today)
            recordLiveGoalIfMissing(prefs, today)
        }
    }

    /** Makes a scheduled strategy change the active one once its date has come. */
    private fun promoteScheduledGoal(prefs: MutablePreferences, today: LocalDate) {
        val scheduledDateStr = prefs[Keys.SCHEDULED_EFFECTIVE_DATE]
        val scheduledGoalStr = prefs[Keys.SCHEDULED_FITNESS_GOAL]
        if (!scheduledDateStr.isNullOrBlank() && !scheduledGoalStr.isNullOrBlank()) {
            val scheduledDate = try { LocalDate.parse(scheduledDateStr) } catch (_: Exception) { null }
            if (scheduledDate != null && !today.isBefore(scheduledDate)) {
                prefs[Keys.FITNESS_GOAL] = scheduledGoalStr
                val scheduledMaint = prefs[Keys.SCHEDULED_MAINTENANCE_CALORIES]
                if (scheduledMaint != null) {
                    prefs[Keys.MAINTENANCE_CALORIES] = scheduledMaint
                }
                prefs.remove(Keys.SCHEDULED_FITNESS_GOAL)
                prefs.remove(Keys.SCHEDULED_MAINTENANCE_CALORIES)
                prefs.remove(Keys.SCHEDULED_EFFECTIVE_DATE)
            }
        }
    }

    /**
     * Older versions changed calorie and macro targets without a history record, so the record
     * in force today can hold old targets. Recording the live goal for today keeps today and
     * every later day scored against it once those days are past (BUG-024). Writes only when
     * the history and the live goal disagree.
     */
    private fun recordLiveGoalIfMissing(prefs: MutablePreferences, today: LocalDate) {
        val history = parseTransitions(prefs[Keys.TRANSITIONS_HISTORY])
        if (history.isEmpty()) return
        val inForce = history
            .mapNotNull { record -> runCatching { LocalDate.parse(record.effectiveDate) }.getOrNull()?.let { it to record } }
            .filter { !it.first.isAfter(today) }
            .maxByOrNull { it.first }
            ?.second
            ?: return
        val live = GoalTransitionRecord(
            effectiveDate = today.toString(),
            fitnessGoal = FitnessGoal.fromString(prefs[Keys.FITNESS_GOAL]).name,
            maintenanceCalories = prefs[Keys.MAINTENANCE_CALORIES] ?: (prefs[Keys.DAILY_CALORIE_GOAL] ?: 2000.0),
            dailyCalorieGoal = prefs[Keys.DAILY_CALORIE_GOAL] ?: 2000.0,
            carbPercentage = prefs[Keys.CARB_PERCENTAGE] ?: 50.0,
            proteinPercentage = prefs[Keys.PROTEIN_PERCENTAGE] ?: 25.0,
            fatPercentage = prefs[Keys.FAT_PERCENTAGE] ?: 25.0
        )
        if (inForce.copy(effectiveDate = live.effectiveDate, fitnessGoal = FitnessGoal.fromString(inForce.fitnessGoal).name) == live) return
        val updated = history.filterNot { it.effectiveDate == live.effectiveDate } + live
        prefs[Keys.TRANSITIONS_HISTORY] = serializeTransitions(updated.sortedBy { it.effectiveDate })
    }

    override suspend fun getGoals(): Goal {
        checkAndPromoteScheduledGoal()
        return observeGoals().first()
    }

    override fun observeGoals(): Flow<Goal> {
        return store.data.map { prefs ->
            val today = clock()
            val scheduledDateStr = prefs[Keys.SCHEDULED_EFFECTIVE_DATE]
            val scheduledGoalStr = prefs[Keys.SCHEDULED_FITNESS_GOAL]

            var activeFitnessGoal = FitnessGoal.fromString(prefs[Keys.FITNESS_GOAL])
            var activeMaintenance = prefs[Keys.MAINTENANCE_CALORIES] ?: (prefs[Keys.DAILY_CALORIE_GOAL] ?: 2000.0)

            var pendingFitnessGoal: FitnessGoal? = null
            var pendingMaintenance: Double? = null
            var pendingEffectiveDate: LocalDate? = null

            if (!scheduledDateStr.isNullOrBlank() && !scheduledGoalStr.isNullOrBlank()) {
                val scheduledDate = try { LocalDate.parse(scheduledDateStr) } catch (_: Exception) { null }
                if (scheduledDate != null) {
                    if (!today.isBefore(scheduledDate)) {
                        activeFitnessGoal = FitnessGoal.fromString(scheduledGoalStr)
                        activeMaintenance = prefs[Keys.SCHEDULED_MAINTENANCE_CALORIES] ?: activeMaintenance
                    } else {
                        pendingFitnessGoal = FitnessGoal.fromString(scheduledGoalStr)
                        pendingMaintenance = prefs[Keys.SCHEDULED_MAINTENANCE_CALORIES] ?: activeMaintenance
                        pendingEffectiveDate = scheduledDate
                    }
                }
            }

            Goal(
                dailyCalorieGoal = prefs[Keys.DAILY_CALORIE_GOAL] ?: 2000.0,
                carbPercentage = prefs[Keys.CARB_PERCENTAGE] ?: 50.0,
                proteinPercentage = prefs[Keys.PROTEIN_PERCENTAGE] ?: 25.0,
                fatPercentage = prefs[Keys.FAT_PERCENTAGE] ?: 25.0,
                fitnessGoal = activeFitnessGoal,
                maintenanceCalories = activeMaintenance,
                scheduledFitnessGoal = pendingFitnessGoal,
                scheduledMaintenanceCalories = pendingMaintenance,
                scheduledEffectiveDate = pendingEffectiveDate
            )
        }
    }

    override suspend fun updateGoals(goals: Goal) {
        // One date and one transaction: a change scheduled for a day that starts during the save
        // is promoted before this save's history is written
        val today = clock()
        val tomorrow = today.plusDays(1)

        store.edit { prefs ->
            promoteScheduledGoal(prefs, today)
            recordLiveGoalIfMissing(prefs, today)
            val currentFitnessGoal = FitnessGoal.fromString(prefs[Keys.FITNESS_GOAL])
            val currentMaintenance = prefs[Keys.MAINTENANCE_CALORIES] ?: (prefs[Keys.DAILY_CALORIE_GOAL] ?: 2000.0)
            // The targets in force until now, read before they are overwritten (BUG-024)
            val oldCalories = prefs[Keys.DAILY_CALORIE_GOAL] ?: 2000.0
            val oldCarbs = prefs[Keys.CARB_PERCENTAGE] ?: 50.0
            val oldProtein = prefs[Keys.PROTEIN_PERCENTAGE] ?: 25.0
            val oldFat = prefs[Keys.FAT_PERCENTAGE] ?: 25.0

            // Immediate updates for calorie goal and macro percentages
            prefs[Keys.DAILY_CALORIE_GOAL] = goals.dailyCalorieGoal
            prefs[Keys.CARB_PERCENTAGE] = goals.carbPercentage
            prefs[Keys.PROTEIN_PERCENTAGE] = goals.proteinPercentage
            prefs[Keys.FAT_PERCENTAGE] = goals.fatPercentage

            val strategyChanged = (goals.fitnessGoal != currentFitnessGoal) || (goals.maintenanceCalories != currentMaintenance)
            val targetsChanged = goals.dailyCalorieGoal != oldCalories || goals.carbPercentage != oldCarbs ||
                goals.proteinPercentage != oldProtein || goals.fatPercentage != oldFat

            if (strategyChanged) {
                // Schedule change to take effect starting tomorrow (T + 1)
                prefs[Keys.SCHEDULED_FITNESS_GOAL] = goals.fitnessGoal.name
                prefs[Keys.SCHEDULED_MAINTENANCE_CALORIES] = goals.maintenanceCalories
                prefs[Keys.SCHEDULED_EFFECTIVE_DATE] = tomorrow.toString()
            } else {
                if (prefs[Keys.FITNESS_GOAL] == null) {
                    prefs[Keys.FITNESS_GOAL] = goals.fitnessGoal.name
                }
                if (prefs[Keys.MAINTENANCE_CALORIES] == null) {
                    prefs[Keys.MAINTENANCE_CALORIES] = goals.maintenanceCalories
                }
            }

            // History of every goal change, so each past day is scored against the goal it had
            if (strategyChanged || targetsChanged) {
                val history = parseTransitions(prefs[Keys.TRANSITIONS_HISTORY]).toMutableList()

                // Baseline for all days before the first recorded change: the goal as it was
                if (history.isEmpty()) {
                    history.add(
                        GoalTransitionRecord(
                            effectiveDate = "2000-01-01",
                            fitnessGoal = currentFitnessGoal.name,
                            maintenanceCalories = currentMaintenance,
                            dailyCalorieGoal = oldCalories,
                            carbPercentage = oldCarbs,
                            proteinPercentage = oldProtein,
                            fatPercentage = oldFat
                        )
                    )
                }

                if (targetsChanged) {
                    // New calorie and macro targets apply from today, with today's strategy
                    val todayStr = today.toString()
                    history.removeAll { it.effectiveDate == todayStr }
                    history.add(
                        GoalTransitionRecord(
                            effectiveDate = todayStr,
                            fitnessGoal = currentFitnessGoal.name,
                            maintenanceCalories = currentMaintenance,
                            dailyCalorieGoal = goals.dailyCalorieGoal,
                            carbPercentage = goals.carbPercentage,
                            proteinPercentage = goals.proteinPercentage,
                            fatPercentage = goals.fatPercentage
                        )
                    )
                    // A strategy change already scheduled for later keeps its strategy but takes
                    // the new targets, which stay in force from today on
                    history.replaceAll { record ->
                        val date = runCatching { LocalDate.parse(record.effectiveDate) }.getOrNull()
                        if (date != null && date.isAfter(today)) {
                            record.copy(
                                dailyCalorieGoal = goals.dailyCalorieGoal,
                                carbPercentage = goals.carbPercentage,
                                proteinPercentage = goals.proteinPercentage,
                                fatPercentage = goals.fatPercentage
                            )
                        } else {
                            record
                        }
                    }
                }

                if (strategyChanged) {
                    // Add or replace transition record for tomorrow
                    val tomorrowStr = tomorrow.toString()
                    history.removeAll { it.effectiveDate == tomorrowStr }
                    history.add(
                        GoalTransitionRecord(
                            effectiveDate = tomorrowStr,
                            fitnessGoal = goals.fitnessGoal.name,
                            maintenanceCalories = goals.maintenanceCalories,
                            dailyCalorieGoal = goals.dailyCalorieGoal,
                            carbPercentage = goals.carbPercentage,
                            proteinPercentage = goals.proteinPercentage,
                            fatPercentage = goals.fatPercentage
                        )
                    )
                }

                prefs[Keys.TRANSITIONS_HISTORY] = serializeTransitions(history.sortedBy { it.effectiveDate })
            }
        }
        MacroBaseWidgetProvider.updateAllWidgets(context)
    }

    override suspend fun getGoalForDate(date: LocalDate): Goal = getGoalsForDates(listOf(date)).first()

    /** One read of the stored goals for any number of days (the rank replays every logged day). */
    override suspend fun getGoalsForDates(dates: List<LocalDate>): List<Goal> {
        val active = getGoals()
        val today = clock()
        val prefs = store.data.first()

        val scheduledGoalName = prefs[Keys.SCHEDULED_FITNESS_GOAL]
        val scheduledDate = prefs[Keys.SCHEDULED_EFFECTIVE_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val history = parseTransitions(prefs[Keys.TRANSITIONS_HISTORY])
            .mapNotNull { record -> runCatching { LocalDate.parse(record.effectiveDate) }.getOrNull()?.let { it to record } }
            .sortedBy { it.first }

        return dates.map { date ->
            // 1. If date > today and a scheduled goal exists, return scheduled goal
            if (date.isAfter(today) && !scheduledGoalName.isNullOrBlank() && scheduledDate != null && !date.isBefore(scheduledDate)) {
                return@map Goal(
                    dailyCalorieGoal = active.dailyCalorieGoal,
                    carbPercentage = active.carbPercentage,
                    proteinPercentage = active.proteinPercentage,
                    fatPercentage = active.fatPercentage,
                    fitnessGoal = FitnessGoal.fromString(scheduledGoalName),
                    // Same fallback as the pending change shown by observeGoals
                    maintenanceCalories = prefs[Keys.SCHEDULED_MAINTENANCE_CALORIES] ?: active.maintenanceCalories
                )
            }

            // 2. Today (and later, with nothing scheduled) is the goal in force now: targets apply as
            // soon as they are saved, also where an older version left them out of the history (BUG-024)
            if (!date.isBefore(today)) return@map active

            // 3. A past day: the change in force on that date, or the active goal before any history
            val record = history.lastOrNull { !it.first.isAfter(date) }?.second ?: return@map active
            Goal(
                dailyCalorieGoal = record.dailyCalorieGoal,
                carbPercentage = record.carbPercentage,
                proteinPercentage = record.proteinPercentage,
                fatPercentage = record.fatPercentage,
                fitnessGoal = FitnessGoal.fromString(record.fitnessGoal),
                maintenanceCalories = record.maintenanceCalories
            )
        }
    }

    override suspend fun exportGoalBackup(): GoalBackupDto {
        val goal = getGoals()
        val transitions = parseTransitions(store.data.first()[Keys.TRANSITIONS_HISTORY])
        return GoalBackupDto.from(goal).copy(
            transitions = transitions.map {
                GoalTransitionBackupDto(
                    effectiveDate = it.effectiveDate,
                    fitnessGoal = it.fitnessGoal,
                    maintenanceCalories = it.maintenanceCalories,
                    dailyCalorieGoal = it.dailyCalorieGoal,
                    carbPercentage = it.carbPercentage,
                    proteinPercentage = it.proteinPercentage,
                    fatPercentage = it.fatPercentage
                )
            }
        )
    }

    override suspend fun restoreGoalBackup(backup: GoalBackupDto) {
        store.edit { prefs ->
            prefs[Keys.DAILY_CALORIE_GOAL] = backup.dailyCalorieGoal
            prefs[Keys.CARB_PERCENTAGE] = backup.carbPercentage
            prefs[Keys.PROTEIN_PERCENTAGE] = backup.proteinPercentage
            prefs[Keys.FAT_PERCENTAGE] = backup.fatPercentage

            // Backups before format 1.2.0 stop here, and so does a strategy this version does not
            // know: this device keeps its own strategy, scheduled change and history instead of
            // falling back to Maintaining / 2,000 kcal
            val fitnessGoal = knownFitnessGoal(backup.fitnessGoal)
            if (fitnessGoal == null) {
                // The backup's targets are the only ones known for its diary: every history record
                // takes them, so restored days are not scored against targets this device had for
                // its own, replaced diary. Dates and strategies stay as they are.
                val history = parseTransitions(prefs[Keys.TRANSITIONS_HISTORY])
                if (history.isNotEmpty()) {
                    prefs[Keys.TRANSITIONS_HISTORY] = serializeTransitions(
                        history.map {
                            it.copy(
                                dailyCalorieGoal = backup.dailyCalorieGoal,
                                carbPercentage = backup.carbPercentage,
                                proteinPercentage = backup.proteinPercentage,
                                fatPercentage = backup.fatPercentage
                            )
                        }
                    )
                }
                return@edit
            }
            prefs[Keys.FITNESS_GOAL] = fitnessGoal.name
            val maintenance = backup.maintenanceCalories
            if (maintenance != null) prefs[Keys.MAINTENANCE_CALORIES] = maintenance else prefs.remove(Keys.MAINTENANCE_CALORIES)

            val scheduledGoal = knownFitnessGoal(backup.scheduledFitnessGoal)
            val scheduledDate = backup.scheduledEffectiveDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (scheduledGoal != null && scheduledDate != null) {
                prefs[Keys.SCHEDULED_FITNESS_GOAL] = scheduledGoal.name
                prefs[Keys.SCHEDULED_EFFECTIVE_DATE] = scheduledDate.toString()
                val scheduledMaintenance = backup.scheduledMaintenanceCalories
                if (scheduledMaintenance != null) {
                    prefs[Keys.SCHEDULED_MAINTENANCE_CALORIES] = scheduledMaintenance
                } else {
                    prefs.remove(Keys.SCHEDULED_MAINTENANCE_CALORIES)
                }
            } else {
                prefs.remove(Keys.SCHEDULED_FITNESS_GOAL)
                prefs.remove(Keys.SCHEDULED_MAINTENANCE_CALORIES)
                prefs.remove(Keys.SCHEDULED_EFFECTIVE_DATE)
            }

            // A backup that did not record history leaves this device's history alone
            val transitions = backup.transitions ?: return@edit
            // Re-validated so a damaged record cannot break the "|"-separated history format;
            // records with a date or strategy this version cannot read are left out
            val history = transitions.mapNotNull { t ->
                val date = runCatching { LocalDate.parse(t.effectiveDate) }.getOrNull() ?: return@mapNotNull null
                val goal = knownFitnessGoal(t.fitnessGoal) ?: return@mapNotNull null
                GoalTransitionRecord(
                    effectiveDate = date.toString(),
                    fitnessGoal = goal.name,
                    maintenanceCalories = t.maintenanceCalories,
                    dailyCalorieGoal = t.dailyCalorieGoal,
                    carbPercentage = t.carbPercentage,
                    proteinPercentage = t.proteinPercentage,
                    fatPercentage = t.fatPercentage
                )
            }
            if (history.isEmpty()) {
                prefs.remove(Keys.TRANSITIONS_HISTORY)
            } else {
                prefs[Keys.TRANSITIONS_HISTORY] = serializeTransitions(history)
            }
        }
        MacroBaseWidgetProvider.updateAllWidgets(context)
    }

    /** The strategy named [name], or null for a name this version does not know (never a default). */
    private fun knownFitnessGoal(name: String?): FitnessGoal? =
        FitnessGoal.entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

    override suspend fun hasSavedGoals(): Boolean {
        val prefs = store.data.first()
        return prefs[Keys.DAILY_CALORIE_GOAL] != null || prefs[Keys.FITNESS_GOAL] != null
    }
}
