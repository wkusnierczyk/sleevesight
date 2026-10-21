package app.sleevesight.app.keyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/** Where a [BleKeyboard] is. */
enum class KeyboardState {
    /** Opening the service and starting to advertise it. */
    STARTING,

    /** Advertising; no computer has sent a message yet, or the one that did has gone. */
    WAITING,

    /** A computer is connected and has sent a message the session took. */
    CONNECTED,

    /** Bluetooth is off; the keyboard starts once it is on. */
    BLUETOOTH_OFF,

    /** The phone has no Bluetooth Low Energy, or cannot advertise. */
    UNSUPPORTED,

    /** The service or the advertising could not be started. */
    FAILED,
}

/**
 * The phone's side of *Type on a computer* (#394): a GATT server with the [session]'s service and
 * its one writable characteristic, advertised under the session's UUID so that the page's device
 * chooser lists this phone alone. Each message written is opened by the [session]; the text of one
 * it takes goes to [onText], and one it refuses gets an error back. Nothing is sent but Bluetooth's
 * acknowledgements. A message longer than one packet comes as prepared writes, kept per computer
 * until executed.
 *
 * [start] follows Bluetooth being turned off and on; [stop] ends everything, and the [session]'s
 * owner then forgets its key. [onText] and [onState] are called on the main thread.
 *
 * The caller holds the permissions (hence `MissingPermission` suppressed): `BluetoothKeyboard`
 * asks for them before it starts one, and before Android 12 they are granted with the app. A
 * permission withdrawn meanwhile ends the process, so no call here meets a `SecurityException`.
 */
@SuppressLint("MissingPermission")
class BleKeyboard(
    private val context: Context,
    private val session: KeyboardSession,
    private val onText: (String) -> Unit,
    private val onState: (KeyboardState) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val lock = Any()

    // Set and cleared on the main thread; read from Bluetooth's, to answer writes.
    @Volatile private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // Every device linked to the phone over LE, which the GATT server hears of whatever service it
    // came for (a watch, earbuds), and those of them that sent a message the session took. Changed
    // on the main thread alone, in the order Bluetooth's callbacks came, so the state said is
    // always the one they leave.
    private val connected = mutableSetOf<BluetoothDevice>()
    private val typing = mutableSetOf<BluetoothDevice>()
    private val prepared = mutableMapOf<BluetoothDevice, ByteArrayOutputStream>()
    private var listening = false
    private var stopped = false

    private val bluetoothChanges =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> open()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> close(KeyboardState.BLUETOOTH_OFF)
                }
            }
        }

    /** Opens the service and advertises it, now or once Bluetooth is on. */
    fun start() {
        if (stopped || listening) return
        listening = true
        // A protected broadcast, which only the platform may send. Exported, since before Android 13
        // `ContextCompat` guards a receiver that is not with a permission of this app's, which the
        // Bluetooth app that sends it does not hold.
        ContextCompat.registerReceiver(
            context,
            bluetoothChanges,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        open()
    }

    /** Stops advertising, disconnects any computer and closes the service, for good. */
    fun stop() {
        if (stopped) return
        stopped = true
        if (listening) context.unregisterReceiver(bluetoothChanges)
        close(null)
    }

    private fun open() {
        if (stopped || server != null) return
        val adapter = manager?.adapter
        when {
            adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> {
                state(KeyboardState.UNSUPPORTED)
            }

            !adapter.isEnabled -> {
                state(KeyboardState.BLUETOOTH_OFF)
            }

            !adapter.isMultipleAdvertisementSupported || adapter.bluetoothLeAdvertiser == null -> {
                state(KeyboardState.UNSUPPORTED)
            }

            else -> {
                state(KeyboardState.STARTING)
                advertiser = adapter.bluetoothLeAdvertiser
                server = manager.openGattServer(context, callback)
                val service = BluetoothGattService(session.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                service.addCharacteristic(
                    BluetoothGattCharacteristic(
                        KeyboardSession.CHARACTERISTIC,
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE,
                    ),
                )
                // Advertising starts once the service is added (`onServiceAdded`).
                if (server?.addService(service) != true) close(KeyboardState.FAILED)
            }
        }
    }

    // Ends what is open, then says [then], if anything.
    private fun close(then: KeyboardState?) {
        stopAdvertising()
        advertiser = null
        val devices = connected.toList()
        connected.clear()
        typing.clear()
        synchronized(lock) { prepared.clear() }
        server?.let { s ->
            devices.forEach { s.cancelConnection(it) }
            s.close()
        }
        server = null
        then?.let { state(it) }
    }

    private fun advertise() {
        val settings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
        // The service's UUID alone: the phone's name is not advertised (a device that connects can
        // still read it, from the Generic Access service Android keeps).
        val data =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(session.serviceUuid))
                .build()
        // Throws if Bluetooth went off meanwhile, which the receiver then reports too.
        try {
            advertiser?.startAdvertising(settings, data, advertising)
        } catch (_: IllegalStateException) {
            // Advertising could not start again. With devices connected, among them perhaps the
            // computer, the session goes on, and it is tried again at the next connection change;
            // with none, there is no way in, and the session has failed.
            when {
                manager?.adapter?.isEnabled != true -> close(KeyboardState.BLUETOOTH_OFF)
                connected.isEmpty() -> close(KeyboardState.FAILED)
            }
        }
    }

    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertising)
        } catch (_: IllegalStateException) {
            // Bluetooth is off, which has stopped it.
        }
    }

    private val advertising =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                connection()
            }

            override fun onStartFailure(errorCode: Int) {
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> {}
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> close(KeyboardState.UNSUPPORTED)
                    // As when it throws, in `advertise`.
                    else -> if (connected.isEmpty()) close(KeyboardState.FAILED)
                }
            }
        }

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService,
            ) {
                main.post {
                    if (status == BluetoothGatt.GATT_SUCCESS) advertise() else close(KeyboardState.FAILED)
                }
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                if (newState != BluetoothProfile.STATE_CONNECTED) synchronized(lock) { prepared -= device }
                main.post {
                    if (server == null) return@post
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected += device
                    } else {
                        connected -= device
                        typing -= device
                    }
                    connection()
                    // A connection may end the advertising, and the device that came may not be the
                    // computer: it starts again at each change, so the computer can always find it.
                    stopAdvertising()
                    advertise()
                }
            }

            @Suppress("LongParameterList") // Android's signature.
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                val bytes = value ?: ByteArray(0)
                val status =
                    when {
                        characteristic.uuid != KeyboardSession.CHARACTERISTIC -> {
                            BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                        }

                        preparedWrite -> prepare(device, offset, bytes)
                        offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                        else -> receive(device, bytes)
                    }
                // A prepared write's answer repeats what it was given.
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, status, offset, bytes.takeIf { preparedWrite })
                }
            }

            override fun onExecuteWrite(
                device: BluetoothDevice,
                requestId: Int,
                execute: Boolean,
            ) {
                val written = synchronized(lock) { prepared.remove(device) }
                val status =
                    if (execute && written != null) {
                        receive(device, written.toByteArray())
                    } else {
                        BluetoothGatt.GATT_SUCCESS
                    }
                server?.sendResponse(device, requestId, status, 0, null)
            }
        }

    // Keeps a part of a long message, written in order.
    private fun prepare(
        device: BluetoothDevice,
        offset: Int,
        bytes: ByteArray,
    ): Int =
        synchronized(lock) {
            val buffer = prepared.getOrPut(device) { ByteArrayOutputStream() }
            when {
                offset != buffer.size() -> BluetoothGatt.GATT_INVALID_OFFSET
                buffer.size() + bytes.size > MAX_WRITE_BYTES -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                else -> BluetoothGatt.GATT_SUCCESS.also { buffer.write(bytes) }
            }
        }

    // Opens [message] from [device]; the first it takes makes that device the computer.
    private fun receive(
        device: BluetoothDevice,
        message: ByteArray,
    ): Int {
        val text = session.open(message) ?: return REFUSED
        main.post {
            if (stopped || server == null) return@post
            if (typing.add(device)) connection()
            onText(text)
        }
        return BluetoothGatt.GATT_SUCCESS
    }

    // Says whether a computer is typing, on the main thread, as the sets now are.
    private fun connection() {
        if (!stopped &&
            server != null
        ) {
            onState(if (typing.isEmpty()) KeyboardState.WAITING else KeyboardState.CONNECTED)
        }
    }

    private fun state(state: KeyboardState) {
        main.post { if (!stopped) onState(state) }
    }

    private companion object {
        // Web Bluetooth's largest write.
        const val MAX_WRITE_BYTES = 512

        // An application error (ATT 0x80): the message was refused.
        const val REFUSED = 0x80
    }
}
