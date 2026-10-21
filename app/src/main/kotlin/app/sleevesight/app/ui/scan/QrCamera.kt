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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * The camera for a QR scan (#268), on Android's own camera API rather than CameraX, which would
 * bring a dozen libraries for one view and one stream of frames. It shows the camera's view on a
 * `SurfaceView` it is the [SurfaceHolder.Callback] of, which turns the view as the screen turns, and
 * hands each frame's luminance to [QrDecoder] on a thread of its own, the newest frame only.
 *
 * The camera opens when the view's surface is created and closes when it is destroyed (the app
 * leaving the screen), so it opens again on return; the surface is not given back until the camera
 * has let go of it. [onScanned] hears the first code read, once, and [onUnavailable] that the camera
 * cannot be used; both on the main thread, and neither after [release], which ends it. Everything
 * that touches the camera runs on the camera thread, in order, so a camera that opens after its
 * surface has gone, or after the release, is closed at once; the thread ends only once no camera is
 * still opening, so none is left open.
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
    private val released = AtomicBoolean(false)

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

    /**
     * The frames' size, the view's too, so one that both the frames and the view may take: the one
     * nearest [WANTED], which ZXing reads quickly.
     */
    val size: Size? =
        camera?.second?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
            val frames = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val views = map.getOutputSizes(SurfaceHolder::class.java).orEmpty().toSet()
            frames
                .filter { it in views }
                .minByOrNull { abs(it.width - WANTED.width) + abs(it.height - WANTED.height) }
        }

    /** Whether the camera focuses on its own; a fixed-focus one, as many front ones are, does not. */
    private val autofocus: Boolean =
        camera
            ?.second
            ?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true

    /** How the camera sits in the phone, for the view's proportions. */
    val mounting: Mounting =
        Mounting(
            sensorOrientation = camera?.second?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            frontFacing =
                camera?.second?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT,
        )

    // The camera thread's state.
    private var surface: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val frames = FrameDecoder()

    /** Whether a camera is being opened, its callback still to come. */
    private var opening = false

    /** Whether [release] has been asked for. */
    private var ending = false

    /** Counted down once the camera being closed for a destroyed surface has let go of it. */
    private var letGo: CountDownLatch? = null

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
        // The surface may not be used once this returns: wait, briefly, for the camera to let go.
        // `CameraDevice.close()` returns before the camera has, so the wait ends at `onClosed`.
        val closed = CountDownLatch(1)
        val posted =
            handler.post {
                surface = null
                if (device == null) {
                    closed.countDown()
                } else {
                    letGo = closed
                    close()
                }
            }
        if (posted) closed.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** Closes the camera and ends its thread, once no camera is still opening. */
    fun release() {
        released.set(true)
        handler.post {
            ending = true
            close()
            quitIfIdle()
        }
    }

    private fun quitIfIdle() {
        if (ending && !opening) thread.quitSafely()
    }

    // The permission is asked for before the view, and so this, exists (`CameraQrScan`).
    @SuppressLint("MissingPermission")
    private fun open() {
        val id = camera?.first
        val frames = size
        // One camera at a time: one still opening takes whichever surface there is when it opens.
        if (opening || device != null) return
        if (id == null || frames == null || ending) return unavailable()
        opening = true
        try {
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false
                        val shown = surface
                        if (shown == null || ending) {
                            camera.close()
                            quitIfIdle()
                        } else {
                            device = camera
                            startSession(camera, shown, frames)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        opening = false
                        val shown = device === camera
                        close()
                        if (!shown) camera.close()
                        // Taken by another app: the view would freeze, so the scan says it cannot go on.
                        if (shown && surface != null && !ending) unavailable()
                        quitIfIdle()
                    }

                    override fun onClosed(camera: CameraDevice) {
                        letGo?.countDown()
                        letGo = null
                    }

                    override fun onError(
                        camera: CameraDevice,
                        error: Int,
                    ) {
                        opening = false
                        camera.close()
                        if (device === camera) device = null
                        // Not after the surface or the scan has gone: the camera was only let go.
                        if (surface != null && !ending) unavailable()
                        quitIfIdle()
                    }
                },
                handler,
            )
        } catch (_: CameraAccessException) {
            opening = false
            unavailable()
        } catch (_: SecurityException) {
            opening = false
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
        reader?.close()
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
                                if (autofocus) {
                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                    )
                                }
                            }
                        configured.setRepeatingRequest(request.build(), null, handler)
                    } catch (_: CameraAccessException) {
                        unavailable()
                    } catch (_: IllegalStateException) {
                        // Closed meanwhile: the surface went.
                    }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    // A session for a camera since closed fails too, and says nothing.
                    if (device === camera && surface != null) unavailable()
                }
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
        } catch (_: IllegalArgumentException) {
            // The view's surface went meanwhile, which the wait in [surfaceDestroyed] should prevent.
            close()
        }
    }

    // The newest frame, if any: its luminance, decoded.
    private fun read(images: ImageReader) {
        images.acquireLatestImage()?.use { image ->
            val text = if (done.get()) null else frames.decode(image)
            if (text != null && done.compareAndSet(false, true)) main.post { if (!released.get()) onScanned(text) }
        }
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
        main.post { if (!released.get()) onUnavailable() }
    }

    private companion object {
        val WANTED = Size(1280, 720)
        const val CLOSE_WAIT_MS = 2_000L
    }
}

/** How a camera sits in the phone: its sensor's turn from the phone's natural orientation, and which way it faces. */
internal class Mounting(
    private val sensorOrientation: Int,
    private val frontFacing: Boolean,
) {
    /**
     * Whether the view shows the frames turned a quarter, so their width and height swap, with the
     * display turned [displayRotation] (`Surface.ROTATION_0` to `ROTATION_270`) from its natural
     * orientation: from the sensor's orientation and the display's, as Android's camera guide
     * reckons it, so a tablet whose natural orientation is landscape is right too.
     */
    fun swapsSides(displayRotation: Int): Boolean {
        val display = displayRotation * QUARTER_TURN
        val relative = if (frontFacing) sensorOrientation + display else sensorOrientation - display + FULL_TURN
        return relative % FULL_TURN % HALF_TURN != 0
    }

    private companion object {
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
        const val FULL_TURN = 360
    }
}

/** Decodes camera frames, one at a time, into one buffer kept between them. */
private class FrameDecoder {
    private var luminance = ByteArray(0)

    /** The QR code in [image]'s luminance plane (YUV_420_888, one byte a pixel), or null. */
    fun decode(image: Image): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        if (luminance.size != buffer.remaining()) luminance = ByteArray(buffer.remaining())
        buffer.get(luminance)
        return QrDecoder.decode(luminance, image.width, image.height, plane.rowStride)
    }
}
