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

    override val providerId: String = "BUILT_IN_INDIAN"
    override val displayName: String = "Indian Food Catalog"
    override val isLocal: Boolean = true

    override suspend fun searchFoods(query: String, limit: Int): List<Food> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return@withContext emptyList()

        // Nothing searchable is left, such as "!!!"
        val ftsQuery = sanitizeFtsQuery(trimmed)
        if (ftsQuery.isBlank()) return@withContext emptyList()
        val db = databaseManager.getDatabase()

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
        }

        // No full-text match: a fragment inside a word ("ancake" in "pancake"), or a script the
        // catalog does not use. A substring match still gets a chance (BUG-054).
        if (foods.isEmpty()) {
            likeSearch(db, trimmed, limit).forEach { food ->
                foods.add(food)
                foodIds.add(food.id)
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
        try {
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
                WHERE f.id = ? AND (f.is_active = 1 OR f.is_active IS NULL)
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
        } catch (e: Exception) {
            android.util.Log.e("LocalFoodDb", "Error fetching food by id $id: ${e.message}", e)
            null
        }
    }

    override suspend fun getFoodByBarcode(barcode: String): Food? {
        // Built-in USDA database does not index retail barcodes
        return null
    }

    override suspend fun getFoodServings(foodId: Long): List<Serving> = withContext(Dispatchers.IO) {
        try {
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
        } catch (e: Exception) {
            android.util.Log.e("LocalFoodDb", "Error fetching servings for foodId $foodId: ${e.message}", e)
            emptyList()
        }
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
        val rawQuantity = getDouble(getColumnIndexOrThrow("quantity"))
        val gramWeight = getDouble(getColumnIndexOrThrow("gram_weight"))
        val isDefault = getInt(getColumnIndexOrThrow("is_default")) == 1
        val sequence = getInt(getColumnIndexOrThrow("sequence"))

        // 1 serving of "100 g" represents 1.0 portion unit weighing 100.0 grams
        val normalizedQuantity = if (rawQuantity == 100.0 && gramWeight == 100.0) 1.0 else rawQuantity

        return Serving(
            id = id,
            description = description,
            unit = ServingUnit.fromString(unitTypeStr),
            quantity = normalizedQuantity,
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

    /** Foods whose name contains [query] anywhere, best (prefix) matches first. */
    private fun likeSearch(db: SQLiteDatabase, query: String, limit: Int): List<Food> {
        val words = query.replace(WHITESPACE, " ")
        val safeName = escapeLikeWildcards(words)
        // normalized_name is lower case without accents, so the query is folded the same way
        val safeNormalized = escapeLikeWildcards(foldForNormalizedName(words))
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
            WHERE (f.normalized_name LIKE ? ESCAPE '/' OR f.name LIKE ? ESCAPE '/') AND f.is_active = 1
            ORDER BY CASE WHEN f.normalized_name LIKE ? ESCAPE '/' THEN 0 ELSE 1 END, f.name ASC
            LIMIT ?
        """.trimIndent()
        val foods = mutableListOf<Food>()
        try {
            db.rawQuery(sql, arrayOf("%$safeNormalized%", "%$safeName%", "$safeNormalized%", limit.toString())).use { cursor ->
                while (cursor.moveToNext()) foods.add(cursor.extractFoodWithoutServings())
            }
            android.util.Log.d("LocalFoodDb", "LIKE query '$query' matched ${foods.size} foods")
        } catch (e: Exception) {
            android.util.Log.e("LocalFoodDb", "LIKE search for '$query' failed: ${e.message}", e)
        }
        return foods
    }

    companion object {
        private val NOT_A_WORD_CHARACTER = Regex("""[^\p{L}\p{M}\p{N}]+""")
        private val WHITESPACE = Regex("""\s+""")
        // Accents on Latin letters only: the vowel signs of Indic scripts are part of the word
        private val LATIN_ACCENTS = Regex("""(?<=\p{IsLatin})\p{Mn}+""")

        /**
         * An FTS5 prefix query from what the user typed: "greek yogurt" becomes `"greek"* "yogurt"*`.
         * Letters of every script, their combining marks and digits are kept (BUG-054), and the
         * table's unicode61 tokenizer folds accents, so an accented word finds the plain spelling.
         * Each word is quoted, so nothing in it is read as FTS5 syntax. Empty when no word is left.
         */
        internal fun sanitizeFtsQuery(raw: String): String =
            raw.split(NOT_A_WORD_CHARACTER)
                .filter { it.isNotEmpty() }
                .joinToString(" ") { "\"$it\"*" }

        /** [text] lower-cased without accents on Latin letters, as the catalog's normalized_name is stored. */
        internal fun foldForNormalizedName(text: String): String =
            java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(LATIN_ACCENTS, "")
                .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }
    }

    private fun escapeLikeWildcards(input: String): String {
        return input.replace("/", "//")
                    .replace("%", "/%")
                    .replace("_", "/_")
    }
}
