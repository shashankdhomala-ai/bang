package com.bang.offlinechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Low-latency PCM call engine for BANG's local peer transport.
 * 16 kHz mono PCM is deliberately simple for the first real-audio milestone.
 * Packets are AES-GCM encrypted when a 32-byte call key is supplied.
 */
class AudioCallEngine(private val context: Context) {
    companion object {
        const val PORT = 39871
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = 320 // 20 ms
        private const val MAGIC = 0x42414E47
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var server: ServerSocket? = null
    private var capture: AudioRecord? = null
    private var playback: AudioTrack? = null
    private var captureThread: Thread? = null
    private var receiveThread: Thread? = null

    fun startServer(onConnected: () -> Unit, key: ByteArray? = null) {
        if (running.get()) return
        thread(name = "bang-call-server") {
            try {
                server = ServerSocket(PORT)
                server!!.soTimeout = 1000
                var s: Socket? = null
                while (running.get() && s == null) {
                    try { s = server!!.accept() } catch (_: java.net.SocketTimeoutException) { }
                }
                if (s == null) return@thread
                startStreams(s, key)
                onConnected()
            } catch (_: Exception) { stop() }
        }
    }

    fun connect(host: String, onConnected: () -> Unit, key: ByteArray? = null) {
        if (running.get()) return
        thread(name = "bang-call-connect") {
            try {
                val s = Socket()
                s.connect(java.net.InetSocketAddress(host, PORT), 3000)
                startStreams(s, key)
                onConnected()
            } catch (_: Exception) { stop() }
        }
    }

    private fun startStreams(s: Socket, key: ByteArray?) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            s.close(); return
        }
        runCatching { s.tcpNoDelay = true }
        socket = s
        running.set(true)
        val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, FORMAT).coerceAtLeast(FRAME_SAMPLES * 4)
        val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, FORMAT).coerceAtLeast(FRAME_SAMPLES * 4)
        capture = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_IN, FORMAT, minIn * 2)
        playback = AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, CHANNEL_OUT, FORMAT, minOut * 2, AudioTrack.MODE_STREAM)
        if (capture?.state != AudioRecord.STATE_INITIALIZED || playback?.state != AudioTrack.STATE_INITIALIZED) {
            stop()
            runCatching { s.close() }
            return
        }
        playback?.play()
        capture?.startRecording()

        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))

        captureThread = thread(name = "bang-call-capture") {
            val pcm = ByteArray(FRAME_SAMPLES * 2)
            while (running.get()) {
                val n = capture?.read(pcm, 0, pcm.size) ?: -1
                if (n <= 0) continue
                try {
                    val payload = if (key != null) encrypt(pcm.copyOf(n), key) else pcm.copyOf(n)
                    synchronized(out) {
                        out.writeInt(MAGIC); out.writeShort(payload.size); out.write(payload); out.flush()
                    }
                } catch (_: Exception) { break }
            }
        }

        receiveThread = thread(name = "bang-call-playback") {
            try {
                while (running.get()) {
                    if (input.readInt() != MAGIC) break
                    val len = input.readUnsignedShort()
                    if (len > 8192) break
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    val pcm = if (key != null) decrypt(payload, key) else payload
                    playback?.write(pcm, 0, pcm.size)
                }
            } catch (_: Exception) { }
            stop()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { capture?.stop() }
        runCatching { playback?.stop() }
        runCatching { capture?.release() }
        runCatching { playback?.release() }
        runCatching { socket?.close() }
        runCatching { server?.close() }
        capture = null; playback = null; socket = null; server = null
    }

    private fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(data)
    }

    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (data.size < 28) return ByteArray(0)
        val nonce = data.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(128, nonce))
        return c.doFinal(data.copyOfRange(12, data.size))
    }
}
