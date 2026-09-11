# BANG A→B→C live-audio relay

## Topology

- A = caller endpoint
- B = relay phone / Wi-Fi Direct group owner
- C = callee endpoint

A and C establish TCP connections to B's local Wi-Fi Direct address on port 39871.
B's `RelayAudioEngine` accepts the first two peers and forwards the framed bytes in both directions.

The relay treats the stream as opaque bytes. It does not receive the AES call key and does not decode PCM.

## Current scope

- Two-peer relay slot (A and C) per B.
- Bidirectional byte forwarding.
- Existing AudioCallEngine framing is preserved.
- Web UI controls to start/stop relay.
- Android microphone foreground-service declaration is included for future call lifecycle integration.

## Important limitation

This is a transport relay foundation. Production calling still needs robust call-session negotiation, authenticated key exchange, reconnection/jitter buffering, codec support (for example Opus), congestion handling, and a complete Android Telecom/foreground-call lifecycle before Play Store release.
