package com.macrobase.app.data.provider

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.FoodType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete provider implementation that queries the read-only built-in SQLite food database
 * using FTS5 full-text indexing, B-tree indexes, and structured portion servings.
 */
class LocalFoodDatabaseProvider(
    private val databaseManager: BuiltInDatabaseManager
) : FoodDataProvider {

    override val providerId: String = "BUILT_IN_USDA"
    override val displayName: String = "USDA Food Catalog"
    override val isLocal: Boolean = true

    override suspend fun searchFoods(query: String, limit: Int): List<Food> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return@withContext emptyList()

        val db = databaseManager.getDatabase()
        val ftsQuery = sanitizeFtsQuery(trimmed)
        if (ftsQuery.isBlank()) return@withContext emptyList()

        val foods = mutableListOf<Food>()
        val foodIds = mutableListOf<Long>()

        val sql = """
            SELECT f.id, f.uuid, f.source_id, f.name, f.normalized_name, f.brand, f.food_type, f.serving_basis,
                   f.calories, f.protein, f.carbohydrates, f.fat, f.fiber, f.sugar,
                   f.saturated_fat, f.trans_fat, f.cholesterol, f.sodium,
                   f.potassium, f.calcium, f.iron, f.magnesium, f.phosphorus, f.zinc,
                   f.vitamin_a_rae, f.vitamin_c, f.vitamin_d_mcg, f.vitamin_e, f.vitamin_k,
                   f.vitamin_b6, f.vitamin_b12, f.folate_b9, f.water,
                   c.name as category_name
            FROM foods_fts fts
            JOIN foods f ON f.id = fts.food_id
            LEFT JOIN categories c ON f.category_id = c.id
            WHERE foods_fts MATCH ? AND f.is_active = 1
            ORDER BY rank
            LIMIT ?
        """.trimIndent()

        try {
            db.rawQuery(sql, arrayOf(ftsQuery, limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val food = cursor.extractFoodWithoutServings()
                    foods.add(food)
                    foodIds.add(food.id)
                }
            }
            android.util.Log.d("LocalFoodDb", "Query '$trimmed' (FTS '$ftsQuery') matched ${foods.size} foods")
        } catch (e: Exception) {
            android.util.Log.w("LocalFoodDb", "FTS5 search unavailable on OS SQLite, falling back to indexed LIKE search: ${e.message}")
            val fallbackSql = """
                SELECT f.id, f.uuid, f.source_id, f.name, f.normalized_name, f.brand, f.food_type, f.serving_basis,
                       f.calories, f.protein, f.carbohydrates, f.fat, f.fiber, f.sugar,
                       f.saturated_fat, f.trans_fat, f.cholesterol, f.sodium,
                       f.potassium, f.calcium, f.iron, f.magnesium, f.phosphorus, f.zinc,
                       f.vitamin_a_rae, f.vitamin_c, f.vitamin_d_mcg, f.vitamin_e, f.vitamin_k,
                       f.vitamin_b6, f.vitamin_b12, f.folate_b9, f.water,
                       c.name as category_name
                FROM foods f
                LEFT JOIN categories c ON f.category_id = c.id
                WHERE (f.normalized_name LIKE ? OR f.name LIKE ?) AND f.is_active = 1
                ORDER BY CASE WHEN f.normalized_name LIKE ? THEN 0 ELSE 1 END, f.name ASC
                LIMIT ?
            """.trimIndent()
            try {
                db.rawQuery(fallbackSql, arrayOf("%$trimmed%", "%$trimmed%", "$trimmed%", limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        val food = cursor.extractFoodWithoutServings()
                        foods.add(food)
                        foodIds.add(food.id)
                    }
                }
                android.util.Log.d("LocalFoodDb", "Fallback LIKE query '$trimmed' matched ${foods.size} foods")
            } catch (fallbackEx: Exception) {
                android.util.Log.e("LocalFoodDb", "Fallback search query '$trimmed' failed: ${fallbackEx.message}", fallbackEx)
            }
        }

        if (foods.isEmpty()) return@withContext emptyList()

        // Batch load servings for all matched foods
        val servingsMap = loadServingsForFoodIds(db, foodIds)

        foods.map { food ->
            food.copy(servings = servingsMap[food.id] ?: emptyList())
        }
    }

    override suspend fun getFoodById(id: Long): Food? = withContext(Dispatchers.IO) {
        val db = databaseManager.getDatabase()

        val sql = """
            SELECT f.id, f.uuid, f.source_id, f.name, f.normalized_name, f.brand, f.food_type, f.serving_basis,
                   f.calories, f.protein, f.carbohydrates, f.fat, f.fiber, f.sugar,
                   f.saturated_fat, f.trans_fat, f.cholesterol, f.sodium,
                   f.potassium, f.calcium, f.iron, f.magnesium, f.phosphorus, f.zinc,
                   f.vitamin_a_rae, f.vitamin_c, f.vitamin_d_mcg, f.vitamin_e, f.vitamin_k,
                   f.vitamin_b6, f.vitamin_b12, f.folate_b9, f.water,
                   c.name as category_name
            FROM foods f
            LEFT JOIN categories c ON f.category_id = c.id
            WHERE f.id = ? AND f.is_active = 1
            LIMIT 1
        """.trimIndent()

        var food: Food? = null
        db.rawQuery(sql, arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) {
                food = cursor.extractFoodWithoutServings()
            }
        }

        val baseFood = food ?: return@withContext null
        val servings = getFoodServings(id)

        baseFood.copy(servings = servings)
    }

    override suspend fun getFoodByBarcode(barcode: String): Food? {
        // Built-in USDA database does not index retail barcodes
        return null
    }

    override suspend fun getFoodServings(foodId: Long): List<Serving> = withContext(Dispatchers.IO) {
        val db = databaseManager.getDatabase()
        val servings = mutableListOf<Serving>()

        val sql = """
            SELECT id, food_id, description, unit_type, quantity, gram_weight, is_default, sequence
            FROM servings
            WHERE food_id = ?
            ORDER BY sequence ASC, id ASC
        """.trimIndent()

        db.rawQuery(sql, arrayOf(foodId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                servings.add(cursor.extractServing())
            }
        }

        servings
    }

    private fun loadServingsForFoodIds(db: SQLiteDatabase, foodIds: List<Long>): Map<Long, List<Serving>> {
        if (foodIds.isEmpty()) return emptyMap()

        val placeholders = foodIds.joinToString(",") { "?" }
        val sql = """
            SELECT id, food_id, description, unit_type, quantity, gram_weight, is_default, sequence
            FROM servings
            WHERE food_id IN ($placeholders)
            ORDER BY sequence ASC, id ASC
        """.trimIndent()

        val map = mutableMapOf<Long, MutableList<Serving>>()
        db.rawQuery(sql, foodIds.map { it.toString() }.toTypedArray()).use { cursor ->
            val foodIdIdx = cursor.getColumnIndexOrThrow("food_id")
            while (cursor.moveToNext()) {
                val fId = cursor.getLong(foodIdIdx)
                val serving = cursor.extractServing()
                map.getOrPut(fId) { mutableListOf() }.add(serving)
            }
        }
        return map
    }

    private fun Cursor.extractFoodWithoutServings(): Food {
        val id = getLong(getColumnIndexOrThrow("id"))
        val uuid = getStringOrNull("uuid") ?: id.toString()
        val sourceId = getStringOrNull("source_id")
        val name = getStringOrNull("name") ?: "Food $id"
        val normalizedName = getStringOrNull("normalized_name") ?: name.lowercase()
        val brand = getStringOrNull("brand")
        val category = getStringOrNull("category_name")
        val foodTypeStr = getStringOrNull("food_type") ?: "generic"
        val servingBasis = getStringOrNull("serving_basis") ?: "100g"

        val nutrition = Nutrition(
            calories = getDouble(getColumnIndexOrThrow("calories")),
            proteinGrams = getDouble(getColumnIndexOrThrow("protein")),
            carbsGrams = getDouble(getColumnIndexOrThrow("carbohydrates")),
            fatGrams = getDouble(getColumnIndexOrThrow("fat")),
            fiberGrams = getDoubleOrNull("fiber"),
            sugarGrams = getDoubleOrNull("sugar"),
            saturatedFatGrams = getDoubleOrNull("saturated_fat"),
            transFatGrams = getDoubleOrNull("trans_fat"),
            cholesterolMg = getDoubleOrNull("cholesterol"),
            sodiumMg = getDoubleOrNull("sodium"),
            potassiumMg = getDoubleOrNull("potassium"),
            calciumMg = getDoubleOrNull("calcium"),
            ironMg = getDoubleOrNull("iron"),
            magnesiumMg = getDoubleOrNull("magnesium"),
            phosphorusMg = getDoubleOrNull("phosphorus"),
            zincMg = getDoubleOrNull("zinc"),
            vitaminARaeMcg = getDoubleOrNull("vitamin_a_rae"),
            vitaminCMg = getDoubleOrNull("vitamin_c"),
            vitaminDMcg = getDoubleOrNull("vitamin_d_mcg"),
            vitaminEMg = getDoubleOrNull("vitamin_e"),
            vitaminKMcg = getDoubleOrNull("vitamin_k"),
            vitaminB6Mg = getDoubleOrNull("vitamin_b6"),
            vitaminB12Mcg = getDoubleOrNull("vitamin_b12"),
            folateMcg = getDoubleOrNull("folate_b9"),
            waterGrams = getDoubleOrNull("water")
        )

        return Food(
            id = id,
            uuid = uuid,
            source = FoodSource.BUILT_IN,
            sourceId = sourceId,
            name = name,
            normalizedName = normalizedName,
            brand = brand,
            category = category,
            foodType = if (foodTypeStr.equals("branded", ignoreCase = true)) FoodType.BRANDED else FoodType.GENERIC,
            isUserOwned = false,
            servingBasis = servingBasis,
            nutrition = nutrition,
            servings = emptyList()
        )
    }

    private fun Cursor.extractServing(): Serving {
        val id = getLong(getColumnIndexOrThrow("id"))
        val description = getStringOrNull("description") ?: "1 serving"
        val unitTypeStr = getStringOrNull("unit_type")
        val quantity = getDouble(getColumnIndexOrThrow("quantity"))
        val gramWeight = getDouble(getColumnIndexOrThrow("gram_weight"))
        val isDefault = getInt(getColumnIndexOrThrow("is_default")) == 1
        val sequence = getInt(getColumnIndexOrThrow("sequence"))

        return Serving(
            id = id,
            description = description,
            unit = ServingUnit.fromString(unitTypeStr),
            quantity = quantity,
            gramWeight = gramWeight,
            isDefault = isDefault,
            sequence = sequence
        )
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndex(column)
        if (idx == -1 || isNull(idx)) return null
        return getString(idx)
    }

    private fun Cursor.getDoubleOrNull(column: String): Double? {
        val idx = getColumnIndex(column)
        if (idx == -1 || isNull(idx)) return null
        return getDouble(idx)
    }

    /**
     * Sanitizes and constructs an FTS5 search query supporting prefix matching.
     * E.g. "greek yogurt" -> "greek* yogurt*"
     */
    private fun sanitizeFtsQuery(raw: String): String {
        val tokens = raw.replace(Regex("[^a-zA-Z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return ""

        return tokens.joinToString(" ") { "$it*" }
    }
}
