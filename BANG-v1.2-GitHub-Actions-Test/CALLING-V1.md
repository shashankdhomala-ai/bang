# BANG Calling v1

Calling is a separate real-time transport layer. BLE remains the discovery/control channel; it is not used as the continuous voice-audio pipe.

## Current build
- Adds a Calls section to the BANG UI.
- Shows nearby BANG devices discovered by Mesh v1.
- Does not pretend BLE can carry reliable live audio.

## Next implementation
1. Establish a direct peer transport over Wi-Fi Direct / local Wi-Fi.
2. Capture microphone audio with Android AudioRecord.
3. Encode low-bitrate voice frames.
4. Add jitter buffering, packet-loss handling and call controls.
5. Encrypt the audio transport end-to-end.
6. Integrate call setup with mesh discovery/routing.

For a true offline BANG call, the audio path must not depend on the central BANG server.
