package org.groundzero.mesh.transport

import org.groundzero.mesh.propagation.NodeId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * The laptop side of the [TcpTransport] bridge: a [Transport] that accepts many inbound
 * connections instead of holding one outbound one — the hub of the star topology, standing
 * in for a real third [org.groundzero.mesh.agent.NodeAgent] relay when no radio-capable
 * third device is available. Meant to be driven by a bare `Gossip`, not a `NodeAgent` — a
 * relay only ever carries, never originates, so nothing here needs the agent at all. See
 * `TcpRelayMain` (the runnable laptop program) for that wiring.
 *
 * ### Handshake
 *
 * Per accepted connection: read the client's [NodeId] first, then write this server's own.
 * [TcpTransport] does the mirror image (write first, then read) — the two are a fixed pair,
 * so there is nothing to negotiate and no way for both sides to block waiting on each
 * other.
 *
 * ### Broadcast really does reach everyone, including the sender
 *
 * `send(frame, to = null)` writes to every currently connected socket, the same "everyone
 * reachable" semantics [org.groundzero.mesh.transport.Transport.send]'s doc describes, and
 * — deliberately, matching `NearbyTransport` — that includes whichever connection the frame
 * originally arrived on. `Gossip`'s own `propagationKey` dedup on the sender's side is what
 * keeps that from becoming an echo loop; this class does not need to know or care who sent
 * what.
 */
class TcpRelayServer(
    private val port: Int,
    override val localId: NodeId,
) : Transport {

    /** Same class of hop as [TcpTransport] — see its doc for why this is the JSON budget. */
    override val maxFrameBytes: Int = TcpTransport.MAX_FRAME_BUDGET

    @Volatile private var running = false
    @Volatile private var listener: ((from: NodeId, frame: ByteArray) -> Unit)? = null
    @Volatile private var peerConnectedListener: ((NodeId) -> Unit)? = null
    @Volatile private var serverSocket: ServerSocket? = null

    private val connections = ConcurrentHashMap<NodeId, Connection>()
    private var acceptThread: Thread? = null

    override fun start() {
        if (running) return
        running = true
        val ss = ServerSocket(port)
        serverSocket = ss
        acceptThread = Thread({ acceptLoop(ss) }, "TcpRelayServer-accept").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        connections.values.toList().forEach { it.close() }
        connections.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    override fun send(frame: ByteArray, to: NodeId?) {
        require(frame.size <= maxFrameBytes) { "frame ${frame.size} > $maxFrameBytes" }
        if (to == null) {
            connections.values.forEach { it.trySend(frame) }
        } else {
            val conn = connections[to]
            if (conn == null) {
                log("send: no live connection for $to")
            } else {
                conn.trySend(frame)
            }
        }
    }

    override fun onReceive(listener: (from: NodeId, frame: ByteArray) -> Unit) {
        this.listener = listener
    }

    /** Fires once per connection, right after its handshake completes. */
    fun onPeerConnected(listener: (NodeId) -> Unit) {
        this.peerConnectedListener = listener
    }

    override fun knownPeers(): List<NodeId> = connections.keys.toList()

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) log("accept failed: ${e.message}")
                continue
            }
            Thread({ handleConnection(socket) }, "TcpRelayServer-conn-${socket.remoteSocketAddress}")
                .apply { isDaemon = true; start() }
        }
    }

    private fun handleConnection(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        val peerId = try {
            val idBytes = TcpFraming.readFrame(input)
                ?: return // client disconnected before completing the handshake
            val id = NodeId.parse(String(idBytes, StandardCharsets.UTF_8))
            TcpFraming.writeFrame(output, localId.canonical().toByteArray(StandardCharsets.UTF_8))
            id
        } catch (e: IOException) {
            log("handshake failed: ${e.message}")
            runCatching { socket.close() }
            return
        }

        val connection = Connection(socket, output)
        connections[peerId] = connection
        peerConnectedListener?.invoke(peerId)

        try {
            while (running) {
                val frame = TcpFraming.readFrame(input) ?: break
                listener?.invoke(peerId, frame)
            }
        } catch (e: IOException) {
            log("connection to $peerId dropped: ${e.message}")
        } finally {
            connections.remove(peerId, connection)
            connection.close()
        }
    }

    private fun log(msg: String) = println("[TcpRelayServer $localId] $msg")

    /** One accepted socket, plus the lock its writes need — [send] can be called from a
     *  different connection's reader thread while this one is mid-write. */
    private class Connection(private val socket: Socket, private val out: DataOutputStream) {
        private val writeLock = Any()

        fun trySend(frame: ByteArray) {
            synchronized(writeLock) {
                try {
                    TcpFraming.writeFrame(out, frame)
                } catch (e: IOException) {
                    close()
                }
            }
        }

        fun close() = runCatching { socket.close() }
    }
}
