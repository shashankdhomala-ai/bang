# BANG v1.0 — A→B→C live-audio foundation

This build adds the first real microphone/speaker transport. BLE remains the low-power discovery and signaling layer. Wi-Fi Direct is used for higher-bandwidth local transport, following Android's documented P2P model.

## What is real in this build
- RECORD_AUDIO permission and AudioRecord capture.
- AudioTrack playback at 16 kHz mono PCM, 20 ms frames.
- TCP framing over a local Wi-Fi Direct connection.
- AES-GCM packet protection when a call key is supplied.
- Wi-Fi Direct peer discovery and connection-info callbacks.

## A→B→C
For the first test, B acts as the relay/group owner. A and C join B's Wi-Fi Direct group. The next routing step is to add a relay socket on B that maps A↔C call streams while preserving the encrypted payload.

This is deliberately not presented as a finished production VoIP stack. Production calling should use a mature low-latency codec/transport (for example, Opus/WebRTC) plus robust call-key management, jitter buffering, packet loss handling, echo cancellation, and Android foreground-service/background rules.
