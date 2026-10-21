package app.sleevesight.app.ui.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.sleevesight.app.R
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

// Reading a QR code with the camera (#268): a key for its own field, or a setup code of several.
// ZXing decodes on the phone, and nothing of a frame is kept or sent: unlike Google's code
// scanner, whose ML Kit reports usage to Google.

/**
 * Shows a scan until a QR code is read, then calls `onScanned` once with its text, or `onDismiss`
 * when the user leaves without one. The settings screen takes it as a parameter, so its tests scan
 * without a camera.
 */
typealias QrScanner = @Composable (onScanned: (String) -> Unit, onDismiss: () -> Unit) -> Unit

/** The phone's camera, as [QrScanner]. */
val CameraQrScanner: QrScanner = { onScanned, onDismiss -> CameraQrScan(onScanned, onDismiss) }

/** Decodes a QR code from a camera frame's luminance. */
object QrDecoder {
    private val HINTS =
        mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )

    /**
     * The text of the QR code in [luminance], a [width] by [height] image whose rows are [rowStride]
     * bytes apart, or null when none is found or it cannot be read. A code drawn light on dark, as
     * a terminal that ignores the CLI's colours might show it, is read too. (`ALSO_INVERTED` is
     * honoured by ZXing's `MultiFormatReader` only, which would bring every other format's reader.)
     */
    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return read(source) ?: read(InvertedLuminanceSource(source))
    }

    // Any failure is no code: a frame ZXing trips on must not end the scan, or the app.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun read(source: LuminanceSource): String? =
        try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
        } catch (_: ReaderException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}

/**
 * The camera scan: asks for the camera when it opens, unless already allowed, and shows the
 * camera's view until a code is read. The window is secure (`FLAG_SECURE`): no screenshot or
 * screen recording shows the code, which may be the keys. Without the camera, it says why.
 */
@Composable
private fun CameraQrScan(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var allowed by remember { mutableStateOf(granted(context)) }
    var refused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = granted
            refused = !granted
        }
    LaunchedEffect(Unit) { if (hasCamera && !allowed) ask.launch(Manifest.permission.CAMERA) }
    // Allowed in the phone's settings while the scan was open: the camera starts on return.
    LifecycleResumeEffect(Unit) {
        if (!allowed && granted(context)) {
            allowed = true
            refused = false
        }
        onPauseOrDispose {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCamera && allowed && !failed) CameraView(onScanned, onUnavailable = { failed = true })
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .safeDrawingPadding()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val message =
                    when {
                        !hasCamera || failed -> R.string.scan_no_camera
                        refused -> R.string.scan_camera_refused
                        else -> R.string.scan_hint
                    }
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        }
    }
}

private fun granted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The camera's view, as tall as the screen allows at the frames' proportions, its frames read by
 * [QrCamera]. [onUnavailable] hears that no camera could be opened.
 */
@Composable
private fun CameraView(
    onScanned: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val scanned by rememberUpdatedState(onScanned)
    val unavailable by rememberUpdatedState(onUnavailable)
    val camera = remember { QrCamera(context, { scanned(it) }, { unavailable() }) }
    DisposableEffect(camera) { onDispose { camera.release() } }
    val size = camera.size
    if (size == null) {
        LaunchedEffect(Unit) { unavailable() }
        return
    }
    // The view turns the frames upright, which may swap their width and height. Read again on a
    // configuration change, which a turn of the display is.
    val view = LocalView.current
    val rotation = remember(LocalConfiguration.current) { view.display?.rotation ?: Surface.ROTATION_0 }
    val ratio = size.width.toFloat() / size.height
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                SurfaceView(it).apply {
                    holder.setFixedSize(size.width, size.height)
                    holder.addCallback(camera)
                }
            },
            modifier =
                Modifier.fillMaxWidth().aspectRatio(
                    if (camera.mounting.swapsSides(rotation)) 1 / ratio else ratio,
                ),
        )
    }
}
