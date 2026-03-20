# Design: Raw Wire Logging in YamuxSession

**Date:** 2026-03-19
**Status:** Approved
**Context:** `filesGet` returns count=0; Go filenode shows 0 rpcLog entries for Android peer; 7 anomalous bytes `02020000000801` received. Root cause unknown — need to see the exact bytes on the TCP wire before yamux frame parsing.

---

## Goal

Add read-side and write-side raw byte logging to `YamuxSession` so every byte transiting the `rawSocket` is hexdumped to logcat. This is purely observational — no protocol logic changes.

---

## What We Confirmed Before This Design

- **rawSocket/TLS hypothesis ruled out**: anytype-heart (`net/transport/yamux/yamux.go Dial()`) confirms Go uses the original raw TCP conn for yamux, not the TLS conn. Android already does the same.
- **DRPC path fixed (2026-03-19)**: leading slash added to invoke path.
- **FIN handling fixed (2026-03-19)**: `handleWindowUpdateFrame` now synthesizes DATA|FIN when FIN flag is set.
- **7-byte response parsed**: `02 02 00 00 00 08 01` → KindInvoke(streamId=2, msgId=0, data=[]) + 3 unparseable bytes. `parseMessagePayloads` skips KindInvoke and fails on remaining 3 bytes → empty list → count=0.
- **Filenode shows 0 rpcLog entries** for Android peer → DRPC handler never ran for Android's request.

---

## Design

### Logging Wrappers

Two thin pass-through stream decorators added inside `YamuxSession`:

**`LoggingInputStream`** — wraps `rawSocket.getInputStream()`. After each `read()` / `read(buf, off, len)` call returns bytes, logs them as hex at `Log.v(TAG, "WIRE_IN hex=...")`. Chunks output at 256 bytes of hex per log line to stay within logcat limits.

**`LoggingOutputStream`** — wraps `rawSocket.getOutputStream()`. Before each `write()` / `write(buf, off, len)` forwards bytes, logs them as hex at `Log.v(TAG, "WIRE_OUT hex=...")`.

Both wrappers are defined as private inner classes in `YamuxSession.kt`. They add zero buffering and do not alter bytes or timing.

### Integration Point

In `YamuxSession.init` (or `start()`), replace:
```kotlin
val inputStream  = socket.getInputStream()
val outputStream = socket.getOutputStream()
```
with:
```kotlin
val inputStream  = LoggingInputStream(socket.getInputStream())
val outputStream = LoggingOutputStream(socket.getOutputStream())
```

### Log Interpretation Table

| WIRE_IN shows | Conclusion |
|---|---|
| 12 bytes `00 01 00 02 00 00 00 01 ...` | Go sent SYN-ACK (WINDOW_UPDATE\|ACK) — yamux healthy |
| 7 raw bytes `02 02 00 00 00 08 01` | 7 bytes arrived before yamux framing — not yamux-framed; problem is upstream of yamux |
| 0 bytes then connection closed | Go rejected the connection immediately |
| Valid yamux frames then FIN | Yamux OK; stream data routing issue in Android frame reader |

### Removal

Wrappers are removed once root cause is identified and fixed. They are not part of any production build.

---

## Scope

- **File changed:** `YamuxSession.kt` only
- **Lines added:** ~40
- **Risk:** None — purely observational, no logic changes
