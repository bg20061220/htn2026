package com.example.guidedogtest.ocr

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CameraYuvFrame(
    val width: Int,
    val height: Int,
    val y: PlaneCopy,
    val u: PlaneCopy,
    val v: PlaneCopy
)

data class PlaneCopy(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int)

class ArCoreVisionProcessor(
    context: Context,
    private val onText: (OcrFrameResult) -> Unit,
    private val onObjects: (List<VisionObjectDetection>) -> Unit,
    private val onSceneAwareness: (SceneAwarenessResult) -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val detector = ObjectDetectionEngine(context)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val sceneAwarenessAnalyzer = SceneAwarenessAnalyzer()
    private var lastOcrMillis = 0L

    fun submit(frame: CameraYuvFrame, rotation: Int, depth: DepthFrame?) {
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                depth?.let { sceneAwarenessAnalyzer.analyzeIfDue(it) }
                    ?.let(onSceneAwareness)
                val bitmap = frame.toBitmap()
                val uprightWidth = if (rotation == 90 || rotation == 270) frame.height else frame.width
                val uprightHeight = if (rotation == 90 || rotation == 270) frame.width else frame.height
                val detections = detector.detect(bitmap.copy(Bitmap.Config.ARGB_8888, false), rotation)
                onObjects(
                    DepthObjectFusion.attachDepth(
                        detections, uprightWidth, uprightHeight,
                        frame.width, frame.height, rotation, depth
                    )
                )

                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastOcrMillis < OCR_INTERVAL_MILLIS) {
                    bitmap.recycle()
                    busy.set(false)
                    return@execute
                }
                lastOcrMillis = now
                val input = InputImage.fromBitmap(bitmap, rotation)
                recognizer.process(input)
                    .addOnSuccessListener { text ->
                        onText(
                            OcrFrameResult(
                                text.text,
                                text.textBlocks.mapNotNull { block ->
                                    block.boundingBox?.let { OcrTextBlock(block.text, android.graphics.Rect(it)) }
                                },
                                uprightWidth,
                                uprightHeight
                            )
                        )
                    }
                    .addOnFailureListener { onError(it.message ?: "Text recognition failed.") }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        busy.set(false)
                    }
            } catch (error: Exception) {
                busy.set(false)
                onError(error.message ?: "AR camera frame analysis failed.")
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
        recognizer.close()
        detector.close()
    }

    companion object {
        fun copy(image: Image): CameraYuvFrame {
            fun plane(index: Int): PlaneCopy {
                val source = image.planes[index]
                val buffer = source.buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                return PlaneCopy(bytes, source.rowStride, source.pixelStride)
            }
            return CameraYuvFrame(image.width, image.height, plane(0), plane(1), plane(2))
        }

        private const val OCR_INTERVAL_MILLIS = 800L
    }
}

private fun CameraYuvFrame.toBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        for (col in 0 until width) {
            val yValue = y.bytes[row * y.rowStride + col * y.pixelStride].toInt() and 0xff
            val uvRow = row / 2
            val uvCol = col / 2
            val uValue = u.bytes[uvRow * u.rowStride + uvCol * u.pixelStride].toInt() and 0xff
            val vValue = v.bytes[uvRow * v.rowStride + uvCol * v.pixelStride].toInt() and 0xff
            val yf = (yValue - 16).coerceAtLeast(0)
            val uf = uValue - 128
            val vf = vValue - 128
            val r = ((298 * yf + 409 * vf + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * yf - 100 * uf - 208 * vf + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * yf + 516 * uf + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
