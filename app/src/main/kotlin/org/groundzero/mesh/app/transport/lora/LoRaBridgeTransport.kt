package org.groundzero.mesh.app.transport.lora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import org.groundzero.mesh.propagation.CompactCodec
import org.groundzero.mesh.propagation.NodeId
import org.groundzero.mesh.transport.Transport
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [Transport] over an ESP32 / Meshtastic LoRa radio reached through a BLE-to-serial bridge.
 *
 * Carries [CompactCodec] frames — this class never encodes or decodes an envelope, it just
 * moves opaque bytes onto the radio and off it.
 *
 * State of completeness: the BLE GATT plumbing (connect, discover, notify, MTU, chunked
 * write) is here and correct by inspection; it is **not** verified against hardware. Every
 * such spot is marked `VERIFY(hardware)`. The on-air framing is [MeshtasticFrame], a
 * stopgap until the Meshtastic protobuf API is linked (see that file's TODO).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is gated by MeshPermissions before start()
class LoRaBridgeTransport(
    private val context: Context,
    override val localId: NodeId,
    private val deviceAddress: String,
) : Transport {

    /**
     * Meshtastic firmware `DATA_PAYLOAD_LEN` is 233. On the stopgap framing the 8-byte
     * [MeshtasticFrame] header sits inside that budget, so the codec is told 233 - 8. Once
     * the native Meshtastic header carries `from`/`to` outside the payload this becomes the
     * full 233.
     */
    override val maxFrameBytes: Int = CompactCodec.LORA_MAX_FRAME - MeshtasticFrame.HEADER_BYTES

    private val localNodeNum: Long = localId.value and 0xFFFF_FFFFL

    private val nodeNumToId = ConcurrentHashMap<Long, NodeId>()
    private val reassembler = MeshtasticFrame.Reassembler(maxPayload = CompactCodec.LORA_MAX_FRAME)
    private val writeQueue = ArrayDeque<ByteArray>()

    @Volatile private var listener: ((from: NodeId, frame: ByteArray) -> Unit)? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null   // phone -> radio (write)
    @Volatile private var txChar: BluetoothGattCharacteristic? = null   // radio -> phone (notify)
    @Volatile private var mtuPayload: Int = DEFAULT_MTU_PAYLOAD
    @Volatile private var writeInFlight = false
    @Volatile private var running = false

    override fun start() {
        if (running) return
        running = true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter?.getRemoteDevice(deviceAddress) ?: run {
            Log.e(TAG, "no bluetooth adapter / bad address $deviceAddress")
            running = false
            return
        }
        gatt = device.connectGatt(context, /* autoConnect = */ true, gattCallback)
    }

    override fun stop() {
        running = false
        synchronized(writeQueue) { writeQueue.clear() }
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        rxChar = null
        txChar = null
    }

    override fun send(frame: ByteArray, to: NodeId?) {
        check(running) { "transport not started" }
        require(frame.size <= maxFrameBytes) { "frame ${frame.size} > $maxFrameBytes" }
        // LoRa is a broadcast medium; `to` is advisory only and not honoured on this hop.
        val datagram = MeshtasticFrame.encode(localNodeNum, frame)
        synchronized(writeQueue) {
            var offset = 0
            while (offset < datagram.size) {
                val end = minOf(offset + mtuPayload, datagram.size)
                writeQueue.addLast(datagram.copyOfRange(offset, end))
                offset = end
            }
        }
        pumpWrites()
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun knownPeers(): List<NodeId> = nodeNumToId.values.toList()

    // --- BLE plumbing ---

    private fun pumpWrites() {
        val chunk: ByteArray
        synchronized(writeQueue) {
            if (writeInFlight || writeQueue.isEmpty()) return
            chunk = writeQueue.removeFirst()
            writeInFlight = true
        }
        val g = gatt
        val rx = rxChar
        if (g == null || rx == null) { writeInFlight = false; return }
        // VERIFY(hardware): some bridges need WRITE_TYPE_NO_RESPONSE for throughput; others
        // drop bytes without an acked write. Confirm against the real module.
        @Suppress("DEPRECATION")
        run {
            rx.value = chunk
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!g.writeCharacteristic(rx)) {
                writeInFlight = false
                Log.w(TAG, "writeCharacteristic returned false")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(REQUESTED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxChar = null; txChar = null
                // autoConnect = true lets the stack reconnect on its own.
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuPayload = (mtu - ATT_WRITE_OVERHEAD).coerceAtLeast(20)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(NUS_SERVICE) ?: run {
                Log.e(TAG, "VERIFY(hardware): NUS service not found — is the bridge exposing " +
                    "Nordic UART, or the native Meshtastic BLE service?")
                return
            }
            rxChar = service.getCharacteristic(NUS_RX)
            txChar = service.getCharacteristic(NUS_TX)?.also { enableNotifications(g, it) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            pumpWrites()
        }

        @Deprecated("kept for API < 33; the TYPE_BYTES variant is on Android 13+")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleInbound(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleInbound(value)
        }
    }

    private fun handleInbound(chunk: ByteArray) {
        for (dg in reassembler.offer(chunk)) {
            val from = nodeNumToId.getOrPut(dg.sourceNodeNum) { NodeId(dg.sourceNodeNum and NodeId.MAX_VALUE) }
            listener?.invoke(from, dg.payload)
        }
    }

    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: return
        // VERIFY(hardware): a few bridges want INDICATION rather than NOTIFICATION.
        @Suppress("DEPRECATION")
        run {
            cccd.value = BluetoothGattDescriptorValues.ENABLE_NOTIFICATION
            g.writeDescriptor(cccd)
        }
    }

    private object BluetoothGattDescriptorValues {
        val ENABLE_NOTIFICATION = byteArrayOf(0x01, 0x00)
    }

    companion object {
        private const val TAG = "LoRaBridgeTransport"
        private const val REQUESTED_MTU = 247
        private const val ATT_WRITE_OVERHEAD = 3
        private const val DEFAULT_MTU_PAYLOAD = 20

        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
