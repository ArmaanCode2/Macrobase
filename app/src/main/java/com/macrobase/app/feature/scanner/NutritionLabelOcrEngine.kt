package com.macrobase.app.feature.scanner

import android.graphics.Bitmap
import android.graphics.Rect

interface NutritionLabelOcrEngine {
    /** Text in [bitmap]. Returns only once the engine has stopped reading it, so it can be recycled. */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult
    fun shutdown()
}
