package com.example.guidedogtest.ocr

import android.graphics.PointF
import kotlin.math.roundToInt

data class DepthFrame(
    val width: Int,
    val height: Int,
    val millimeters: IntArray,
    val timestampNanos: Long,
    /** IMAGE_PIXELS camera-image corners TL, TR, BL transformed to TEXTURE_NORMALIZED. */
    val textureCorners: FloatArray,
    /** Display-space TL, TR, BL mapped from OpenGL NDC into the depth texture. */
    val displayTextureCorners: FloatArray
)

object DepthObjectFusion {
    fun attachDepth(
        detections: List<VisionObjectDetection>,
        uprightWidth: Int,
        uprightHeight: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        depth: DepthFrame?
    ): List<VisionObjectDetection> {
        if (depth == null) return detections
        return detections.map { detection ->
            val center = PointF(
                detection.boundingBox.centerX().coerceIn(0f, uprightWidth - 1f),
                detection.boundingBox.centerY().coerceIn(0f, uprightHeight - 1f)
            )
            val raw = uprightToRaw(center, rawWidth, rawHeight, rotationDegrees)
            val nx = (raw.x / rawWidth).coerceIn(0f, 1f)
            val ny = (raw.y / rawHeight).coerceIn(0f, 1f)
            val c = depth.textureCorners
            val u = c[0] + nx * (c[2] - c[0]) + ny * (c[4] - c[0])
            val v = c[1] + nx * (c[3] - c[1]) + ny * (c[5] - c[1])
            detection.copy(distanceMeters = medianMeters(depth, u, v))
        }
    }

    private fun uprightToRaw(p: PointF, width: Int, height: Int, rotation: Int): PointF =
        when ((rotation % 360 + 360) % 360) {
            90 -> PointF(p.y, height - 1f - p.x)
            180 -> PointF(width - 1f - p.x, height - 1f - p.y)
            270 -> PointF(width - 1f - p.y, p.x)
            else -> p
        }

    private fun medianMeters(depth: DepthFrame, u: Float, v: Float): Float? {
        if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        val cx = (u * (depth.width - 1)).roundToInt()
        val cy = (v * (depth.height - 1)).roundToInt()
        val values = ArrayList<Int>(25)
        for (y in (cy - 2).coerceAtLeast(0)..(cy + 2).coerceAtMost(depth.height - 1)) {
            for (x in (cx - 2).coerceAtLeast(0)..(cx + 2).coerceAtMost(depth.width - 1)) {
                depth.millimeters[y * depth.width + x].takeIf { it > 0 }?.let(values::add)
            }
        }
        if (values.size < 3) return null
        values.sort()
        return values[values.size / 2] / 1000f
    }
}
