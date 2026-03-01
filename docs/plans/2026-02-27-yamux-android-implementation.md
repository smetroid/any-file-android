# Yamux Android Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Implement yamux protocol in any-file-android to enable communication with any-sync infrastructure

**Architecture:** Custom yamux implementation with DRPC over yamux streams, replacing HTTP/protobuf approach

**Tech Stack:** Kotlin, coroutines, protobuf, custom yamux protocol, TLS, DRPC

---

## Context

### Problem Statement

**Current Situation:**
- Go E2E tests: 13/13 passing ✅ - Use yamux protocol over TCP connections
- Android E2E tests: 2/7 passing ❌ - Use HTTP/protobuf, but infrastructure only supports yamux/quic
- Root cause: Protocol mismatch between Android clients and any-sync services

**Evidence:**
- curl to coordinator:1004 returns "Empty reply from server" (no HTTP endpoint)
- Coordinator/filenode only expose TCP/UDP ports (1004-1005), no HTTP ports
- Go clients successfully connect using yamux over TCP

### Go Implementation Pattern

```
TCP Dial → TLS Handshake → Yamux Session → Open Streams → DRPC over Streams
```

### Critical Files (Go Reference)

- `internal/p2p/yamux_client.go` - Yamux session wrapper with MultiConn interface
- `internal/coordinator/client.go` - Coordinator connection pattern
- `internal/filenode/yamux_client.go` - Filenode connection pattern
- `pkg/drpc/client.go` - DRPC client over streams

### Critical Files (Android - to modify)

- `app/src/main/java/com/anyproto/anyfile/data/network/CoordinatorClient.kt` - Current HTTP implementation
- `app/src/main/java/com/anyproto/anyfile/data/network/FilenodeClient.kt` - Current HTTP implementation
- `app/src/androidTest/java/com/anyproto/anyfile/e2e/TestSetup.kt` - Port forwarding configuration
- `app/src/main/java/com/anyproto/anyfile/di/NetworkModule.kt` - DI configuration

---

## Architecture

### Library Choice: Custom Implementation

**Why Custom Implementation:**
1. No production-ready Kotlin yamux library (jvm-libp2p's yamux is prototype/beta)
2. Yamux has a relatively simple binary protocol (~500 lines of Kotlin)
3. Ensures exact compatibility with Go yamux implementation
4. DRPC integration requires specific protocol handling
5. Reduces external dependencies

### Connection Management Pattern

**Singleton YamuxConnectionManager with Per-Service Sessions:**

```
┌─────────────────────────────────────────────────────────────┐
│                  YamuxConnectionManager                     │
│  - Manages TCP connections                                  │
│  - Handles TLS handshakes                                   │
│  - Creates yamux sessions                                   │
│  - Provides stream factory                                  │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
    ┌────▼────┐         ┌────▼────┐         ┌────▼────┐
    │Coordinator│       │ Filenode  │       │   Node    │
    │  Session  │       │  Session  │       │  Session  │
    └───────────┘       └───────────┘       └───────────┘
```

---

## Implementation Tasks

### Task 1: Yamux Protocol Implementation

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxProtocol.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxFrame.kt`
- Test: `app/src/test/java/com/anyproto/anyfile/data/network/yamux/YamuxProtocolTest.kt`

**YamuxFrame.kt data class:**
```kotlin
sealed class YamuxFrame {
    enum class Type(val value: Byte) {
        DATA(0x00),
        WINDOW_UPDATE(0x01),
        PING(0x02),
        GO_AWAY(0x03)
    }

    data class Data(
        val streamId: Int,
        val flags: Byte,
        val length: Int,
        val data: ByteArray
    ) : YamuxFrame()

    data class WindowUpdate(
        val streamId: Int,
        val flags: Byte,
        val delta: Int
    ) : YamuxFrame()

    data class Ping(
        val streamId: Int,
        val flags: Byte,
        val value: Int
    ) : YamuxFrame()

    data class GoAway(
        val flags: Byte,
        val errorCode: Int,
        val lastStreamId: Int
    ) : YamuxFrame()
}
```

**Step 1: Write failing test for frame encoding**
```kotlin
@Test
fun `encode data frame correctly`() {
    val frame = YamuxFrame.Data(
        streamId = 1,
        flags = 0x00,
        length = 5,
        data = byteArrayOf(1, 2, 3, 4, 5)
    )
    val encoded = YamuxProtocol.encodeFrame(frame)
    assertEquals(0x00, encoded[0]) // Version
    assertEquals(0x00, encoded[1]) // Type: DATA
}
```

**Step 2: Run test to verify it fails**
```bash
./gradlew test --tests YamuxProtocolTest
```
Expected: FAIL with "YamuxProtocol not implemented"

**Step 3: Implement minimal frame encoding**
```kotlin
object YamuxProtocol {
    private const val PROTOCOL_VERSION = 0x00.toByte()

    fun encodeFrame(frame: YamuxFrame): ByteArray {
        return when (frame) {
            is YamuxFrame.Data -> encodeDataFrame(frame)
            // ... other frame types
        }
    }

    private fun encodeDataFrame(frame: YamuxFrame.Data): ByteArray {
        val buffer = ByteBuffer.allocate(12 + frame.length).order(ByteOrder.BIG_ENDIAN)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(YamuxFrame.Type.DATA.value)
        buffer.putShort(frame.flags.toShort())
        buffer.putInt(frame.length)
        buffer.putInt(frame.streamId)
        buffer.put(frame.data)
        return buffer.array()
    }
}
```

**Step 4: Run test to verify it passes**
```bash
./gradlew test --tests YamuxProtocolTest
```
Expected: PASS

**Step 5: Commit**
```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/yamux/
git add app/src/test/java/com/anyproto/anyfile/data/network/yamux/
git commit -m "feat: implement yamux protocol frame encoding/decoding"
```

---

### Task 2: TLS Configuration Provider

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/tls/TlsConfigProvider.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/tls/AnySyncHandshake.kt`
- Test: `app/src/test/java/com/anyproto/anyfile/data/network/tls/TlsConfigProviderTest.kt`

**Reference Go implementation:** `pkg/tls/config.go`

**Step 1: Write failing test for TLS socket creation**
```kotlin
@Test
fun `create TLS socket for coordinator`() {
    val provider = TlsConfigProvider()
    val socket = provider.createTlsSocket("localhost", 1004)
    assertNotNull(socket)
    assertTrue(socket.isConnected)
}
```

**Step 2: Run test to verify it fails**
```bash
./gradlew test --tests TlsConfigProviderTest
```
Expected: FAIL with "TlsConfigProvider not implemented"

**Step 3: Implement TLS socket creation**
```kotlin
class TlsConfigProvider @Inject constructor() {
    private val sslContext: SSLContext = SSLContext.getInstance("TLSv1.3")

    fun createTlsSocket(host: String, port: Int): Socket {
        val socket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        return socket
    }
}
```

**Step 4: Run test to verify it passes**
```bash
./gradlew test --tests TlsConfigProviderTest
```
Expected: PASS

**Step 5: Commit**
```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/tls/
git commit -m "feat: add TLS configuration provider"
```

---

### Task 3: Yamux Session Implementation

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxSession.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxStream.kt`
- Modify: `app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxProtocol.kt` (add session methods)

**Reference Go implementation:** `internal/p2p/yamux_client.go`

**Step 1: Write failing test for session creation**
```kotlin
@Test
fun `create yamux session over TLS`() = runTest {
    val tlsProvider = TlsConfigProvider()
    val socket = tlsProvider.createTlsSocket("localhost", 1004)
    val session = YamuxSession(socket)
    assertTrue(session.isActive)
}
```

**Step 2: Run test to verify it fails**
```bash
./gradlew test --tests YamuxSessionTest
```
Expected: FAIL with "YamuxSession not implemented"

**Step 3: Implement minimal YamuxSession**
```kotlin
class YamuxSession(
    private val socket: Socket,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val streams = ConcurrentHashMap<Int, YamuxStream>()
    private val nextStreamId = AtomicInteger(1)
    private val incomingChannel = Channel<YamuxStream>(capacity = Channel.UNLIMITED)

    val isActive: Boolean
        get() = !socket.isClosed

    suspend fun openStream(): YamuxStream {
        val streamId = nextStreamId.getAndIncrement()
        val stream = YamuxStream(streamId, socket)
        streams[streamId] = stream
        return stream
    }

    fun start() {
        scope.launch {
            while (isActive) {
                val frame = YamuxProtocol.readFrame(socket.getInputStream())
                handleFrame(frame)
            }
        }
    }

    private fun handleFrame(frame: YamuxFrame) {
        when (frame) {
            is YamuxFrame.Data -> handleDataFrame(frame)
            // ... other frame types
        }
    }
}
```

**Step 4: Run test to verify it passes**
```bash
./gradlew test --tests YamuxSessionTest
```
Expected: PASS

**Step 5: Commit**
```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/yamux/
git commit -m "feat: implement yamux session management"
```

---

### Task 4: DRPC Client Implementation

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/drpc/DrpcClient.kt`
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/drpc/DrpcMessage.kt`
- Test: `app/src/test/java/com/anyproto/anyfile/data/network/drpc/DrpcClientTest.kt`

**Reference Go implementation:** `pkg/drpc/client.go`

**Step 1: Write failing test for DRPC call**
```kotlin
@Test
fun `encode DRPC call message`() {
    val message = DrpcMessage.Call(
        serviceId = "coordinator",
        methodId = "SpaceSign",
        payload = byteArrayOf(1, 2, 3)
    )
    val encoded = DrpcClient.encodeMessage(message)
    assertNotNull(encoded)
    assertTrue(encoded.isNotEmpty())
}
```

**Step 2: Run test to verify it fails**
```bash
./gradlew test --tests DrpcClientTest
```
Expected: FAIL with "DrpcClient not implemented"

**Step 3: Implement DRPC encoding**
```kotlin
object DrpcClient {
    suspend fun call(
        stream: YamuxStream,
        serviceId: String,
        methodId: String,
        request: MessageLite,
        responseType: Class<out MessageLite>
    ): MessageLite = withContext(Dispatchers.IO) {
        val call = DrpcMessage.Call(
            serviceId = serviceId,
            methodId = methodId,
            payload = request.toByteArray()
        )
        stream.write(encodeMessage(call))

        val responseBytes = stream.read()
        parseResponse(responseBytes, responseType)
    }

    fun encodeMessage(message: DrpcMessage): ByteArray {
        // Implement DRPC binary encoding
        val buffer = ByteBuffer.allocate(4 + message.payload.size)
        buffer.putInt(message.payload.size)
        buffer.put(message.payload)
        return buffer.array()
    }
}
```

**Step 4: Run test to verify it passes**
```bash
./gradlew test --tests DrpcClientTest
```
Expected: PASS

**Step 5: Commit**
```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/drpc/
git commit -m "feat: implement DRPC client"
```

---

### Task 5: Yamux Connection Manager

**Files:**
- Create: `app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxConnectionManager.kt`
- Modify: `app/src/main/java/com/anyproto/anyfile/di/NetworkModule.kt` (add provider)

**Step 1: Write failing test for connection manager**
```kotlin
@Test
fun `get or create coordinator session`() = runTest {
    val manager = YamuxConnectionManager()
    val session1 = manager.getSession("localhost", 1004)
    val session2 = manager.getSession("localhost", 1004)
    assertSame(session1, session2) // Should return cached session
}
```

**Step 2: Run test to verify it fails**
```bash
./gradlew test --tests YamuxConnectionManagerTest
```
Expected: FAIL with "YamuxConnectionManager not implemented"

**Step 3: Implement connection manager**
```kotlin
@Singleton
class YamuxConnectionManager @Inject constructor(
    private val tlsProvider: TlsConfigProvider
) {
    private val sessions = ConcurrentHashMap<String, YamuxSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getSession(host: String, port: Int): YamuxSession {
        val key = "$host:$port"
        return sessions.getOrPut(key) {
            val socket = tlsProvider.createTlsSocket(host, port)
            val session = YamuxSession(socket, scope)
            session.start()
            session
        }
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
```

**Step 4: Run test to verify it passes**
```bash
./gradlew test --tests YamuxConnectionManagerTest
```
Expected: PASS

**Step 5: Update NetworkModule.kt**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideYamuxConnectionManager(
        tlsProvider: TlsConfigProvider
    ): YamuxConnectionManager {
        return YamuxConnectionManager(tlsProvider)
    }
}
```

**Step 6: Run tests to verify it passes**
```bash
./gradlew test
```
Expected: PASS

**Step 7: Commit**
```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxConnectionManager.kt
git add app/src/main/java/com/anyproto/anyfile/di/NetworkModule.kt
git commit -m "feat: add yamux connection manager with DI"
```

---

### Task 6: Update CoordinatorClient to Use Yamux

**Files:**
- Modify: `app/src/main/java/com/anyproto/anyfile/data/network/CoordinatorClient.kt`

**Step 1: Update initialize method**
```kotlin
@Singleton
class CoordinatorClient @Inject constructor(
    private val connectionManager: YamuxConnectionManager,
) {
    private var coordinatorSession: YamuxSession? = null

    fun initialize(host: String, port: Int) {
        this.coordinatorSession = connectionManager.getSession(host, port)
    }
}
```

**Step 2: Update signSpace method**
```kotlin
suspend fun signSpace(
    spaceId: String,
    header: ByteArray,
    forceRequest: Boolean = false,
): Result<SpaceSignResult> = withContext(Dispatchers.IO) {
    try {
        ensureInitialized()

        val session = coordinatorSession ?: throw IllegalStateException("No session")

        // Build protobuf request
        val requestProto = SpaceSignRequest.newBuilder()
            .setSpaceId(spaceId)
            .setHeader(com.google.protobuf.ByteString.copyFrom(header))
            .setForceRequest(forceRequest)
            .build()

        // Open stream and make DRPC call
        val stream = session.openStream()
        val responseProto = DrpcClient.call(
            stream,
            "coordinator",
            "SpaceSign",
            requestProto,
            SpaceSignResponse::class.java
        )

        // Parse response
        val receiptProto = responseProto.receipt
        val receiptBytes = receiptProto.spaceReceiptPayload.toByteArray()
        val signature = receiptProto.signature.toByteArray()

        val receipt = com.anyproto.anyfile.protos.SpaceReceipt.parseFrom(receiptBytes)
        val spaceReceipt = com.anyproto.anyfile.data.network.model.SpaceReceipt.fromProto(receipt)

        Result.success(SpaceSignResult(spaceReceipt, signature))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

**Step 3: Remove HTTP-specific code**
- Remove OkHttp dependency
- Remove postProto() method
- Remove parseProtoResponse() method
- Remove baseUrl field

**Step 4: Run unit tests**
```bash
./gradlew test
```
Expected: PASS (or update tests that depend on HTTP)

**Step 5: Commit**
```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/CoordinatorClient.kt
git commit -m "feat: migrate CoordinatorClient to yamux+DRPC"
```

---

### Task 7: Update FilenodeClient to Use Yamux

**Files:**
- Modify: `app/src/main/java/com/anyproto/anyfile/data/network/FilenodeClient.kt`

**Step 1: Follow same pattern as CoordinatorClient**
```kotlin
@Singleton
class FilenodeClient @Inject constructor(
    private val connectionManager: YamuxConnectionManager,
) {
    private var filenodeSession: YamuxSession? = null

    fun initialize(host: String, port: Int) {
        this.filenodeSession = connectionManager.getSession(host, port)
    }

    // Update all methods to use DRPC over yamux streams
}
```

**Step 2: Run unit tests**
```bash
./gradlew test
```
Expected: PASS

**Step 3: Commit**
```bash
git add app/src/main/java/com/anyproto/anyfile/data/network/FilenodeClient.kt
git commit -m "feat: migrate FilenodeClient to yamux+DRPC"
```

---

### Task 8: Update Test Configuration

**Files:**
- Modify: `app/src/androidTest/java/com/anyproto/anyfile/e2e/TestSetup.kt`
- Modify: `app/src/androidTest/java/com/anyproto/anyfile/di/TestNetworkModule.kt`

**Step 1: Update EmulatorPortForwarding to return TCP addresses**
```kotlin
object EmulatorPortForwarding {
    fun getCoordinatorAddress(): String = "localhost"  // Remove "http://"
    fun getCoordinatorPort(): Int = 11004
    fun getFilenodeAddress(): String = "localhost"    // Remove "http://"
    fun getFilenodePort(): Int = 11005
}
```

**Step 2: Update client initialization in tests**
```kotlin
@Before
fun setup() {
    // Old: coordinatorClient.initialize("http://localhost:11004")
    coordinatorClient.initialize(
        EmulatorPortForwarding.getCoordinatorAddress(),
        EmulatorPortForwarding.getCoordinatorPort()
    )
}
```

**Step 3: Run Android E2E tests**
```bash
cd /Users/kike/projects/anyproto/any-file-android
./test-emulator-e2e.sh anyfile_emu
```
Expected: All 7 tests PASS

**Step 4: Commit**
```bash
git add app/src/androidTest/java/com/anyproto/anyfile/e2e/
git commit -m "test: update E2E tests to use yamux addresses"
```

---

### Task 9: Integration Testing with Real Infrastructure

**Files:**
- Modify: `app/src/androidTest/java/com/anyproto/anyfile/e2e/InfrastructureTest.kt`

**Step 1: Add connection test**
```kotlin
@Test
fun testYamuxConnection() = runTest {
    val session = connectionManager.getSession("localhost", 11004)
    assertTrue(session.isActive)

    val stream = session.openStream()
    assertNotNull(stream)
}
```

**Step 2: Add DRPC test**
```kotlin
@Test
fun testCoordinatorDrpcCall() = runTest {
    coordinatorClient.initialize("localhost", 11004)

    val result = coordinatorClient.getNetworkConfiguration()
    assertTrue(result.isSuccess)

    val config = result.getOrNull()
    assertNotNull(config)
    assertTrue(config.nodes.isNotEmpty())
}
```

**Step 3: Run full E2E test suite**
```bash
cd /Users/kike/projects/anyproto/any-file-android
./test-emulator-e2e.sh anyfile_emu
```
Expected: All 7 tests PASS

**Step 4: Commit**
```bash
git add app/src/androidTest/java/com/anyproto/anyfile/e2e/
git commit -m "test: add yamux integration tests"
```

---

### Task 10: Documentation and Cleanup

**Files:**
- Create: `docs/yamux-android-implementation-notes.md`
- Update: `docs/E2E_AUTOMATION_PROGRESS.md`

**Step 1: Document yamux implementation**
```markdown
# Yamux Android Implementation Notes

## Overview
Custom yamux protocol implementation for Android to communicate with any-sync infrastructure.

## Architecture
- YamuxProtocol: Binary protocol encoding/decoding
- YamuxSession: Session management over TLS
- YamuxStream: Multiplexed stream I/O
- DrpcClient: DRPC protocol over yamux streams
- YamuxConnectionManager: Singleton connection manager

## Usage
```kotlin
val session = connectionManager.getSession("localhost", 1004)
val stream = session.openStream()
val response = DrpcClient.call(stream, "coordinator", "SpaceSign", request, Response::class.java)
```
```

**Step 2: Update progress documentation**
- Mark Android E2E tests as passing (7/7)
- Add yamux implementation to fixes applied
- Update timeline

**Step 3: Run final validation**
```bash
# Build
./gradlew assembleDebug

# Unit tests
./gradlew test

# E2E tests (with infrastructure running)
cd ../any-file
docker-compose up -d
cd ../any-file-android
./test-emulator-e2e.sh anyfile_emu
```
Expected: All pass

**Step 4: Commit**
```bash
git add docs/
git commit -m "docs: add yamux implementation documentation"
```

---

## Verification

After completing all tasks, run the following to verify:

```bash
# 1. Build APK
cd /Users/kike/projects/anyproto/any-file-android
./gradlew assembleDebug

# 2. Run unit tests
./gradlew test

# 3. Start infrastructure
cd /Users/kike/projects/anyproto/any-file/docker
docker-compose up -d

# 4. Start emulator
$HOME/Library/Android/sdk/emulator/emulator -avd anyfile_emu -no-window -no-audio &

# 5. Run E2E tests
cd /Users/kike/projects/anyproto/any-file-android
./test-emulator-e2e.sh anyfile_emu
```

**Success Criteria:**
- All unit tests pass
- All 7 Android E2E tests pass
- No regression in existing functionality

---

## Rollback Plan

If implementation fails at any phase:

**Task 1-3 Failure (Protocol Layer):**
- Evaluate jvm-libp2p Mplex as alternative
- Consider creating protocol gateway in Go

**Task 4-5 Failure (Session/DRPC Layer):**
- Use protobuf directly over yamux streams without DRPC
- Investigate if Go services can support plain protobuf

**Task 6-7 Failure (Client Migration):**
- Revert to HTTP implementation
- Investigate gRPC-web as alternative approach

**Task 8-10 Failure (Testing):**
- Keep both implementations with feature flag
- Test against mock infrastructure first

---

## Dependencies

No new external dependencies required for custom yamux implementation.

Existing dependencies used:
- kotlinx-coroutines-core (for async operations)
- protobuf-kotlin-lite (for message serialization)
- javax.net.ssl (for TLS)

---

## Risks and Mitigation

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Yamux protocol complexity | Medium | High | Reference Go implementation, start minimal |
| TLS handshake issues | Low | Medium | Use standard Android TLS APIs |
| DRPC protocol differences | Medium | High | Implement based on Go source code |
| Android network restrictions | Low | Low | Test on emulator and device |
| Performance issues | Low | Medium | Implement connection pooling |

---

## References

- Go yamux implementation: `internal/p2p/yamux_client.go`
- Coordinator client: `internal/coordinator/client.go`
- Filenode client: `internal/filenode/yamux_client.go`
- DRPC client: `pkg/drpc/client.go`
- HashiCorp yamux spec: https://github.com/hashicorp/yamux/blob/master/spec.md
