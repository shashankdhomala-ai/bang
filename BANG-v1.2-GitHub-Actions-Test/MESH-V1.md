# BANG Mesh v1

BANG Mesh v1 adds a native Android BLE prototype underneath the existing WebView UI.

## What works in this milestone

- BLE low-power advertising with a BANG service UUID.
- BLE low-power scanning for nearby BANG devices.
- Android 12+ Nearby devices permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`).
- A GATT server with RX/TX characteristics.
- GATT connection from one BANG phone to another.
- A small ECDH handshake and AES-GCM encrypted text transport.
- 16-byte BLE frame payloads with reassembly for small messages.
- WebView bridge so the existing BANG UI can start/stop mesh, connect to a peer, send messages, and receive messages.

## What is intentionally NOT finished yet

This is a mesh **v1 transport prototype**, not the final BANG network. It does not yet provide:

- A -> B -> C multi-hop routing.
- Store-and-forward queues.
- Offline message persistence across restarts.
- A Signal Protocol implementation.
- Large-file/media transfer.
- Always-on background operation.
- Production-grade identity/key verification or metadata protection.

The current cryptography is an initial authenticated-encryption transport test. Do not describe it as Signal-level E2EE yet.

## Testing

Use two physical Android phones with BLE enabled. Install/run the same debug build on both, open BANG, and tap **Start Mesh** on both. When a nearby BANG phone appears, tap **Connect**. Once the UI reports an encrypted mesh link, send a short text message.

BLE behavior varies by phone manufacturer, Android version, and Bluetooth chipset, so physical-device testing is required.
