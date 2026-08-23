# PaddleOCR Integration Plan

This document outlines the architecture, dependencies, and strategy for adding PP-OCRv6_small as a secondary on-device OCR engine in MacroBase, adhering to the official PaddleOCR Android deployment documentation.

## 1. Official Documentation & Versions

- **Source of Truth**: [PaddleOCR/deploy/ppocr-android](https://github.com/PaddlePaddle/PaddleOCR/tree/main/deploy/ppocr-android)
- **Model**: PP-OCRv6_small
  - Detection Model: `PP-OCRv6_small_det_onnx`
  - Recognition Model: `PP-OCRv6_small_rec_onnx` (includes `inference.yml`)
- **Dependencies**:
  - `com.microsoft.onnxruntime:onnxruntime-android:1.21.1`
  - `com.quickbirdstudios:opencv:4.5.3` (or an equivalent native OpenCV build)
  - Kotlin Coroutines

## 2. Architecture & OCR Provider Abstraction

We will introduce a common OCR provider interface so the same nutrition label parser can consume results from either ML Kit or PaddleOCR.

```kotlin
interface NutritionLabelOcrEngine {
    suspend fun processImage(image: ImageProxy): OcrDocument
    fun release()
}

data class OcrDocument(
    val fullText: String,
    val lines: List<OcrLine>
)

data class OcrLine(
    val text: String,
    val boundingBox: Rect?,
    val elements: List<OcrElement>,
    val spatialBounds: SpatialBox?
)

data class OcrElement(
    val text: String,
    val boundingBox: Rect?,
    val confidence: Float,
    val spatialBounds: SpatialBox?
)
```

The existing `NutritionLabelOcrEngine` will be renamed to `MlKitOcrEngine`, and a new `PaddleOcrEngine` will be created. A factory or repository will provide the engine based on a configuration flag.

## 3. Model Assets

The models will be packaged locally inside the APK at:
- `app/src/main/assets/models/det/inference.onnx`
- `app/src/main/assets/models/rec/inference.onnx`
- `app/src/main/assets/models/rec/inference.yml`

This guarantees offline capability (no network requests).

## 4. Initialization & Lifecycle

PaddleOCR requires OpenCV initialization and ONNX session creation. This will occur once per scanner session (or application scope) to avoid memory leaks and excessive cold-start times.

- `PaddleOcrEngine.init()` invoked when the scanner opens.
- `PaddleOcrEngine.release()` invoked when the scanner closes.

## 5. Preprocessing & Benchmarking

The existing CameraX integration (`ImageProxy`) will pass the raw or cropped image to the `PaddleOcrEngine`. PaddleOCR uses its own internal preprocessing (resizing to multiples of 32, standard deviation normalization, etc.), so we will hand off the highest resolution cropped image.

After integration, we will run the 5 provided ground-truth images (`LABEL_1.png` - `LABEL_5.png`) through both engines to benchmark:
1. OCR Text Quality (Words detected, structure)
2. Nutrition Extraction (Calories, Protein, Carbs, Fat, etc.)
3. Timing & Memory (Inference time, peak memory usage)

No code from ML Kit will be removed during this phase.
