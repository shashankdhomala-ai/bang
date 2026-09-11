package com.bang.offlinechat

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * BANG A->B->C encrypted-audio relay.
 *
 * The relay never decodes PCM or touches the AES key. It forwards the already
 * framed/encrypted audio bytes unchanged between the first two connected peers.
 * B is therefore a transport relay, not a trusted audio endpoint.
 */
class RelayAudioEngine {
    companion object { const val PORT = AudioCallEngine.PORT }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val peers = CopyOnWriteArrayList<Socket>()

    fun start(onStatus: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        thread(name = "bang-audio-relay") {
            try {
                server = ServerSocket(PORT)
                onStatus("A→B→C relay listening on port $PORT")
                while (running.get()) {
                    val socket = server!!.accept()
                    if (!running.get()) { socket.close(); break }
                    if (peers.size >= 2) {
                        socket.close()
                        onStatus("Relay full: already serving A→B→C")
                        continue
                    }
                    peers += socket
                    onStatus("Relay peer connected (${peers.size}/2)")
                    if (peers.size == 2) startForwarding(onStatus)
                }
            } catch (e: Exception) {
                if (running.get()) onStatus("Relay stopped: ${e.message ?: "network error"}")
            } finally { stop() }
        }
    }

    private fun startForwarding(onStatus: (String) -> Unit) {
        val left = peers[0]
        val right = peers[1]
        onStatus("A→B→C audio relay active")
        pipe(left, right, onStatus)
        pipe(right, left, onStatus)
    }

    private fun pipe(from: Socket, to: Socket, onStatus: (String) -> Unit) {
        thread(name = "bang-relay-pipe") {
            try {
                val input = BufferedInputStream(from.getInputStream())
                val output = BufferedOutputStream(to.getOutputStream())
                val buffer = ByteArray(16 * 1024)
                while (running.get()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    output.flush()
                }
            } catch (_: Exception) { }
            finally {
                onStatus("A→B→C relay link ended")
                stop()
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        peers.forEach { runCatching { it.close() } }
        peers.clear()
        runCatching { server?.close() }
        server = null
    }
}
