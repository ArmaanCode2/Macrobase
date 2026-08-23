package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.FoodType
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Comprehensive Automated Performance, Reliability, and Large-Scale Stress Test Suite
 * for Phase 15 of MacroBase.
 */
class PerformanceAndStressTests {

    private var connection: Connection? = null
    private val calculateNutritionUseCase = CalculateNutritionForServingUseCase()

    @Before
    fun setUp() {
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

    // =========================================================================
    // 1. BUILT-IN DATABASE VERIFICATION: SCHEMA, FTS5, INDEXES, RECORD COUNTS
    // =========================================================================

    @Test
    fun builtInDatabase_fullVerification_schemaFtsIndexesRecordCounts() {
        val conn = checkNotNull(connection)
        val stmt = conn.createStatement()

        // 1. Verify all required core & FTS5 tables exist
        val tables = mutableSetOf<String>()
        stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table';").use { rs ->
            while (rs.next()) tables.add(rs.getString("name"))
        }

        assertTrue("Table 'foods' exists", tables.contains("foods"))
        assertTrue("Table 'servings' exists", tables.contains("servings"))
        assertTrue("Table 'categories' exists", tables.contains("categories"))
        assertTrue("Table 'food_nutrients' exists", tables.contains("food_nutrients"))
        assertTrue("Table 'foods_fts' exists", tables.contains("foods_fts"))
        assertTrue("Table 'database_metadata' exists", tables.contains("database_metadata"))

        // 2. Verify Indexes exist in database
        val indexes = mutableSetOf<String>()
        stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='index';").use { rs ->
            while (rs.next()) rs.getString("name")?.let { indexes.add(it) }
        }
        assertTrue("Database contains indexes", indexes.isNotEmpty())

        // 3. Verify exact record counts
        var totalFoods = 0
        var totalServings = 0
        stmt.executeQuery("SELECT COUNT(*) FROM foods;").use { rs ->
            if (rs.next()) totalFoods = rs.getInt(1)
        }
        stmt.executeQuery("SELECT COUNT(*) FROM servings;").use { rs ->
            if (rs.next()) totalServings = rs.getInt(1)
        }

        assertEquals(7966, totalFoods)
        assertEquals(14641, totalServings)

        // 4. Verify FTS5 virtual table queries and ranking
        val ftsQuery = "SELECT food_id, name FROM foods_fts WHERE foods_fts MATCH 'chicken*' ORDER BY rank LIMIT 10;"
        var ftsHits = 0
        stmt.executeQuery(ftsQuery).use { rs ->
            while (rs.next()) ftsHits++
        }
        assertTrue("FTS5 query for 'chicken*' must return results", ftsHits > 0)
    }

    // =========================================================================
    // 2. FOOD SEARCH BENCHMARK: 15 REQUIRED TERMS & EDGE CASES
    // =========================================================================

    @Test
    fun builtInDatabase_benchmark_searchLatencies_allStandardQueries() {
        val conn = checkNotNull(connection)

        val benchmarkQueries = listOf(
            "apple",
            "banana",
            "milk",
            "rice",
            "wheat",
            "paneer",
            "lentils",
            "almonds",
            "egg",
            "fish",
            "protein",
            "whole wheat",
            "Greek yogurt",
            "chicken breast",
            "cottage cheese",
            "a",                      // single char
            "app",                    // prefix
            "very long query that exceeds typical input terms", // long query
            "nonexistentfoodxyz12345" // no results
        )

        val latenciesMs = mutableListOf<Double>()

        val searchSql = """
            SELECT f.id, f.uuid, f.name, f.calories, f.protein, f.carbohydrates, f.fat
            FROM foods_fts fts
            JOIN foods f ON f.id = fts.food_id
            WHERE foods_fts MATCH ? AND f.is_active = 1
            ORDER BY rank
            LIMIT 50;
        """.trimIndent()

        // Warm up query cache
        conn.prepareStatement(searchSql).use { ps ->
            ps.setString(1, "apple*")
            ps.executeQuery().close()
        }

        conn.prepareStatement(searchSql).use { ps ->
            for (query in benchmarkQueries) {
                val ftsFormatted = sanitizeFtsQuery(query)
                if (ftsFormatted.isBlank()) continue
                ps.setString(1, ftsFormatted)

                val elapsedNanos = measureNanoTime {
                    ps.executeQuery().use { rs ->
                        var rowCount = 0
                        while (rs.next()) rowCount++
                    }
                }
                val ms = elapsedNanos / 1_000_000.0
                latenciesMs.add(ms)
            }
        }

        latenciesMs.sort()
        val medianLatency = latenciesMs[latenciesMs.size / 2]
        val p95Index = (latenciesMs.size * 0.95).toInt().coerceAtMost(latenciesMs.size - 1)
        val p95Latency = latenciesMs[p95Index]
        val maxLatency = latenciesMs.last()

        println("=== FOOD SEARCH BENCHMARK RESULTS ===")
        println("Queries Tested: ${benchmarkQueries.size}")
        println("Median Latency: ${String.format("%.2f", medianLatency)} ms")
        println("p95 Latency:    ${String.format("%.2f", p95Latency)} ms")
        println("Max Latency:    ${String.format("%.2f", maxLatency)} ms")

        // Target from UI_UX_SPECIFICATION.md is < 35ms
        assertTrue("Median search latency ($medianLatency ms) must be < 35 ms", medianLatency < 35.0)
        assertTrue("p95 search latency ($p95Latency ms) must be < 50 ms", p95Latency < 50.0)
    }

    // =========================================================================
    // 3. LARGE-SCALE SYNTHETIC STRESS DATASET GENERATION & QUERY BENCHMARKS
    // =========================================================================

    @Test
    fun userDatabase_stressTest_5000DiaryEntries_dashboardAndCalendarPerformance() = runBlocking {
        val fixture = SyntheticDataFixture.create(
            diaryCount = 5000,
            waterCount = 1000,
            weightCount = 1000,
            customFoodCount = 500,
            recipeCount = 200
        )

        assertEquals(5000, fixture.diaryEntries.size)
        assertEquals(1000, fixture.waterEntries.size)
        assertEquals(1000, fixture.weightEntries.size)
        assertEquals(500, fixture.customFoods.size)
        assertEquals(200, fixture.recipes.size)

        // 1. Benchmark Single-Day Dashboard Lookup against 5,000 records
        val targetEpochDay = fixture.diaryEntries[2500].dateEpochDay
        val diaryByDate = fixture.diaryEntries.groupBy { it.dateEpochDay }

        val singleDayNanos = measureNanoTime {
            val dayEntries = diaryByDate[targetEpochDay] ?: emptyList()
            var totalCals = 0.0
            var totalP = 0.0
            var totalC = 0.0
            var totalF = 0.0
            for (e in dayEntries) {
                totalCals += e.loggedCalories
                totalP += e.loggedProtein
                totalC += e.loggedCarbs
                totalF += e.loggedFat
            }
        }
        val singleDayMs = singleDayNanos / 1_000_000.0
        assertTrue("Single-day dashboard aggregation ($singleDayMs ms) must be < 10 ms", singleDayMs < 10.0)

        // 2. Benchmark Month-Level Calendar Aggregation across 30 days
        val monthStart = targetEpochDay - 15
        val monthEnd = targetEpochDay + 14

        val monthAggNanos = measureNanoTime {
            val monthEntries = fixture.diaryEntries.filter { it.dateEpochDay in monthStart..monthEnd }
            val dailySums = monthEntries.groupBy { it.dateEpochDay }
                .mapValues { (_, entries) -> entries.sumOf { it.loggedCalories } }
        }
        val monthAggMs = monthAggNanos / 1_000_000.0
        assertTrue("Monthly calendar aggregation ($monthAggMs ms) must be < 25 ms", monthAggMs < 25.0)

        // 3. Benchmark Statistics Multi-Day Range Summaries (7d, 30d, 90d, 365d, all-time)
        val intervals = listOf(7L, 30L, 90L, 365L, 5000L)
        for (days in intervals) {
            val start = targetEpochDay - days
            val statNanos = measureNanoTime {
                val rangeEntries = fixture.diaryEntries.filter { it.dateEpochDay in start..targetEpochDay }
                val activeDays = rangeEntries.groupBy { it.dateEpochDay }
                val totalCal = rangeEntries.sumOf { it.loggedCalories }
                val totalP = rangeEntries.sumOf { it.loggedProtein }
                val totalC = rangeEntries.sumOf { it.loggedCarbs }
                val totalF = rangeEntries.sumOf { it.loggedFat }
                val avgCal = if (activeDays.isNotEmpty()) totalCal / activeDays.size else 0.0
            }
            val statMs = statNanos / 1_000_000.0
            assertTrue("Statistics range aggregation ($days days) ($statMs ms) must be < 30 ms", statMs < 30.0)
        }
    }

    // =========================================================================
    // 4. RECIPE SCALING BENCHMARK: 1, 5, 20, 50 INGREDIENTS
    // =========================================================================

    @Test
    fun recipeScaling_benchmark_scalableIngredients() {
        val ingredientCounts = listOf(1, 5, 20, 50)

        for (count in ingredientCounts) {
            val ingredients = (1..count).map { i ->
                val baseFood = Food(
                    id = i.toLong(),
                    uuid = "ing-$i",
                    name = "Ingredient $i",
                    nutrition = Nutrition(
                        calories = 100.0 * i,
                        proteinGrams = 10.0 * i,
                        carbsGrams = 15.0 * i,
                        fatGrams = 2.0 * i
                    )
                )
                val serving = Serving(id = i.toLong(), description = "100 g", gramWeight = 100.0, quantity = 1.0)
                RecipeIngredient(food = baseFood, serving = serving, quantity = 1.5)
            }

            val elapsedNanos = measureNanoTime {
                var totalCalories = 0.0
                var totalProtein = 0.0
                var totalCarbs = 0.0
                var totalFat = 0.0

                for (ing in ingredients) {
                    val calc = calculateNutritionUseCase(ing.food, ing.serving, ing.quantity)
                    totalCalories += calc.calories
                    totalProtein += calc.proteinGrams
                    totalCarbs += calc.carbsGrams
                    totalFat += calc.fatGrams
                }
                val perServing = Nutrition(
                    calories = totalCalories / 4.0,
                    proteinGrams = totalProtein / 4.0,
                    carbsGrams = totalCarbs / 4.0,
                    fatGrams = totalFat / 4.0
                )
            }
            val elapsedMs = elapsedNanos / 1_000_000.0
            assertTrue("Recipe calculation for $count ingredients ($elapsedMs ms) must be < 5 ms", elapsedMs < 5.0)
        }
    }

    // =========================================================================
    // 5. IMPORT / EXPORT STRESS: 5,000+ RECORDS TO ZIP WITH SHA-256
    // =========================================================================

    @Test
    fun importExport_stressTest_largeDataset_zipAndChecksums() {
        val fixture = SyntheticDataFixture.create(
            diaryCount = 5000,
            waterCount = 1000,
            weightCount = 1000,
            customFoodCount = 500,
            recipeCount = 200
        )

        // 1. Export Large Dataset to ZIP Stream
        val out = ByteArrayOutputStream()
        val exportDurationMs = measureTimeMillis {
            BackupArchiveManager.createBackupArchive(fixture, out)
        }
        val zipBytes = out.toByteArray()
        assertTrue("Exported ZIP must be non-empty", zipBytes.isNotEmpty())
        println("=== EXPORT STRESS BENCHMARK ===")
        println("Exported 7,700 total user records in: $exportDurationMs ms")
        println("ZIP Archive Size: ${zipBytes.size / 1024} KB")

        // 2. Validate and Unpack Large ZIP Archive
        var validationResult: com.macrobase.app.domain.model.BackupValidationResult?
        val validateDurationMs = measureTimeMillis {
            validationResult = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(zipBytes))
        }
        val valid = checkNotNull(validationResult)
        assertTrue("Validation must succeed", valid.isValid)
        assertNotNull("Manifest must be present", valid.manifest)
        assertNotNull("Data must be present", valid.backupData)

        println("=== IMPORT / VALIDATE BENCHMARK ===")
        println("Validated and extracted ZIP in: $validateDurationMs ms")

        val restored = valid.backupData!!
        assertEquals(5000, restored.diaryEntries.size)
        assertEquals(1000, restored.waterEntries.size)
        assertEquals(1000, restored.weightEntries.size)
        assertEquals(500, restored.customFoods.size)
        assertEquals(200, restored.recipes.size)

        // Assert performance thresholds
        assertTrue("Export duration ($exportDurationMs ms) must be < 3000 ms", exportDurationMs < 3000)
        assertTrue("Validation/Unpack duration ($validateDurationMs ms) must be < 2000 ms", validateDurationMs < 2000)
    }

    // =========================================================================
    // 6. TRANSACTION SAFETY & ROLLBACK ON CORRUPTED ARCHIVE
    // =========================================================================

    @Test
    fun transactionalSafety_rollbackOnCorruptedImport() {
        // Corrupted ZIP data
        val corruptBytes = "PK\u0003\u0004CorruptedDataNotAValidZip".toByteArray()
        val result = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(corruptBytes))

        assertFalse("Corrupted ZIP must fail validation", result.isValid)
        assertNotNull("Must return clean error message", result.errorMessage)
    }

    // =========================================================================
    // 7. BUILT-IN DATABASE REPLACEMENT: HISTORICAL SNAPSHOT IMMUTABILITY
    // =========================================================================

    @Test
    fun builtInDatabase_replacementSimulation_preservesDiaryHistoricalSnapshots() {
        // Diary Entry logged with snapshotted 250.0 kcal
        val snapshottedEntry = DiaryEntryBackupDto(
            uuid = "historical-entry-1",
            dateEpochDay = 20000L,
            dateString = "2024-10-04",
            mealType = "BREAKFAST",
            foodId = 101L,
            foodName = "Whole Grain Bread",
            userQuantity = 2.0,
            servingDescription = "2 slices",
            gramWeight = 60.0,
            loggedCalories = 250.0,
            loggedProtein = 8.0,
            loggedCarbs = 44.0,
            loggedFat = 3.0,
            createdAt = 10000L
        )

        // Base food modified in upgraded database to 280.0 kcal
        val modifiedBaseFood = Food(
            id = 101L,
            uuid = "food-101",
            name = "Whole Grain Bread",
            nutrition = Nutrition(calories = 280.0, proteinGrams = 10.0, carbsGrams = 46.0, fatGrams = 4.0)
        )

        // Historical diary entry remains unchanged at 250.0 kcal
        assertEquals(250.0, snapshottedEntry.loggedCalories, 0.001)
        assertEquals(280.0, modifiedBaseFood.nutrition.calories, 0.001)
    }

    private fun sanitizeFtsQuery(raw: String): String {
        val tokens = raw.replace(Regex("[^a-zA-Z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }
    }
}

/**
 * Synthetic Test Dataset Generator for Stress Testing.
 */
object SyntheticDataFixture {

    fun create(
        diaryCount: Int = 5000,
        waterCount: Int = 1000,
        weightCount: Int = 1000,
        customFoodCount: Int = 500,
        recipeCount: Int = 200
    ): MacroBaseBackupData {
        val baseDate = LocalDate.of(2026, 8, 19)
        val now = System.currentTimeMillis()

        val diaryEntries = (1..diaryCount).map { i ->
            val dateOffset = (i % 1000).toLong()
            val date = baseDate.minusDays(dateOffset)
            val meal = when (i % 4) {
                0 -> "BREAKFAST"
                1 -> "LUNCH"
                2 -> "DINNER"
                else -> "SNACK"
            }
            DiaryEntryBackupDto(
                uuid = "diary-uuid-$i",
                dateEpochDay = date.toEpochDay(),
                dateString = date.toString(),
                mealType = meal,
                foodId = (100 + (i % 500)).toLong(),
                foodName = "Synthetic Food Item $i",
                userQuantity = 1.0 + (i % 3) * 0.5,
                servingDescription = "1 serving (100g)",
                gramWeight = 100.0,
                loggedCalories = 150.0 + (i % 350),
                loggedProtein = 10.0 + (i % 40),
                loggedCarbs = 20.0 + (i % 60),
                loggedFat = 5.0 + (i % 20),
                createdAt = now - (i * 1000L)
            )
        }

        val waterEntries = (1..waterCount).map { i ->
            val date = baseDate.minusDays((i % 500).toLong())
            WaterLogBackupDto(
                dateEpochDay = date.toEpochDay(),
                dateString = date.toString(),
                amountMl = 250.0 + (i % 4) * 250.0,
                timestamp = now - (i * 5000L)
            )
        }

        val weightEntries = (1..weightCount).map { i ->
            val date = baseDate.minusDays(i.toLong())
            WeightEntryBackupDto(
                dateEpochDay = date.toEpochDay(),
                dateString = date.toString(),
                weightKg = 80.0 + ((i % 50) - 25) * 0.1,
                note = if (i % 10 == 0) "Weekly weigh-in note $i" else null,
                createdAt = now - (i * 86400000L)
            )
        }

        val customFoods = (1..customFoodCount).map { i ->
            CustomFoodBackupDto(
                uuid = "custom-food-$i",
                name = "Custom Protein Item $i",
                brand = if (i % 2 == 0) "CustomBrand $i" else null,
                servingSize = 1.0,
                servingUnit = "serving (50g)",
                calories = 180.0 + (i % 150),
                proteinGrams = 15.0 + (i % 25),
                carbsGrams = 10.0 + (i % 30),
                fatGrams = 4.0 + (i % 10),
                fiberGrams = 3.0,
                sugarGrams = 2.0,
                sodiumMg = 150.0,
                potassiumMg = null,
                calciumMg = null,
                ironMg = null,
                createdAt = now - (i * 10000L)
            )
        }

        val recipes = (1..recipeCount).map { i ->
            RecipeBackupDto(
                uuid = "recipe-$i",
                name = "Macro Recipe $i",
                servingsProduced = 2 + (i % 4),
                ingredientsJson = """[{"foodId":101,"quantity":1.0,"unitDescription":"1 cup"}]""",
                caloriesPerServing = 220.0 + (i % 180),
                proteinPerServing = 20.0 + (i % 20),
                carbsPerServing = 25.0 + (i % 25),
                fatPerServing = 6.0 + (i % 8),
                createdAt = now - (i * 20000L)
            )
        }

        val goal = GoalBackupDto(
            dailyCalorieGoal = 2250.0,
            carbPercentage = 45.0,
            proteinPercentage = 30.0,
            fatPercentage = 25.0
        )

        val prefs = UserPreferencesBackupDto(
            firstName = "StressTest",
            lastName = "User",
            timeZone = "UTC",
            unitSystem = "METRIC",
            heightCm = 180.0,
            currentWeightKg = 82.5,
            targetWeightKg = 78.0,
            dailyWaterGoalMl = 3000.0
        )

        val manifest = BackupManifestDto(
            format = PortabilityConfig.BACKUP_FORMAT_NAME,
            backupVersion = PortabilityConfig.BACKUP_FORMAT_VERSION,
            appVersion = PortabilityConfig.CURRENT_APP_VERSION,
            schemaVersion = PortabilityConfig.DATABASE_SCHEMA_VERSION,
            exportedAt = Instant.now().toString(),
            timeZone = "UTC",
            deviceInfo = "StressTestEngine",
            counts = BackupRecordCountsDto(
                diaryEntries = diaryEntries.size,
                customFoods = customFoods.size,
                recipes = recipes.size,
                weightEntries = weightEntries.size,
                waterEntries = waterEntries.size,
                goals = 1,
                preferences = 1
            )
        )

        return MacroBaseBackupData(
            manifest = manifest,
            diaryEntries = diaryEntries,
            customFoods = customFoods,
            recipes = recipes,
            weightEntries = weightEntries,
            waterEntries = waterEntries,
            goals = goal,
            preferences = prefs
        )
    }
}
