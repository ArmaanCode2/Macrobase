package com.macrobase.app

import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.data.portability.SimpleJsonParser
import com.macrobase.app.core.util.briefErrorText
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.WaterLogBackupDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * P3 phase 2 (BUG-047): a hostile or very large backup fails with a short message that never quotes
 * the file, and a heavy user's own backup still restores. Plain JVM: the archive code has no Android
 * dependencies.
 */
class P3PhaseTwoBackupImportTests {

    // ---------------------------------------------------------------------------------------
    // Size limits
    // ---------------------------------------------------------------------------------------

    // Five years at 30 entries a day: the limits must leave twice that much room
    @Test
    fun heavyUsersOwnBackupRestoresWithTwiceTheRoomToSpare() {
        val entries = (0 until 5 * 365 * 30).map { diaryEntry(it) }
        val diaryBytes = BackupJsonSerializer.serializeDiaryEntries(entries).toByteArray(Charsets.UTF_8).size

        assertTrue("diary.json is $diaryBytes bytes", diaryBytes * 2L <= BackupArchiveManager.MAX_ENTRY_SIZE_BYTES)
        assertTrue(entries.size * 2 <= BackupArchiveManager.MAX_DIARY_RECORDS)

        val result = validate(archive(backupData(diary = entries)))
        assertTrue(result.errorMessage, result.isValid)
        assertEquals(entries.size, result.backupData!!.diaryEntries.size)
        assertEquals(entries.last(), result.backupData!!.diaryEntries.last())
    }

    @Test
    fun aZipBombIsStoppedWithAShortMessage() {
        val files = unzip(archive(backupData()))
        files.remove(PortabilityConfig.FILE_DIARY)
        val bomb = rezip(files, extra = mapOf(PortabilityConfig.FILE_DIARY to BackupArchiveManager.MAX_ENTRY_SIZE_BYTES + 1024))

        val result = validate(bomb)
        assertFalse(result.isValid)
        assertShortAndClean(result)
        assertEquals(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE, result.errorMessage)
    }

    // Each file stays under its own limit; together they pass the whole-archive limit
    @Test
    fun theWholeArchiveLimitCountsEveryFileIncludingUnknownOnes() {
        val files = unzip(archive(backupData()))
        val half = BackupArchiveManager.MAX_TOTAL_UNCOMPRESSED_BYTES / 2 + 1024
        val result = validate(rezip(files, extra = mapOf("notes/a.bin" to half, "notes/b.bin" to half)))

        assertFalse(result.isValid)
        assertShortAndClean(result)
    }

    @Test
    fun unknownFilesAreSkippedAndTheBackupStillRestores() {
        val data = backupData(diary = listOf(diaryEntry(1)))
        val withExtra = rezip(unzip(archive(data)), extra = mapOf("photos/cat.bin" to 10L * 1024 * 1024))

        val result = validate(withExtra)
        assertTrue(result.errorMessage, result.isValid)
        assertEquals(1, result.backupData!!.diaryEntries.size)
    }

    @Test
    fun tooManyRecordsInOneFileIsRefused() {
        val records = "[" + List(BackupArchiveManager.MAX_WATER_RECORDS + 1) { "{}" }.joinToString(",") + "]"
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_WATER, records))

        assertFalse(result.isValid)
        // Real data can get there, so it is "too large", not "corrupted"
        assertEquals(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE, result.errorMessage)
    }

    // ---------------------------------------------------------------------------------------
    // Error messages never quote the file
    // ---------------------------------------------------------------------------------------

    @Test
    fun aMalformedDiaryIsReportedByPositionWithoutItsContent() {
        val secret = "SECRET-SNACK-".repeat(70_000) // about 0.9 MB of the user's own text
        val broken = """[{"foodName": "$secret", "uuid": oops}]"""
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_DIARY, broken))

        assertFalse(result.isValid)
        assertShortAndClean(result)
        assertTrue(result.errorMessage, result.errorMessage!!.startsWith("Corrupted diary.json"))
    }

    @Test
    fun anUnterminatedStringIsReportedWithoutItsContent() {
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_DIARY, """[{"foodName": "SECRET-SNACK and more"""))

        assertFalse(result.isValid)
        assertShortAndClean(result)
    }

    @Test
    fun aMaliciousLongEntryNameIsCutShort() {
        val files = unzip(archive(backupData()))
        files["../" + "x".repeat(5000)] = ByteArray(1)
        val result = validate(rezip(files))

        assertFalse(result.isValid)
        assertShortAndClean(result)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("malicious"))
    }

    @Test
    fun parserMessagesNameAPositionNotTheText() {
        val notAnArray = assertThrows(IllegalArgumentException::class.java) {
            SimpleJsonParser.parseArray("""{"foodName": "SECRET-SNACK"}""")
        }
        assertEquals("JSON is not an array", notAnArray.message)

        val afterAValue = assertThrows(IllegalArgumentException::class.java) {
            SimpleJsonParser.parseObject("""{"foodName": "SECRET-SNACK" oops}""")
        }
        assertFalse(afterAValue.message!!, afterAValue.message!!.contains("SECRET-SNACK"))

        // A field name is quoted, cut to 40 characters
        val longKey = assertThrows(IllegalArgumentException::class.java) {
            SimpleJsonParser.parseObject("{\"" + "k".repeat(10_000) + "\" 1}")
        }
        assertTrue(longKey.message!!, longKey.message!!.length < 120)
    }

    // ---------------------------------------------------------------------------------------
    // Every record is small and flat, so memory follows the record count
    // ---------------------------------------------------------------------------------------

    // One record padded with tiny nested objects used to cost far more memory than its size
    @Test
    fun aRecordWithNestedValuesIsRefused() {
        val nested = "[{\"x\": [" + List(100_000) { "{}" }.joinToString(",") + "]}]"
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_DIARY, nested))

        assertFalse(result.isValid)
        assertShortAndClean(result)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("Nested value"))
    }

    @Test
    fun aRecordWithTooManyFieldsIsRefused() {
        val wide = "[{" + (0..64).joinToString(",") { "\"k$it\": 0" } + "}]"
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_WEIGHT, wide))

        assertFalse(result.isValid)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("more than 64 fields"))
    }

    @Test
    fun anOverlongRecordIsRefused() {
        val long = "[{\"foodName\": \"" + "a".repeat(BackupArchiveManager.MAX_RECORD_CHARS.toInt()) + "\"}]"
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_DIARY, long))

        assertFalse(result.isValid)
        assertShortAndClean(result)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("longer than"))
    }

    @Test
    fun anElementThatIsNotARecordIsRefused() {
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_WATER, "[1, 2]"))

        assertFalse(result.isValid)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("Expected a record object"))
    }

    // The text budget is shared, so splitting text over records or files does not get past it
    @Test
    fun theTextKeptFromOneBackupIsCapped() {
        val ingredients = "a".repeat(190_000)
        val count = (BackupArchiveManager.MAX_TEXT_CHARS / ingredients.length).toInt() + 1
        val recipes = "[" + List(count) { "{\"name\": \"r$it\", \"ingredientsJson\": \"$ingredients\"}" }.joinToString(",") + "]"
        val result = validate(withFile(archive(backupData()), PortabilityConfig.FILE_RECIPES, recipes))

        assertFalse(result.isValid)
        assertEquals(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE, result.errorMessage)
    }

    @Test
    fun anOversizedSettingsFileIsRefused() {
        val files = unzip(archive(backupData()))
        files[PortabilityConfig.FILE_PREFERENCES] = ByteArray((BackupArchiveManager.MAX_SETTINGS_FILE_BYTES + 1).toInt()) { ' '.code.toByte() }
        val result = validate(rezip(files))

        assertFalse(result.isValid)
        assertEquals(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE, result.errorMessage)
    }

    @Test
    fun briefErrorTextIsOneShortLine() {
        val text = briefErrorText(IllegalStateException("first line " + "x".repeat(500) + "\nsecond line"), "fallback")
        assertFalse(text.contains("second line"))
        assertTrue(text, text.length <= 203)
        assertEquals("fallback", briefErrorText(IllegalStateException(), "fallback"))
        assertEquals("bad ? char", briefErrorText(IllegalStateException("bad \u0007 char"), "fallback"))
        // Line separators and direction overrides cannot break or reorder the message
        assertEquals("a?b?c", briefErrorText(IllegalStateException("a\u2028b\u202Ec"), "fallback"))
    }

    // ---------------------------------------------------------------------------------------
    // Streaming keeps the old guarantees
    // ---------------------------------------------------------------------------------------

    @Test
    fun aVeryLongStringIsRefused() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SimpleJsonParser.parseObject("{\"a\": \"" + "x".repeat(SimpleJsonParser.MAX_STRING_CHARS + 1) + "\"}")
        }
        assertTrue(ex.message!!, ex.message!!.contains("longer than"))
    }

    // The old parser read "[1," as [1]; a cut-off file is now an error
    @Test
    fun truncatedJsonIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { SimpleJsonParser.parseArray("[1,") }
        assertThrows(IllegalArgumentException::class.java) { SimpleJsonParser.parseArray("[{\"a\": 1}") }
        assertThrows(IllegalArgumentException::class.java) { SimpleJsonParser.parseObject("{\"a\": 1") }
    }

    @Test
    fun filesMayArriveInAnyOrder() {
        val files = unzip(archive(backupData(diary = listOf(diaryEntry(1), diaryEntry(2)))))
        val manifest = files.remove(PortabilityConfig.FILE_MANIFEST)!!
        files[PortabilityConfig.FILE_MANIFEST] = manifest // now the last entry

        val result = validate(rezip(files))
        assertTrue(result.errorMessage, result.isValid)
        assertEquals(2, result.backupData!!.diaryEntries.size)
    }

    // Parsing stops at the closing bracket; the checksum must still cover the rest of the file
    @Test
    fun theChecksumCoversBytesAfterTheJson() {
        val files = unzip(archive(backupData(diary = listOf(diaryEntry(1)))))
        files[PortabilityConfig.FILE_DIARY] = files.getValue(PortabilityConfig.FILE_DIARY) + "   ".toByteArray()

        val result = validate(rezip(files))
        assertFalse(result.isValid)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("Checksum mismatch"))
    }

    @Test
    fun everyRecordTypeSurvivesTheStreamingParser() {
        val data = backupData(
            diary = listOf(diaryEntry(1).copy(foodName = "Dal \"tadka\" \u00e9\u4e2d", loggedFiber = null)),
            water = listOf(WaterLogBackupDto(dateEpochDay = 20000, dateString = "2024-10-04", amountMl = 250.0, timestamp = 1))
        )
        val result = validate(archive(data))

        assertTrue(result.errorMessage, result.isValid)
        assertEquals(data.diaryEntries, result.backupData!!.diaryEntries)
        assertEquals(data.waterEntries, result.backupData!!.waterEntries)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun assertShortAndClean(result: BackupValidationResult) {
        val message = checkNotNull(result.errorMessage)
        assertTrue("message is ${message.length} chars", message.length < 300)
        assertFalse(message, message.contains("SECRET-SNACK"))
        assertFalse(message, message.contains('\n'))
    }

    private fun diaryEntry(i: Int): DiaryEntryBackupDto {
        // Values like a real export: long food ids and full-precision doubles
        val calories = 37.0 + i % 900 + (i % 7) / 3.0
        return DiaryEntryBackupDto(
            uuid = String.format(Locale.US, "4c94f58b-bf11-4f32-9447-%012d", i),
            dateEpochDay = 18_000L + i / 30,
            dateString = "2026-08-19",
            mealType = "BREAKFAST",
            foodId = 1_000_000_000_000_000L + i,
            foodName = "Paneer Butter Masala",
            userQuantity = 1.5,
            servingDescription = "1 cup cooked",
            gramWeight = 234.0,
            loggedCalories = calories,
            loggedProtein = calories * 0.07,
            loggedCarbs = calories * 0.12,
            loggedFat = calories * 0.035,
            createdAt = 1_787_121_996_830L + i,
            loggedFiber = calories * 0.012,
            loggedSugar = calories * 0.024,
            loggedSodium = calories * 0.8,
            loggedSaturatedFat = calories / 97.0
        )
    }

    private fun backupData(
        diary: List<DiaryEntryBackupDto> = emptyList(),
        water: List<WaterLogBackupDto> = emptyList()
    ) = MacroBaseBackupData(
        manifest = BackupManifestDto(exportedAt = "2026-10-10T00:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto()),
        diaryEntries = diary,
        waterEntries = water
    )

    private fun archive(data: MacroBaseBackupData): ByteArray =
        ByteArrayOutputStream().also { BackupArchiveManager.createBackupArchive(data, it) }.toByteArray()

    private fun validate(bytes: ByteArray) = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(bytes))

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return files
    }

    /** Writes [files], then each [extra] entry as that many zero bytes, streamed so the test stays small. */
    private fun rezip(files: Map<String, ByteArray>, extra: Map<String, Long> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            val zeros = ByteArray(64 * 1024)
            extra.forEach { (name, size) ->
                zip.putNextEntry(ZipEntry(name))
                var left = size
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    zip.write(zeros, 0, n)
                    left -= n
                }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Replaces one file and updates its checksum, so validation reaches the parsing step. */
    private fun withFile(bytes: ByteArray, name: String, content: String): ByteArray {
        val files = unzip(bytes)
        val oldHash = sha256(files.getValue(name))
        val newBytes = content.toByteArray(Charsets.UTF_8)
        val manifest = files.getValue(PortabilityConfig.FILE_MANIFEST).toString(Charsets.UTF_8)
        // Empty files share a hash, so replace this file's entry only
        val oldEntry = "\"$name\":\"$oldHash\""
        check(manifest.contains(oldEntry))
        files[PortabilityConfig.FILE_MANIFEST] =
            manifest.replace(oldEntry, "\"$name\":\"${sha256(newBytes)}\"").toByteArray(Charsets.UTF_8)
        files[name] = newBytes
        return rezip(files)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.US, "%02x", it) }
}
