# Wire Logging Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add read-side and write-side raw byte logging to `YamuxSession` to capture every byte transiting `rawSocket`, so we can see exactly what Go sends to Android before yamux frame parsing.

**Architecture:** Two private inner-class stream decorators (`LoggingInputStream`, `LoggingOutputStream`) replace the bare `socket.getInputStream()` / `socket.getOutputStream()` assignments at lines 123-124 of `YamuxSession.kt`. They pass all bytes through unchanged and log each chunk as hex at `Log.v`. No buffering, no timing changes, no logic changes.

**Tech Stack:** Kotlin, Android `android.util.Log`, `java.io.InputStream` / `OutputStream`

---

### Task 1: Add `LoggingInputStream` and `LoggingOutputStream` to `YamuxSession.kt`

**Files:**
- Modify: `app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxSession.kt:123-124`

**Step 1: Locate the I/O stream assignments**

Open `YamuxSession.kt`. Find lines 123-124:
```kotlin
private val inputStream: InputStream = socket.getInputStream()
private val outputStream: OutputStream = socket.getOutputStream()
```

**Step 2: Replace with logging wrappers**

Replace those two lines with:
```kotlin
private val inputStream: InputStream = LoggingInputStream(socket.getInputStream())
private val outputStream: OutputStream = LoggingOutputStream(socket.getOutputStream())
```

**Step 3: Add the two inner classes**

Add this block anywhere inside the `YamuxSession` class body (e.g. just before the closing `}` of the class, after the last existing method):

```kotlin
// ── Wire-level logging (remove after root-cause is identified) ─────────────

private inner class LoggingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) Log.v(TAG, "WIRE_IN hex=%02x".format(b))
        return b
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(buf, off, len)
        if (n > 0) logChunked("WIRE_IN", buf, off, n)
        return n
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

private inner class LoggingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) {
        Log.v(TAG, "WIRE_OUT hex=%02x".format(b))
        delegate.write(b)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        logChunked("WIRE_OUT", buf, off, len)
        delegate.write(buf, off, len)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

/**
 * Log [len] bytes from [buf] starting at [off], chunked to ≤64 bytes per line
 * to stay within logcat's line limit.
 */
private fun logChunked(tag: String, buf: ByteArray, off: Int, len: Int) {
    val chunkSize = 64
    var pos = off
    val end = off + len
    while (pos < end) {
        val count = minOf(chunkSize, end - pos)
        val hex = buf.copyOfRange(pos, pos + count).joinToString("") { "%02x".format(it) }
        Log.v(TAG, "$tag hex=$hex")
        pos += count
    }
}
```

**Step 4: Build the APK**

```bash
cd /Users/kike/projects/any-file-workspace/any-file-android
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`

**Step 5: Install and run**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Start the app, tap **Start** to begin sync.

**Step 6: Capture logcat and filter wire logs**

```bash
adb logcat -s YamuxSession:V | grep "WIRE_"
```

Let it run for ~15 seconds (one full `filesGet` poll cycle).

**Step 7: Interpret the output**

Copy the WIRE_IN and WIRE_OUT lines and decode them:

| Pattern | Meaning |
|---|---|
| `WIRE_OUT hex=000000010000000100000000` | Android sent yamux SYN for stream 1 (correct) |
| `WIRE_IN hex=000100020000000100010000` | Go sent SYN-ACK (WINDOW_UPDATE\|ACK, stream 1) — yamux handshake OK |
| `WIRE_IN hex=02020000000801...` | 7 raw bytes arrived before yamux framing — upstream issue |
| `WIRE_IN hex=00000...` (12+ bytes) | Full yamux DATA frame arrived — check data bytes inside |
| `WIRE_IN` shows nothing after WIRE_OUT | Go is not responding — connection rejected or silent |

**Step 8: Commit the debug build**

```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxSession.kt
git commit -m "debug: add wire-level hex logging to YamuxSession (temporary)"
```

---

### Task 2: Analyse and remove logging

Once the WIRE_IN output reveals the root cause:

1. Fix the identified issue (will be a separate plan).
2. Remove the two inner classes and the `logChunked` helper from `YamuxSession.kt`.
3. Revert the `inputStream` / `outputStream` assignments to the bare `socket.getInputStream()` / `socket.getOutputStream()` calls.
4. Build and confirm `filesGet count > 0`.
5. Commit:

```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxSession.kt
git commit -m "chore: remove temporary wire logging from YamuxSession"
```

---

## Expected Outcome

After Task 1 we will have unambiguous evidence of what bytes Go sends to Android at the TCP level. The WIRE_IN hex will either:

- **Confirm valid yamux frames** → the problem is in Android's frame parser or stream routing
- **Show 7 raw non-yamux bytes** → the connection is being intercepted or corrupted before yamux
- **Show nothing** → Go silently drops the connection (auth/ACL issue at filenode)

Task 2 is a placeholder — the actual fix will be designed once Task 1 output is in hand.
