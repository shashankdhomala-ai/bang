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
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BANG Mesh v3.
 *
 * BLE is the discovery/transport layer. Chat payloads are now recipient-specific
 * end-to-end encrypted: only the intended BANG device has the private key needed
 * to decrypt the message. Relay phones forward the opaque e2e_msg packet and do
 * not call onMessage().
 *
 * This is a protocol prototype. It is not yet a full Signal-style identity,
 * ratchet, group-encryption, or AirTag/Find My implementation.
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
        private const val MAX_PACKET = 4096
        private const val DEFAULT_TTL = 4
        private const val KEY_ANNOUNCE_TTL = 4
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
    private val peerPublicKeys = ConcurrentHashMap<String, ByteArray>()
    private var scanning = false
    private var advertising = false

    private data class Session(
        var key: ByteArray? = null,
        var localKeyPair: KeyPair? = null,
        var remotePublic: ByteArray? = null,
        var helloSent: Boolean = false
    )

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
        ensureIdentityKeyPair()
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
        peerPublicKeys.clear()
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
        sessions[peer.address] = Session(localKeyPair = ensureIdentityKeyPair())
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        connections[peer.address] = gatt
    }

    /** Legacy broadcast send. Kept for compatibility with the existing UI. */
    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val ready = connections.keys.filter { sessions[it]?.key != null }
        if (ready.isEmpty()) {
            listener.onError("No encrypted mesh peer is connected yet.")
            return
        }
        val id = UUID.randomUUID().toString()
        seenRoutePackets.add(id)
        ready.forEach { address -> sendHopEncrypted(address, clean, id, localAdvertisedName(), DEFAULT_TTL) }
    }

    /**
     * Send a message specifically to targetName. The message is encrypted to the
     * target's public key before it enters the mesh. Relays forward the ciphertext.
     */
    fun send(text: String, targetName: String) {
        val clean = text.trim()
        val target = targetName.trim()
        if (clean.isEmpty() || target.isEmpty()) return
        if (clean.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE) {
            listener.onError("Mesh message is limited to 2 KB.")
            return
        }
        if (target.equals(localAdvertisedName(), ignoreCase = true)) {
            listener.onError("Choose another BANG device as the recipient.")
            return
        }
        val recipientKey = peerPublicKeys[target]
        if (recipientKey == null) {
            listener.onError("I don't have $target's encryption key yet. Keep both phones in mesh range for a moment and try again.")
            announceLocalKeyToConnectedPeers()
            return
        }
        val id = UUID.randomUUID().toString()
        val packet = buildE2eMessage(clean, target, id, recipientKey) ?: return
        seenRoutePackets.add(id)
        var sent = false
        connections.keys.forEach { address ->
            if (sessions[address]?.key != null) {
                connections[address]?.let {
                    sendPacket(address, packet, it)
                    sent = true
                }
            }
        }
        if (!sent) listener.onError("Connect to at least one nearby BANG phone first.")
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
                sessions.putIfAbsent(device.address, Session(localKeyPair = ensureIdentityKeyPair()))
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
            sessions[gatt.device.address]?.let { if (it.localKeyPair == null) it.localKeyPair = ensureIdentityKeyPair() }
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
        val publicKey = session.localKeyPair?.public?.encoded ?: ensureIdentityKeyPair().public.encoded
        val packet = JSONObject().apply {
            put("t", "hello")
            put("pk", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            put("name", localAdvertisedName())
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
                val remoteName = json.optString("name").ifBlank { peers[address]?.name ?: "BANG device" }
                val session = sessions.getOrPut(address) { Session(localKeyPair = ensureIdentityKeyPair()) }
                session.remotePublic = remote
                session.key = deriveKey(session.localKeyPair ?: ensureIdentityKeyPair(), remote)
                peerPublicKeys[remoteName] = remote
                val ack = JSONObject().apply {
                    put("t", "hello_ack")
                    put("pk", Base64.encodeToString(ensureIdentityKeyPair().public.encoded, Base64.NO_WRAP))
                    put("name", localAdvertisedName())
                }.toString().toByteArray(Charsets.UTF_8)
                if (notifyDevice != null) sendPacket(address, ack, notifyDevice)
                else connections[address]?.let { sendPacket(address, ack, it) }
                listener.onStatus("🔐 Encrypted link ready")
                announceLocalKeyToConnectedPeers(exclude = address)
            }
            "hello_ack" -> {
                val remote = runCatching { Base64.decode(json.getString("pk"), Base64.NO_WRAP) }.getOrNull() ?: return
                val remoteName = json.optString("name").ifBlank { peers[address]?.name ?: "BANG device" }
                val session = sessions.getOrPut(address) { Session(localKeyPair = ensureIdentityKeyPair()) }
                session.remotePublic = remote
                session.key = deriveKey(session.localKeyPair ?: ensureIdentityKeyPair(), remote)
                peerPublicKeys[remoteName] = remote
                listener.onStatus("🔐 Encrypted link ready")
                announceLocalKeyToConnectedPeers(exclude = address)
            }
            "key_announce" -> {
                val id = json.optString("id")
                if (id.isEmpty() || !seenRoutePackets.add(id)) return
                val name = json.optString("name")
                val publicKey = runCatching { Base64.decode(json.getString("pk"), Base64.NO_WRAP) }.getOrNull() ?: return
                val ttl = json.optInt("ttl", 0)
                if (name.isBlank() || name == localAdvertisedName()) return
                peerPublicKeys[name] = publicKey
                if (ttl <= 0) return
                val forwarded = JSONObject(json.toString()).apply { put("ttl", ttl - 1) }.toString().toByteArray(Charsets.UTF_8)
                connections.keys.forEach { next ->
                    if (next != address && sessions[next]?.key != null) connections[next]?.let { sendPacket(next, forwarded, it) }
                }
            }
            "e2e_msg" -> {
                val id = json.optString("id")
                if (id.isEmpty() || !seenRoutePackets.add(id)) return
                val target = json.optString("target")
                val ttl = json.optInt("ttl", 0)
                if (target.equals(localAdvertisedName(), ignoreCase = true)) {
                    val text = decryptE2e(json) ?: return
                    val from = json.optString("from").ifBlank { "BANG device" }
                    listener.onMessage(text, Peer(address, from))
                    return
                }
                if (ttl <= 0) return
                val forwarded = JSONObject(json.toString()).apply { put("ttl", ttl - 1) }.toString().toByteArray(Charsets.UTF_8)
                connections.keys.forEach { next ->
                    if (next != address && sessions[next]?.key != null) connections[next]?.let { sendPacket(next, forwarded, it) }
                }
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
        }
    }

    private fun buildE2eMessage(text: String, target: String, id: String, recipientPublic: ByteArray): ByteArray? = runCatching {
        val recipient = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(recipientPublic))
        val ephemeral = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephemeral.private)
        ka.doPhase(recipient, true)
        val key = MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }.doFinal(text.toByteArray(Charsets.UTF_8))
        JSONObject().apply {
            put("t", "e2e_msg")
            put("id", id)
            put("from", localAdvertisedName())
            put("target", target)
            put("ttl", DEFAULT_TTL)
            put("epk", Base64.encodeToString(ephemeral.public.encoded, Base64.NO_WRAP))
            put("n", Base64.encodeToString(nonce, Base64.NO_WRAP))
            put("c", Base64.encodeToString(cipher, Base64.NO_WRAP))
        }.toString().toByteArray(Charsets.UTF_8)
    }.getOrNull()

    private fun decryptE2e(json: JSONObject): String? = runCatching {
        val ephemeral = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(json.getString("epk"), Base64.NO_WRAP))
        )
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ensureIdentityKeyPair().private)
        ka.doPhase(ephemeral, true)
        val key = MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
        val nonce = Base64.decode(json.getString("n"), Base64.NO_WRAP)
        val cipher = Base64.decode(json.getString("c"), Base64.NO_WRAP)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }.doFinal(cipher)
        String(plain, Charsets.UTF_8)
    }.getOrNull()

    private fun announceLocalKeyToConnectedPeers(exclude: String? = null) {
        val publicKey = ensureIdentityKeyPair().public.encoded
        val packet = JSONObject().apply {
            put("t", "key_announce")
            put("id", UUID.randomUUID().toString())
            put("name", localAdvertisedName())
            put("pk", Base64.encodeToString(publicKey, Base64.NO_WRAP))
            put("ttl", KEY_ANNOUNCE_TTL)
        }.toString().toByteArray(Charsets.UTF_8)
        seenRoutePackets.add(JSONObject(String(packet, Charsets.UTF_8)).getString("id"))
        connections.keys.forEach { address ->
            if (address != exclude && sessions[address]?.key != null) connections[address]?.let { sendPacket(address, packet, it) }
        }
    }

    private fun sendHopEncrypted(address: String, text: String, id: String, from: String, ttl: Int) {
        val session = sessions[address] ?: return
        val key = session.key ?: return
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
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
        if (packet.size > MAX_PACKET) return
        Thread { sendPacketBlocking(address, packet, transport) }.start()
    }

    @SuppressLint("MissingPermission")
    private fun sendPacketBlocking(address: String, packet: ByteArray, transport: Any) {
        val total = (packet.size + FRAME_PAYLOAD - 1) / FRAME_PAYLOAD
        if (total > 255) return
        val id = (packet.contentHashCode() xor System.nanoTime().toInt()) and 0xffff
        for (seq in 0 until total) {
            val start = seq * FRAME_PAYLOAD
            val end = minOf(packet.size, start + FRAME_PAYLOAD)
            val frame = ByteArray(4 + end - start)
            ByteBuffer.wrap(frame).apply {
                putShort(id.toShort())
                put(seq.toByte())
                put(total.toByte())
                put(packet, start, end - start)
            }
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

    private fun ensureIdentityKeyPair(): KeyPair {
        val prefs = context.getSharedPreferences("bang_mesh", Context.MODE_PRIVATE)
        val privateB64 = prefs.getString("identity_private", null)
        val publicB64 = prefs.getString("identity_public", null)
        if (privateB64 != null && publicB64 != null) {
            return runCatching {
                val kf = KeyFactory.getInstance("EC")
                val privateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.decode(privateB64, Base64.NO_WRAP)))
                val publicKey = kf.generatePublic(X509EncodedKeySpec(Base64.decode(publicB64, Base64.NO_WRAP)))
                KeyPair(publicKey, privateKey)
            }.getOrElse {
                prefs.edit().remove("identity_private").remove("identity_public").apply()
                saveIdentityKeyPair(newKeyPair(), prefs)
            }
        }
        return saveIdentityKeyPair(newKeyPair(), prefs)
    }

    private fun saveIdentityKeyPair(pair: KeyPair, prefs: android.content.SharedPreferences): KeyPair {
        prefs.edit()
            .putString("identity_private", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString("identity_public", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .apply()
        return pair
    }

    private fun deriveKey(local: KeyPair, remoteEncoded: ByteArray): ByteArray {
        val remote = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remoteEncoded))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(local.private)
        ka.doPhase(remote, true)
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
    }

    private fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }.doFinal(plain)
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
