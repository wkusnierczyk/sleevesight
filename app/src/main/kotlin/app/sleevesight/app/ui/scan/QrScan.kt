package app.sleevesight.app.ui.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
 * The camera's view for a QR scan, filling the space it is given, until a QR code is read: then it
 * calls `onScanned` once with the code's text. The settings screen takes it as a parameter, so its
 * tests scan without a camera. Where it is shown, [QrScanDialog] for one key's field or the *Load
 * keys* screen, says what to do and offers *Cancel*.
 */
typealias QrScanner = @Composable (onScanned: (String) -> Unit) -> Unit

/** The phone's camera, as [QrScanner]. */
val CameraQrScanner: QrScanner = { onScanned -> CameraViewfinder(onScanned) }

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
 * A scan for one key's field (#268): [scanner]'s view over the screen, whose frame says where to
 * hold the code (or, without the camera, why not), and under it *Cancel*, which calls [onDismiss],
 * as Back does. The window is secure (`FLAG_SECURE`): no
 * screenshot or screen recording shows the code, which may be the keys.
 */
@Composable
internal fun QrScanDialog(
    scanner: QrScanner,
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { scanner(onScanned) }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .safeDrawingPadding()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        }
    }
}

/**
 * The camera's view, as [QrScanner]: asks for the camera when it is first shown, unless already
 * allowed, and shows the camera's view, with the frame to hold the code in ([ScanFrame]), until a
 * code is read. Without the camera, it says why in its place.
 */
@Composable
private fun CameraViewfinder(onScanned: (String) -> Unit) {
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            !hasCamera || failed -> CameraMessage(R.string.scan_no_camera)
            refused -> CameraMessage(R.string.scan_camera_refused)
            allowed -> CameraView(onScanned, onUnavailable = { failed = true })
            else -> {}
        }
    }
}

/** Why the camera's view is not shown, in its place. */
@Composable
private fun CameraMessage(
    @StringRes message: Int,
) {
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

private fun granted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The camera's view, as large as its space allows at the frames' proportions, centred, its frames
 * read by [QrCamera], with the [ScanFrame] over it. [onUnavailable] hears that no camera could be
 * opened.
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
    val frames = size.width.toFloat() / size.height
    val ratio = if (camera.mounting.swapsSides(rotation)) 1 / frames else frames
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Fitted inside the space: the whole of the camera's picture shows, as the frame needs.
        val width = minOf(maxWidth, maxHeight * ratio)
        val fitted = Modifier.size(width, width / ratio)
        AndroidView(
            factory = {
                SurfaceView(it).apply {
                    holder.setFixedSize(size.width, size.height)
                    holder.addCallback(camera)
                }
            },
            modifier = fitted,
        )
        ScanFrame(fitted)
    }
}

/** The frame's side, as a share of the picture's shorter side. */
private const val FRAME_SHARE = 0.7f

/** How dark the picture is around the frame. */
private const val DIM_ALPHA = 0.6f

/**
 * Where to hold the code (#446): a rounded square in the middle of the camera's picture, the
 * picture around it dimmed. Not blurred: the picture is a `SurfaceView`'s, drawn by the system
 * apart from the app's views, which neither `RenderEffect` nor Compose's `blur` reaches. A screen
 * reader reads it as what it is for.
 */
@Composable
internal fun ScanFrame(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.scan_view)
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val side = size.minDimension * FRAME_SHARE
        val corner = Offset((size.width - side) / 2, (size.height - side) / 2)
        val square = Size(side, side)
        val radius = CornerRadius(FRAME_CORNER.toPx())
        val hole = Path().apply { addRoundRect(RoundRect(Rect(corner, square), radius)) }
        clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = DIM_ALPHA)) }
        drawRoundRect(Color.White, corner, square, radius, style = Stroke(FRAME_STROKE.toPx()))
    }
}

private val FRAME_CORNER = 20.dp
private val FRAME_STROKE = 3.dp
