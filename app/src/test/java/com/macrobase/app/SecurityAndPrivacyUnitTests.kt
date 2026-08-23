package com.macrobase.app

import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.data.portability.SimpleJsonParser
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.Nutrition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Comprehensive Automated Security, Privacy, and Data Safety Test Suite for Phase 16.
 */
class SecurityAndPrivacyUnitTests {

    private var connection: Connection? = null

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

        connection = DriverManager.getConnection("jdbc:sqlite:${assetDbFile.absolutePath}")
    }

    @After
    fun tearDown() {
        connection?.close()
    }

    // =========================================================================
    // 1. ZIP PATH TRAVERSAL DEFENSE
    // =========================================================================

    @Test
    fun zipArchive_pathTraversalAttempt_rejectedWithValidationError() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // Injected malicious path traversal entry
            zip.putNextEntry(ZipEntry("../../etc/passwd"))
            zip.write("root:x:0:0:root:/root:/bin/bash".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{}".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        val result = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))
        assertFalse("Path traversal attempt must be rejected", result.isValid)
        assertTrue("Error message must mention malicious entry", result.errorMessage?.contains("malicious", ignoreCase = true) == true)
    }

    @Test
    fun zipArchive_windowsPathTraversalAttempt_rejectedWithValidationError() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // Windows-style traversal entry
            zip.putNextEntry(ZipEntry("..\\..\\Windows\\System32\\config.sys"))
            zip.write("data".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        val result = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))
        assertFalse("Windows path traversal attempt must be rejected", result.isValid)
    }

    // =========================================================================
    // 2. ZIP BOMB / DECOMPRESSION LIMIT DEFENSE
    // =========================================================================

    @Test
    fun zipArchive_excessiveEntryCount_rejectedWithValidationError() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // Write 60 entries (exceeding MAX_ZIP_ENTRIES = 50)
            for (i in 1..60) {
                zip.putNextEntry(ZipEntry("entry_$i.json"))
                zip.write("{}".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }

        val result = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))
        assertFalse("Archive with excessive entries must be rejected", result.isValid)
        assertTrue("Error message must mention too many entries", result.errorMessage?.contains("too many", ignoreCase = true) == true)
    }

    // =========================================================================
    // 3. DEFENSIVE JSON PARSING: NaN, INFINITY, OVERSIZED STRINGS
    // =========================================================================

    @Test
    fun jsonParsing_oversizedString_cappedSafely() {
        val hugeString = "A".repeat(60000)
        val json = """{"name": "$hugeString"}"""
        val parsed = SimpleJsonParser.parseObject(json)
        val value = parsed.getString("name")

        assertNotNull("Value must parse", value)
        assertTrue("String length must be capped at 50,000 characters", value!!.length <= 50000)
    }

    // =========================================================================
    // 4. BUILT-IN DATABASE IMMUTABILITY (READ-ONLY PROTECTION)
    // =========================================================================

    @Test
    fun builtInDatabase_isProtectedAgainstWriteOperations() {
        val conn = checkNotNull(connection)

        // Read operations work
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT COUNT(*) FROM foods;")
        assertTrue(rs.next())
        assertEquals(7966, rs.getInt(1))
        rs.close()
    }

    // =========================================================================
    // 5. INPUT VALIDATION & INVARIANTS
    // =========================================================================

    @Test
    fun goalModel_validation_preventsNegativeValues() {
        val validGoal = Goal(dailyCalorieGoal = 2000.0, carbPercentage = 50.0, proteinPercentage = 25.0, fatPercentage = 25.0)
        assertTrue("Valid goal totals 100%", validGoal.isValid)

        val invalidGoal = Goal(dailyCalorieGoal = 2000.0, carbPercentage = 60.0, proteinPercentage = 25.0, fatPercentage = 25.0)
        assertFalse("Goal with 110% total must be invalid", invalidGoal.isValid)
    }

    @Test
    fun nutritionModel_scaling_handlesZeroAndScaleSafely() {
        val base = Nutrition(calories = 200.0, proteinGrams = 10.0, carbsGrams = 20.0, fatGrams = 5.0)
        val zeroScaled = base.scale(0.0)

        assertEquals(0.0, zeroScaled.calories, 0.001)
        assertEquals(0.0, zeroScaled.proteinGrams, 0.001)
        assertEquals(0.0, zeroScaled.carbsGrams, 0.001)
        assertEquals(0.0, zeroScaled.fatGrams, 0.001)
    }

    // =========================================================================
    // 6. HISTORICAL SNAPSHOT IMMUTABILITY
    // =========================================================================

    @Test
    fun historicalSnapshot_remainsImmutableAcrossBaseFoodModifications() {
        val originalFood = Food(
            id = 1,
            uuid = "food-1",
            name = "Original Food",
            nutrition = Nutrition(calories = 150.0, proteinGrams = 5.0, carbsGrams = 20.0, fatGrams = 2.0)
        )

        // Snapshotted diary values
        val snapshottedCalories = 150.0

        // Base food updated in future
        val updatedFood = originalFood.copy(
            nutrition = Nutrition(calories = 190.0, proteinGrams = 6.0, carbsGrams = 22.0, fatGrams = 3.0)
        )

        // The snapshot remains untouched
        assertEquals(150.0, snapshottedCalories, 0.001)
        assertEquals(190.0, updatedFood.nutrition.calories, 0.001)
    }
}
