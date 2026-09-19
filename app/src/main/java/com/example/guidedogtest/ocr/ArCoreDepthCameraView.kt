package com.example.guidedogtest.ocr

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
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
            onStatus(ArDepthStatus(true, depthSupported, message = if (depthSupported) null else "Depth API is not supported on this device."))
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
            val cameraImage = try { frame.acquireCameraImage() } catch (_: NotYetAvailableException) { return }
            val copied = try { ArCoreVisionProcessor.copy(cameraImage) } finally { cameraImage.close() }
            val rotation = cameraRotationDegrees()
            val depth = acquireDepth(frame, copied.width, copied.height)
            lastAnalysisNanos = frame.timestamp
            processor.submit(copied, rotation, depth)
        } catch (error: Exception) {
            if (reportedError.compareAndSet(false, true)) onError(error.message ?: "ARCore vision session failed.")
        }
    }

    private fun acquireDepth(frame: Frame, imageWidth: Int, imageHeight: Int): DepthFrame? {
        if (!depthSupported) return null
        val depthImage = try { frame.acquireDepthImage16Bits() } catch (_: NotYetAvailableException) { return null }
        return try {
            val plane = depthImage.planes[0]
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val values = IntArray(depthImage.width * depthImage.height)
            for (y in 0 until depthImage.height) {
                for (x in 0 until depthImage.width) {
                    values[y * depthImage.width + x] = buffer.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xffff
                }
            }
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
        private const val ANALYSIS_INTERVAL_NANOS = 150_000_000L
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
