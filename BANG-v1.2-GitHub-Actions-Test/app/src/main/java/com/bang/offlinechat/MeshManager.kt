package com.bang.offlinechat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BANG Mesh v2.
 *
 * Nearby discovery and encrypted BLE links are kept from Mesh v1. Messages now
 * carry a route id and bounded TTL so a connected A-B-C chain can forward chat
 * traffic across multiple phones. Encryption is hop-by-hop: every relay can
 * decrypt a message and re-encrypt it for the next peer. This is a transport
 * prototype, not a Signal-Protocol end-to-end encrypted messenger.
 */
class MeshManager(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(text: String)
        fun onPeerFound(peer: Peer)
        fun onPeerConnected(peer: Peer)
        fun onMessage(text: String, peer: Peer)
        fun onError(text: String)
        fun onCallEvent(event: String, from: String, target: String)
    }

    data class Peer(val address: String, val name: String)

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("8b8a2a80-9f6f-4b3d-9b5e-2d5b7e0c2026")
        private val RX_UUID: UUID = UUID.fromString("8b8a2a81-9f6f-4b3d-9b5e-2d5b7e0c2026")
        private val TX_UUID: UUID = UUID.fromString("8b8a2a82-9f6f-4b3d-9b5e-2d5b7e0c2026")
        private const val FRAME_PAYLOAD = 16
        private const val MAX_MESSAGE = 2048
        private const val DEFAULT_TTL = 4
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val connections = ConcurrentHashMap<String, BluetoothGatt>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val assemblies = ConcurrentHashMap<String, Assembly>()
    private val seenRoutePackets = ConcurrentHashMap.newKeySet<String>()
    private var scanning = false
    private var advertising = false

    private data class Session(var key: ByteArray? = null, var localKeyPair: KeyPair? = null, var remotePublic: ByteArray? = null, var helloSent: Boolean = false)
    private data class Assembly(val total: Int, val parts: MutableMap<Int, ByteArray> = mutableMapOf())

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val uuids = record.serviceUuids ?: return
            if (uuids.none { it.uuid == SERVICE_UUID }) return
            val address = result.device.address ?: return
            val serviceData = record.getServiceData(ParcelUuid(SERVICE_UUID))
            val advertised = serviceData?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            val name = advertised ?: record.deviceName ?: result.device.name ?: "BANG device"
            if (name == localAdvertisedName()) return
            val peer = Peer(address, name)
            peers[address] = peer
            listener.onPeerFound(peer)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onError("BLE scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasBlePermissions()) {
            listener.onError("BANG needs Nearby devices permission to use mesh mode.")
            return
        }
        val a = adapter ?: run { listener.onError("Bluetooth is not available on this phone."); return }
        if (!a.isEnabled) {
            listener.onError("Turn on Bluetooth, then start BANG Mesh again.")
            return
        }
        startServer()
        startAdvertising()
        startScanning()
        listener.onStatus("🟢 Mesh active — looking for nearby BANG phones")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasBlePermissions()) return
        if (scanning) scanner?.stopScan(scanCallback)
        scanning = false
        if (advertising) advertiser?.stopAdvertising(advertiseCallback)
        advertising = false
        connections.values.forEach { it.close() }
        connections.clear()
        sessions.clear()
        assemblies.clear()
        seenRoutePackets.clear()
        gattServer?.close()
        gattServer = null
        listener.onStatus("⚪ Mesh stopped")
    }

    @SuppressLint("MissingPermission")
    fun connect(peer: Peer) {
        if (!hasBlePermissions()) return
        val device = adapter?.getRemoteDevice(peer.address) ?: return
        if (connections.containsKey(peer.address)) return
        listener.onStatus("🔗 Connecting to ${peer.name}…")
        sessions[peer.address] = Session(localKeyPair = newKeyPair())
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        connections[peer.address] = gatt
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (clean.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE) {
            listener.onError("Mesh message is limited to 2 KB for v2.")
            return
        }
        val ready = connections.entries.filter { sessions[it.key]?.key != null }
        if (ready.isEmpty()) {
            listener.onError("No encrypted mesh peer is connected yet.")
            return
        }
        val id = UUID.randomUUID().toString()
        seenRoutePackets.add(id)
        ready.forEach { (address, _) ->
            sendEncrypted(address, clean, id, localAdvertisedName(), DEFAULT_TTL)
        }
    }

    fun peerList(): List<Peer> = peers.values.sortedBy { it.name.lowercase() }

    fun callTarget(targetName: String): Boolean {
        val target = targetName.trim()
        if (target.isEmpty()) { listener.onError("Choose a BANG device to call."); return false }
        val id = UUID.randomUUID().toString()
        val packet = JSONObject().apply {
            put("t", "call_invite")
            put("id", id)
            put("from", localAdvertisedName())
            put("target", target)
            put("ttl", DEFAULT_TTL)
        }.toString().toByteArray(Charsets.UTF_8)
        seenRoutePackets.add(id)
        var sent = false
        connections.keys.forEach { address ->
            if (sessions[address]?.key != null) {
                connections[address]?.let { sendPacket(address, packet, it); sent = true }
            }
        }
        if (!sent) listener.onError("Connect to at least one nearby BANG phone first.")
        else listener.onCallEvent("outgoing", localAdvertisedName(), target)
        return sent
    }

    fun endCall(targetName: String) {
        val packet = JSONObject().apply {
            put("t", "call_end")
            put("id", UUID.randomUUID().toString())
            put("from", localAdvertisedName())
            put("target", targetName.trim())
            put("ttl", DEFAULT_TTL)
        }.toString().toByteArray(Charsets.UTF_8)
        connections.keys.forEach { address ->
            if (sessions[address]?.key != null) connections[address]?.let { sendPacket(address, packet, it) }
        }
        listener.onCallEvent("ended", localAdvertisedName(), targetName.trim())
    }

    private fun hasBlePermissions(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (advertising) return
        advertiser = adapter?.bluetoothLeAdvertiser ?: run { listener.onError("BLE advertising is unavailable on this phone."); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), localAdvertisedName().toByteArray(Charsets.UTF_8).take(20).toByteArray())
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            listener.onError("BLE advertising failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServer() {
        if (gattServer != null) return
        gattServer = bluetoothManager?.openGattServer(context, serverCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        txCharacteristic = BluetoothGattCharacteristic(TX_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        txCharacteristic?.addDescriptor(BluetoothGattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val rx = BluetoothGattCharacteristic(RX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        service.addCharacteristic(txCharacteristic)
        service.addCharacteristic(rx)
        gattServer?.addService(service)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (characteristic.uuid != RX_UUID) return
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            handleFrame(device.address, value, notifyDevice = device)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val peer = peers[device.address] ?: Peer(device.address, "BANG device")
                peers[device.address] = peer
                sessions.putIfAbsent(device.address, Session(localKeyPair = newKeyPair()))
                listener.onPeerConnected(peer)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sessions.remove(device.address)
                listener.onStatus("⚪ ${peers[device.address]?.name ?: "Peer"} disconnected")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onStatus("🔗 Connected — discovering BANG service")
                gatt.discoverServices()
                val peer = peers[gatt.device.address] ?: Peer(gatt.device.address, "BANG device")
                listener.onPeerConnected(peer)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connections.remove(gatt.device.address)
                sessions.remove(gatt.device.address)
                gatt.close()
                listener.onStatus("⚪ ${peers[gatt.device.address]?.name ?: "Peer"} disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(SERVICE_UUID) ?: return
            val rx = service.getCharacteristic(RX_UUID) ?: return
            val tx = service.getCharacteristic(TX_UUID) ?: return
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            sessions[gatt.device.address]?.let { if (it.localKeyPair == null) it.localKeyPair = newKeyPair() }
            sendHandshake(gatt.device.address, gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_UUID) handleFrame(gatt.device.address, characteristic.value, null)
        }
    }

    private fun sendHandshake(address: String, transport: Any) {
        val session = sessions[address] ?: return
        if (session.helloSent) return
        session.helloSent = true
        val publicKey = session.localKeyPair?.public?.encoded ?: return
        val packet = JSONObject().apply {
            put("t", "hello")
            put("pk", Base64.encodeToString(publicKey, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)
        sendPacket(address, packet, transport)
    }

    private fun handleFrame(address: String, frame: ByteArray, notifyDevice: BluetoothDevice?) {
        if (frame.size < 4) return
        val bb = ByteBuffer.wrap(frame)
        val id = bb.short.toInt() and 0xffff
        val seq = bb.get().toInt() and 0xff
        val total = bb.get().toInt() and 0xff
        val payload = ByteArray(bb.remaining()).also { bb.get(it) }
        if (total == 0 || total > 255 || seq >= total) return
        val key = "$address:$id"
        val assembly = assemblies.getOrPut(key) { Assembly(total) }
        assembly.parts[seq] = payload
        if (assembly.parts.size != total) return
        val bytes = ByteArray(assembly.parts.values.sumOf { it.size })
        var pos = 0
        for (i in 0 until total) {
            val p = assembly.parts[i] ?: return
            System.arraycopy(p, 0, bytes, pos, p.size)
            pos += p.size
        }
        assemblies.remove(key)
        processPacket(address, bytes, notifyDevice)
    }

    private fun processPacket(address: String, bytes: ByteArray, notifyDevice: BluetoothDevice?) {
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        when (json.optString("t")) {
            "hello" -> {
                val remote = runCatching { Base64.decode(json.getString("pk"), Base64.NO_WRAP) }.getOrNull() ?: return
                val session = sessions.getOrPut(address) { Session(localKeyPair = newKeyPair()) }
                session.remotePublic = remote
                session.key = deriveKey(session.localKeyPair ?: return, remote)
                val ack = JSONObject().apply {
                    put("t", "hello_ack")
                    put("pk", Base64.encodeToString(session.localKeyPair?.public?.encoded ?: return, Base64.NO_WRAP))
                }.toString().toByteArray(Charsets.UTF_8)
                if (notifyDevice != null) sendPacket(address, ack, notifyDevice)
                else connections[address]?.let { sendPacket(address, ack, it) }
                listener.onStatus("🔐 Encrypted link ready")
            }
            "hello_ack" -> {
                val remote = runCatching { Base64.decode(json.getString("pk"), Base64.NO_WRAP) }.getOrNull() ?: return
                val session = sessions.getOrPut(address) { Session(localKeyPair = newKeyPair()) }
                session.remotePublic = remote
                session.key = deriveKey(session.localKeyPair ?: return, remote)
                listener.onStatus("🔐 Encrypted link ready")
            }
            "call_invite", "call_end" -> {
                val id = json.optString("id")
                if (id.isEmpty() || !seenRoutePackets.add(id)) return
                val from = json.optString("from")
                val target = json.optString("target")
                val ttl = json.optInt("ttl", 0)
                if (target.equals(localAdvertisedName(), ignoreCase = true)) {
                    listener.onCallEvent(if (json.optString("t") == "call_invite") "incoming" else "ended", from, target)
                    return
                }
                if (ttl <= 0) return
                val forwarded = JSONObject(json.toString()).apply { put("ttl", ttl - 1) }.toString().toByteArray(Charsets.UTF_8)
                connections.keys.forEach { next ->
                    if (next != address && sessions[next]?.key != null) connections[next]?.let { sendPacket(next, forwarded, it) }
                }
            }
            "msg" -> {
                val session = sessions[address] ?: return
                val key = session.key ?: return
                val nonce = Base64.decode(json.getString("n"), Base64.NO_WRAP)
                val cipher = Base64.decode(json.getString("c"), Base64.NO_WRAP)
                val text = decrypt(key, nonce, cipher) ?: return
                val id = json.optString("id")
                if (id.isNotEmpty() && !seenRoutePackets.add(id)) return
                val from = json.optString("from").ifBlank { peers[address]?.name ?: "BANG device" }
                listener.onMessage(text, Peer(address, from))
                val ttl = json.optInt("ttl", 0)
                if (id.isNotEmpty() && ttl > 0) {
                    val nextTtl = ttl - 1
                    if (nextTtl > 0) {
                        connections.keys.forEach { next ->
                            if (next != address && sessions[next]?.key != null) {
                                sendEncrypted(next, text, id, from, nextTtl)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendEncrypted(address: String, text: String, id: String = UUID.randomUUID().toString(), from: String = localAdvertisedName(), ttl: Int = 0) {
        val session = sessions[address] ?: return
        val key = session.key ?: return
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = encrypt(key, nonce, text.toByteArray(Charsets.UTF_8)) ?: return
        val packet = JSONObject().apply {
            put("t", "msg")
            put("id", id)
            put("from", from)
            put("ttl", ttl)
            put("n", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("c", Base64.encodeToString(cipher, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)
        connections[address]?.let { sendPacket(address, packet, it) }
    }

    @SuppressLint("MissingPermission")
    private fun sendPacket(address: String, packet: ByteArray, transport: Any) {
        if (packet.size > 4096) return
        Thread { sendPacketBlocking(address, packet, transport) }.start()
    }

    @SuppressLint("MissingPermission")
    private fun sendPacketBlocking(address: String, packet: ByteArray, transport: Any) {
        val total = (packet.size + FRAME_PAYLOAD - 1) / FRAME_PAYLOAD
        val id = (packet.contentHashCode() xor System.nanoTime().toInt()) and 0xffff
        for (seq in 0 until total) {
            val start = seq * FRAME_PAYLOAD
            val end = minOf(packet.size, start + FRAME_PAYLOAD)
            val frame = ByteArray(4 + end - start)
            ByteBuffer.wrap(frame).apply { putShort(id.toShort()); put(seq.toByte()); put(total.toByte()); put(packet, start, end - start) }
            when (transport) {
                is BluetoothGatt -> {
                    val service = transport.getService(SERVICE_UUID) ?: return
                    val rx = service.getCharacteristic(RX_UUID) ?: return
                    rx.value = frame
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    transport.writeCharacteristic(rx)
                }
                is BluetoothDevice -> {
                    val tx = txCharacteristic ?: return
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        gattServer?.notifyCharacteristicChanged(transport, tx, false, frame)
                    } else {
                        tx.value = frame
                        @Suppress("DEPRECATION")
                        gattServer?.notifyCharacteristicChanged(transport, tx, false)
                    }
                }
            }
            try { Thread.sleep(8) } catch (_: InterruptedException) { }
        }
    }

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun deriveKey(local: KeyPair, remoteEncoded: ByteArray): ByteArray {
        val remote = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remoteEncoded))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(local.private)
        ka.doPhase(remote, true)
        val shared = ka.generateSecret()
        return MessageDigest.getInstance("SHA-256").digest(shared)
    }

    private fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(plain)
    }.getOrNull()

    private fun decrypt(key: ByteArray, nonce: ByteArray, cipher: ByteArray): String? = runCatching {
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(cipher)
        String(plain, Charsets.UTF_8)
    }.getOrNull()

    private fun localAdvertisedName(): String = "BANG-${installId().takeLast(6)}"

    private fun installId(): String {
        val prefs = context.getSharedPreferences("bang_mesh", Context.MODE_PRIVATE)
        var id = prefs.getString("id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("id", id).apply()
        }
        return id
    }
}
