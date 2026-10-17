package com.macrobase.app.data.portability

import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.core.util.briefErrorText
import com.macrobase.app.core.util.printableText
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handles ZIP archive compression, extraction, and SHA-256 checksum verification.
 */
object BackupArchiveManager {

    // Sized from measurement (BUG-047). A diary entry takes about 770 bytes in a backup, so five
    // years at 30 entries a day is about 42 MB and 55,000 records; the limits allow twice that.
    // Data files are parsed record by record as they stream, and every record is a small flat
    // object, so memory is bounded by the record counts and the text budget, not the file size.
    const val MAX_ARCHIVE_SIZE_BYTES = 50 * 1024 * 1024L        // 50 MB compressed input
    const val MAX_ENTRY_SIZE_BYTES = 96 * 1024 * 1024L          // 96 MB per data file
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 128 * 1024 * 1024L // 128 MB for the whole archive
    const val MAX_SETTINGS_FILE_BYTES = 1024 * 1024L            // manifest, goals and preferences
    const val MAX_ZIP_ENTRIES = 50                              // Max 50 files in archive

    const val MAX_DIARY_RECORDS = 120_000
    const val MAX_WATER_RECORDS = 120_000
    const val MAX_WEIGHT_RECORDS = 40_000
    const val MAX_CUSTOM_FOOD_RECORDS = 20_000
    const val MAX_RECIPE_RECORDS = 5_000

    /** JSON characters in one record; a real diary entry has about 770. */
    const val MAX_RECORD_CHARS = 16 * 1024L
    /** A recipe carries its ingredient list as one string. */
    const val MAX_RECIPE_RECORD_CHARS = 512 * 1024L
    /** String values the whole backup may keep: a five-year diary keeps about 5 million characters. */
    const val MAX_TEXT_CHARS = 24_000_000L

    private val DATA_FILES = listOf(
        PortabilityConfig.FILE_DIARY, PortabilityConfig.FILE_CUSTOM_FOODS, PortabilityConfig.FILE_RECIPES,
        PortabilityConfig.FILE_WEIGHT, PortabilityConfig.FILE_WATER
    )
    private val SETTINGS_FILES = setOf(
        PortabilityConfig.FILE_MANIFEST, PortabilityConfig.FILE_GOALS, PortabilityConfig.FILE_PREFERENCES
    )

    /** An archive went past a size limit. */
    private class LimitExceededException(message: String) : IOException(message)

    /**
     * Creates a ZIP archive containing all user data JSON files and manifest.
     */
    fun createBackupArchive(data: MacroBaseBackupData, outputStream: OutputStream) {
        val diaryJson = BackupJsonSerializer.serializeDiaryEntries(data.diaryEntries)
        val customFoodsJson = BackupJsonSerializer.serializeCustomFoods(data.customFoods)
        val recipesJson = BackupJsonSerializer.serializeRecipes(data.recipes)
        val weightJson = BackupJsonSerializer.serializeWeightEntries(data.weightEntries)
        val waterJson = BackupJsonSerializer.serializeWaterLogs(data.waterEntries)
        val goalsJson = data.goals?.let { BackupJsonSerializer.serializeGoals(it) } ?: "{}"
        val preferencesJson = data.preferences?.let { BackupJsonSerializer.serializePreferences(it) } ?: "{}"

        val checksumMap = mapOf(
            PortabilityConfig.FILE_DIARY to calculateSha256(diaryJson),
            PortabilityConfig.FILE_CUSTOM_FOODS to calculateSha256(customFoodsJson),
            PortabilityConfig.FILE_RECIPES to calculateSha256(recipesJson),
            PortabilityConfig.FILE_WEIGHT to calculateSha256(weightJson),
            PortabilityConfig.FILE_WATER to calculateSha256(waterJson),
            PortabilityConfig.FILE_GOALS to calculateSha256(goalsJson),
            PortabilityConfig.FILE_PREFERENCES to calculateSha256(preferencesJson)
        )

        val manifest = BackupManifestDto(
            format = PortabilityConfig.BACKUP_FORMAT_NAME,
            backupVersion = PortabilityConfig.BACKUP_FORMAT_VERSION,
            appVersion = PortabilityConfig.CURRENT_APP_VERSION,
            schemaVersion = PortabilityConfig.DATABASE_SCHEMA_VERSION,
            exportedAt = Instant.now().toString(),
            timeZone = TimeZone.getDefault().id,
            deviceInfo = "Android",
            counts = BackupRecordCountsDto(
                diaryEntries = data.diaryEntries.size,
                customFoods = data.customFoods.size,
                recipes = data.recipes.size,
                weightEntries = data.weightEntries.size,
                waterEntries = data.waterEntries.size,
                goals = if (data.goals != null) 1 else 0,
                preferences = if (data.preferences != null) 1 else 0
            ),
            checksums = checksumMap
        )

        val manifestJson = BackupJsonSerializer.serializeManifest(manifest)

        ZipOutputStream(outputStream).use { zip ->
            writeZipEntry(zip, PortabilityConfig.FILE_MANIFEST, manifestJson)
            writeZipEntry(zip, PortabilityConfig.FILE_DIARY, diaryJson)
            writeZipEntry(zip, PortabilityConfig.FILE_CUSTOM_FOODS, customFoodsJson)
            writeZipEntry(zip, PortabilityConfig.FILE_RECIPES, recipesJson)
            writeZipEntry(zip, PortabilityConfig.FILE_WEIGHT, weightJson)
            writeZipEntry(zip, PortabilityConfig.FILE_WATER, waterJson)
            writeZipEntry(zip, PortabilityConfig.FILE_GOALS, goalsJson)
            writeZipEntry(zip, PortabilityConfig.FILE_PREFERENCES, preferencesJson)
            zip.finish()
            zip.flush()
        }
    }

    /**
     * Validates and parses a backup ZIP archive from an InputStream with strict security checks.
     * Never throws: every failure, including running out of memory, becomes a short message that
     * never quotes the file's content (BUG-047).
     */
    fun extractAndValidateBackup(inputStream: InputStream): BackupValidationResult {
        return try {
            extract(inputStream)
        } catch (e: LimitExceededException) {
            invalid(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE)
        } catch (e: OutOfMemoryError) {
            invalid(PortabilityConfig.BACKUP_TOO_LARGE_FOR_DEVICE_MESSAGE)
        } catch (e: Exception) {
            invalid("Failed to extract backup archive: ${briefErrorText(e, "Invalid ZIP file")}")
        }
    }

    private fun invalid(message: String) = BackupValidationResult(isValid = false, errorMessage = message)

    private fun extract(inputStream: InputStream): BackupValidationResult {
        val totals = Totals()
        val textBudget = TextBudget(MAX_TEXT_CHARS)
        val seen = mutableSetOf<String>()
        val digests = mutableMapOf<String, String>()
        val settingsText = mutableMapOf<String, String>()
        val parseErrors = mutableMapOf<String, String>()
        var diaryEntries: List<DiaryEntryBackupDto> = emptyList()
        var customFoods: List<CustomFoodBackupDto> = emptyList()
        var recipes: List<RecipeBackupDto> = emptyList()
        var weightEntries: List<WeightEntryBackupDto> = emptyList()
        var waterEntries: List<WaterLogBackupDto> = emptyList()

        // One pass: each file is hashed while it streams, data files are parsed as they arrive, and
        // the manifest is checked once everything has been read, whatever order the files came in
        ZipInputStream(CompressedLimitStream(inputStream, totals)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (seen.size + 1 > MAX_ZIP_ENTRIES) {
                    return invalid("Archive contains too many entries (max $MAX_ZIP_ENTRIES).")
                }
                val name = entry.name
                // Defensive check against ZIP path traversal attacks
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":") || name.contains("\u0000")) {
                    return invalid("Potentially malicious entry name in archive: '${printableText(name, 60)}'")
                }
                if (!seen.add(name)) {
                    return invalid("Archive contains ${printableText(name, 60)} more than once. It may have been edited or damaged.")
                }

                val maxBytes = if (name in SETTINGS_FILES) MAX_SETTINGS_FILE_BYTES else MAX_ENTRY_SIZE_BYTES
                val stream = EntryStream(zip, maxBytes, totals)
                when (name) {
                    in SETTINGS_FILES -> settingsText[name] = String(stream.readBytes(), StandardCharsets.UTF_8)
                    in DATA_FILES -> {
                        val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
                        try {
                            when (name) {
                                PortabilityConfig.FILE_DIARY -> diaryEntries = BackupJsonSerializer.parseDiaryEntries(
                                    reader, RecordLimits(MAX_DIARY_RECORDS, MAX_RECORD_CHARS, textBudget)
                                )
                                PortabilityConfig.FILE_CUSTOM_FOODS -> customFoods = BackupJsonSerializer.parseCustomFoods(
                                    reader, RecordLimits(MAX_CUSTOM_FOOD_RECORDS, MAX_RECORD_CHARS, textBudget)
                                )
                                PortabilityConfig.FILE_RECIPES -> recipes = BackupJsonSerializer.parseRecipes(
                                    reader, RecordLimits(MAX_RECIPE_RECORDS, MAX_RECIPE_RECORD_CHARS, textBudget)
                                )
                                PortabilityConfig.FILE_WEIGHT -> weightEntries = BackupJsonSerializer.parseWeightEntries(
                                    reader, RecordLimits(MAX_WEIGHT_RECORDS, MAX_RECORD_CHARS, textBudget)
                                )
                                PortabilityConfig.FILE_WATER -> waterEntries = BackupJsonSerializer.parseWaterLogs(
                                    reader, RecordLimits(MAX_WATER_RECORDS, MAX_RECORD_CHARS, textBudget)
                                )
                            }
                        } catch (e: LimitExceededException) {
                            throw e
                        } catch (e: BackupTooLargeException) {
                            // Real data can reach these limits, so this is "too large", not "corrupted"
                            return invalid(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE)
                        } catch (e: Exception) {
                            // Reported after the manifest checks, in the order they always ran
                            parseErrors[name] = "Corrupted $name: ${briefErrorText(e, "unreadable")}"
                        }
                    }
                }
                // The rest of the entry still counts toward the limits and the checksum; files this
                // app does not know are hashed and counted but never kept
                stream.drain()
                digests[name] = stream.sha256Hex()
                zip.closeEntry()
            }
        }

        if (seen.isEmpty()) {
            return invalid("Selected backup archive is empty or invalid.")
        }

        val manifestContent = settingsText[PortabilityConfig.FILE_MANIFEST]
            ?: return invalid("Missing required manifest.json in archive.")

        val manifest = try {
            BackupJsonSerializer.parseManifest(manifestContent)
        } catch (e: Exception) {
            return invalid("Corrupted manifest.json: ${briefErrorText(e, "unreadable")}")
        }

        // Version & Format Compatibility Check
        if (manifest.format != PortabilityConfig.BACKUP_FORMAT_NAME) {
            return invalid("Invalid backup format: '${printableText(manifest.format, 40)}'")
        }

        val majorVersion = manifest.backupVersion.split(".").firstOrNull()?.toIntOrNull() ?: 1
        val currentMajor = PortabilityConfig.BACKUP_FORMAT_VERSION.split(".").firstOrNull()?.toIntOrNull() ?: 1
        if (majorVersion > currentMajor) {
            return invalid(
                "Backup version ${printableText(manifest.backupVersion, 20)} is newer and unsupported " +
                    "by this version of MacroBase. Please update the app."
            )
        }

        // Every file the manifest lists must be present: a missing data file used to be read
        // as an empty list, and Overwrite then emptied that table (BUG-017)
        manifest.checksums.forEach { (filename, expectedHash) ->
            val shownName = printableText(filename, 60)
            val actualHash = digests[filename]
                ?: return invalid("This backup is incomplete: $shownName is missing. Nothing can be restored from it.")
            if (expectedHash.isNotBlank() && !actualHash.equals(expectedHash, ignoreCase = true)) {
                return invalid("Checksum mismatch in $shownName. Archive may be corrupted.")
            }
        }

        // Every export writes all five data files, and Overwrite empties those tables, so a
        // backup without one of them is incomplete even when its manifest does not list it
        DATA_FILES.firstOrNull { it !in seen }?.let { missing ->
            return invalid("This backup is incomplete: $missing is missing. Nothing can be restored from it.")
        }

        DATA_FILES.firstNotNullOfOrNull { parseErrors[it] }?.let { return invalid(it) }

        diaryEntries = BackupJsonSerializer.legacyZerosAsUnknown(diaryEntries, manifest.backupVersion)

        val goals = settingsText[PortabilityConfig.FILE_GOALS]?.let {
            if (it.trim() != "{}" && it.isNotBlank()) {
                try { BackupJsonSerializer.parseGoals(it) } catch (e: Exception) { null }
            } else null
        }

        val preferences = settingsText[PortabilityConfig.FILE_PREFERENCES]?.let {
            if (it.trim() != "{}" && it.isNotBlank()) {
                try { BackupJsonSerializer.parsePreferences(it) } catch (e: Exception) { null }
            } else null
        }

        // The record counts written at export must match what was read: fewer records means a
        // truncated or edited file, and restoring it would silently drop the rest (BUG-017)
        val declared = manifest.counts
        listOf(
            Triple(PortabilityConfig.FILE_DIARY, diaryEntries.size, declared.diaryEntries),
            Triple(PortabilityConfig.FILE_CUSTOM_FOODS, customFoods.size, declared.customFoods),
            Triple(PortabilityConfig.FILE_RECIPES, recipes.size, declared.recipes),
            Triple(PortabilityConfig.FILE_WEIGHT, weightEntries.size, declared.weightEntries),
            Triple(PortabilityConfig.FILE_WATER, waterEntries.size, declared.waterEntries)
        ).firstOrNull { (_, read, listed) -> read != listed }?.let { (filename, read, listed) ->
            return invalid(
                "This backup is damaged: $filename holds $read records but the backup lists $listed. Nothing can be restored from it."
            )
        }
        // Goals and profile the backup says it has must be readable, never silently dropped
        val unreadableSettings = when {
            declared.goals > 0 && (goals == null || !goals.hasUsableTargets) -> PortabilityConfig.FILE_GOALS
            declared.preferences > 0 && preferences == null -> PortabilityConfig.FILE_PREFERENCES
            else -> null
        }
        if (unreadableSettings != null) {
            return invalid("This backup is damaged: $unreadableSettings cannot be read. Nothing can be restored from it.")
        }

        val backupData = MacroBaseBackupData(
            manifest = manifest,
            diaryEntries = diaryEntries,
            customFoods = customFoods,
            recipes = recipes,
            weightEntries = weightEntries,
            waterEntries = waterEntries,
            goals = goals,
            preferences = preferences
        )

        return BackupValidationResult(
            isValid = true,
            manifest = manifest,
            backupData = backupData
        )
    }

    /** Bytes read so far, across the whole archive. */
    private class Totals {
        var compressed = 0L
        var uncompressed = 0L
    }

    /** Counts the compressed bytes and stops once the archive passes [MAX_ARCHIVE_SIZE_BYTES]. */
    private class CompressedLimitStream(input: InputStream, private val totals: Totals) : FilterInputStream(input) {
        override fun read(): Int {
            val b = super.read()
            if (b != -1) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n)
            return n
        }

        private fun count(n: Int) {
            totals.compressed += n
            if (totals.compressed > MAX_ARCHIVE_SIZE_BYTES) {
                throw LimitExceededException(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE)
            }
        }
    }

    /**
     * One zip entry. Every byte read is counted against the per-file and whole-archive limits and
     * added to the entry's SHA-256. Closing it leaves the zip stream open for the next entry.
     */
    private class EntryStream(
        private val zip: InputStream,
        private val maxBytes: Long,
        private val totals: Totals
    ) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var bytes = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = zip.read(b, off, len)
            if (n > 0) {
                bytes += n
                totals.uncompressed += n
                if (bytes > maxBytes || totals.uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw LimitExceededException(PortabilityConfig.BACKUP_OVER_LIMIT_MESSAGE)
                }
                digest.update(b, off, n)
            }
            return n
        }

        override fun close() {
            // The zip stream belongs to the caller
        }

        fun drain() {
            val buffer = ByteArray(8192)
            while (read(buffer, 0, buffer.size) != -1) {
                // Counted and hashed by read()
            }
        }

        fun sha256Hex(): String = toHex(digest.digest())
    }

    private fun writeZipEntry(zip: ZipOutputStream, filename: String, content: String) {
        val entry = ZipEntry(filename)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    fun calculateSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return toHex(digest.digest(content.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun toHex(hashBytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }
}

