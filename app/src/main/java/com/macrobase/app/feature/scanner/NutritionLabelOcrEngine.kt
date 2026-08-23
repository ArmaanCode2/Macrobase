package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Rect

interface NutritionLabelOcrEngine {
    suspend fun recognizeText(bitmap: Bitmap): OcrResult
    suspend fun recognizeAllPasses(bitmap: Bitmap): List<OcrResult>
    fun shutdown()
}
