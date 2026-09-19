package com.example.guidedogtest.ocr

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect
)

data class OcrFrameResult(
    val fullText: String = "",
    val blocks: List<OcrTextBlock> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

class OcrTextAnalyzer(
    context: android.content.Context,
    private val onResult: (OcrFrameResult) -> Unit,
    private val onObjectDetections: (List<VisionObjectDetection>) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer, Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val objectDetectionEngine = ObjectDetectionEngine(context)
    private val processing = AtomicBoolean(false)
    private var lastObjectDetectionMillis = 0L
    private var lastOcrMillis = 0L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (!processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val uprightWidth = if (rotation == 90 || rotation == 270) {
            imageProxy.height
        } else {
            imageProxy.width
        }
        val uprightHeight = if (rotation == 90 || rotation == 270) {
            imageProxy.width
        } else {
            imageProxy.height
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val shouldDetectObjects = now - lastObjectDetectionMillis >= OBJECT_INTERVAL_MILLIS
        val shouldRunOcr = now - lastOcrMillis >= OCR_INTERVAL_MILLIS

        if (!shouldDetectObjects && !shouldRunOcr) {
            processing.set(false)
            imageProxy.close()
            return
        }

        try {
            if (shouldDetectObjects) {
                lastObjectDetectionMillis = now
                try {
                    onObjectDetections(
                        objectDetectionEngine.detect(imageProxy.toBitmap(), rotation)
                    )
                } catch (error: Exception) {
                    onError(error.message ?: "Object detection failed.")
                }
            }

            if (!shouldRunOcr) {
                processing.set(false)
                imageProxy.close()
                return
            }

            lastOcrMillis = now
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
            recognizer.process(inputImage)
                .addOnSuccessListener { recognizedText ->
                    onResult(
                        OcrFrameResult(
                            fullText = recognizedText.text,
                            blocks = recognizedText.textBlocks.mapNotNull { block ->
                                block.boundingBox?.let { box ->
                                    OcrTextBlock(block.text, Rect(box))
                                }
                            },
                            imageWidth = uprightWidth,
                            imageHeight = uprightHeight
                        )
                    )
                }
                .addOnFailureListener { error ->
                    onError(error.message ?: "Text recognition failed.")
                }
                .addOnCompleteListener {
                    processing.set(false)
                    imageProxy.close()
                }
        } catch (error: Exception) {
            processing.set(false)
            imageProxy.close()
            onError(error.message ?: "Unable to analyze the camera frame.")
        }
    }

    override fun close() {
        recognizer.close()
        objectDetectionEngine.close()
    }

    private companion object {
        const val OBJECT_INTERVAL_MILLIS = 150L
        const val OCR_INTERVAL_MILLIS = 800L
    }
}
