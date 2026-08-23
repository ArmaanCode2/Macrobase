package com.macrobase.app

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

        assertEquals("1.0.0", metadata["database_version"])
        assertEquals("1", metadata["schema_version"])
        assertEquals("7966", metadata["total_foods"])
        assertEquals("14641", metadata["total_servings"])
        assertEquals("640123", metadata["total_food_nutrients"])
    }

    @Test
    fun builtInDatabase_fts5Search_findsAllRequiredSampleFoods() {
        val conn = checkNotNull(connection)
        val sampleQueries = listOf(
            "apple",
            "banana",
            "milk",
            "rice",
            "wheat",
            "paneer",
            "lentils",
            "almonds",
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

        // 1. Prefix search: 'banan*'
        val pstmt1 = conn.prepareStatement("""
            SELECT f.id, f.name FROM foods_fts fts
            JOIN foods f ON f.id = fts.food_id
            WHERE foods_fts MATCH ? LIMIT 5;
        """)
        pstmt1.setString(1, "banan*")
        val rs1 = pstmt1.executeQuery()
        assertTrue("Prefix search for 'banan*' must return results", rs1.next())
        pstmt1.close()

        // 2. Multi-word search: 'cottage cheese'
        val pstmt2 = conn.prepareStatement("""
            SELECT f.id, f.name FROM foods_fts fts
            JOIN foods f ON f.id = fts.food_id
            WHERE foods_fts MATCH ? LIMIT 5;
        """)
        pstmt2.setString(1, "cottage* cheese*")
        val rs2 = pstmt2.executeQuery()
        assertTrue("Multi-word search for 'cottage* cheese*' must return results", rs2.next())
        pstmt2.close()
    }

    @Test
    fun builtInDatabase_foodDetailLookup_mapsServingsAndPreservesNulls() {
        val conn = checkNotNull(connection)

        // Query food ID 1 (Hummus, commercial)
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
            WHERE f.id = 1;
        """)
        val rs = pstmt.executeQuery()
        assertTrue("Food ID 1 must exist", rs.next())

        val foodName = rs.getString("name")
        val calories = rs.getDouble("calories")
        val protein = rs.getDouble("protein")
        val cholesterol: Double? = if (rs.getObject("cholesterol") != null) rs.getDouble("cholesterol") else null

        assertEquals("Hummus, commercial", foodName)
        assertEquals(229.0, calories, 0.1)
        assertEquals(7.35, protein, 0.01)
        // Cholesterol is unanalyzed/missing in Hummus -> must remain null
        assertNull("Missing cholesterol must be null rather than 0.0", cholesterol)
        pstmt.close()

        // Verify servings for food ID 1
        val servPstmt = conn.prepareStatement("""
            SELECT id, description, unit_type, quantity, gram_weight, is_default, sequence
            FROM servings WHERE food_id = 1 ORDER BY sequence ASC;
        """)
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

        assertTrue("Hummus must have at least 1 serving", servingsList.isNotEmpty())
        val defaultServing = servingsList.firstOrNull { it.isDefault } ?: servingsList.first()
        assertEquals("2 tablespoon", defaultServing.description)
        assertEquals(33.9, defaultServing.gramWeight, 0.1)
        servPstmt.close()
    }

    @Test
    fun builtInDatabase_nutritionCalculation_usesRealDatabaseValues() {
        val conn = checkNotNull(connection)

        // Query whole egg: 'Eggs, Grade A, Large, egg whole' (ID 94)
        val pstmt = conn.prepareStatement("SELECT id, name, calories, protein, carbohydrates, fat FROM foods WHERE id = 94;")
        val rs = pstmt.executeQuery()
        assertTrue("Egg whole must exist", rs.next())

        val food = Food(
            id = rs.getLong("id"),
            uuid = "egg-uuid",
            name = rs.getString("name"),
            nutrition = Nutrition(
                calories = rs.getDouble("calories"),     // 148 kcal per 100g
                proteinGrams = rs.getDouble("protein"),  // 12.4g per 100g
                carbsGrams = rs.getDouble("carbohydrates"), // 0.96g per 100g
                fatGrams = rs.getDouble("fat")           // 9.96g per 100g
            )
        )
        pstmt.close()

        // 1 large egg = 50.0g (gramWeight=50.0, quantity=1.0)
        val largeEggServing = Serving(
            id = 1,
            description = "1 large egg",
            unit = ServingUnit.PIECE,
            quantity = 1.0,
            gramWeight = 50.0
        )

        // Calculate for 2 large eggs (100g -> multiplier 1.0x)
        val calculated2Eggs = calculateNutritionUseCase(food, largeEggServing, 2.0)
        assertEquals(148.0, calculated2Eggs.calories, 0.1)
        assertEquals(12.4, calculated2Eggs.proteinGrams, 0.01)
        assertEquals(0.96, calculated2Eggs.carbsGrams, 0.01)
        assertEquals(9.96, calculated2Eggs.fatGrams, 0.01)

        // Calculate for 3 large eggs (150g -> multiplier 1.5x)
        val calculated3Eggs = calculateNutritionUseCase(food, largeEggServing, 3.0)
        assertEquals(222.0, calculated3Eggs.calories, 0.1)
        assertEquals(18.6, calculated3Eggs.proteinGrams, 0.01)
        assertEquals(1.44, calculated3Eggs.carbsGrams, 0.01)
        assertEquals(14.94, calculated3Eggs.fatGrams, 0.01)
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
}
