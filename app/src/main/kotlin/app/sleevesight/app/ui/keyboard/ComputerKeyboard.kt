package app.sleevesight.app.ui.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.sleevesight.app.R
import app.sleevesight.app.keyboard.BleKeyboard
import app.sleevesight.app.keyboard.KeyboardSession
import app.sleevesight.app.keyboard.KeyboardState

/**
 * Runs a session of *Type on a computer* (#394) while it is shown: says where it is, passes each
 * text the computer sends to `onText`, and offers *Stop*, which calls `onStop`. Leaving it ends the
 * session and forgets its key. The settings screen takes it as a parameter, so its tests type
 * without Bluetooth.
 */
typealias ComputerKeyboard =
    @Composable (session: KeyboardSession, onText: (String) -> Unit, onStop: () -> Unit) -> Unit

/** The phone's Bluetooth, as [ComputerKeyboard]. */
val BluetoothComputerKeyboard: ComputerKeyboard = { session, onText, onStop ->
    BluetoothKeyboard(session, onText, onStop)
}

// Android 12's runtime permissions for advertising and taking connections; before it, BLUETOOTH and
// BLUETOOTH_ADMIN, which come with the app.
private val PERMISSIONS =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }

/**
 * Asks for Bluetooth's permissions when it opens, unless already allowed, then runs a
 * [BleKeyboard] until it leaves. A refusal is said, and a permission allowed in the phone's settings
 * meanwhile starts it on return.
 */
@Composable
private fun BluetoothKeyboard(
    session: KeyboardSession,
    onText: (String) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(granted(context)) }
    var refused by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(KeyboardState.STARTING) }
    val text by rememberUpdatedState(onText)
    val ask =
        // Read again rather than from the results, which an interrupted request leaves empty.
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            allowed = granted(context)
            refused = !allowed
        }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(PERMISSIONS) }
    LifecycleResumeEffect(Unit) {
        if (!allowed && granted(context)) {
            allowed = true
            refused = false
        }
        onPauseOrDispose {}
    }
    DisposableEffect(session) { onDispose { session.forget() } }
    if (allowed) {
        DisposableEffect(session) {
            val keyboard = BleKeyboard(context, session, { text(it) }, { state = it })
            keyboard.start()
            onDispose { keyboard.stop() }
        }
    }
    KeyboardStatus(if (refused) R.string.keyboard_refused else message(state), onStop)
}

/** Where the session is, and *Stop*. */
@Composable
internal fun KeyboardStatus(
    @StringRes message: Int,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onStop) { Text(stringResource(R.string.keyboard_stop)) }
    }
}

@StringRes
private fun message(state: KeyboardState): Int =
    when (state) {
        KeyboardState.STARTING -> R.string.keyboard_starting
        KeyboardState.WAITING -> R.string.keyboard_waiting
        KeyboardState.CONNECTED -> R.string.keyboard_connected
        KeyboardState.BLUETOOTH_OFF -> R.string.keyboard_off
        KeyboardState.UNSUPPORTED -> R.string.keyboard_unsupported
        KeyboardState.FAILED -> R.string.keyboard_failed
    }

private fun granted(context: Context): Boolean =
    PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
