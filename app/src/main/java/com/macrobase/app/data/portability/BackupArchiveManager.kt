package com.macrobase.app.data.portability

import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handles ZIP archive compression, extraction, and SHA-256 checksum verification.
 */
object BackupArchiveManager {

    const val MAX_ARCHIVE_SIZE_BYTES = 50 * 1024 * 1024L       // 50 MB Max Input ZIP
    const val MAX_ENTRY_SIZE_BYTES = 50 * 1024 * 1024L         // 50 MB Max Uncompressed Entry
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 100 * 1024 * 1024L// 100 MB Max Total Extraction
    const val MAX_ZIP_ENTRIES = 50                             // Max 50 files in archive

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
     */
    fun extractAndValidateBackup(inputStream: InputStream): BackupValidationResult {
        return try {
            val bytes = inputStream.readBytes()
            if (bytes.isEmpty()) {
                return BackupValidationResult(isValid = false, errorMessage = "Selected file is empty.")
            }
            if (bytes.size > MAX_ARCHIVE_SIZE_BYTES) {
                return BackupValidationResult(isValid = false, errorMessage = "Archive exceeds maximum permitted size of ${MAX_ARCHIVE_SIZE_BYTES / (1024 * 1024)} MB.")
            }

            val entryMap = mutableMapOf<String, String>()
            var totalUncompressedBytes = 0L
            var entryCount = 0

            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        return BackupValidationResult(isValid = false, errorMessage = "Archive contains too many entries (max $MAX_ZIP_ENTRIES).")
                    }

                    val name = entry.name
                    // Defensive check against ZIP path traversal attacks
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":") || name.contains("\u0000")) {
                        return BackupValidationResult(isValid = false, errorMessage = "Potentially malicious entry name in archive: '$name'")
                    }

                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var read: Int
                    var entryBytes = 0L

                    while (zip.read(buffer).also { read = it } != -1) {
                        entryBytes += read
                        totalUncompressedBytes += read
                        if (entryBytes > MAX_ENTRY_SIZE_BYTES || totalUncompressedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            return BackupValidationResult(isValid = false, errorMessage = "Archive entry exceeds safe decompression limits (Zip Bomb protection).")
                        }
                        out.write(buffer, 0, read)
                    }

                    val content = out.toString(StandardCharsets.UTF_8.name())
                    entryMap[name] = content
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val manifestContent = entryMap[PortabilityConfig.FILE_MANIFEST]
                ?: return BackupValidationResult(isValid = false, errorMessage = "Missing required manifest.json in archive.")

            val manifest = try {
                BackupJsonSerializer.parseManifest(manifestContent)
            } catch (e: Exception) {
                return BackupValidationResult(isValid = false, errorMessage = "Corrupted manifest.json: ${e.message}")
            }

            // Version & Format Compatibility Check
            if (manifest.format != PortabilityConfig.BACKUP_FORMAT_NAME) {
                return BackupValidationResult(isValid = false, errorMessage = "Invalid backup format: '${manifest.format}'")
            }

            val majorVersion = manifest.backupVersion.split(".").firstOrNull()?.toIntOrNull() ?: 1
            val currentMajor = PortabilityConfig.BACKUP_FORMAT_VERSION.split(".").firstOrNull()?.toIntOrNull() ?: 1
            if (majorVersion > currentMajor) {
                return BackupValidationResult(
                    isValid = false,
                    errorMessage = "Backup version ${manifest.backupVersion} is newer and unsupported by this version of MacroBase. Please update the app."
                )
            }

            // Verify checksums
            manifest.checksums.forEach { (filename, expectedHash) ->
                val content = entryMap[filename]
                if (content != null && expectedHash.isNotBlank()) {
                    val actualHash = calculateSha256(content)
                    if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                        return BackupValidationResult(
                            isValid = false,
                            errorMessage = "Checksum mismatch in $filename. Archive may be corrupted."
                        )
                    }
                }
            }

            // Parse data files safely
            val diaryEntries = entryMap[PortabilityConfig.FILE_DIARY]?.let {
                try { BackupJsonSerializer.parseDiaryEntries(it) } catch (e: Exception) {
                    return BackupValidationResult(isValid = false, errorMessage = "Corrupted diary.json: ${e.message}")
                }
            } ?: emptyList()

            val customFoods = entryMap[PortabilityConfig.FILE_CUSTOM_FOODS]?.let {
                try { BackupJsonSerializer.parseCustomFoods(it) } catch (e: Exception) {
                    return BackupValidationResult(isValid = false, errorMessage = "Corrupted custom_foods.json: ${e.message}")
                }
            } ?: emptyList()

            val recipes = entryMap[PortabilityConfig.FILE_RECIPES]?.let {
                try { BackupJsonSerializer.parseRecipes(it) } catch (e: Exception) {
                    return BackupValidationResult(isValid = false, errorMessage = "Corrupted recipes.json: ${e.message}")
                }
            } ?: emptyList()

            val weightEntries = entryMap[PortabilityConfig.FILE_WEIGHT]?.let {
                try { BackupJsonSerializer.parseWeightEntries(it) } catch (e: Exception) {
                    return BackupValidationResult(isValid = false, errorMessage = "Corrupted weight.json: ${e.message}")
                }
            } ?: emptyList()

            val waterEntries = entryMap[PortabilityConfig.FILE_WATER]?.let {
                try { BackupJsonSerializer.parseWaterLogs(it) } catch (e: Exception) {
                    return BackupValidationResult(isValid = false, errorMessage = "Corrupted water.json: ${e.message}")
                }
            } ?: emptyList()

            val goals = entryMap[PortabilityConfig.FILE_GOALS]?.let {
                if (it.trim() != "{}" && it.isNotBlank()) {
                    try { BackupJsonSerializer.parseGoals(it) } catch (e: Exception) { null }
                } else null
            }

            val preferences = entryMap[PortabilityConfig.FILE_PREFERENCES]?.let {
                if (it.trim() != "{}" && it.isNotBlank()) {
                    try { BackupJsonSerializer.parsePreferences(it) } catch (e: Exception) { null }
                } else null
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

            BackupValidationResult(
                isValid = true,
                manifest = manifest,
                backupData = backupData
            )
        } catch (e: Exception) {
            BackupValidationResult(
                isValid = false,
                errorMessage = "Failed to extract backup archive: ${e.message ?: "Invalid ZIP file"}"
            )
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, filename: String, content: String) {
        val entry = ZipEntry(filename)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    fun calculateSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder()
        for (b in hashBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
