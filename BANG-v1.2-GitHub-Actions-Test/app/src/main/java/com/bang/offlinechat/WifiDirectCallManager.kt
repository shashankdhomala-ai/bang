package com.bang.offlinechat

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import java.net.InetAddress

/** Wi-Fi Direct transport helper for BANG voice calls. */
class WifiDirectCallManager(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(text: String)
        fun onGroupOwner(host: String)
        fun onPeer(name: String, address: String)
        fun onError(text: String)
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)
    private var receiver: BroadcastReceiver? = null
    private var registered = false

    fun start() {
        if (registered) return
        if (!context.packageManager.hasSystemFeature("android.hardware.wifi.direct")) {
            listener.onError("This phone does not support Wi-Fi Direct.")
            return
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        listener.onStatus(if (enabled) "Wi-Fi Direct ready" else "Wi-Fi Direct is off")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
        discover()
    }

    fun stop() {
        if (registered) runCatching { receiver?.let { context.unregisterReceiver(it) } }
        registered = false
        receiver = null
        runCatching { manager.stopPeerDiscovery(channel, null) }
    }

    fun discover() {
        if (!hasPermission()) { listener.onError("Allow Nearby devices for Wi-Fi Direct."); return }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { listener.onStatus("Searching for BANG call peers…") }
            override fun onFailure(reason: Int) { listener.onError("Wi-Fi Direct discovery failed: $reason") }
        })
    }

    fun connect(address: String) {
        if (!hasPermission()) { listener.onError("Allow Nearby devices for Wi-Fi Direct."); return }
        val config = WifiP2pConfig().apply { deviceAddress = address }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { listener.onStatus("Connecting voice transport…") }
            override fun onFailure(reason: Int) { listener.onError("Wi-Fi Direct connect failed: $reason") }
        })
    }

    private fun requestPeers() {
        if (!hasPermission()) return
        manager.requestPeers(channel) { list -> list.deviceList.forEach { listener.onPeer(it.deviceName ?: "BANG device", it.deviceAddress) } }
    }

    private fun requestConnection() {
        if (!hasPermission()) return
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            if (info.groupFormed) {
                val host = info.groupOwnerAddress?.hostAddress
                if (!host.isNullOrBlank()) listener.onGroupOwner(host)
            }
        }
    }

    private fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    } else true
}
