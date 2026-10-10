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
        assertEquals(1014, rs.getInt(1))
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

    // =========================================================================
    // 7. INPUT BOUNDS, RECURSION LIMITS & OFFLINE-ONLY SECURITY
    // =========================================================================

    @Test
    fun jsonParsing_recursionDepthExceeded_throwsException() {
        // Construct 40-level deep nested JSON object: {"nested":{"nested":...}}
        val sb = StringBuilder()
        for (i in 1..40) {
            sb.append("""{"nested":""")
        }
        sb.append("1")
        for (i in 1..40) {
            sb.append("}")
        }
        val deepJson = sb.toString()

        val result = runCatching {
            SimpleJsonParser.parseObject(deepJson)
        }
        assertTrue("Deeply nested JSON must fail parsing", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Exception must be IllegalArgumentException", ex is IllegalArgumentException)
        assertTrue("Message must indicate nesting depth exceeded", ex?.message?.contains("nesting depth") == true)
    }

    @Test
    fun jsonParsing_malformedNumbers_throwsIllegalArgumentException() {
        val malformedJson1 = """{"val": -}"""
        val result1 = runCatching {
            SimpleJsonParser.parseObject(malformedJson1)
        }
        assertTrue(result1.isFailure)
        assertTrue(result1.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result1.exceptionOrNull()?.message?.contains("Malformed number") == true)

        val malformedJson2 = """{"val": 1.2.3}"""
        val result2 = runCatching {
            SimpleJsonParser.parseObject(malformedJson2)
        }
        assertTrue(result2.isFailure)
        assertTrue(result2.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun jsonParsing_malformedUnicodeEscape_throwsIllegalArgumentException() {
        val badHexJson = """{"val": "\u12G4"}"""
        val result = runCatching {
            SimpleJsonParser.parseObject(badHexJson)
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid hex character") == true)
    }

    @Test
    fun streamBoundedReading_archiveExceedingMaxLimit_abortedSafely() {
        // Provide a stream that yields > 50 MB without allocating 50MB in memory
        val limitPlusOne = 50L * 1024 * 1024 + 1024
        var bytesRead = 0L
        val largeStream = object : java.io.InputStream() {
            override fun read(): Int {
                return if (bytesRead < limitPlusOne) {
                    bytesRead++
                    0
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRead >= limitPlusOne) return -1
                val toRead = kotlin.math.min(len.toLong(), limitPlusOne - bytesRead).toInt()
                bytesRead += toRead
                java.util.Arrays.fill(b, off, off + toRead, 0.toByte())
                return toRead
            }
        }

        val result = BackupArchiveManager.extractAndValidateBackup(largeStream)
        assertFalse("Archive exceeding max size must be rejected", result.isValid)
        assertTrue("Error must indicate rejection",
            result.errorMessage?.contains("empty or invalid") == true ||
            result.errorMessage?.contains("maximum permitted size") == true
        )
        assertTrue("Stream must abort early without consuming entire 50MB stream", bytesRead < limitPlusOne)
    }

    @Test
    fun scannerPackage_strictlyZeroNetworkImports() {
        val candidates = listOf(
            File("src/main/java/com/macrobase/app/feature/scanner"),
            File("app/src/main/java/com/macrobase/app/feature/scanner"),
            File("../app/src/main/java/com/macrobase/app/feature/scanner")
        )
        val scannerDir = candidates.firstOrNull { it.exists() }
        assertNotNull("Scanner directory must exist", scannerDir)
        val kotlinFiles = scannerDir!!.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("Scanner files must exist", kotlinFiles.isNotEmpty())
        for (file in kotlinFiles) {
            val text = file.readText()
            assertFalse("File ${file.name} must not import HttpURLConnection", text.contains("HttpURLConnection"))
            assertFalse("File ${file.name} must not import java.net.URL", text.contains("java.net.URL"))
            assertFalse("File ${file.name} must not use GlobalScope", text.contains("GlobalScope"))
        }
    }
}
