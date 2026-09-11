package com.bang.offlinechat

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var mesh: MeshManager
    private val meshPermissionRequest = 7001
    private val audioPermissionRequest = 7002
    private var pendingAudioAction: (() -> Unit)? = null
    private lateinit var wifiCalls: WifiDirectCallManager
    private lateinit var audio: AudioCallEngine
    private lateinit var relay: RelayAudioEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = AudioCallEngine(this)
        relay = RelayAudioEngine()
        wifiCalls = WifiDirectCallManager(this, object : WifiDirectCallManager.Listener {
            override fun onStatus(text: String) = sendMeshEvent("wifi_status", text)
            override fun onGroupOwner(host: String) = sendMeshEvent("wifi_host", host)
            override fun onPeer(name: String, address: String) = sendMeshPeer(MeshManager.Peer(address, name))
            override fun onError(text: String) = sendMeshEvent("error", text)
        })
        mesh = MeshManager(this, object : MeshManager.Listener {
            override fun onStatus(text: String) = sendMeshEvent("status", text)
            override fun onPeerFound(peer: MeshManager.Peer) = sendMeshPeer(peer)
            override fun onPeerConnected(peer: MeshManager.Peer) = sendMeshEvent("peer_connected", peer.name)
            override fun onMessage(text: String, peer: MeshManager.Peer) = sendMeshMessage(text, peer.name)
            override fun onError(text: String) = sendMeshEvent("error", text)
            override fun onCallEvent(event: String, from: String, target: String) = sendCallEvent(event, from, target)
        })

        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val api = BuildConfig.BANG_API_URL
                if (api.isNotBlank()) {
                    val escaped = api.replace("\\", "\\\\").replace("'", "\\'")
                    view.evaluateJavascript("localStorage.setItem('bang_api','$escaped');", null)
                }
            }
        }
        web.addJavascriptInterface(MeshBridge(), "BangMesh")
        web.loadUrl("file:///android_asset/index.html")
    }

    @JavascriptInterface
    inner class MeshBridge {
        @JavascriptInterface fun start() = runOnUiThread { requestMeshPermissionsAndStart() }
        @JavascriptInterface fun stop() = runOnUiThread { mesh.stop() }
        @JavascriptInterface fun send(text: String) = mesh.send(text)
        @JavascriptInterface fun peers(): String = JSONObject().put("peers", mesh.peerList().map { JSONObject().put("name", it.name).put("address", it.address) }).toString()
        @JavascriptInterface fun call(targetName: String) = runOnUiThread { mesh.callTarget(targetName) }
        @JavascriptInterface fun endCall(targetName: String) = runOnUiThread { mesh.endCall(targetName); audio.stop(); stopCallService() }
        @JavascriptInterface fun startWifiCalls() = runOnUiThread { requestAudioPermissionsAndStartWifi() }
        @JavascriptInterface fun startAudioServer() = runOnUiThread { requestAudioPermissions { startCallService(); audio.startServer({ sendCallEvent("audio_connected", "", "") }) } }
        @JavascriptInterface fun connectAudio(host: String) = runOnUiThread { requestAudioPermissions { startCallService(); audio.connect(host, { sendCallEvent("audio_connected", "", host) }) } }
        @JavascriptInterface fun stopAudio() = runOnUiThread { audio.stop(); stopCallService() }
        @JavascriptInterface fun startRelay() = runOnUiThread { relay.start { text -> sendMeshEvent("relay_status", text) } }
        @JavascriptInterface fun stopRelay() = runOnUiThread { relay.stop(); sendMeshEvent("relay_status", "A→B→C relay stopped") }
        @JavascriptInterface fun connect(address: String) = runOnUiThread {
            mesh.peerList().firstOrNull { it.address == address }?.let { mesh.connect(it) }
        }
    }

    private fun requestAudioPermissions(afterGranted: () -> Unit) {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.NEARBY_WIFI_DEVICES
        if (missing.isNotEmpty()) {
            pendingAudioAction = afterGranted
            requestPermissions(missing.toTypedArray(), audioPermissionRequest)
            return
        }
        afterGranted()
    }

    private fun requestAudioPermissionsAndStartWifi() {
        requestAudioPermissions { wifiCalls.start() }
    }

    private fun requestAudioPermissionsAndConnect(host: String) {
        requestAudioPermissions { audio.connect(host, { sendCallEvent("audio_connected", "", host) }) }
    }

    private fun startCallService() {
        val intent = Intent(this, BangCallService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopCallService() {
        stopService(Intent(this, BangCallService::class.java))
    }

    private fun requestMeshPermissionsAndStart() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).forEach {
                if (checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) missing += it
            }
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), meshPermissionRequest)
            return
        }
        enableBluetoothIfNeeded()
    }

    private fun enableBluetoothIfNeeded() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && !adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        mesh.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val action = pendingAudioAction
            pendingAudioAction = null
            action?.invoke() ?: wifiCalls.start()
        } else if (requestCode == audioPermissionRequest) {
            pendingAudioAction = null
            sendMeshEvent("error", "Microphone/Nearby permission was not granted.")
        } else if (requestCode == meshPermissionRequest && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            enableBluetoothIfNeeded()
        } else if (requestCode == meshPermissionRequest) {
            sendMeshEvent("error", "Nearby devices permission was not granted.")
        }
    }

    private fun sendMeshEvent(type: String, text: String) {
        runOnUiThread {
            val js = "window.bangMeshEvent && window.bangMeshEvent(${JSONObject.quote(type)},${JSONObject.quote(text)});"
            if (::web.isInitialized) web.evaluateJavascript(js, null)
        }
    }

    private fun sendMeshPeer(peer: MeshManager.Peer) {
        runOnUiThread {
            val js = "window.bangMeshPeer && window.bangMeshPeer(${JSONObject.quote(peer.name)},${JSONObject.quote(peer.address)});"
            if (::web.isInitialized) web.evaluateJavascript(js, null)
        }
    }

    private fun sendCallEvent(event: String, from: String, target: String) {
        runOnUiThread {
            val js = "window.bangCallEvent && window.bangCallEvent(${JSONObject.quote(event)},${JSONObject.quote(from)},${JSONObject.quote(target)});"
            if (::web.isInitialized) web.evaluateJavascript(js, null)
        }
    }

    private fun sendMeshMessage(text: String, peerName: String) {
        runOnUiThread {
            val js = "window.bangMeshMessage && window.bangMeshMessage(${JSONObject.quote(text)},${JSONObject.quote(peerName)});"
            if (::web.isInitialized) web.evaluateJavascript(js, null)
        }
    }

override fun onBackPressed() {
    if (web.canGoBack()) {
        web.goBack()
    } else {
        super.onBackPressed()
    }
}

    override fun onDestroy() {
        audio.stop()
        stopCallService()
        relay.stop()
        wifiCalls.stop()
        mesh.stop()
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
