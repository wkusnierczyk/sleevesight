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
 * The phone's Bluetooth name is advertised only with [showName] (*Show this phone's name to the
 * computer*, #433), in the scan response, so the chooser lists the phone by it; without it, the
 * chooser lists it as an unknown device. A start with the name that fails is made again without
 * it ([PhoneName]). [onName] hears, at each start of the advertising, the name it carries as the
 * chooser shows it ([advertisedName]), or null for none.
 *
 * Each time the service is opened (at [start], and again when Bluetooth comes back on) it is an
 * [Opening] of its own: its server, its advertising, the devices linked to it and their writes.
 * Closing one makes everything it is still told, by Bluetooth or by work it posted, do nothing, so
 * nothing of an opening closed can reach the next.
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
    private val showName: Boolean = false,
    private val onName: (String?) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)

    // The opening now open, set and cleared on the main thread.
    private var current: Opening? = null
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
        if (stopped || current != null) return
        val adapter = manager?.adapter
        val advertiser = adapter?.takeIf { it.isEnabled && it.isMultipleAdvertisementSupported }?.bluetoothLeAdvertiser
        when {
            adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> {
                state(KeyboardState.UNSUPPORTED)
            }

            !adapter.isEnabled -> {
                state(KeyboardState.BLUETOOTH_OFF)
            }

            advertiser == null -> {
                state(KeyboardState.UNSUPPORTED)
            }

            else -> {
                state(KeyboardState.STARTING)
                // Current before it opens, so a failure while it opens ends it.
                val opening = Opening(manager, advertiser, PhoneName(showName) { adapter.name })
                current = opening
                opening.open()
            }
        }
    }

    // Closes the opening now open, if any, then says [then], if anything.
    private fun close(then: KeyboardState?) {
        current?.close()
        current = null
        then?.let { state(it) }
    }

    private fun state(state: KeyboardState) {
        main.post { if (!stopped) onState(state) }
    }

    /**
     * One opening of the service: its server, its advertising, the devices linked to it and their
     * writes, and whether its starts carry the phone's [name]. Once [closed], what Bluetooth still
     * tells it, and work it posted, does nothing.
     */
    private inner class Opening(
        private val manager: BluetoothManager,
        private val advertiser: BluetoothLeAdvertiser,
        private val name: PhoneName,
    ) {
        private val lock = Any()

        @Volatile private var closed = false

        // Set on the main thread; read from Bluetooth's, to answer writes.
        @Volatile private var server: BluetoothGattServer? = null

        // The advertising started last; a result of one started before it is ignored. Whether one
        // has started at all, in this opening.
        private var advertising: AdvertiseCallback? = null
        private var advertised = false

        // Tries advertising again after a failed restart, while the opening is open, nothing
        // advertises since, and no computer is typing. One at a time: posting it again replaces it.
        private val retry =
            Runnable {
                val idle = advertising == null && typing.isEmpty()
                if (!closed && !stopped && idle) advertise()
            }

        // Every device linked to the phone over LE, which the GATT server hears of whatever service
        // it came for (a watch, earbuds), and those of them that sent a message the session took.
        // Changed on the main thread alone, in the order Bluetooth's callbacks came, so the state
        // said is always the one they leave.
        private val connected = mutableSetOf<BluetoothDevice>()
        private val typing = mutableSetOf<BluetoothDevice>()

        // Long messages' parts, per device, under the lock: Bluetooth writes them from its threads.
        private val prepared = mutableMapOf<BluetoothDevice, ByteArrayOutputStream>()

        fun open() {
            val opened = manager.openGattServer(context, callbacks)
            server = opened
            val service = BluetoothGattService(session.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    KeyboardSession.CHARACTERISTIC,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                ),
            )
            // Advertising starts once the service is added (`onServiceAdded`).
            if (opened?.addService(service) != true) fail(KeyboardState.FAILED)
        }

        fun close() {
            synchronized(lock) {
                closed = true
                prepared.clear()
            }
            main.removeCallbacks(retry)
            stopAdvertising()
            server?.let { s ->
                connected.forEach { s.cancelConnection(it) }
                s.close()
            }
            server = null
            connected.clear()
            typing.clear()
        }

        // Ends this opening, if it is still the one open, and says [state].
        private fun fail(state: KeyboardState) {
            if (!closed && current === this) this@BleKeyboard.close(state)
        }

        // Runs [work] on the main thread while this opening is open.
        private fun later(work: () -> Unit) {
            main.post { if (!closed && !stopped) work() }
        }

        private fun advertise() {
            val settings =
                AdvertiseSettings
                    .Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .build()
            // The service's UUID, with the flags Android adds: 21 of the advertisement's 31 bytes,
            // which leave little room for a name. The phone's name is not in it (a device that connects
            // can still read it, from the Generic Access service Android keeps).
            val data =
                AdvertiseData
                    .Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ParcelUuid(session.serviceUuid))
                    .build()
            // The name, if shown, goes in the scan response, alone: the answer to a computer's scan
            // request, 31 bytes of its own. Android shortens a name past 26 bytes there, but refuses
            // one whose length in characters, with its field's 2 bytes, passes 31 (`totalBytes`).
            // Read at each start, as it is now.
            val shown = name.next()
            val response =
                shown?.let {
                    AdvertiseData
                        .Builder()
                        .setIncludeDeviceName(true)
                        .setIncludeTxPowerLevel(false)
                        .build()
                }
            // One advertising at a time: the one before, if any, is stopped first.
            stopAdvertising()
            val attempt = Advertising(shown)
            advertising = attempt
            // Throws if Bluetooth went off meanwhile, which the receiver then reports too.
            try {
                if (response == null) {
                    advertiser.startAdvertising(settings, data, attempt)
                } else {
                    advertiser.startAdvertising(settings, data, response, attempt)
                }
            } catch (_: IllegalStateException) {
                attempt.failed(bluetoothOff = manager.adapter?.isEnabled != true)
            }
        }

        private fun stopAdvertising() {
            val attempt = advertising ?: return
            advertising = null
            try {
                advertiser.stopAdvertising(attempt)
            } catch (_: IllegalStateException) {
                // Bluetooth is off, which has stopped it.
            }
        }

        // Says whether a computer is typing, as the sets now are.
        private fun connection() {
            if (!closed && !stopped) onState(if (typing.isEmpty()) KeyboardState.WAITING else KeyboardState.CONNECTED)
        }

        /**
         * One start of the advertising, with the phone's name [shown] in its scan response, or none;
         * its results count only while it is the latest.
         */
        private inner class Advertising(
            private val shown: String?,
        ) : AdvertiseCallback() {
            private fun latest() = !closed && advertising === this

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (!latest()) return
                advertised = true
                name.started(shown)
                if (!stopped) onName(shown?.let(::advertisedName))
                connection()
            }

            override fun onStartFailure(errorCode: Int) {
                if (!latest()) return
                // A start with the name failed: made again without it, in this opening, at once.
                if (name.retryNameless(shown, errorCode)) {
                    advertising = null
                    advertise()
                    return
                }
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> {}
                    // Once advertising has started in this opening, a restart's failure of any kind
                    // is retried, as `failed` says.
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                        if (advertised) failed(bluetoothOff = false) else fail(KeyboardState.UNSUPPORTED)
                    }
                    else -> failed(bluetoothOff = false)
                }
            }

            // Advertising could not start. The first time in an opening, the session has failed: the
            // computer has no way in. Later, at a connection change, the session goes on whatever
            // is linked (a computer that has just connected writes only some time after), and it is
            // tried again a little later, while no computer is typing, and at the next change.
            fun failed(bluetoothOff: Boolean) {
                advertising = null
                when {
                    bluetoothOff -> fail(KeyboardState.BLUETOOTH_OFF)
                    !advertised -> fail(KeyboardState.FAILED)
                    else -> {
                        main.removeCallbacks(retry)
                        main.postDelayed(retry, ADVERTISE_RETRY_MS)
                    }
                }
            }
        }

        private val callbacks =
            object : BluetoothGattServerCallback() {
                override fun onServiceAdded(
                    status: Int,
                    service: BluetoothGattService,
                ) {
                    later { if (status == BluetoothGatt.GATT_SUCCESS) advertise() else fail(KeyboardState.FAILED) }
                }

                override fun onConnectionStateChange(
                    device: BluetoothDevice,
                    status: Int,
                    newState: Int,
                ) {
                    if (newState != BluetoothProfile.STATE_CONNECTED) synchronized(lock) { prepared -= device }
                    later {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            connected += device
                        } else {
                            connected -= device
                            typing -= device
                        }
                        connection()
                        // A connection may end the advertising, and the device that came may not be
                        // the computer: it starts again at each change, so the computer can always
                        // find it.
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
                            closed -> BluetoothGatt.GATT_FAILURE
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
                    closed -> BluetoothGatt.GATT_FAILURE
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
            // An opening closed takes nothing more.
            val text = (if (closed) null else session.open(message)) ?: return REFUSED
            later {
                if (typing.add(device)) connection()
                onText(text)
            }
            return BluetoothGatt.GATT_SUCCESS
        }
    }

    private companion object {
        // Web Bluetooth's largest write.
        const val MAX_WRITE_BYTES = 512

        // An application error (ATT 0x80): the message was refused.
        const val REFUSED = 0x80

        // How long after a failed restart the advertising is tried again.
        const val ADVERTISE_RETRY_MS = 5_000L
    }
}
