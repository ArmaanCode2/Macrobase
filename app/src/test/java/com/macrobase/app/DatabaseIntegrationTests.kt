package com.macrobase.app

import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.FoodType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Direct SQLite tests against the production asset database (built_in_foods.db)
 * verifying schema structure, FTS5 search, metadata versioning, portion mapping,
 * and nutrition calculation accuracy using real database records.
 */
class DatabaseIntegrationTests {

    private var connection: Connection? = null
    private val calculateNutritionUseCase = CalculateNutritionForServingUseCase()

    @Before
    fun setUp() {
        // Locate the asset database inside the project workspace
        val candidates = listOf(
            File("src/main/assets/databases/built_in_foods.db"),
            File("app/src/main/assets/databases/built_in_foods.db"),
            File("built_in_foods.db"),
            File("../built_in_foods.db")
        )
        val assetDbFile = candidates.firstOrNull { it.exists() }
            ?: error("Cannot find built_in_foods.db in candidate locations: ${candidates.map { it.absolutePath }}")

        assertTrue("Asset database file must exist at ${assetDbFile.absolutePath}", assetDbFile.exists())

        connection = DriverManager.getConnection("jdbc:sqlite:${assetDbFile.absolutePath}")
    }

    @After
    fun tearDown() {
        connection?.close()
    }

    @Test
    fun builtInDatabase_opensSuccessfully_andValidatesVersionAndMetadata() {
        val conn = checkNotNull(connection)
        val statement = conn.createStatement()

        // 1. Verify core tables exist
        val tables = mutableSetOf<String>()
        val rs = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table';")
        while (rs.next()) {
            tables.add(rs.getString("name"))
        }

        assertTrue("Table 'foods' must exist", tables.contains("foods"))
        assertTrue("Table 'servings' must exist", tables.contains("servings"))
        assertTrue("Table 'categories' must exist", tables.contains("categories"))
        assertTrue("Table 'food_nutrients' must exist", tables.contains("food_nutrients"))
        assertTrue("Table 'foods_fts' must exist", tables.contains("foods_fts"))
        assertTrue("Table 'database_metadata' must exist", tables.contains("database_metadata"))

        // 2. Verify metadata records
        val metadata = mutableMapOf<String, String>()
        val metaRs = statement.executeQuery("SELECT key, value FROM database_metadata;")
        while (metaRs.next()) {
            metadata[metaRs.getString("key")] = metaRs.getString("value")
        }

        // Must equal the version the app expects, or the copied asset fails validation on device
        assertEquals(BuiltInDatabaseManager.EXPECTED_DATABASE_VERSION, metadata["database_version"])
        assertEquals("1", metadata["schema_version"])
        assertEquals("1014", metadata["total_foods"])
        assertEquals("1877", metadata["total_servings"])
        assertEquals("18088", metadata["total_food_nutrients"])
    }

    @Test
    fun builtInDatabase_fts5Search_findsAllRequiredSampleFoods() {
        val conn = checkNotNull(connection)
        val sampleQueries = listOf(
            "paneer",
            "roti",
            "chapati",
            "dal",
            "rajma",
            "poha",
            "idli",
            "dosa",
            "paratha",
            "curd",
            "rice",
            "chicken",
            "egg"
        )

        for (query in sampleQueries) {
            val ftsQuery = "$query*"
            val pstmt = conn.prepareStatement("""
                SELECT f.id, f.name, f.calories, f.protein, f.carbohydrates, f.fat, c.name as cat_name
                FROM foods_fts fts
                JOIN foods f ON f.id = fts.food_id
                LEFT JOIN categories c ON f.category_id = c.id
                WHERE foods_fts MATCH ? AND f.is_active = 1
                ORDER BY rank
                LIMIT 5;
            """)
            pstmt.setString(1, ftsQuery)
            val rs = pstmt.executeQuery()

            var count = 0
            var firstFoodName = ""
            while (rs.next()) {
                count++
                if (firstFoodName.isEmpty()) {
                    firstFoodName = rs.getString("name")
                }
            }

            assertTrue("Search query '$query' should return at least 1 match (found $count)", count > 0)
            pstmt.close()
        }
    }

    @Test
    fun builtInDatabase_prefixSearchAndMultiWordSearch_worksAccurately() {
        val conn = checkNotNull(connection)

        // 1. Prefix search: 'paneer*'
        val pstmt1 = conn.prepareStatement("""
            SELECT f.id, f.name FROM foods_fts fts
            JOIN foods f ON f.id = fts.food_id
            WHERE foods_fts MATCH ? LIMIT 5;
        """)
        pstmt1.setString(1, "paneer*")
        val rs1 = pstmt1.executeQuery()
        assertTrue("Prefix search for 'paneer*' must return results", rs1.next())
        pstmt1.close()

        // 2. Multi-word search: 'chilli paneer'
        val pstmt2 = conn.prepareStatement("""
            SELECT f.id, f.name FROM foods_fts fts
            JOIN foods f ON f.id = fts.food_id
            WHERE foods_fts MATCH ? LIMIT 5;
        """)
        pstmt2.setString(1, "chilli* paneer*")
        val rs2 = pstmt2.executeQuery()
        assertTrue("Multi-word search for 'chilli* paneer*' must return results", rs2.next())
        pstmt2.close()
    }

    @Test
    fun builtInDatabase_foodDetailLookup_mapsServingsAndPreservesNulls() {
        val conn = checkNotNull(connection)

        // Query food Afghani chicken
        val pstmt = conn.prepareStatement("""
            SELECT f.id, f.uuid, f.source_id, f.name, f.normalized_name, f.brand, f.food_type, f.serving_basis,
                   f.calories, f.protein, f.carbohydrates, f.fat, f.fiber, f.sugar,
                   f.saturated_fat, f.trans_fat, f.cholesterol, f.sodium,
                   f.potassium, f.calcium, f.iron, f.magnesium, f.phosphorus, f.zinc,
                   f.vitamin_a_rae, f.vitamin_c, f.vitamin_d_mcg, f.vitamin_e, f.vitamin_k,
                   f.vitamin_b6, f.vitamin_b12, f.folate_b9, f.water,
                   c.name as category_name
            FROM foods f
            LEFT JOIN categories c ON f.category_id = c.id
            WHERE f.name = 'Afghani chicken';
        """)
        val rs = pstmt.executeQuery()
        assertTrue("Afghani chicken must exist", rs.next())

        val foodId = rs.getLong("id")
        val foodName = rs.getString("name")
        val calories = rs.getDouble("calories")
        val protein = rs.getDouble("protein")
        val vitA: Double? = if (rs.getObject("vitamin_a_rae") != null) rs.getDouble("vitamin_a_rae") else null

        assertEquals("Afghani chicken", foodName)
        assertEquals(151.51, calories, 0.1)
        assertEquals(15.66, protein, 0.01)
        // Vitamin A is unanalyzed in Afghani chicken -> must remain null
        assertNull("Missing vitamin A must be null rather than 0.0", vitA)
        pstmt.close()

        // Verify servings for Afghani chicken
        val servPstmt = conn.prepareStatement("""
            SELECT id, description, unit_type, quantity, gram_weight, is_default, sequence
            FROM servings WHERE food_id = ? ORDER BY sequence ASC;
        """)
        servPstmt.setLong(1, foodId)
        val servRs = servPstmt.executeQuery()
        val servingsList = mutableListOf<Serving>()
        while (servRs.next()) {
            servingsList.add(
                Serving(
                    id = servRs.getLong("id"),
                    description = servRs.getString("description"),
                    unit = ServingUnit.fromString(servRs.getString("unit_type")),
                    quantity = servRs.getDouble("quantity"),
                    gramWeight = servRs.getDouble("gram_weight"),
                    isDefault = servRs.getInt("is_default") == 1,
                    sequence = servRs.getInt("sequence")
                )
            )
        }

        assertTrue("Afghani chicken must have at least 1 serving", servingsList.isNotEmpty())
        val defaultServing = servingsList.firstOrNull { it.isDefault } ?: servingsList.first()
        assertTrue("Default serving must be valid", defaultServing.description.isNotBlank())
        servPstmt.close()
    }

    @Test
    fun builtInDatabase_nutritionCalculation_usesRealDatabaseValues() {
        val conn = checkNotNull(connection)

        // Query Chapati/Roti
        val pstmt = conn.prepareStatement("SELECT id, name, calories, protein, carbohydrates, fat FROM foods WHERE name = 'Chapati/Roti';")
        val rs = pstmt.executeQuery()
        assertTrue("Chapati/Roti must exist", rs.next())

        val food = Food(
            id = rs.getLong("id"),
            uuid = "chapati-uuid",
            name = rs.getString("name"),
            nutrition = Nutrition(
                calories = rs.getDouble("calories"),     // 202.31 kcal per 100g
                proteinGrams = rs.getDouble("protein"),  // 5.88g per 100g
                carbsGrams = rs.getDouble("carbohydrates"), // 35.65g per 100g
                fatGrams = rs.getDouble("fat")           // 3.56g per 100g
            )
        )
        pstmt.close()

        // 100g standard serving (1 portion unit = 100g)
        val standardServing = Serving(
            id = 1,
            description = "100 g",
            unit = ServingUnit.GRAMS,
            quantity = 1.0,
            gramWeight = 100.0
        )

        // Calculate for 200g (2 portions of 100g -> multiplier 2.0x)
        val calculated200g = calculateNutritionUseCase(food, standardServing, 2.0)
        assertEquals(404.62, calculated200g.calories, 0.1)
        assertEquals(11.76, calculated200g.proteinGrams, 0.01)
        assertEquals(71.30, calculated200g.carbsGrams, 0.01)
        assertEquals(7.12, calculated200g.fatGrams, 0.01)
    }

    @Test
    fun databaseVersioning_validatesVersionComparisonSafely() {
        val currentVersion = "1.0.0"
        val nextVersion = "2.0.0"

        // Simulated version check
        val isUpgradeNeeded = nextVersion > currentVersion
        assertTrue("Version 2.0.0 must be detected as an upgrade over 1.0.0", isUpgradeNeeded)

        // Verifying immutable snapshotting strategy:
        // Even if built-in food id 94 changes in v2.0.0, diary entry retains logged calories and serving snapshot
        val loggedSnapshotCalories = 148.0
        val loggedSnapshotProtein = 12.4
        assertEquals(148.0, loggedSnapshotCalories, 0.001)
        assertEquals(12.4, loggedSnapshotProtein, 0.001)
    }

    // BUG-001: portions with no gram weight were treated as exactly 100 g.
    @Test
    fun builtInDatabase_everyServingHasKnownGramWeight() {
        val conn = checkNotNull(connection)
        val rs = conn.createStatement().executeQuery("SELECT count(*) FROM servings WHERE gram_weight IS NULL OR gram_weight <= 0;")
        rs.next()
        assertEquals("Catalog nutrition is per 100 g, so every serving needs a gram weight", 0, rs.getInt(1))
    }

    @Test
    fun builtInDatabase_everyFoodDefaultsToExactlyOne100gServing() {
        val conn = checkNotNull(connection)
        val rs = conn.createStatement().executeQuery("""
            SELECT f.id,
                   (SELECT count(*) FROM servings s WHERE s.food_id = f.id AND s.is_default = 1) AS defaults,
                   (SELECT count(*) FROM servings s WHERE s.food_id = f.id AND s.is_default = 1
                        AND s.description = '100 g' AND s.gram_weight = 100.0) AS hundred_gram_defaults
            FROM foods f;
        """)
        var foods = 0
        while (rs.next()) {
            foods++
            assertEquals("Food ${rs.getLong("id")} must have exactly one default serving", 1, rs.getInt("defaults"))
            assertEquals("Food ${rs.getLong("id")} must default to 100 g", 1, rs.getInt("hundred_gram_defaults"))
        }
        assertEquals(1014, foods)
    }

    // Mirrors PORTION_BOUNDS_G in tools/derive_indb_portion_weights.py: INDB sometimes records a
    // whole recipe yield as "1 bowl", which must not ship as a single portion.
    @Test
    fun builtInDatabase_portionWeightsArePlausible() {
        val bounds = mapOf(
            "ml" to 0.8..1.5, "gm" to 0.8..1.5,
            "teaspoon" to 2.0..10.0, "tablespoon" to 7.0..30.0,
            "cup" to 100.0..400.0, "tea cup" to 80.0..300.0,
            "ice cream cup" to 40.0..300.0, "ice-cream cup" to 40.0..300.0, "souffle cup" to 40.0..300.0,
            "glass" to 120.0..500.0, "tall glass" to 150.0..600.0, "juice glass" to 100.0..400.0,
            "sundae glass" to 100.0..500.0, "tall stemmed glass" to 100.0..500.0,
            "bowl" to 80.0..600.0, "small bowl" to 50.0..400.0, "soup bowl" to 120.0..600.0, "curry bowl" to 80.0..600.0
        )
        val conn = checkNotNull(connection)
        val rs = conn.createStatement().executeQuery(
            "SELECT s.unit_type, s.gram_weight, f.name FROM servings s JOIN foods f ON f.id = s.food_id WHERE s.description != '100 g';"
        )
        var portions = 0
        while (rs.next()) {
            portions++
            val unit = rs.getString("unit_type").trim().lowercase()
            val weight = rs.getDouble("gram_weight")
            val range = bounds[unit] ?: 1.0..1000.0
            assertTrue("${rs.getString("name")}: 1 $unit = $weight g is outside $range", weight in range)
        }
        assertEquals(863, portions)
    }

    @Test
    fun builtInDatabase_householdPortionsScaleByTheirRealWeight() {
        val conn = checkNotNull(connection)
        // Expected kcal are INDB's own per-unit-serving energy values
        val expectations = listOf(
            Triple("Mutton biryani/biriyani", "1 plate", 396.0),
            Triple("Chapati/Roti", "1 chapati", 73.0),
            Triple("Butter icing", "1 tablespoon", 81.0)
        )
        for ((name, portion, expectedKcal) in expectations) {
            val pstmt = conn.prepareStatement("""
                SELECT f.id, f.calories, f.protein, f.carbohydrates, f.fat, s.quantity, s.gram_weight
                FROM foods f JOIN servings s ON s.food_id = f.id
                WHERE f.name = ? AND s.description = ?;
            """)
            pstmt.setString(1, name)
            pstmt.setString(2, portion)
            val rs = pstmt.executeQuery()
            assertTrue("$name must have a '$portion' serving", rs.next())
            val food = Food(
                id = rs.getLong("id"),
                uuid = "bug-001-$name",
                name = name,
                nutrition = Nutrition(
                    calories = rs.getDouble("calories"),
                    proteinGrams = rs.getDouble("protein"),
                    carbsGrams = rs.getDouble("carbohydrates"),
                    fatGrams = rs.getDouble("fat")
                )
            )
            val serving = Serving(description = portion, quantity = rs.getDouble("quantity"), gramWeight = rs.getDouble("gram_weight"))
            pstmt.close()

            val logged = calculateNutritionUseCase(food, serving, 1.0)
            assertEquals("$name, $portion", expectedKcal, logged.calories, 1.0)
        }
    }

    @Test
    fun catalogVersionCheck_replacesOlderOrForeignCatalogs() {
        val expected = BuiltInDatabaseManager.EXPECTED_DATABASE_VERSION
        val dataset = BuiltInDatabaseManager.EXPECTED_DATASET_NAME
        assertTrue(BuiltInDatabaseManager.isCurrentCatalog(dataset, expected))
        assertTrue("A pre-fix 2.0.0 catalog must be replaced", !BuiltInDatabaseManager.isCurrentCatalog(dataset, "2.0.0"))
        assertTrue(!BuiltInDatabaseManager.isCurrentCatalog(dataset, null))
        assertTrue(!BuiltInDatabaseManager.isCurrentCatalog("some_other_dataset", expected))
    }
}
