package com.example.guidedogtest.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.Closeable

data class VisionObjectDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val distanceMeters: Float? = null
)

class ObjectDetectionEngine(context: Context) : Closeable {
    private val detector: ObjectDetector

    init {
        val baseOptions = BaseOptions.builder()
            .setNumThreads(OBJECT_DETECTION_THREADS)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(MIN_CONFIDENCE)
            .setMaxResults(MAX_RESULTS)
            .build()
        detector = ObjectDetector.createFromFileAndOptions(
            context,
            EFFICIENTDET_LITE0_MODEL,
            options
        )
    }

    fun detect(sourceBitmap: Bitmap, rotationDegrees: Int): List<VisionObjectDetection> {
        val uprightBitmap = rotateBitmap(sourceBitmap, rotationDegrees)
        return try {
            detector.detect(TensorImage.fromBitmap(uprightBitmap)).mapNotNull { detection ->
                val category = detection.categories.maxByOrNull { it.score }
                    ?: return@mapNotNull null
                val label = category.displayName.ifBlank { category.label }
                if (label.isBlank()) return@mapNotNull null
                VisionObjectDetection(
                    label = label,
                    confidence = category.score,
                    boundingBox = RectF(detection.boundingBox)
                )
            }
        } finally {
            if (uprightBitmap !== sourceBitmap) uprightBitmap.recycle()
            sourceBitmap.recycle()
        }
    }

    override fun close() {
        detector.close()
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private companion object {
        const val EFFICIENTDET_LITE0_MODEL = "efficientdet-lite0.tflite"
        const val MIN_CONFIDENCE = 0.45f
        const val MAX_RESULTS = 10
        const val OBJECT_DETECTION_THREADS = 4
    }
}
