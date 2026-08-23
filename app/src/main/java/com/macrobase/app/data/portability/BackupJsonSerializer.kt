package com.macrobase.app.data.portability

import com.macrobase.app.domain.model.BackupCompatibilityDto
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto

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
    "createdAt": ${e.createdAt}
  }"""
        }
        return "[\n$items\n]"
    }

    fun parseDiaryEntries(json: String): List<DiaryEntryBackupDto> {
        val array = SimpleJsonParser.parseArray(json)
        return array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Expected diary object")
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
                createdAt = obj.getLong("createdAt") ?: System.currentTimeMillis()
            )
        }
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
    "calories": ${f.calories},
    "proteinGrams": ${f.proteinGrams},
    "carbsGrams": ${f.carbsGrams},
    "fatGrams": ${f.fatGrams},
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

    fun parseCustomFoods(json: String): List<CustomFoodBackupDto> {
        val array = SimpleJsonParser.parseArray(json)
        return array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Expected custom food object")
            CustomFoodBackupDto(
                uuid = obj.getString("uuid") ?: java.util.UUID.randomUUID().toString(),
                name = obj.getString("name") ?: "",
                brand = obj.getString("brand"),
                servingSize = obj.getDouble("servingSize") ?: 1.0,
                servingUnit = obj.getString("servingUnit") ?: "serving",
                calories = obj.getDouble("calories") ?: 0.0,
                proteinGrams = obj.getDouble("proteinGrams") ?: 0.0,
                carbsGrams = obj.getDouble("carbsGrams") ?: 0.0,
                fatGrams = obj.getDouble("fatGrams") ?: 0.0,
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

    fun parseRecipes(json: String): List<RecipeBackupDto> {
        val array = SimpleJsonParser.parseArray(json)
        return array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Expected recipe object")
            RecipeBackupDto(
                uuid = obj.getString("uuid") ?: java.util.UUID.randomUUID().toString(),
                name = obj.getString("name") ?: "",
                servingsProduced = obj.getInt("servingsProduced") ?: 1,
                ingredientsJson = obj.getString("ingredientsJson") ?: "[]",
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

    fun parseWeightEntries(json: String): List<WeightEntryBackupDto> {
        val array = SimpleJsonParser.parseArray(json)
        return array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Expected weight object")
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

    fun parseWaterLogs(json: String): List<WaterLogBackupDto> {
        val array = SimpleJsonParser.parseArray(json)
        return array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Expected water object")
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
        return """{
  "dailyCalorieGoal": ${goal.dailyCalorieGoal},
  "carbPercentage": ${goal.carbPercentage},
  "proteinPercentage": ${goal.proteinPercentage},
  "fatPercentage": ${goal.fatPercentage}
}"""
    }

    fun parseGoals(json: String): GoalBackupDto {
        val obj = SimpleJsonParser.parseObject(json)
        return GoalBackupDto(
            dailyCalorieGoal = obj.getDouble("dailyCalorieGoal") ?: 2000.0,
            carbPercentage = obj.getDouble("carbPercentage") ?: 50.0,
            proteinPercentage = obj.getDouble("proteinPercentage") ?: 25.0,
            fatPercentage = obj.getDouble("fatPercentage") ?: 25.0
        )
    }

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
                        sb.append(String.format("\\u%04x", c.code))
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

object SimpleJsonParser {

    fun parseObject(json: String): JsonObject {
        val parser = StringParser(json.trim())
        val res = parser.parseValue()
        return (res as? JsonObject) ?: throw IllegalArgumentException("JSON is not an object: $json")
    }

    @Suppress("UNCHECKED_CAST")
    fun parseArray(json: String): List<Any?> {
        val parser = StringParser(json.trim())
        val res = parser.parseValue()
        return (res as? List<Any?>) ?: throw IllegalArgumentException("JSON is not an array: $json")
    }

    private class StringParser(private val text: String) {
        private var pos = 0

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) {
                pos++
            }
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) return null
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw IllegalArgumentException("Unexpected char '$c' at pos $pos in $text")
                }
            }
        }

        private fun parseObject(): JsonObject {
            pos++ // skip '{'
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonObject(map)
            }
            while (pos < text.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') throw IllegalArgumentException("Expected ':' after key '$key' at pos $pos")
                pos++ // skip ':'
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (peek() == ',') {
                    pos++
                } else if (peek() == '}') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at pos $pos, found '${peek()}'")
                }
            }
            return JsonObject(map)
        }

        private fun parseArray(): List<Any?> {
            pos++ // skip '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (pos < text.length) {
                val value = parseValue()
                list.add(value)
                skipWhitespace()
                if (peek() == ',') {
                    pos++
                } else if (peek() == ']') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at pos $pos, found '${peek()}'")
                }
            }
            return list
        }

        private fun parseString(): String {
            if (peek() != '"') throw IllegalArgumentException("Expected '\"' at pos $pos")
            pos++ // skip opening '"'
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') {
                    return sb.toString()
                } else if (c == '\\') {
                    if (pos >= text.length) throw IllegalArgumentException("Unterminated escape at pos $pos")
                    when (val esc = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw IllegalArgumentException("Invalid unicode escape at pos $pos")
                            val hex = text.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string starting at $pos")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')) {
                pos++
            }
            val numStr = text.substring(start, pos)
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                numStr.toDouble()
            } else {
                numStr.toLongOrNull() ?: numStr.toDouble()
            }
        }

        private fun parseBoolean(): Boolean {
            if (text.startsWith("true", pos)) {
                pos += 4
                return true
            }
            if (text.startsWith("false", pos)) {
                pos += 5
                return false
            }
            throw IllegalArgumentException("Expected boolean at pos $pos")
        }

        private fun parseNull(): Any? {
            if (text.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Expected null at pos $pos")
        }
    }
}
