package com.cardash.dashboard.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Classic Bluetooth (SPP) link to an ELM327 adapter. */
@SuppressLint("MissingPermission")
class BluetoothObdConnection(
    private val device: BluetoothDevice,
) : ObdConnection {

    override val name: String = runCatching { device.name }.getOrNull() ?: device.address

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /**
     * Cheap ELM327 clones often refuse the standard secure socket ("read failed, socket might
     * closed"). Try secure, then insecure, then the raw channel-1 socket that works on most clones.
     */
    override suspend fun open() = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } // discovery breaks connects
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "channel 1" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((label, make) in attempts) {
            val s = runCatching { make() }.getOrElse { last = it as? Exception; null } ?: continue
            try {
                s.connect()
                android.util.Log.i("BtObd", "Connected via $label socket")
                socket = s
                input = s.inputStream
                output = s.outputStream
                return@withContext
            } catch (e: Exception) {
                android.util.Log.w("BtObd", "$label socket failed: ${e.message}")
                last = e
                runCatching { s.close() }
                kotlinx.coroutines.delay(500)
            }
        }
        throw IOException(
            "Couldn't open the adapter. Check the ignition is on, unplug the adapter for 10 seconds, plug it back in and try again.",
            last,
        )
    }

    /**
     * Send one command and read up to the ELM '>' prompt. Never blocks forever: if the adapter
     * goes quiet or the link drops, this throws so the manager can reconnect.
     */
    override suspend fun send(command: String): String = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("Not connected")
        val inp = input ?: throw IOException("Not connected")
        // Discard anything stale from a previous, timed-out reply.
        while (inp.available() > 0) { if (inp.read(ByteArray(inp.available())) < 0) throw IOException("Adapter closed the connection") }
        out.write((command + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val buf = ByteArray(512)
        // Reset and the first protocol search can take several seconds; normal queries are quick.
        val limit = if (command == "ATZ" || command == "0100" || command.startsWith("ATSP")) 10_000L else 4_000L
        val deadline = android.os.SystemClock.elapsedRealtime() + limit
        while (true) {
            if (inp.available() > 0) {
                val n = inp.read(buf)
                if (n < 0) throw IOException("Adapter closed the connection")
                sb.append(String(buf, 0, n))
                if (sb.indexOf(">") >= 0) break
            } else {
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw IOException("Adapter stopped responding")
                kotlinx.coroutines.delay(4)
            }
        }
        sb.toString()
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
