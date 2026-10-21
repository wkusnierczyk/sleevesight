package app.sleevesight.app.ui.scan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * The camera for a QR scan (#268), on Android's own camera API rather than CameraX, which would
 * bring a dozen libraries for one view and one stream of frames. It shows the camera's view on a
 * `SurfaceView` it is the [SurfaceHolder.Callback] of, which turns the view as the screen turns, and
 * hands each frame's luminance to [QrDecoder] on a thread of its own, the newest frame only.
 *
 * The camera opens when the view's surface is created and closes when it is destroyed (the app
 * leaving the screen), so it opens again on return. [onScanned] hears the first code read, once,
 * and [onUnavailable] that the camera cannot be used; both on the main thread. [release] ends it.
 * Everything that touches the camera runs on the camera thread, in order, so a camera that opens
 * after its surface has gone is closed at once.
 */
internal class QrCamera(
    context: Context,
    private val onScanned: (String) -> Unit,
    private val onUnavailable: () -> Unit,
) : SurfaceHolder.Callback {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("qr-scan").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)

    /** The camera, the back one if there is one, and what it can do; null when none can be used. */
    private val camera: Pair<String, CameraCharacteristics>? =
        try {
            val all = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
            all.firstOrNull {
                it.second.get(
                    CameraCharacteristics.LENS_FACING,
                ) == CameraCharacteristics.LENS_FACING_BACK
            }
                ?: all.firstOrNull()
        } catch (_: CameraAccessException) {
            null
        }

    /** The frames' size, the view's too: the one nearest [WANTED], which ZXing reads quickly. */
    val size: Size? =
        camera
            ?.second
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { abs(it.width - WANTED.width) + abs(it.height - WANTED.height) }

    /** Whether the sensor is turned a quarter from the phone's natural orientation, as most are. */
    val sideways: Boolean =
        (camera?.second?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: QUARTER_TURN) % HALF_TURN != 0

    // The camera thread's state.
    private var surface: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var frame = ByteArray(0)

    override fun surfaceCreated(holder: SurfaceHolder) {
        val shown = holder.surface
        handler.post {
            surface = shown
            open()
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        handler.post {
            surface = null
            close()
        }
    }

    /** Closes the camera and ends its thread. */
    fun release() {
        handler.post { close() }
        thread.quitSafely()
    }

    // The permission is asked for before the view, and so this, exists (`CameraQrScan`).
    @SuppressLint("MissingPermission")
    private fun open() {
        val id = camera?.first
        val frames = size
        if (id == null || frames == null) return unavailable()
        try {
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        val shown = surface
                        if (shown == null) {
                            camera.close()
                        } else {
                            device = camera
                            startSession(camera, shown, frames)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (device === camera) device = null
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        camera.close()
                        if (device === camera) device = null
                        unavailable()
                    }
                },
                handler,
            )
        } catch (_: CameraAccessException) {
            unavailable()
        } catch (_: SecurityException) {
            unavailable()
        }
    }

    private fun startSession(
        camera: CameraDevice,
        shown: Surface,
        frames: Size,
    ) {
        val images = ImageReader.newInstance(frames.width, frames.height, ImageFormat.YUV_420_888, 2)
        images.setOnImageAvailableListener({ read(it) }, handler)
        reader = images
        val callback =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (device !== camera) return configured.close()
                    session = configured
                    try {
                        val request =
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(shown)
                                addTarget(images.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                        configured.setRepeatingRequest(request.build(), null, handler)
                    } catch (_: CameraAccessException) {
                        unavailable()
                    } catch (_: IllegalStateException) {
                        // Closed meanwhile: the surface went.
                    }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) = unavailable()
            }
        val outputs = listOf(shown, images.surface)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val onThread = Executor { handler.post(it) }
                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs.map(::OutputConfiguration),
                        onThread,
                        callback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION") // The only way before API 28.
                camera.createCaptureSession(outputs, callback, handler)
            }
        } catch (_: CameraAccessException) {
            unavailable()
        }
    }

    // The newest frame, if any: its luminance, decoded.
    private fun read(images: ImageReader) {
        images.acquireLatestImage()?.use { image ->
            val text = if (done.get()) null else decode(image)
            if (text != null && done.compareAndSet(false, true)) main.post { onScanned(text) }
        }
    }

    // The QR code in [image]'s luminance plane (YUV_420_888, one byte a pixel), or null.
    private fun decode(image: Image): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        if (frame.size != buffer.remaining()) frame = ByteArray(buffer.remaining())
        buffer.get(frame)
        return QrDecoder.decode(frame, image.width, image.height, plane.rowStride)
    }

    private fun close() {
        session?.close()
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
    }

    private fun unavailable() {
        main.post(onUnavailable)
    }

    private companion object {
        val WANTED = Size(1280, 720)
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
    }
}
