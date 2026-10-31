package com.macrobase.app.data.provider

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.FoodType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Concrete provider implementation that queries the read-only built-in SQLite food database
 * through its food_search word table, B-tree indexes, and structured portion servings. With
 * [brandedManager], the Open Food Facts products in branded_foods.db are searched and looked up too:
 * the two catalogs share their tables and keep their food ids apart (tools/catalog/build_branded.py).
 */
class LocalFoodDatabaseProvider(
    private val databaseManager: BuiltInDatabaseManager,
    private val brandedManager: BuiltInDatabaseManager? = null
) : FoodDataProvider {

    /** A search result and how well it matched: see [matchTier]. */
    private data class Hit(val food: Food, val tier: Int)

    // Set when the branded file cannot be installed or read: the app then uses the catalog alone until it restarts,
    // rather than copying the asset again on every keystroke
    @Volatile
    private var brandedFailed = false

    private fun branded(): BuiltInDatabaseManager? = brandedManager?.takeIf { !brandedFailed }

    private fun noteReadFailure(manager: BuiltInDatabaseManager, e: Exception) {
        if (manager === brandedManager) brandedFailed = true
        android.util.Log.e("LocalFoodDb", "Food catalog read failed: ${e.message}", e)
    }

    override val providerId: String = "BUILT_IN_INDIAN"
    override val displayName: String = "Indian Food Catalog"
    override val isLocal: Boolean = true

    override suspend fun searchFoods(query: String, limit: Int): List<Food> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || limit <= 0) return@withContext emptyList()

        // Nothing searchable is left, such as "!!!"
        val words = searchWords(trimmed)
        if (words.isEmpty()) return@withContext emptyList()

        val hits = searchCatalog(databaseManager.getDatabase(), words, trimmed, limit).toMutableList()
        // Branded products rank among the catalog's foods by how well they match, after them on a tie,
        // so "parle g" finds Parle-G first and "biscuit" the plain biscuits
        branded()?.let { branded ->
            try {
                hits += searchCatalog(branded.getDatabase(), words, trimmed, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                noteReadFailure(branded, e)
            }
        }
        val foods = hits.sortedBy { it.tier }.take(limit).map { it.food }
        android.util.Log.d("LocalFoodDb", "Query '$trimmed' matched ${foods.size} foods")
        foods
    }

    /** One catalog's matches for [words], best first, each with its servings. */
    private fun searchCatalog(db: SQLiteDatabase, words: List<String>, trimmed: String, limit: Int): List<Hit> {
        // Foods with a word starting with every query word, in their name, an alias or a spelling of one
        val hits = queryHits(db, wordSearchSql(words.size), wordSearchArgs(words, limit)) { cursor ->
            matchTier(cursor.getStringOrNull("search_names"), cursor.getStringOrNull("search_terms"), words)
        }.toMutableList()
        // Then names holding the query inside a word: a fragment ("ancake" in "pancake", "nut" in
        // "peanut") or a script the catalog does not use (BUG-054), after every word match
        if (hits.size < limit) {
            val found = hits.mapTo(HashSet()) { it.food.id }
            queryHits(db, LIKE_SEARCH_SQL, likeSearchArgs(trimmed, limit)) { SUBSTRING_TIER }
                .filter { it.food.id !in found }
                .take(limit - hits.size)
                .forEach { hits.add(it) }
        }
        if (hits.isEmpty()) return hits

        // Batch load servings for all matched foods
        val servingsMap = loadServingsForFoodIds(db, hits.map { it.food.id })
        return hits.map { it.copy(food = it.food.copy(servings = servingsMap[it.food.id] ?: emptyList())) }
    }

    override suspend fun getFoodById(id: Long): Food? = withContext(Dispatchers.IO) {
        if (!isCatalogId(id)) return@withContext null
        findFood(databaseManager, id) ?: branded()?.let { findFood(it, id) }
    }

    private suspend fun findFood(manager: BuiltInDatabaseManager, id: Long): Food? {
        return try {
            val db = manager.getDatabase()

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

            val baseFood = food ?: return null
            baseFood.copy(servings = servingsOf(manager, id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteReadFailure(manager, e)
            null
        }
    }

    override suspend fun getFoodByBarcode(barcode: String): Food? {
        // Neither catalog is looked up by barcode yet
        return null
    }

    override suspend fun getFoodServings(foodId: Long): List<Serving> = withContext(Dispatchers.IO) {
        if (!isCatalogId(foodId)) return@withContext emptyList()
        // Every catalog food has its 100 g serving, so none means the food is a branded one
        servingsOf(databaseManager, foodId).ifEmpty { branded()?.let { servingsOf(it, foodId) } ?: emptyList() }
    }

    private suspend fun servingsOf(manager: BuiltInDatabaseManager, foodId: Long): List<Serving> {
        return try {
            val db = manager.getDatabase()
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteReadFailure(manager, e)
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

    private fun queryHits(db: SQLiteDatabase, sql: String, args: Array<String>, tier: (Cursor) -> Int): List<Hit> {
        val hits = mutableListOf<Hit>()
        try {
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) hits.add(Hit(cursor.extractFoodWithoutServings(), tier(cursor)))
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalFoodDb", "Food search failed: ${e.message}", e)
        }
        return hits
    }

    companion object {
        private val NOT_A_WORD_CHARACTER = Regex("""[^\p{L}\p{M}\p{N}]+""")
        private val WHITESPACE = Regex("""\s+""")
        // Accents on Latin letters only: the vowel signs of Indic scripts are part of the word
        private val LATIN_ACCENTS = Regex("""(?<=\p{IsLatin})\p{Mn}+""")
        // Dropped from a word rather than splitting it: apostrophes ("lady's" is "ladys") and the
        // zero-width joiners Indic keyboards type
        private val JOINED_INSIDE_WORDS = Regex("['\u2019\u200C\u200D]")
        // Words past this many are ignored, so a pasted paragraph stays a small query
        private const val MAX_SEARCH_WORDS = 8
        // matchTier of a name that only holds the query inside a word
        private const val SUBSTRING_TIER = 3

        private const val SEARCH_COLUMNS = """SELECT f.id, f.uuid, f.source_id, f.name, f.normalized_name, f.brand, f.food_type, f.serving_basis,
                   f.calories, f.protein, f.carbohydrates, f.fat, f.fiber, f.sugar,
                   f.saturated_fat, f.trans_fat, f.cholesterol, f.sodium,
                   f.potassium, f.calcium, f.iron, f.magnesium, f.phosphorus, f.zinc,
                   f.vitamin_a_rae, f.vitamin_c, f.vitamin_d_mcg, f.vitamin_e, f.vitamin_k,
                   f.vitamin_b6, f.vitamin_b12, f.folate_b9, f.water,
                   c.name as category_name"""

        /** Foods whose name contains the query anywhere, best (prefix) matches first; takes [likeSearchArgs]. */
        private val LIKE_SEARCH_SQL = """
            $SEARCH_COLUMNS
            FROM foods f
            LEFT JOIN categories c ON f.category_id = c.id
            WHERE (f.normalized_name LIKE ? ESCAPE '/' OR f.name LIKE ? ESCAPE '/') AND f.is_active = 1
            ORDER BY CASE WHEN f.normalized_name LIKE ? ESCAPE '/' THEN 0 ELSE 1 END, f.name ASC
            LIMIT ?
        """.trimIndent()

        private fun likeSearchArgs(query: String, limit: Int): Array<String> {
            val words = query.replace(WHITESPACE, " ")
            val safeName = escapeLikeWildcards(words)
            // normalized_name is lower case without accents, so the query is folded the same way
            val safeNormalized = escapeLikeWildcards(foldForNormalizedName(words))
            return arrayOf("%$safeNormalized%", "%$safeName%", "$safeNormalized%", limit.toString())
        }

        /**
         * The words of what the user typed, folded like normalized_name: "Low-fat Paneer" is "low",
         * "fat" and "paneer", and Latin accents go. Letters of every script, their combining marks and digits are kept
         * (BUG-054), so an Indic vowel sign stays in its word. tools/catalog/build_catalog.py splits
         * the catalog's words the same way. Empty when no word is left.
         */
        internal fun searchWords(raw: String): List<String> =
            foldForNormalizedName(raw).replace(JOINED_INSIDE_WORDS, "").split(NOT_A_WORD_CHARACTER)
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_SEARCH_WORDS)

        /**
         * Foods with a word starting with each of [wordCount] query words, in any order, in their name,
         * an alias or a spelling of one: the food_search table, built by tools/catalog/build_catalog.py,
         * since Android's SQLite has no FTS5. Ranked by the name or an alias typed in full, then foods
         * holding every query word whole (so "kheer" puts kheers above "kheere ka raita"), then how
         * commonly the food is eaten, then names that start with the query, then names holding every
         * word, then shorter names. Takes [wordSearchArgs].
         */
        internal fun wordSearchSql(wordCount: Int): String {
            val inTerms = List(wordCount) { "s.terms LIKE ? ESCAPE '/'" }.joinToString(" AND ")
            val wholeInTerms = List(wordCount) { "s.terms LIKE ? ESCAPE '/'" }.joinToString(" AND ")
            val inName = List(wordCount) { "s.name_terms LIKE ? ESCAPE '/'" }.joinToString(" AND ")
            return """
                $SEARCH_COLUMNS, s.names AS search_names, s.terms AS search_terms
                FROM food_search s
                JOIN foods f ON f.id = s.food_id
                LEFT JOIN categories c ON f.category_id = c.id
                WHERE $inTerms AND f.is_active = 1
                ORDER BY CASE WHEN s.names LIKE ? ESCAPE '/' THEN 0 ELSE 1 END,
                         CASE WHEN $wholeInTerms THEN 0 ELSE 1 END,
                         s.priority DESC,
                         CASE WHEN s.name_terms LIKE ? ESCAPE '/' THEN 0 ELSE 1 END,
                         CASE WHEN $inName THEN 0 ELSE 1 END,
                         length(f.name), f.name
                LIMIT ?
            """.trimIndent()
        }

        /**
         * How well a word search result matched, as [wordSearchSql] ranks first: 0 for its name or an
         * alias typed in full ([names] from food_search.names), 1 for every query word whole ([terms]),
         * 2 for word starts only. Results from the two catalogs merge by it.
         */
        internal fun matchTier(names: String?, terms: String?, words: List<String>): Int = when {
            names != null && names.contains("|${words.joinToString(" ")}|") -> 0
            terms != null && words.all { terms.contains(" $it ") } -> 1
            else -> 2
        }

        /**
         * Whether [id] can be a catalog food's: custom foods and recipes take 100,000,000 up to
         * Recipe.FOOD_ID_RANGE_END, and neither catalog has a food there (AGENTS.md 4.2).
         */
        internal fun isCatalogId(id: Long): Boolean =
            id > 0 && !(id >= FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET && id < Recipe.FOOD_ID_RANGE_END)

        /** The arguments of [wordSearchSql] for [words] (from [searchWords]). */
        internal fun wordSearchArgs(words: List<String>, limit: Int): Array<String> {
            // terms and name_terms put a space before and after every word, so " dal%" is a word
            // starting with dal and " dal %" the word dal itself
            val escaped = words.map { escapeLikeWildcards(it) }
            val wordStarts = escaped.map { "% $it%" }
            val wholeWords = escaped.map { "% $it %" }
            val phrase = escaped.joinToString(" ")
            return (wordStarts + "%|$phrase|%" + wholeWords + " $phrase%" + wordStarts + limit.toString()).toTypedArray()
        }

        private fun escapeLikeWildcards(input: String): String {
            return input.replace("/", "//")
                        .replace("%", "/%")
                        .replace("_", "/_")
        }

        /** [text] lower-cased without accents on Latin letters, as the catalog's normalized_name is stored. */
        internal fun foldForNormalizedName(text: String): String =
            java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replace(LATIN_ACCENTS, "")
                .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }
    }
}
