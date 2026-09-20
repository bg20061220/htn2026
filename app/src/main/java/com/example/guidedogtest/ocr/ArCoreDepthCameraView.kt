package com.example.guidedogtest.ocr

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES11Ext
import android.media.Image
import android.opengl.GLES20
import android.util.Log
import android.opengl.GLSurfaceView
import android.view.Surface
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class ArDepthStatus(
    val sessionActive: Boolean = false,
    val depthActive: Boolean = false,
    val latestDepthTimestampNanos: Long? = null,
    val message: String? = null
)

class ArCoreDepthCameraView(
    context: Context,
    onText: (OcrFrameResult) -> Unit,
    onObjects: (List<VisionObjectDetection>) -> Unit,
    onSceneAwareness: (SceneAwarenessResult) -> Unit,
    private val onStatus: (ArDepthStatus) -> Unit,
    onError: (String) -> Unit
) : GLSurfaceView(context) {
    private val processor = ArCoreVisionProcessor(
        context, onText, onObjects, onSceneAwareness, onError
    )
    private val session: Session
    private val depthSupported: Boolean
    private val renderer: CameraRenderer
    private var resumed = false

    init {
        setEGLContextClientVersion(2)
        session = Session(context)
        depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val config = session.config.apply {
            depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        }
        session.configure(config)
        renderer = CameraRenderer(context, session, processor, depthSupported, onStatus, onError)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun resumeSession() {
        if (resumed) return
        try {
            session.resume()
            resumed = true
            super.onResume()
            onStatus(ArDepthStatus(true, false, message = if (depthSupported) "Waiting for depth..." else "Depth API is not supported on this device."))
        } catch (error: CameraNotAvailableException) {
            onStatus(ArDepthStatus(message = "ARCore camera is unavailable: ${error.message}"))
        }
    }

    fun pauseSession() {
        if (!resumed) return
        super.onPause()
        session.pause()
        resumed = false
        onStatus(ArDepthStatus(false, false))
    }

    fun release() {
        pauseSession()
        processor.close()
        session.close()
    }
}

private class CameraRenderer(
    private val context: Context,
    private val session: Session,
    private val processor: ArCoreVisionProcessor,
    private val depthSupported: Boolean,
    private val onStatus: (ArDepthStatus) -> Unit,
    private val onError: (String) -> Unit
) : GLSurfaceView.Renderer {
    private var textureId = -1
    private var program = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var lastAnalysisNanos = 0L
    private var latestDepthTimestamp: Long? = null
    private val reportedError = AtomicBoolean(false)
    private val vertices = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val textureCoordinates = floatBuffer(FloatArray(8))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textureId = createExternalTexture()
        session.setCameraTextureName(textureId)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        session.setDisplayGeometry(displayRotation(), surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        try {
            val frame = session.update()
            drawCamera(frame)
            if (frame.timestamp == 0L || frame.timestamp - lastAnalysisNanos < ANALYSIS_INTERVAL_NANOS) return
            // Depth first, and out of this frame's own camera dimensions.
            //
            // Both images belong to the frame session.update() just returned, and the depth image is
            // the one the safety path cannot do without. It was being asked for *after* the camera
            // image - which can block waiting for a buffer, and is held while the YUV planes are
            // copied - so the request landed on a frame that had already moved on. ARCore answers that
            // with a perfectly valid, perfectly empty image: 0 of 14,400 pixels measured, while the
            // panel still says "Depth API: active". The dimensions come from the intrinsics, so no
            // camera buffer needs to be held to ask.
            val imageDimensions = frame.camera.imageIntrinsics.imageDimensions
            val depth = acquireDepth(frame, imageDimensions[0], imageDimensions[1])
            if (depth != null) processor.submitDepth(depth)

            val cameraImage = try { frame.acquireCameraImage() } catch (_: NotYetAvailableException) { null }
            if (cameraImage == null) {
                visionIntervalMillis = lastAnalysisNanos.takeIf { it != 0L }?.let { (frame.timestamp - it) / 1_000_000.0 } ?: 0.0
                lastAnalysisNanos = frame.timestamp
                return
            }
            val viewCorners = imageToViewCorners(frame, cameraImage.width, cameraImage.height)
            val cameraCopyStartedNanos = System.nanoTime()
            val copied = try { ArCoreVisionProcessor.copy(cameraImage, viewCorners) } finally { cameraImage.close() }
            cameraCopyMillis = (System.nanoTime() - cameraCopyStartedNanos) / 1_000_000.0
            val rotation = cameraRotationDegrees()
            visionIntervalMillis = lastAnalysisNanos.takeIf { it != 0L }?.let { (frame.timestamp - it) / 1_000_000.0 } ?: 0.0
            lastAnalysisNanos = frame.timestamp
            processor.submit(copied, rotation, depth)
        } catch (error: Exception) {
            if (reportedError.compareAndSet(false, true)) onError(error.message ?: "ARCore vision session failed.")
        }
    }

    /**
     * Copies the depth image out, a row at a time.
     *
     * `getShort` per pixel through the plane's row and pixel strides cost 6-19 ms a frame on the S21 -
     * on the GL thread, at the frame rate, for 160x90 numbers - because every read recomputed an
     * offset into a direct buffer. A row of this image is contiguous (pixel stride 2), so it can be
     * read in bulk instead; the strided path is kept for an image that is not.
     */
    private fun copyDepth(
        plane: Image.Plane,
        buffer: ShortBuffer,
        values: IntArray,
        width: Int,
        height: Int,
    ) {
        val rowShorts = ShortArray(width)
        for (y in 0 until height) {
            val rowStart = y * plane.rowStride / Short.SIZE_BYTES
            if (plane.pixelStride == Short.SIZE_BYTES) {
                buffer.position(rowStart)
                buffer.get(rowShorts, 0, width)
                val rowOffset = y * width
                for (x in 0 until width) values[rowOffset + x] = rowShorts[x].toInt() and 0xffff
            } else {
                val pixelStep = plane.pixelStride / Short.SIZE_BYTES
                val rowOffset = y * width
                for (x in 0 until width) {
                    values[rowOffset + x] = buffer.get(rowStart + x * pixelStep).toInt() and 0xffff
                }
            }
        }
    }

    private fun imageToViewCorners(frame: Frame, width: Int, height: Int): FloatArray {
        val input = floatBuffer(floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat()))
        val output = floatBuffer(FloatArray(6))
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            input,
            Coordinates2d.VIEW_NORMALIZED,
            output
        )
        return FloatArray(6).also { output.rewind(); output.get(it) }
    }

    private var depthFrames = 0
    private var depthLogMillis = System.currentTimeMillis()
    private var cameraCopyMillis = 0.0
    private var depthNotReady = 0
    private var visionIntervalMillis = 0.0

    private fun acquireDepth(frame: Frame, imageWidth: Int, imageHeight: Int): DepthFrame? {
        if (!depthSupported) {
            onStatus(ArDepthStatus(true, false, message = "Depth API is not supported on this device."))
            return null
        }
        val depthImage = try {
            frame.acquireDepthImage16Bits()
        } catch (_: NotYetAvailableException) {
            // "Not yet available" is what depth-from-motion looks like when the camera has not moved:
            // counted, because on a stationary robot it is the difference between "the depth module is
            // broken" and "the robot needs to be nudged for the camera to have parallax to work with".
            depthNotReady++
            onStatus(ArDepthStatus(true, false, latestDepthTimestamp, "Depth measurement temporarily unavailable."))
            return null
        }
        return try {
            val plane = depthImage.planes[0]
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val copyStartedNanos = System.nanoTime()
            val values = IntArray(depthImage.width * depthImage.height)
            copyDepth(plane, buffer, values, depthImage.width, depthImage.height)
            val copyMillis = (System.nanoTime() - copyStartedNanos) / 1_000_000.0
            val input = floatBuffer(floatArrayOf(0f, 0f, imageWidth.toFloat(), 0f, 0f, imageHeight.toFloat()))
            val output = floatBuffer(FloatArray(6))
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, input, Coordinates2d.TEXTURE_NORMALIZED, output)
            val corners = FloatArray(6)
            output.rewind(); output.get(corners)
            val displayInput = floatBuffer(floatArrayOf(-1f, 1f, 1f, 1f, -1f, -1f))
            val displayOutput = floatBuffer(FloatArray(6))
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                displayInput,
                Coordinates2d.TEXTURE_NORMALIZED,
                displayOutput
            )
            val displayCorners = FloatArray(6)
            displayOutput.rewind(); displayOutput.get(displayCorners)
            latestDepthTimestamp = depthImage.timestamp
            onStatus(ArDepthStatus(true, true, latestDepthTimestamp))
            // The bench log: how often depth arrives and what the copy out of it costs. Rate-limited
            // to once a second so the log itself is not part of what it measures.
            depthFrames++
            val nowMillis = System.currentTimeMillis()
            if (nowMillis - depthLogMillis >= 1_000L) {
                val fps = depthFrames * 1000.0 / (nowMillis - depthLogMillis)
                Log.d(
                    DEPTH_LOG_TAG,
                    "vision %dx%d  %.1f fps  (every %.0f ms)  camera copy %.2f ms  depth copy %.2f ms  depth not ready %d".format(
                        depthImage.width, depthImage.height, fps, visionIntervalMillis,
                        cameraCopyMillis, copyMillis, depthNotReady,
                    ),
                )
                depthFrames = 0
                depthNotReady = 0
                depthLogMillis = nowMillis
            }
            DepthFrame(
                depthImage.width,
                depthImage.height,
                values,
                depthImage.timestamp,
                corners,
                displayCorners
            )
        } finally {
            depthImage.close()
        }
    }

    private fun drawCamera(frame: Frame) {
        val ndc = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        textureCoordinates.clear()
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, textureCoordinates)
        textureCoordinates.rewind()
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "cameraTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    private fun displayRotation(): Int = @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation

    private fun cameraRotationDegrees(): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sensor = manager.getCameraCharacteristics(session.cameraConfig.cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayDegrees = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - displayDegrees + 360) % 360
    }

    companion object {

        /** Bench log for the depth path: frame rate and the cost of copying a frame out. */
        const val DEPTH_LOG_TAG = "DepthCamera"
        /** One depth frame every 100 ms. Pulling one out is ~1 ms now, so the limit is ARCore. */
        private const val ANALYSIS_INTERVAL_NANOS = 100_000_000L
        private const val VERTEX_SHADER = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){ gl_Position=aPosition; vTexCoord=aTexCoord; }"
        private const val FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES cameraTexture; void main(){ gl_FragColor=texture2D(cameraTexture,vTexCoord); }"

        private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); rewind() }

        private fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
            }
        }
    }
}
