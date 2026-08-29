package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * [Transport] over a single, plain TCP socket to a fixed [TcpRelayServer] address — the
 * laptop-relay counterpart to `NearbyTransport`'s BLE/Wi-Fi Direct radio (app module), for
 * exactly the case where a real third device for a relay role is not available: this node
 * connects out, as a TCP client, to a relay running on the same Wi-Fi hotspot or LAN.
 *
 * ### Star, not mesh
 *
 * One upstream peer by design. [knownPeers] never holds more than the relay's own [NodeId];
 * `to = null` on [send] means "the relay," the same way it would mean "everyone reachable"
 * on a transport with more than one peer. Two nodes both connected to the same relay never
 * talk to each other directly — the relay is the only path between them, which is exactly
 * what a real out-of-range pair would experience on the real radio.
 *
 * ### Reconnects on its own
 *
 * A background thread holds the connection and retries with a fixed backoff on failure —
 * whether the relay was not up yet when this node started, or a connection drop later. A
 * phone should not need restarting just because the laptop was.
 *
 * Handshake: on connect, this side writes its own [NodeId] as the first frame, then reads
 * the relay's. [TcpRelayServer] does the mirror image (read first, then write) so there is
 * no coordination needed beyond "client writes first" — see its class doc.
 */
class TcpTransport(
    private val host: String,
    private val port: Int,
    override val localId: NodeId,
    private val reconnectDelayMs: Long = RECONNECT_DELAY_MS,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
) : Transport {

    /** Matches `NearbyTransport.MAX_BYTES_PAYLOAD` — this is the same class of hop (a LAN
     *  standing in for Wi-Fi Direct), not a LoRa link, so it gets the JSON codec too. */
    override val maxFrameBytes: Int = MAX_FRAME_BUDGET

    @Volatile private var running = false
    @Volatile private var listener: ((from: NodeId, frame: ByteArray) -> Unit)? = null
    @Volatile private var peerConnectedListener: ((NodeId) -> Unit)? = null
    @Volatile private var relayId: NodeId? = null

    private val writeLock = Any()
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var socket: Socket? = null
    private var connectThread: Thread? = null

    override fun start() {
        if (running) return
        running = true
        connectThread = Thread(::connectLoop, "TcpTransport-connect").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        closeQuietly()
        connectThread?.interrupt()
        connectThread = null
    }

    override fun send(frame: ByteArray, to: NodeId?) {
        require(frame.size <= maxFrameBytes) { "frame ${frame.size} > $maxFrameBytes" }
        // `to` is either null (broadcast) or the relay's own id — either way there is only
        // one socket to write to. A `to` that names neither is a caller bug elsewhere in
        // the mesh, not something this single-peer transport can route.
        val stream = out ?: run {
            log("send: not connected to relay yet")
            return
        }
        synchronized(writeLock) {
            try {
                TcpFraming.writeFrame(stream, frame)
            } catch (e: IOException) {
                log("send failed, dropping the connection to reconnect: ${e.message}")
                closeQuietly()
            }
        }
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        this.listener = listener
    }

    /** Fires once, after the handshake, each time a connection to the relay is (re)established. */
    fun onPeerConnected(listener: (NodeId) -> Unit) {
        this.peerConnectedListener = listener
    }

    override fun knownPeers(): List<NodeId> = relayId?.let { listOf(it) } ?: emptyList()

    private fun connectLoop() {
        while (running) {
            try {
                connectOnce()
            } catch (e: IOException) {
                log("relay unreachable at $host:$port (${e.message}); retrying in ${reconnectDelayMs}ms")
            } catch (e: InterruptedException) {
                return
            } finally {
                closeQuietly()
            }
            if (!running) return
            try {
                Thread.sleep(reconnectDelayMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Connects, handshakes, then blocks reading frames until the connection ends. */
    private fun connectOnce() {
        // Bounded, unlike the OS default connect timeout (which can run to a minute or
        // more) — a stuck connect attempt should not leave `stop()` waiting on a thread
        // that plain `Thread.interrupt()` cannot unblock.
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

        // Client writes first — see the class doc for why this cannot deadlock against
        // TcpRelayServer's read-first handshake.
        TcpFraming.writeFrame(output, localId.canonical().toByteArray(StandardCharsets.UTF_8))
        val relayIdBytes = TcpFraming.readFrame(input)
            ?: throw IOException("relay closed the connection before completing the handshake")
        val remoteId = NodeId.parse(String(relayIdBytes, StandardCharsets.UTF_8))
        relayId = remoteId
        out = output

        peerConnectedListener?.invoke(remoteId)

        while (running) {
            val frame = TcpFraming.readFrame(input) ?: break
            listener?.invoke(remoteId, frame)
        }
    }

    private fun closeQuietly() {
        out = null
        relayId = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun log(msg: String) = println("[TcpTransport $localId] $msg")

    companion object {
        const val MAX_FRAME_BUDGET = 32 * 1024
        const val RECONNECT_DELAY_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
