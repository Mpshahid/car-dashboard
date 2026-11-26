package com.cardash.dashboard.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Low Energy link to an ELM327 (adapters that advertise as e.g. "OBDBLE", "IOS-Vlink").
 * BLE adapters don't pair with a PIN: we connect straight to their serial-style GATT service,
 * write commands to one characteristic and collect the reply from notifications on another.
 */
@SuppressLint("MissingPermission")
class BleObdConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) : ObdConnection {

    override val name: String = runCatching { device.name }.getOrNull() ?: device.address

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val ready = CompletableDeferred<Unit>()
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val writeDone = Channel<Unit>(Channel.CONFLATED)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "BLE state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = IOException("The adapter disconnected (Bluetooth LE, status $status)")
                ready.completeExceptionally(e)
                incoming.close(e)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val (notify, write) = pickCharacteristics(g)
            if (notify == null || write == null) {
                ready.completeExceptionally(IOException("This Bluetooth LE device doesn't look like an OBD adapter"))
                return
            }
            Log.i(TAG, "BLE notify=${notify.uuid} write=${write.uuid} mtu=$mtu")
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) { ready.complete(Unit); return }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
            else @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.trySend(Unit)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(String(value, Charsets.US_ASCII))
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                c.value?.let { incoming.trySend(String(it, Charsets.US_ASCII)) }
            }
        }
    }

    override suspend fun open() {
        withContext(Dispatchers.Main) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        try {
            withTimeout(15_000) { ready.await() }
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException("Couldn't connect to the adapter over Bluetooth LE. Is the ignition on?", e)
        }
    }

    override suspend fun send(command: String): String = withContext(Dispatchers.IO) {
        val g = gatt ?: throw IOException("Not connected")
        val ch = writeChar ?: throw IOException("Not connected")
        while (incoming.tryReceive().isSuccess) { /* drop stale data */ }
        val bytes = (command + "\r").toByteArray(Charsets.US_ASCII)
        val chunk = (mtu - 3).coerceIn(20, 240)
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        var i = 0
        while (i < bytes.size) {
            val part = bytes.copyOfRange(i, minOf(bytes.size, i + chunk))
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, part, type)
            else @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = part; g.writeCharacteristic(ch) }
            withTimeoutOrNull(1_500) { writeDone.receive() }
            i += chunk
        }
        // Read until the ELM '>' prompt (protocol search on the first query can take several seconds).
        val sb = StringBuilder()
        val limit = if (command == "ATZ" || command == "0100" || command.startsWith("ATSP")) 12_000L else 4_000L
        val done = withTimeoutOrNull(limit) {
            while (sb.indexOf(">") < 0) sb.append(incoming.receive())
            true
        }
        if (done == null) throw IOException("Adapter stopped responding")
        sb.toString()
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
    }

    /** Known ELM327 BLE layouts first (FFF0 / FFE0 / 18F0), then any notify + write pair. */
    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic?, BluetoothGattCharacteristic?> {
        fun canNotify(c: BluetoothGattCharacteristic) =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        fun canWrite(c: BluetoothGattCharacteristic) =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val services = g.services.orEmpty()
        val preferred = services.sortedBy { s ->
            val id = s.uuid.toString().substring(4, 8).lowercase()
            when (id) { "fff0" -> 0; "ffe0" -> 1; "18f0" -> 2; else -> if (id.startsWith("18")) 9 else 5 }
        }
        for (s in preferred) {
            val n = s.characteristics.firstOrNull { canNotify(it) }
            val w = s.characteristics.firstOrNull { canWrite(it) }
            if (n != null && w != null) return n to w
        }
        return null to null
    }

    companion object {
        private const val TAG = "BleObd"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** True when [d] should be reached over BLE rather than classic Bluetooth. */
        fun isBle(d: BluetoothDevice): Boolean {
            val type = runCatching { d.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
            val n = runCatching { d.name }.getOrNull().orEmpty()
            return type == BluetoothDevice.DEVICE_TYPE_LE ||
                (type != BluetoothDevice.DEVICE_TYPE_CLASSIC && n.contains("BLE", ignoreCase = true))
        }
    }
}
