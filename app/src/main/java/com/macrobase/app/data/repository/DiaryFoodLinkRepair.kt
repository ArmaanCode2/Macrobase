package com.macrobase.app.data.repository

import androidx.room.withTransaction
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.domain.model.Recipe

/**
 * Repairs diary entries that point at the wrong custom food (BUG-006).
 *
 * Backup restores in earlier versions kept the backup's custom-food row ids, so some entries
 * point at an unrelated or missing food. An entry is re-pointed only when exactly one custom
 * food has the entry's logged name and that food already existed when the entry was logged
 * (restored foods keep their original creation time). A food renamed later, or a different
 * food created later with the old name, is never adopted; such entries are left untouched and
 * edit mode keeps using their snapshot. Only the food id changes: names, dates and the logged
 * nutrition snapshot are never modified.
 *
 * Run once per install ([runOnce]): restores in this version re-link entries themselves, so
 * later renames must not be treated as damage.
 */
class DiaryFoodLinkRepair(private val userDatabase: UserDatabase) {

    /** Returns how many entries were re-pointed. */
    suspend fun run(): Int = userDatabase.withTransaction {
        val diaryDao = userDatabase.diaryDao()
        val entries = diaryDao.getEntriesWithFoodIdBetween(
            fromExclusive = FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET,
            toExclusive = Recipe.FOOD_ID_OFFSET
        )
        if (entries.isEmpty()) return@withTransaction 0

        val customFoods = userDatabase.customFoodDao().getAllCustomFoods()
        val foodsByRowId = customFoods.associateBy { it.id }
        val foodsByName = customFoods.groupBy { normalize(it.name) }

        var repaired = 0
        for (entry in entries) {
            val rowId = FoodRepositoryImpl.customRowIdOrNull(entry.foodId) ?: continue
            val linked = foodsByRowId[rowId]
            if (linked != null && normalize(linked.name) == normalize(entry.foodName)) continue

            val match = foodsByName[normalize(entry.foodName)]?.singleOrNull() ?: continue
            if (match.createdAt > entry.createdAt) continue
            diaryDao.updateFoodId(entry.id, FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET + match.id)
            repaired++
        }
        repaired
    }

    /**
     * Runs [run] the first time only. [isDone]/[markDone] persist the flag; it is set only after
     * a successful run, so an interrupted repair is retried on the next start.
     */
    suspend fun runOnce(isDone: () -> Boolean, markDone: () -> Unit): Int {
        if (isDone()) return 0
        val repaired = run()
        markDone()
        return repaired
    }

    private fun normalize(name: String) = name.trim().lowercase()
}
