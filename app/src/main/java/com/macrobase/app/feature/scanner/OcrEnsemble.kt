package com.macrobase.app.feature.scanner

import android.graphics.Rect
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detailed result of a single OCR pass on a specific image variant.
 */
data class OcrPassResult(
    val passIndex: Int,
    val sourceVariantId: String,
    val sourceVariantName: String,
    val fullText: String,
    val blocks: List<OcrBlock>,
    val lines: List<OcrLine>,
    val rotation: Int = 0,
    val keywordCoverageScore: Double = 0.0,
    val numericTokenScore: Double = 0.0,
    val unitDetectionScore: Double = 0.0,
    val rowAlignmentScore: Double = 0.0,
    val overallQualityScore: Double = 0.0
)

/**
 * Consolidated ensemble result comparing and blending multiple OCR passes.
 */
data class OcrEnsembleResult(
    val passes: List<OcrPassResult>,
    val bestOverallPass: OcrPassResult,
    val unifiedLines: List<OcrLine>
)

/**
 * Evaluates and ensembles multiple OCR passes based on measurable nutritional signals.
 */
class OcrEnsembleScorer {

    companion object {
        private val NUTRITION_ANCHORS = listOf(
            "nutrition", "nutritional", "energy", "calories", "kcal", "protein",
            "carbohydrate", "carbs", "sugar", "sugars", "fat", "fats", "saturates",
            "saturated", "sodium", "salt", "fibre", "fiber", "cholesterol",
            "serving", "serve", "100g", "100 g", "approx", "typical", "values"
        )

        private val UNIT_PATTERNS = Regex("""(?i)\b(g|gm|gms|mg|mcg|µg|μg|kcal|cal|kj|ml|%|%rda|%dv)\b""")
        private val NUMERIC_PATTERN = Regex("""\b\d+([.,]\d+)?\b""")
        private val NOISY_OCR_CHARS = Regex("""[~`_^{}\[\]\\|§©®™]""")
    }

    /**
     * Scores an individual OCR pass using measurable signals.
     */
    fun scorePass(
        passIndex: Int,
        variantId: String,
        variantName: String,
        ocrResult: OcrResult,
        rotation: Int = 0
    ): OcrPassResult {
        val fullTextLower = ocrResult.fullText.lowercase(Locale.ROOT)
        val lines = ocrResult.lines

        // 1. Keyword coverage
        var keywordHits = 0
        for (kw in NUTRITION_ANCHORS) {
            if (fullTextLower.contains(kw)) {
                keywordHits++
            }
        }
        val keywordCoverageScore = (keywordHits * 10.0).coerceAtMost(100.0)

        // 2. Numeric token count
        val numericCount = NUMERIC_PATTERN.findAll(ocrResult.fullText).count()
        val numericTokenScore = (numericCount * 6.0).coerceAtMost(100.0)

        // 3. Valid unit detection count
        val unitCount = UNIT_PATTERNS.findAll(ocrResult.fullText).count()
        val unitDetectionScore = (unitCount * 8.0).coerceAtMost(100.0)

        // 4. Noise penalty (garbled non-alphanumeric punctuation)
        val noiseHits = NOISY_OCR_CHARS.findAll(ocrResult.fullText).count()
        val noisePenalty = (noiseHits * 3.0).coerceAtMost(40.0)

        // 5. Row alignment quality: elements that form clean horizontal lines
        var alignedLines = 0
        for (line in lines) {
            val sBox = line.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(line.boundingBox)
            if (sBox != null && sBox.width > 20 && sBox.height > 6) {
                alignedLines++
            }
        }
        val rowAlignmentScore = if (lines.isNotEmpty()) {
            (alignedLines.toDouble() / lines.size * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        // Composite overall quality score
        val overallQualityScore = (
            keywordCoverageScore * 0.35 +
            numericTokenScore * 0.25 +
            unitDetectionScore * 0.25 +
            rowAlignmentScore * 0.15 -
            noisePenalty
        ).coerceIn(0.0, 100.0)

        return OcrPassResult(
            passIndex = passIndex,
            sourceVariantId = variantId,
            sourceVariantName = variantName,
            fullText = ocrResult.fullText,
            blocks = ocrResult.blocks,
            lines = ocrResult.lines,
            rotation = rotation,
            keywordCoverageScore = keywordCoverageScore,
            numericTokenScore = numericTokenScore,
            unitDetectionScore = unitDetectionScore,
            rowAlignmentScore = rowAlignmentScore,
            overallQualityScore = overallQualityScore
        )
    }

    /**
     * Builds the ensemble result, identifying the best overall pass and merging regional improvements.
     */
    fun buildEnsemble(scoredPasses: List<OcrPassResult>): OcrEnsembleResult {
        if (scoredPasses.isEmpty()) {
            val empty = OcrPassResult(
                passIndex = 1,
                sourceVariantId = "empty",
                sourceVariantName = "Empty",
                fullText = "",
                blocks = emptyList(),
                lines = emptyList()
            )
            return OcrEnsembleResult(emptyList(), empty, emptyList())
        }

        val bestPass = scoredPasses.maxByOrNull { it.overallQualityScore } ?: scoredPasses.first()

        // Regional blending: take the primary lines from bestPass, but if another pass
        // has a clearly recognized nutrient row missing in bestPass, incorporate it.
        val combinedLines = bestPass.lines.toMutableList()

        for (pass in scoredPasses) {
            if (pass == bestPass) continue
            for (line in pass.lines) {
                val textLower = line.text.lowercase(Locale.ROOT)
                val isNutrientRow = NUTRITION_ANCHORS.any { textLower.contains(it) } &&
                        NUMERIC_PATTERN.containsMatchIn(line.text)

                if (isNutrientRow) {
                    val lineY = (line.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(line.boundingBox))?.centerY
                    val hasExistingMatch = combinedLines.any { existing ->
                        val exY = (existing.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(existing.boundingBox))?.centerY
                        if (lineY != null && exY != null) {
                            abs(lineY - exY) < 20
                        } else {
                            existing.text.equals(line.text, ignoreCase = true)
                        }
                    }

                    if (!hasExistingMatch) {
                        combinedLines.add(line)
                    }
                }
            }
        }

        val sortedLines = combinedLines.sortedBy {
            (it.spatialBounds ?: NutritionLabelParser.SpatialBox.fromAndroidRect(it.boundingBox))?.top ?: 0
        }

        return OcrEnsembleResult(
            passes = scoredPasses,
            bestOverallPass = bestPass,
            unifiedLines = sortedLines
        )
    }
}
