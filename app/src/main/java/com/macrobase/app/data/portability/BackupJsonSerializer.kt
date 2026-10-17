package com.macrobase.app.data.portability

import com.macrobase.app.core.util.printableText
import com.macrobase.app.domain.model.BackupCompatibilityDto
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.GoalTransitionBackupDto
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto
import java.io.Reader
import java.io.StringReader
import java.util.Locale

/**
 * Pure Kotlin JSON Serializer & Parser for Backup DTOs.
 * Completely self-contained, high-performance, and guaranteed to run on Android and Host JVM tests.
 */
object BackupJsonSerializer {

    // ==========================================
    // MANIFEST SERIALIZATION & PARSING
    // ==========================================

    fun serializeManifest(manifest: BackupManifestDto): String {
        val checksumsJson = manifest.checksums.entries.joinToString(",") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return """{
  "format": "${escapeJson(manifest.format)}",
  "backupVersion": "${escapeJson(manifest.backupVersion)}",
  "appVersion": "${escapeJson(manifest.appVersion)}",
  "schemaVersion": ${manifest.schemaVersion},
  "exportedAt": "${escapeJson(manifest.exportedAt)}",
  "timeZone": "${escapeJson(manifest.timeZone)}",
  "deviceInfo": "${escapeJson(manifest.deviceInfo)}",
  "counts": {
    "diaryEntries": ${manifest.counts.diaryEntries},
    "customFoods": ${manifest.counts.customFoods},
    "recipes": ${manifest.counts.recipes},
    "weightEntries": ${manifest.counts.weightEntries},
    "waterEntries": ${manifest.counts.waterEntries},
    "goals": ${manifest.counts.goals},
    "preferences": ${manifest.counts.preferences}
  },
  "checksums": {$checksumsJson},
  "compatibility": {
    "minSupportedAppVersion": "${escapeJson(manifest.compatibility.minSupportedAppVersion)}",
    "supportedSchemaVersion": ${manifest.compatibility.supportedSchemaVersion}
  }
}"""
    }

    fun parseManifest(json: String): BackupManifestDto {
        val root = SimpleJsonParser.parseObject(json)
        val format = root.getString("format") ?: "MacroBaseBackup"
        val backupVersion = root.getString("backupVersion") ?: "1.0.0"
        val appVersion = root.getString("appVersion") ?: "1.0.0"
        val schemaVersion = root.getInt("schemaVersion") ?: 1
        val exportedAt = root.getString("exportedAt") ?: ""
        val timeZone = root.getString("timeZone") ?: "UTC"
        val deviceInfo = root.getString("deviceInfo") ?: "Android"

        val countsObj = root.getObject("counts") ?: JsonObject(emptyMap())
        val counts = BackupRecordCountsDto(
            diaryEntries = countsObj.getInt("diaryEntries") ?: 0,
            customFoods = countsObj.getInt("customFoods") ?: 0,
            recipes = countsObj.getInt("recipes") ?: 0,
            weightEntries = countsObj.getInt("weightEntries") ?: 0,
            waterEntries = countsObj.getInt("waterEntries") ?: 0,
            goals = countsObj.getInt("goals") ?: 0,
            preferences = countsObj.getInt("preferences") ?: 0
        )

        val checksumsObj = root.getObject("checksums") ?: JsonObject(emptyMap())
        val checksums = checksumsObj.map.mapValues { it.value?.toString() ?: "" }

        val compatObj = root.getObject("compatibility") ?: JsonObject(emptyMap())
        val compatibility = BackupCompatibilityDto(
            minSupportedAppVersion = compatObj.getString("minSupportedAppVersion") ?: "1.0.0",
            supportedSchemaVersion = compatObj.getInt("supportedSchemaVersion") ?: 1
        )

        return BackupManifestDto(
            format = format,
            backupVersion = backupVersion,
            appVersion = appVersion,
            schemaVersion = schemaVersion,
            exportedAt = exportedAt,
            timeZone = timeZone,
            deviceInfo = deviceInfo,
            counts = counts,
            checksums = checksums,
            compatibility = compatibility
        )
    }

    // ==========================================
    // DIARY ENTRIES
    // ==========================================

    fun serializeDiaryEntries(entries: List<DiaryEntryBackupDto>): String {
        val items = entries.joinToString(",\n") { e ->
            """  {
    "uuid": "${escapeJson(e.uuid)}",
    "dateEpochDay": ${e.dateEpochDay},
    "dateString": "${escapeJson(e.dateString)}",
    "mealType": "${escapeJson(e.mealType)}",
    "foodId": ${e.foodId},
    "foodName": "${escapeJson(e.foodName)}",
    "userQuantity": ${e.userQuantity},
    "servingDescription": "${escapeJson(e.servingDescription)}",
    "gramWeight": ${e.gramWeight},
    "loggedCalories": ${e.loggedCalories},
    "loggedProtein": ${e.loggedProtein},
    "loggedCarbs": ${e.loggedCarbs},
    "loggedFat": ${e.loggedFat},
    "loggedFiber": ${e.loggedFiber ?: "null"},
    "loggedSugar": ${e.loggedSugar ?: "null"},
    "loggedSodium": ${e.loggedSodium ?: "null"},
    "loggedSaturatedFat": ${e.loggedSaturatedFat ?: "null"},
    "loggedTransFat": ${e.loggedTransFat ?: "null"},
    "loggedCholesterol": ${e.loggedCholesterol ?: "null"},
    "customFoodUuid": ${e.customFoodUuid?.let { "\"${escapeJson(it)}\"" } ?: "null"},
    "recipeUuid": ${e.recipeUuid?.let { "\"${escapeJson(it)}\"" } ?: "null"},
    "createdAt": ${e.createdAt}
  }"""
        }
        return "[\n$items\n]"
    }

    fun parseDiaryEntries(json: String): List<DiaryEntryBackupDto> = parseDiaryEntries(StringReader(json), RecordLimits.unbounded())

    fun parseDiaryEntries(reader: Reader, limits: RecordLimits): List<DiaryEntryBackupDto> {
        return SimpleJsonParser.parseRecords(reader, limits) { obj ->
            DiaryEntryBackupDto(
                uuid = obj.getString("uuid") ?: java.util.UUID.randomUUID().toString(),
                dateEpochDay = obj.getLong("dateEpochDay") ?: 0L,
                dateString = obj.getString("dateString") ?: "",
                mealType = obj.getString("mealType") ?: "BREAKFAST",
                foodId = obj.getLong("foodId") ?: 0L,
                foodName = obj.getString("foodName") ?: "",
                userQuantity = obj.getDouble("userQuantity") ?: 1.0,
                servingDescription = obj.getString("servingDescription") ?: "",
                gramWeight = obj.getDouble("gramWeight") ?: 100.0,
                loggedCalories = obj.getDouble("loggedCalories") ?: 0.0,
                loggedProtein = obj.getDouble("loggedProtein") ?: 0.0,
                loggedCarbs = obj.getDouble("loggedCarbs") ?: 0.0,
                loggedFat = obj.getDouble("loggedFat") ?: 0.0,
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis(),
                loggedFiber = obj.getDouble("loggedFiber"),
                loggedSugar = obj.getDouble("loggedSugar"),
                loggedSodium = obj.getDouble("loggedSodium"),
                loggedSaturatedFat = obj.getDouble("loggedSaturatedFat"),
                loggedTransFat = obj.getDouble("loggedTransFat"),
                loggedCholesterol = obj.getDouble("loggedCholesterol"),
                customFoodUuid = obj.getString("customFoodUuid"),
                recipeUuid = obj.getString("recipeUuid")
            )
        }
    }

    /**
     * Backups before format 1.3.0 were written from diary columns that stored 0.0 when a nutrient
     * was not known, so their 0.0 fiber, sugar or sodium means "unknown" (BUG-037). Newer backups
     * write null for unknown and are returned unchanged.
     */
    fun legacyZerosAsUnknown(entries: List<DiaryEntryBackupDto>, backupVersion: String): List<DiaryEntryBackupDto> {
        if (!isFormatBefore(backupVersion, "1.3.0")) return entries
        fun Double?.unknownIfZero() = this?.takeIf { it != 0.0 }
        return entries.map {
            it.copy(
                loggedFiber = it.loggedFiber.unknownIfZero(),
                loggedSugar = it.loggedSugar.unknownIfZero(),
                loggedSodium = it.loggedSodium.unknownIfZero()
            )
        }
    }

    /** True when [version] ("major.minor.patch") is older than [other]. Unreadable parts count as 0. */
    fun isFormatBefore(version: String, other: String): Boolean {
        fun parts(v: String) = v.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val a = parts(version)
        val b = parts(other)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    // ==========================================
    // CUSTOM FOODS
    // ==========================================

    fun serializeCustomFoods(foods: List<CustomFoodBackupDto>): String {
        val items = foods.joinToString(",\n") { f ->
            """  {
    "uuid": "${escapeJson(f.uuid)}",
    "name": "${escapeJson(f.name)}",
    "brand": ${f.brand?.let { "\"${escapeJson(it)}\"" } ?: "null"},
    "servingSize": ${f.servingSize},
    "servingUnit": "${escapeJson(f.servingUnit)}",
    "customUnitName": ${f.customUnitName?.let { "\"${escapeJson(it)}\"" } ?: "null"},
    "calories": ${f.calories},
    "proteinGrams": ${f.proteinGrams},
    "carbsGrams": ${f.carbsGrams},
    "fatGrams": ${f.fatGrams},
    "saturatedFatGrams": ${f.saturatedFatGrams ?: "null"},
    "fiberGrams": ${f.fiberGrams ?: "null"},
    "sugarGrams": ${f.sugarGrams ?: "null"},
    "sodiumMg": ${f.sodiumMg ?: "null"},
    "potassiumMg": ${f.potassiumMg ?: "null"},
    "calciumMg": ${f.calciumMg ?: "null"},
    "ironMg": ${f.ironMg ?: "null"},
    "createdAt": ${f.createdAt}
  }"""
        }
        return "[\n$items\n]"
    }

    fun parseCustomFoods(json: String): List<CustomFoodBackupDto> = parseCustomFoods(StringReader(json), RecordLimits.unbounded())

    fun parseCustomFoods(reader: Reader, limits: RecordLimits): List<CustomFoodBackupDto> {
        return SimpleJsonParser.parseRecords(reader, limits) { obj ->
            CustomFoodBackupDto(
                uuid = obj.getString("uuid") ?: java.util.UUID.randomUUID().toString(),
                name = obj.getString("name") ?: "",
                brand = obj.getString("brand"),
                servingSize = obj.getDouble("servingSize") ?: 1.0,
                servingUnit = obj.getString("servingUnit") ?: "serving",
                customUnitName = obj.getString("customUnitName"),
                calories = obj.getDouble("calories") ?: 0.0,
                proteinGrams = obj.getDouble("proteinGrams") ?: 0.0,
                carbsGrams = obj.getDouble("carbsGrams") ?: 0.0,
                fatGrams = obj.getDouble("fatGrams") ?: 0.0,
                // Absent before format 1.4.0: not known
                saturatedFatGrams = obj.getDouble("saturatedFatGrams"),
                fiberGrams = obj.getDouble("fiberGrams"),
                sugarGrams = obj.getDouble("sugarGrams"),
                sodiumMg = obj.getDouble("sodiumMg"),
                potassiumMg = obj.getDouble("potassiumMg"),
                calciumMg = obj.getDouble("calciumMg"),
                ironMg = obj.getDouble("ironMg"),
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis()
            )
        }
    }

    // ==========================================
    // RECIPES
    // ==========================================

    fun serializeRecipes(recipes: List<RecipeBackupDto>): String {
        val items = recipes.joinToString(",\n") { r ->
            """  {
    "uuid": "${escapeJson(r.uuid)}",
    "name": "${escapeJson(r.name)}",
    "servingsProduced": ${r.servingsProduced},
    "ingredientsJson": "${escapeJson(r.ingredientsJson)}",
    "caloriesPerServing": ${r.caloriesPerServing},
    "proteinPerServing": ${r.proteinPerServing},
    "carbsPerServing": ${r.carbsPerServing},
    "fatPerServing": ${r.fatPerServing},
    "createdAt": ${r.createdAt}
  }"""
        }
        return "[\n$items\n]"
    }

    fun parseRecipes(json: String): List<RecipeBackupDto> = parseRecipes(StringReader(json), RecordLimits.unbounded())

    fun parseRecipes(reader: Reader, limits: RecordLimits): List<RecipeBackupDto> {
        return SimpleJsonParser.parseRecords(reader, limits) { obj ->
            RecipeBackupDto(
                uuid = obj.getString("uuid") ?: java.util.UUID.randomUUID().toString(),
                name = obj.getString("name") ?: "",
                servingsProduced = obj.getInt("servingsProduced") ?: 1,
                // Truncating would corrupt the ingredient list of large recipes
                ingredientsJson = obj.getStringUncapped("ingredientsJson") ?: "[]",
                caloriesPerServing = obj.getDouble("caloriesPerServing") ?: 0.0,
                proteinPerServing = obj.getDouble("proteinPerServing") ?: 0.0,
                carbsPerServing = obj.getDouble("carbsPerServing") ?: 0.0,
                fatPerServing = obj.getDouble("fatPerServing") ?: 0.0,
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis()
            )
        }
    }

    // ==========================================
    // WEIGHT ENTRIES
    // ==========================================

    fun serializeWeightEntries(entries: List<WeightEntryBackupDto>): String {
        val items = entries.joinToString(",\n") { w ->
            """  {
    "dateEpochDay": ${w.dateEpochDay},
    "dateString": "${escapeJson(w.dateString)}",
    "weightKg": ${w.weightKg},
    "note": ${w.note?.let { "\"${escapeJson(it)}\"" } ?: "null"},
    "createdAt": ${w.createdAt}
  }"""
        }
        return "[\n$items\n]"
    }

    fun parseWeightEntries(json: String): List<WeightEntryBackupDto> = parseWeightEntries(StringReader(json), RecordLimits.unbounded())

    fun parseWeightEntries(reader: Reader, limits: RecordLimits): List<WeightEntryBackupDto> {
        return SimpleJsonParser.parseRecords(reader, limits) { obj ->
            WeightEntryBackupDto(
                dateEpochDay = obj.getLong("dateEpochDay") ?: 0L,
                dateString = obj.getString("dateString") ?: "",
                weightKg = obj.getDouble("weightKg") ?: 0.0,
                note = obj.getString("note"),
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis()
            )
        }
    }

    // ==========================================
    // WATER LOGS
    // ==========================================

    fun serializeWaterLogs(logs: List<WaterLogBackupDto>): String {
        val items = logs.joinToString(",\n") { w ->
            """  {
    "dateEpochDay": ${w.dateEpochDay},
    "dateString": "${escapeJson(w.dateString)}",
    "amountMl": ${w.amountMl},
    "timestamp": ${w.timestamp}
  }"""
        }
        return "[\n$items\n]"
    }

    fun parseWaterLogs(json: String): List<WaterLogBackupDto> = parseWaterLogs(StringReader(json), RecordLimits.unbounded())

    fun parseWaterLogs(reader: Reader, limits: RecordLimits): List<WaterLogBackupDto> {
        return SimpleJsonParser.parseRecords(reader, limits) { obj ->
            WaterLogBackupDto(
                dateEpochDay = obj.getLong("dateEpochDay") ?: 0L,
                dateString = obj.getString("dateString") ?: "",
                amountMl = obj.getDouble("amountMl") ?: 0.0,
                timestamp = obj.getLong("timestamp") ?: System.currentTimeMillis()
            )
        }
    }

    // ==========================================
    // GOALS
    // ==========================================

    fun serializeGoals(goal: GoalBackupDto): String {
        val transitions = goal.transitions?.joinToString(",\n", prefix = "[\n", postfix = "\n  ]") { t ->
            """    {
      "effectiveDate": "${escapeJson(t.effectiveDate)}",
      "fitnessGoal": "${escapeJson(t.fitnessGoal)}",
      "maintenanceCalories": ${t.maintenanceCalories},
      "dailyCalorieGoal": ${t.dailyCalorieGoal},
      "carbPercentage": ${t.carbPercentage},
      "proteinPercentage": ${t.proteinPercentage},
      "fatPercentage": ${t.fatPercentage}
    }"""
        } ?: "null"
        return """{
  "dailyCalorieGoal": ${goal.dailyCalorieGoal},
  "carbPercentage": ${goal.carbPercentage},
  "proteinPercentage": ${goal.proteinPercentage},
  "fatPercentage": ${goal.fatPercentage},
  "fitnessGoal": ${jsonStringOrNull(goal.fitnessGoal)},
  "maintenanceCalories": ${goal.maintenanceCalories ?: "null"},
  "scheduledFitnessGoal": ${jsonStringOrNull(goal.scheduledFitnessGoal)},
  "scheduledMaintenanceCalories": ${goal.scheduledMaintenanceCalories ?: "null"},
  "scheduledEffectiveDate": ${jsonStringOrNull(goal.scheduledEffectiveDate)},
  "transitions": $transitions
}"""
    }

    fun parseGoals(json: String): GoalBackupDto {
        val obj = SimpleJsonParser.parseObject(json)
        // Format 1.2.0 fields; absent (null) in older backups
        val transitions = obj.getArray("transitions")?.mapNotNull { raw ->
            val t = raw as? JsonObject ?: return@mapNotNull null
            GoalTransitionBackupDto(
                effectiveDate = t.getString("effectiveDate") ?: return@mapNotNull null,
                fitnessGoal = t.getString("fitnessGoal") ?: return@mapNotNull null,
                maintenanceCalories = t.getDouble("maintenanceCalories") ?: return@mapNotNull null,
                dailyCalorieGoal = t.getDouble("dailyCalorieGoal") ?: return@mapNotNull null,
                carbPercentage = t.getDouble("carbPercentage") ?: return@mapNotNull null,
                proteinPercentage = t.getDouble("proteinPercentage") ?: return@mapNotNull null,
                fatPercentage = t.getDouble("fatPercentage") ?: return@mapNotNull null
            )
        }
        // Every version wrote all four targets; a missing one means a damaged file, not a default
        fun target(key: String) = obj.getDouble(key) ?: throw IllegalArgumentException("goals.json has no valid $key")
        return GoalBackupDto(
            dailyCalorieGoal = target("dailyCalorieGoal"),
            carbPercentage = target("carbPercentage"),
            proteinPercentage = target("proteinPercentage"),
            fatPercentage = target("fatPercentage"),
            fitnessGoal = obj.getString("fitnessGoal"),
            maintenanceCalories = obj.getDouble("maintenanceCalories"),
            scheduledFitnessGoal = obj.getString("scheduledFitnessGoal"),
            scheduledMaintenanceCalories = obj.getDouble("scheduledMaintenanceCalories"),
            scheduledEffectiveDate = obj.getString("scheduledEffectiveDate"),
            transitions = transitions
        )
    }

    private fun jsonStringOrNull(value: String?): String = value?.let { "\"${escapeJson(it)}\"" } ?: "null"

    // ==========================================
    // USER PREFERENCES
    // ==========================================

    fun serializePreferences(prefs: UserPreferencesBackupDto): String {
        return """{
  "firstName": "${escapeJson(prefs.firstName)}",
  "lastName": "${escapeJson(prefs.lastName)}",
  "timeZone": "${escapeJson(prefs.timeZone)}",
  "unitSystem": "${escapeJson(prefs.unitSystem)}",
  "heightCm": ${prefs.heightCm ?: "null"},
  "currentWeightKg": ${prefs.currentWeightKg ?: "null"},
  "targetWeightKg": ${prefs.targetWeightKg ?: "null"},
  "dailyWaterGoalMl": ${prefs.dailyWaterGoalMl}
}"""
    }

    fun parsePreferences(json: String): UserPreferencesBackupDto {
        val obj = SimpleJsonParser.parseObject(json)
        return UserPreferencesBackupDto(
            firstName = obj.getString("firstName") ?: "",
            lastName = obj.getString("lastName") ?: "",
            timeZone = obj.getString("timeZone") ?: "UTC",
            unitSystem = obj.getString("unitSystem") ?: "METRIC",
            heightCm = obj.getDouble("heightCm"),
            currentWeightKg = obj.getDouble("currentWeightKg"),
            targetWeightKg = obj.getDouble("targetWeightKg"),
            dailyWaterGoalMl = obj.getDouble("dailyWaterGoalMl") ?: 2500.0
        )
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder()
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }
}

/**
 * Lightweight JSON Object and Tokenizer Parser.
 */
class JsonObject(val map: Map<String, Any?>) {
    fun getString(key: String): String? {
        val str = map[key] as? String ?: return null
        return if (str.length > 50000) str.substring(0, 50000) else str
    }
    /** For structured payloads (e.g. recipe ingredients) that must never be cut short. */
    fun getStringUncapped(key: String): String? = map[key] as? String
    fun getInt(key: String): Int? = (map[key] as? Number)?.toInt()
    fun getLong(key: String): Long? = (map[key] as? Number)?.toLong()
    fun getDouble(key: String): Double? {
        val num = (map[key] as? Number)?.toDouble() ?: return null
        if (num.isNaN() || num.isInfinite()) return null
        return num
    }
    fun getBoolean(key: String): Boolean? = map[key] as? Boolean
    fun getObject(key: String): JsonObject? = map[key] as? JsonObject
    @Suppress("UNCHECKED_CAST")
    fun getArray(key: String): List<Any?>? = map[key] as? List<Any?>
}

/** A backup that is well formed but bigger than MacroBase restores (BUG-047). */
class BackupTooLargeException(message: String) : IllegalArgumentException(message)

/** Characters of string values that one whole backup may keep in memory, shared by all its files. */
class TextBudget(val maxChars: Long) {
    var usedChars = 0L
        private set

    fun spend(chars: Long) {
        usedChars += chars
        if (usedChars > maxChars) throw BackupTooLargeException("The backup holds more than $maxChars characters of text")
    }
}

/**
 * Bounds for one backup file of records (BUG-047). Every record is a flat JSON object: no nested
 * objects or arrays, at most [maxFields] fields and [maxCharsPerRecord] characters of JSON. With
 * [maxRecords] and the shared [budget], memory stays bounded however a file is built.
 */
class RecordLimits(
    val maxRecords: Int,
    val maxCharsPerRecord: Long,
    val budget: TextBudget,
    val maxFields: Int = 64
) {
    companion object {
        /** Only the record shape is checked; for text that does not come from an archive. */
        fun unbounded() = RecordLimits(Int.MAX_VALUE, Long.MAX_VALUE, TextBudget(Long.MAX_VALUE), Int.MAX_VALUE)
    }
}

object SimpleJsonParser {

    private const val MAX_JSON_DEPTH = 32

    /**
     * Longest string value accepted. Real values are far shorter: a recipe's ingredient list is a few
     * KB. Even all non-ASCII, this many characters fits a SQLite row that Android can read back.
     */
    const val MAX_STRING_CHARS = 200_000

    private const val READ_BUFFER_CHARS = 8192

    fun parseObject(json: String): JsonObject = parseObject(StringReader(json), bufferFor(json))

    fun parseArray(json: String): List<Any?> = parseArray(StringReader(json), bufferFor(json))

    // Error messages give a position and at most a 40-character field name or a number token,
    // never a string value: backups hold the user's diary (BUG-047)
    fun parseObject(reader: Reader, bufferChars: Int = READ_BUFFER_CHARS): JsonObject =
        Parser(reader, bufferChars).parseValue() as? JsonObject ?: throw IllegalArgumentException("JSON is not an object")

    @Suppress("UNCHECKED_CAST")
    fun parseArray(reader: Reader, bufferChars: Int = READ_BUFFER_CHARS): List<Any?> =
        Parser(reader, bufferChars).parseValue() as? List<Any?> ?: throw IllegalArgumentException("JSON is not an array")

    /**
     * Reads a top-level JSON array of flat records one at a time. Each record goes to [transform] and
     * is then dropped, so memory holds the results only, never the whole text or its tree (BUG-047).
     */
    fun <T> parseRecords(reader: Reader, limits: RecordLimits, transform: (JsonObject) -> T): List<T> =
        Parser(reader, READ_BUFFER_CHARS).parseRecordArray(limits, transform)

    private fun bufferFor(json: String) = json.length.coerceIn(16, READ_BUFFER_CHARS)

    /** One character for an error message: quoted when printable ASCII, else its code point. */
    private fun describe(c: Char): String =
        if (c in ' '..'~') "'$c'" else String.format(Locale.US, "U+%04X", c.code)

    /** Pulls characters from a [Reader] through a small buffer, with one character of lookahead. */
    private class Parser(private val reader: Reader, bufferChars: Int) {
        private val buffer = CharArray(bufferChars)
        private var bufferLength = 0
        private var bufferIndex = 0
        private var position = 0L
        private var depth = 0

        // While a record is read, reading past this position fails
        private var recordEnd = Long.MAX_VALUE
        private var recordStart = 0L

        private fun fill(): Boolean {
            if (bufferIndex < bufferLength) return true
            bufferLength = reader.read(buffer, 0, buffer.size)
            bufferIndex = 0
            return bufferLength > 0
        }

        private fun atEnd(): Boolean = !fill()

        private fun peek(): Char = if (fill()) buffer[bufferIndex] else '\u0000'

        private fun next(): Char {
            if (!fill()) throw IllegalArgumentException("Unexpected end of JSON at position $position")
            if (position >= recordEnd) {
                throw IllegalArgumentException("Record at position $recordStart is longer than ${recordEnd - recordStart} characters")
            }
            position++
            return buffer[bufferIndex++]
        }

        private fun skipWhitespace() {
            while (fill() && buffer[bufferIndex].isWhitespace()) {
                bufferIndex++
                position++
            }
        }

        private fun describeNext(): String = if (atEnd()) "end of input" else describe(peek())

        private inline fun <T> nested(block: () -> T): T {
            if (depth >= MAX_JSON_DEPTH) {
                throw IllegalArgumentException("Exceeded maximum JSON nesting depth ($MAX_JSON_DEPTH)")
            }
            depth++
            try {
                return block()
            } finally {
                depth--
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) return null
            return when (val c = peek()) {
                '{' -> nested { parseObjectBody(flat = false, maxFields = Int.MAX_VALUE) }
                '[' -> nested { parseArrayBody() }
                '"' -> parseString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw IllegalArgumentException("Unexpected character ${describe(c)} at position $position")
                }
            }
        }

        fun <T> parseRecordArray(limits: RecordLimits, transform: (JsonObject) -> T): List<T> {
            skipWhitespace()
            if (peek() != '[') throw IllegalArgumentException("JSON is not an array")
            val result = ArrayList<T>()
            nested {
                readArray { index ->
                    if (index >= limits.maxRecords) {
                        throw BackupTooLargeException("More than ${limits.maxRecords} records in one file")
                    }
                    result.add(transform(readRecord(limits)))
                }
            }
            return result
        }

        private fun readRecord(limits: RecordLimits): JsonObject {
            skipWhitespace()
            if (peek() != '{') throw IllegalArgumentException("Expected a record object at position $position")
            recordStart = position
            recordEnd = if (limits.maxCharsPerRecord == Long.MAX_VALUE) Long.MAX_VALUE else position + limits.maxCharsPerRecord
            val record = try {
                nested { parseObjectBody(flat = true, maxFields = limits.maxFields) }
            } finally {
                recordEnd = Long.MAX_VALUE
            }
            var chars = 0L
            for (value in record.map.values) if (value is String) chars += value.length
            limits.budget.spend(chars)
            return record
        }

        /** In a [flat] object every value is a string, number, boolean or null. */
        private fun parseObjectBody(flat: Boolean, maxFields: Int): JsonObject {
            next() // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                next()
                return JsonObject(map)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') {
                    throw IllegalArgumentException("Expected ':' after key \"${printableText(key, 40)}\" at position $position")
                }
                next()
                if (flat) {
                    skipWhitespace()
                    if (peek() == '{' || peek() == '[') {
                        throw IllegalArgumentException("Nested value in a record at position $position")
                    }
                }
                map[key] = parseValue()
                if (map.size > maxFields) throw IllegalArgumentException("Record has more than $maxFields fields")
                skipWhitespace()
                when (peek()) {
                    ',' -> next()
                    '}' -> {
                        next()
                        return JsonObject(map)
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at position $position, found ${describeNext()}")
                }
            }
        }

        private fun parseArrayBody(): List<Any?> {
            val list = mutableListOf<Any?>()
            readArray { list.add(parseValue()) }
            return list
        }

        /** Reads '[' ... ']'; [readElement] reads one element and gets its index. A cut-off array is an error. */
        private inline fun readArray(readElement: (Int) -> Unit) {
            next() // '['
            skipWhitespace()
            if (peek() == ']') {
                next()
                return
            }
            var index = 0
            while (true) {
                readElement(index++)
                skipWhitespace()
                when (peek()) {
                    ',' -> next()
                    ']' -> {
                        next()
                        return
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at position $position, found ${describeNext()}")
                }
            }
        }

        private fun parseString(): String {
            if (peek() != '"') throw IllegalArgumentException("Expected '\"' at position $position")
            next() // opening '"'
            val start = position
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string starting at position $start")
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("Unterminated escape at position $position")
                        when (val esc = next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> sb.append(readUnicodeEscape())
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
                if (sb.length > MAX_STRING_CHARS) {
                    throw IllegalArgumentException("String starting at position $start is longer than $MAX_STRING_CHARS characters")
                }
            }
        }

        private fun readUnicodeEscape(): Char {
            val hex = StringBuilder(4)
            repeat(4) {
                if (atEnd()) throw IllegalArgumentException("Invalid unicode escape at position $position")
                hex.append(next())
            }
            val digits = hex.toString()
            if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw IllegalArgumentException("Invalid hex character in unicode escape at position $position")
            }
            return digits.toInt(16).toChar()
        }

        private fun parseNumber(): Number {
            val start = position
            val sb = StringBuilder()
            if (peek() == '-') sb.append(next())
            while (!atEnd()) {
                val c = peek()
                if (!(c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) break
                sb.append(next())
                if (sb.length > 64) throw IllegalArgumentException("Number at position $start is too long")
            }
            val numStr = printableText(sb.toString(), 64)
            if (numStr == "-" || numStr.isEmpty()) {
                throw IllegalArgumentException("Malformed number token '$numStr' at position $start")
            }
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid floating-point number: '$numStr'")
            } else {
                numStr.toLongOrNull() ?: numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid integer number: '$numStr'")
            }
        }

        private fun expectLiteral(word: String, value: Any?): Any? {
            for (expected in word) {
                if (atEnd() || peek() != expected) throw IllegalArgumentException("Expected $word at position $position")
                next()
            }
            return value
        }
    }
}
