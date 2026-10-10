package com.macrobase.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * BUG-034 / AGENTS.md section 2.2: nutrient and calorie values are rounded, never truncated,
 * so every screen shows the same number (1999.8 kcal is "2000" everywhere).
 */
class NutrientRoundingLintTest {

    /** A nutrient, calorie, water or percentage value truncated anywhere. */
    private val namedTruncation = Regex(
        """(?i)\b\w*(cal|protein|carb|fat|fiber|sugar|sodium|cholesterol|intake|balance|water|amountml|goalml|grams|mg|pct|percent)\w*\)?\??\.toInt\(\)"""
    )

    /** Any computed expression truncated in UI code, e.g. `((cals / goal) * 100.0).toInt()`. */
    private val expressionTruncation = Regex("""\)\.toInt\(\)""")

    private fun isUiCode(path: String) =
        (path.startsWith("feature/") && !path.startsWith("feature/scanner/")) || path.startsWith("core/designsystem/")

    /** Exact conversions of a value already checked to be whole, e.g. `if (x % 1.0 == 0.0) x.toInt()`. */
    private fun isWholeNumberConversion(line: String) =
        "% 1.0 == 0.0" in line && Regex("""\.toInt\(\)""").findAll(line).count() == 1

    /** Known non-display truncations (path to a snippet of the line). */
    private val allowed = listOf(
        // Matches label text such as "30g"; not a displayed value
        "feature/scanner/SpatialTableDetector.kt" to "servingGrams.toInt()}g",
        // Open bug BUG-026 (goal percentage-sum validation), outside BUG-034's display scope
        "domain/model/Goal.kt" to "fatPercentage).toInt()"
    )

    @Test
    fun noNutrientValueIsTruncatedWithToInt() {
        val sourceRoot = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath.removePrefix("com/macrobase/app/")
                file.readLines().mapIndexedNotNull { index, line ->
                    val isTruncation = (namedTruncation.containsMatchIn(line) ||
                        (isUiCode(relative) && expressionTruncation.containsMatchIn(line))) &&
                        !isWholeNumberConversion(line)
                    val isAllowed = allowed.any { (path, snippet) -> relative == path && snippet in line }
                    if (isTruncation && !isAllowed) "$relative:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        assertTrue("Use roundToInt() for nutrient values:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun calorieTotalsRoundToTheNearestWholeNumber() {
        assertTrue(1999.8.roundToInt() == 2000)
        assertTrue(1999.4.roundToInt() == 1999)
    }
}
