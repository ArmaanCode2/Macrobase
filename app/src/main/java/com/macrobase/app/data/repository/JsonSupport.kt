package com.macrobase.app.data.repository

import com.macrobase.app.data.portability.JsonObject
import com.macrobase.app.domain.model.Nutrition

/**
 * JSON helpers shared by the app's own stored formats (recipe ingredients, the basket).
 * Output is locale-independent and keeps missing micronutrients absent, never 0.0.
 */
internal object JsonSupport {
    fun encodeNutrition(n: Nutrition): String {
        val fields = linkedMapOf<String, Double?>(
            "calories" to n.calories,
            "proteinGrams" to n.proteinGrams,
            "carbsGrams" to n.carbsGrams,
            "fatGrams" to n.fatGrams,
            "fiberGrams" to n.fiberGrams,
            "sugarGrams" to n.sugarGrams,
            "saturatedFatGrams" to n.saturatedFatGrams,
            "transFatGrams" to n.transFatGrams,
            "cholesterolMg" to n.cholesterolMg,
            "sodiumMg" to n.sodiumMg,
            "potassiumMg" to n.potassiumMg,
            "calciumMg" to n.calciumMg,
            "ironMg" to n.ironMg,
            "magnesiumMg" to n.magnesiumMg,
            "phosphorusMg" to n.phosphorusMg,
            "zincMg" to n.zincMg,
            "vitaminARaeMcg" to n.vitaminARaeMcg,
            "vitaminCMg" to n.vitaminCMg,
            "vitaminDMcg" to n.vitaminDMcg,
            "vitaminEMg" to n.vitaminEMg,
            "vitaminKMcg" to n.vitaminKMcg,
            "vitaminB6Mg" to n.vitaminB6Mg,
            "vitaminB12Mcg" to n.vitaminB12Mcg,
            "folateMcg" to n.folateMcg,
            "waterGrams" to n.waterGrams
        )
        // Missing micronutrients stay absent (null), never 0.0 (AGENTS.md section 2.4)
        return fields.entries
            .filter { it.value != null }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { "\"${it.key}\":${number(it.value!!)}" }
    }

    fun decodeNutrition(o: JsonObject) = Nutrition(
        calories = o.getDouble("calories") ?: 0.0,
        proteinGrams = o.getDouble("proteinGrams") ?: 0.0,
        carbsGrams = o.getDouble("carbsGrams") ?: 0.0,
        fatGrams = o.getDouble("fatGrams") ?: 0.0,
        fiberGrams = o.getDouble("fiberGrams"),
        sugarGrams = o.getDouble("sugarGrams"),
        saturatedFatGrams = o.getDouble("saturatedFatGrams"),
        transFatGrams = o.getDouble("transFatGrams"),
        cholesterolMg = o.getDouble("cholesterolMg"),
        sodiumMg = o.getDouble("sodiumMg"),
        potassiumMg = o.getDouble("potassiumMg"),
        calciumMg = o.getDouble("calciumMg"),
        ironMg = o.getDouble("ironMg"),
        magnesiumMg = o.getDouble("magnesiumMg"),
        phosphorusMg = o.getDouble("phosphorusMg"),
        zincMg = o.getDouble("zincMg"),
        vitaminARaeMcg = o.getDouble("vitaminARaeMcg"),
        vitaminCMg = o.getDouble("vitaminCMg"),
        vitaminDMcg = o.getDouble("vitaminDMcg"),
        vitaminEMg = o.getDouble("vitaminEMg"),
        vitaminKMcg = o.getDouble("vitaminKMcg"),
        vitaminB6Mg = o.getDouble("vitaminB6Mg"),
        vitaminB12Mcg = o.getDouble("vitaminB12Mcg"),
        folateMcg = o.getDouble("folateMcg"),
        waterGrams = o.getDouble("waterGrams")
    )

    /** Locale-independent; non-finite values are stored as 0 so the JSON stays valid. */
    fun number(value: Double): String = if (value.isFinite()) value.toString() else "0"

    fun quote(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
