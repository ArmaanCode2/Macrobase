package com.macrobase.app.feature.scanner

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.min
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NutritionLabelParserIntegrationTest {

    @Test
    fun testAllLabels() = runBlocking {
        val ocrEngine = NutritionLabelOcrEngine()

        val labels = listOf("LABEL_1.png", "LABEL_2.png", "LABEL_3.png", "LABEL_4.png", "LABEL_5.png")

        for (label in labels) {
            val file = File("/data/local/tmp/$label")
            if (!file.exists()) continue

            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue

            Log.i("ParserIntegrationTest", "PROCESSING $label")
            val pass = ocrEngine.recognizeAllPasses(bitmap).first()
            
            val sb = StringBuilder()
            sb.append("{\"fullText\":\"\", \"lines\":[")
            for ((i, line) in pass.lines.withIndex()) {
                sb.append("{\"text\":\"${line.text.replace("\"", "\\\"").replace("\n", "\\n")}\", \"boundingBox\":{\"left\":${line.boundingBox?.left},\"top\":${line.boundingBox?.top},\"right\":${line.boundingBox?.right},\"bottom\":${line.boundingBox?.bottom}},\"elements\":[")
                for ((j, elem) in line.elements.withIndex()) {
                    sb.append("{\"text\":\"${elem.text.replace("\"", "\\\"").replace("\n", "\\n")}\", \"boundingBox\":{\"left\":${elem.boundingBox?.left},\"top\":${elem.boundingBox?.top},\"right\":${elem.boundingBox?.right},\"bottom\":${elem.boundingBox?.bottom}}}")
                    if (j < line.elements.size - 1) sb.append(",")
                }
                sb.append("]}")
                if (i < pass.lines.size - 1) sb.append(",")
            }
            sb.append("]}")
            
            val json = sb.toString()
            val chunkSize = 3000
            Log.i("ParserIntegrationTest", "JSON_START_$label")
            for (i in 0 until json.length step chunkSize) {
                Log.i("ParserIntegrationTest", json.substring(i, min(json.length, i + chunkSize)))
            }
            Log.i("ParserIntegrationTest", "JSON_END_$label")
        }
    }
}
