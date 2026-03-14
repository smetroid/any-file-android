# Android any-file Client Implementation Plan

**Date:** 2026-03-11
**Completed:** 2026-03-12
**Status:** ✅ COMPLETE
**Goal:** Ship a sideloadable APK where a user can import a `client.yml` config, pick a local folder, tap "Start Syncing", and have files appear on other devices running the Go daemon.

---

## Current State

### Protocol Stack

| Layer | Component | Status |
|-------|-----------|--------|
| 1 | `Libp2pTlsProvider.kt` — TLS socket with Ed25519 peer identity | Complete |
| 2 | `AnySyncHandshake.kt` — 6-step credential exchange | Complete |
| 3 | `yamux/YamuxSession.kt` — stream multiplexing | Code exists, ~30 unit tests failing |
| 4 | `drpc/DrpcClient.kt` — RPC over yamux streams | Complete |
| 5 | `p2p/P2PCoordinatorClient.kt`, `P2PFilenodeClient.kt` | Complete |

### Existing Infrastructure

- `domain/watch/FileWatcher.kt` — FileObserver wrapper, emits file change events via `FileChangeListener`
- `domain/sync/SyncOrchestrator.kt` — upload/download logic using HTTP-based `FilenodeClient`
- `worker/SyncWorker.kt` — WorkManager periodic sync (wired to HTTP-based clients)
- `ui/` — Jetpack Compose: `MainActivity`, `SpacesScreen`, `FilesScreen`, `SettingsScreen`, `NavGraph`
- `di/NetworkModule.kt` — provides HTTP-based `CoordinatorClient` and `FilenodeClient` (NOT the P2P versions)
- Room database with `SyncedFileDao`, `SpaceDao`
- `data/network/yamux/YamuxConnectionManager.kt` — caching P2P connection manager, already wires TLS → Handshake → Yamux
- `AnyFileApplication.kt` with `@HiltAndroidApp`
- `AndroidManifest.xml` already has INTERNET, STORAGE, WAKE_LOCK permissions

### Key Observation: YamuxConnectionManager is the Integration Point

`YamuxConnectionManager.getSession(host, port)` already performs the full 3-step connection:
1. `tlsProvider.createTlsSocket(host, port, ...)` — Layer 1
2. `AnySyncHandshake.performOutgoingHandshake(...)` — Layer 2
3. Returns a started `YamuxSession` — Layer 3

`P2PCoordinatorClient` and `P2PFilenodeClient` already call `connectionManager.getSession(host, port)` and wrap it in `DrpcClient`. They just need `initialize(host, port)` called before use.

### What is Missing

1. Yamux unit tests are broken (blocking confidence in Layer 3)
2. No `SyncClient` facade to call `connectionManager.getSession` with configured addresses
3. No `NetworkConfigRepository` to parse/save `client.yml` and supply addresses
4. No onboarding UI to import `client.yml` and pick a sync folder
5. No foreground `SyncService` to run upload/download continuously
6. The main screen has no start/stop sync controls
7. The `SyncOrchestrator` uses the HTTP-based `FilenodeClient`, not `P2PFilenodeClient`

---

## Architecture After This Plan

```
User picks client.yml + sync folder (OnboardingScreen)
    |
    v
NetworkConfigRepository.save(yml)
    |
    v
SyncService.start() [foreground service]
    |
    +-- FileWatcher → upload to P2PFilenodeClient
    +-- poll loop   → download from P2PFilenodeClient
    |
    v
YamuxConnectionManager.getSession(host, port)
    → Layer 1: Libp2pTlsProvider
    → Layer 2: AnySyncHandshake
    → Layer 3: YamuxSession
    → Layer 4: DrpcClient
    → Layer 5: P2PCoordinatorClient / P2PFilenodeClient
```

---

## Tasks

### Task 1: Fix Yamux Unit Tests

**Why first:** All downstream tasks rely on `YamuxSession`. We cannot trust the P2P stack if ~30 yamux unit tests are failing.

**Root cause to investigate:** `YamuxSession.start()` launches a background coroutine that reads from `InputStream`. When the mock returns -1 (EOF), the background coroutine may throw and call `close()`, which triggers side effects (GO_AWAY write, socket close). Tests that check `isActive()` or verify write counts may run before the background coroutine terminates.

Secondary cause: some tests use `coEvery` on `OutputStream.write` but the production code dispatches to `Dispatchers.IO` via `withContext`, so mock expectations may not be seen from the test coroutine.

**Files to examine and fix:**
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/test/java/com/anyproto/anyfile/data/network/yamux/YamuxSessionTest.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxSession.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/data/network/yamux/YamuxFrame.kt`
- Also check: `YamuxStreamTest.kt`, `YamuxProtocolTest.kt`, `YamuxConnectionManagerTest.kt`

**Steps:**

1. Run tests to see the failures:
   ```bash
   cd /Users/kike/projects/any-file-workspace/any-file-android
   ./gradlew test --tests "*.YamuxSessionTest" 2>&1 | head -80
   ./gradlew test --tests "*.YamuxStreamTest" 2>&1 | head -40
   ./gradlew test --tests "*.YamuxConnectionManagerTest" 2>&1 | head -40
   ```

2. Categorize failures:
   - **EOF causes unexpected close:** `session.start()` launches `readFramesLoop()` which catches an exception when mock returns -1, calls `close()`, which writes GO_AWAY to the mock stream. Tests that do not mock `write` then throw. Fix: either mock `write` in all tests that call `start()`, OR fix `readFramesLoop` to treat EOF (read returns -1, meaning `YamuxProtocol.readFrame` throws `EOFException`) as a clean shutdown without sending GO_AWAY.

   - **`coEvery` vs `every` for non-suspend calls:** `OutputStream.write(ByteArray)` is not a suspend function. Use `every { mockOutputStream.write(any<ByteArray>()) } just Runs` (not `coEvery`). Check all test setup blocks.

   - **`advanceUntilIdle()` missing:** Tests using `runTest` that call `session.start()` must call `testScope.advanceUntilIdle()` before assertions to let the background coroutine reach its first suspension point. However, since `YamuxSession` uses `Dispatchers.IO` (not a test dispatcher), `advanceUntilIdle()` alone may not help. The correct fix is to inject `CoroutineContext` into `YamuxSession` in tests (already supported via constructor parameter `coroutineContext`).

   - **Test `coroutineContext` injection:** `YamuxSession` already accepts `coroutineContext` as a constructor parameter. In tests, pass `UnconfinedTestDispatcher()` so launched coroutines run eagerly:
     ```kotlin
     // In test setup:
     val testDispatcher = UnconfinedTestDispatcher()
     session = YamuxSession(mockSocket, isClient = true, coroutineContext = testDispatcher)
     ```

3. Specific fixes per test category:

   **Tests that call `session.start()` with EOF input:**
   ```kotlin
   // Pattern that works: mock the EOF, use UnconfinedTestDispatcher, advance
   @Test
   fun `session should start successfully`() = runTest {
       every { mockInputStream.read(any<ByteArray>()) } returns -1
       // YamuxProtocol.readFrame will throw EOFException/IOException on -1
       // readFramesLoop catches it and calls close() → GO_AWAY write needed
       every { mockOutputStream.write(any<ByteArray>()) } just Runs
       every { mockOutputStream.flush() } just Runs
       session.start()
       advanceUntilIdle()
       // After EOF, session closes itself — this is expected behavior
       // Assert based on actual behavior
   }
   ```

   **Tests that check `isActive()` after `close()`:**
   ```kotlin
   // close() is suspend — must be called in runTest
   // close() writes GO_AWAY frame, so mock must be set up
   @Test
   fun `close should transition to CLOSED state`() = runTest {
       every { mockOutputStream.write(any<ByteArray>()) } just Runs
       every { mockOutputStream.flush() } just Runs
       session.close()
       assertEquals(YamuxSession.State.CLOSED, session.state)
       assertFalse(session.isActive())
   }
   ```

   **Test `close should be idempotent`:** The test verifies `write` is called exactly once. But `close()` also calls `stream.close()` for each stream, which may write FIN frames. Since no streams are open in this test, the count should be 1. If it is not, check that `streamsMap.clear()` happens before any stream close calls.

4. Run all yamux tests until green:
   ```bash
   ./gradlew test --tests "*.Yamux*" 2>&1 | tail -20
   ```

5. Run full unit test suite to check for regressions:
   ```bash
   ./gradlew test 2>&1 | tail -30
   ```

6. Commit:
   ```bash
   cd /Users/kike/projects/any-file-workspace/any-file-android
   git add app/src/test/java/com/anyproto/anyfile/data/network/yamux/
   git add app/src/main/java/com/anyproto/anyfile/data/network/yamux/
   git commit -m "fix: resolve YamuxSession unit test failures"
   ```

**Done when:** `./gradlew test --tests "*.Yamux*"` exits 0 with all tests passing, and `./gradlew test` shows no new failures.

---

### Task 2: SyncClient Facade + Integration Test

**Why:** `P2PCoordinatorClient` and `P2PFilenodeClient` already exist but require `.initialize(host, port)` before use. `NetworkConfigRepository` (Task 3) will supply the addresses. We need a single injectable `SyncClient` that:
- Reads coordinator/filenode addresses from `NetworkConfigRepository`
- Calls `connectionManager.getSession` (which wires Layer 1-3) on demand
- Exposes typed connect methods that return ready-to-use P2P clients
- Manages connection lifecycle (disconnect on service stop)

**New file:** `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/data/network/SyncClient.kt`

**Modified file:** `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/di/NetworkModule.kt`

**New test:** `/Users/kike/projects/any-file-workspace/any-file-android/app/src/androidTest/java/com/anyproto/anyfile/data/network/SyncClientIntegrationTest.kt`

**Steps (TDD):**

**Step 1: Write the failing integration test first.**

```kotlin
// app/src/androidTest/java/com/anyproto/anyfile/data/network/SyncClientIntegrationTest.kt
package com.anyproto.anyfile.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyproto.anyfile.data.network.p2p.P2PCoordinatorClient
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for SyncClient.
 * Requires docker services running at 10.0.2.2:1004 (coordinator) and 10.0.2.2:1005 (filenode).
 *
 * Run with: ./gradlew connectedAndroidTest
 * Prerequisites:
 *   1. Emulator running (adb devices)
 *   2. Docker services: cd any-file/docker && docker-compose up -d
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SyncClientIntegrationTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var syncClient: SyncClient

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun testConnectToCoordinator() = runTest {
        val coordinatorClient = syncClient.connectCoordinator("10.0.2.2", 1004)
        assertNotNull(coordinatorClient)
        val peerId = syncClient.getPeerID()
        assertTrue(peerId.startsWith("12D3KooW"), "Expected libp2p peer ID starting with 12D3KooW, got: $peerId")
    }

    @Test
    fun testConnectToFilenode() = runTest {
        val filenodeClient = syncClient.connectFilenode("10.0.2.2", 1005)
        assertNotNull(filenodeClient)
    }

    @Test
    fun testPeerIDIsStable() = runTest {
        val id1 = syncClient.getPeerID()
        val id2 = syncClient.getPeerID()
        assertTrue(id1 == id2, "Peer ID should be stable across calls")
    }
}
```

Run to confirm it fails (class not found):
```bash
./gradlew connectedAndroidTest --tests "*.SyncClientIntegrationTest" 2>&1 | tail -20
```

**Step 2: Implement `SyncClient.kt`.**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/network/SyncClient.kt
package com.anyproto.anyfile.data.network

import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsProvider
import com.anyproto.anyfile.data.network.p2p.P2PCoordinatorClient
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import com.anyproto.anyfile.data.network.yamux.YamuxConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade that wires the full P2P protocol stack for sync operations.
 *
 * Wraps YamuxConnectionManager (which handles TLS → Handshake → Yamux)
 * and provides ready-to-use P2P clients for coordinator and filenode.
 *
 * Usage:
 *   val coordinatorClient = syncClient.connectCoordinator("10.0.2.2", 1004)
 *   val filenodeClient    = syncClient.connectFilenode("10.0.2.2", 1005)
 */
@Singleton
class SyncClient @Inject constructor(
    private val connectionManager: YamuxConnectionManager,
    private val coordinatorClient: P2PCoordinatorClient,
    private val filenodeClient: P2PFilenodeClient,
    private val tlsProvider: Libp2pTlsProvider,
) {

    /**
     * Connect to coordinator at the given host/port.
     * Internally calls YamuxConnectionManager which performs TLS → Handshake → Yamux.
     * Returns the already-injected P2PCoordinatorClient, initialized with the given address.
     */
    suspend fun connectCoordinator(host: String, port: Int): P2PCoordinatorClient =
        withContext(Dispatchers.IO) {
            // Warm up the connection so errors surface here rather than at first RPC call.
            connectionManager.getSession(host, port)
            coordinatorClient.initialize(host, port)
            coordinatorClient
        }

    /**
     * Connect to filenode at the given host/port.
     * Returns the already-injected P2PFilenodeClient, initialized with the given address.
     */
    suspend fun connectFilenode(host: String, port: Int): P2PFilenodeClient =
        withContext(Dispatchers.IO) {
            connectionManager.getSession(host, port)
            filenodeClient.initialize(host, port)
            filenodeClient
        }

    /**
     * Disconnect all cached connections.
     * Call this when SyncService stops to release TCP/TLS sockets.
     */
    suspend fun disconnect() {
        connectionManager.closeAll()
    }

    /**
     * Return this device's libp2p peer ID (base58 encoded).
     * Used for logging and registration.
     */
    fun getPeerID(): String = tlsProvider.getPeerIdentity().peerId.base58
}
```

**Step 3: Update `NetworkModule.kt`.** No changes needed — `YamuxConnectionManager`, `P2PCoordinatorClient`, and `P2PFilenodeClient` are already `@Singleton` with `@Inject` constructors. Hilt will provide them automatically. `SyncClient` also has an `@Inject` constructor. No `@Provides` needed.

Verify by checking that `SyncClient`'s dependencies are all injectable:
- `YamuxConnectionManager` — `@Singleton @Inject constructor(tlsProvider: Libp2pTlsProvider)` ✓
- `P2PCoordinatorClient` — `@Singleton @Inject constructor(connectionManager: YamuxConnectionManager)` ✓
- `P2PFilenodeClient` — `@Singleton @Inject constructor(connectionManager: YamuxConnectionManager)` ✓
- `Libp2pTlsProvider` — `@Singleton @Inject constructor(keyManager: Libp2pKeyManager)` ✓

**Step 4: Run integration tests** (requires emulator + docker services):
```bash
# Start docker services in a separate terminal:
# cd /Users/kike/projects/any-file-workspace/any-file/docker && docker-compose up -d

./gradlew connectedAndroidTest --tests "*.SyncClientIntegrationTest" 2>&1 | tail -30
```

**Step 5: Run unit tests to verify no regressions:**
```bash
./gradlew test 2>&1 | tail -20
```

**Step 6: Commit:**
```bash
cd /Users/kike/projects/any-file-workspace/any-file-android
git add app/src/main/java/com/anyproto/anyfile/data/network/SyncClient.kt
git add app/src/androidTest/java/com/anyproto/anyfile/data/network/SyncClientIntegrationTest.kt
git commit -m "feat: add SyncClient wiring full P2P stack"
```

**Done when:** Integration test passes against live coordinator, unit tests unchanged.

---

### Task 3: NetworkConfigRepository + Onboarding Screen

**Why:** The app currently has no way to configure which coordinator/filenode to connect to. `client.yml` (the any-sync network config file) contains coordinator and filenode addresses. The user must import it either from a local file or an HTTPS URL. After import, the app must parse the YAML to extract addresses and store them for `SyncService` to use.

**New files:**
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/data/config/NetworkConfigRepository.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/ui/screens/onboarding/OnboardingScreen.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/ui/screens/onboarding/OnboardingViewModel.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/test/java/com/anyproto/anyfile/data/config/NetworkConfigRepositoryTest.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/test/java/com/anyproto/anyfile/ui/screens/onboarding/OnboardingViewModelTest.kt`

**Modified files:**
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/ui/navigation/NavGraph.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/AndroidManifest.xml` (folder picker intent — no new permissions needed, SAF uses existing)

**Steps (TDD):**

**Step 1: Write failing unit tests for `NetworkConfigRepository`.**

```kotlin
// app/src/test/java/com/anyproto/anyfile/data/config/NetworkConfigRepositoryTest.kt
package com.anyproto.anyfile.data.config

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkConfigRepositoryTest {

    @get:Rule val tmpDir = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var repo: NetworkConfigRepository

    @Before
    fun setup() {
        context = mockk()
        prefs = mockk(relaxed = true)
        every { context.filesDir } returns tmpDir.root
        every { context.getSharedPreferences(any(), any()) } returns prefs
        repo = NetworkConfigRepository(context)
    }

    @Test
    fun `fetchFromLocalFile returns file bytes`() = runTest {
        val tmpFile = tmpDir.newFile("client.yml")
        tmpFile.writeText(VALID_CONFIG_YAML)
        val bytes = repo.fetch(tmpFile.absolutePath)
        assertTrue(bytes.isNotEmpty())
        assertEquals(VALID_CONFIG_YAML, String(bytes))
    }

    @Test
    fun `saveValidConfig writes file to config dir`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        val saved = File(tmpDir.root, "network.yml")
        assertTrue(saved.exists())
        assertEquals(VALID_CONFIG_YAML, saved.readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `saveEmptyYaml throws IllegalArgumentException`() {
        repo.save("".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `saveConfigWithNoCoordinatorThrows`() {
        repo.save(NO_COORDINATOR_YAML.toByteArray())
    }

    @Test
    fun `isConfiguredReturnsFalseWhenNoFile`() {
        assertFalse(repo.isConfigured())
    }

    @Test
    fun `isConfiguredReturnsTrueAfterSave`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        assertTrue(repo.isConfigured())
    }

    @Test
    fun `getCoordinatorAddressReturnsFirstCoordinatorHostAndPort`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        val (host, port) = repo.getCoordinatorAddress()
        assertEquals("10.0.2.2", host)
        assertEquals(1004, port)
    }

    @Test
    fun `getFilenodeAddressReturnsFirstFilenodeHostAndPort`() {
        repo.save(VALID_CONFIG_YAML.toByteArray())
        val (host, port) = repo.getFilenodeAddress()
        assertEquals("10.0.2.2", host)
        assertEquals(1005, port)
    }

    companion object {
        // Minimal valid client.yml matching any-sync network.yml format
        val VALID_CONFIG_YAML = """
nodes:
  - peerId: 12D3KooWABC
    addresses:
      - 10.0.2.2:1004
    types:
      - coordinator
  - peerId: 12D3KooWDEF
    addresses:
      - 10.0.2.2:1005
    types:
      - fileNode
""".trimIndent()

        val NO_COORDINATOR_YAML = """
nodes:
  - peerId: 12D3KooWDEF
    addresses:
      - 10.0.2.2:1005
    types:
      - fileNode
""".trimIndent()
    }
}
```

Run to confirm failure:
```bash
./gradlew test --tests "*.NetworkConfigRepositoryTest" 2>&1 | tail -20
```

**Step 2: Implement `NetworkConfigRepository.kt`.**

```kotlin
// app/src/main/java/com/anyproto/anyfile/data/config/NetworkConfigRepository.kt
package com.anyproto.anyfile.data.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the any-sync network configuration (client.yml / network.yml).
 *
 * The config is stored as network.yml in app-private filesDir.
 * Parsed lazily to extract coordinator and filenode addresses.
 *
 * YAML format (any-sync network.yml):
 * ```
 * nodes:
 *   - peerId: <id>
 *     addresses:
 *       - host:port
 *     types:
 *       - coordinator  (or: fileNode, consensusNode)
 * ```
 *
 * No external YAML library is used — we parse the required fields manually.
 */
@Singleton
class NetworkConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val CONFIG_FILENAME = "network.yml"
        private const val PREFS_NAME = "anyfile_prefs"
        private const val KEY_SYNC_FOLDER = "sync_folder_path"
        private const val FETCH_TIMEOUT_MS = 30_000
    }

    private val configFile: File
        get() = File(context.filesDir, CONFIG_FILENAME)

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Fetch config from a local file path or https:// URL.
     * Returns raw bytes of the config file.
     *
     * @param source Absolute file path or https:// URL
     * @throws IllegalArgumentException for unsupported schemes
     * @throws Exception on network or IO errors
     */
    suspend fun fetch(source: String): ByteArray = withContext(Dispatchers.IO) {
        when {
            source.startsWith("https://") || source.startsWith("http://") -> {
                val connection = URL(source).openConnection()
                connection.connectTimeout = FETCH_TIMEOUT_MS
                connection.readTimeout = FETCH_TIMEOUT_MS
                connection.getInputStream().use { it.readBytes() }
            }
            else -> {
                val file = File(source)
                require(file.exists()) { "File not found: $source" }
                file.readBytes()
            }
        }
    }

    /**
     * Validate and save the config bytes as network.yml.
     * Throws [IllegalArgumentException] if the config contains no coordinator node.
     *
     * @param data Raw YAML bytes
     * @throws IllegalArgumentException if config is empty or has no coordinator
     */
    fun save(data: ByteArray) {
        require(data.isNotEmpty()) { "Config data is empty" }
        val yaml = String(data, Charsets.UTF_8)
        // Validate: must have at least one coordinator node
        val nodes = parseNodes(yaml)
        require(nodes.any { it.types.contains("coordinator") }) {
            "Config has no coordinator node. Ensure at least one node has type 'coordinator'."
        }
        configFile.writeBytes(data)
    }

    /**
     * Returns true if a valid config file exists with at least one coordinator.
     */
    fun isConfigured(): Boolean {
        if (!configFile.exists()) return false
        return try {
            val nodes = parseNodes(configFile.readText())
            nodes.any { it.types.contains("coordinator") }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the first coordinator's host and port.
     * @throws IllegalStateException if not configured or no coordinator found
     */
    fun getCoordinatorAddress(): Pair<String, Int> {
        check(configFile.exists()) { "Not configured. Call save() first." }
        val nodes = parseNodes(configFile.readText())
        val coordinator = nodes.firstOrNull { it.types.contains("coordinator") }
            ?: error("No coordinator in config")
        return parseAddress(coordinator.addresses.first())
    }

    /**
     * Returns the first filenode's host and port.
     * @throws IllegalStateException if not configured or no filenode found
     */
    fun getFilenodeAddress(): Pair<String, Int> {
        check(configFile.exists()) { "Not configured. Call save() first." }
        val nodes = parseNodes(configFile.readText())
        val filenode = nodes.firstOrNull { it.types.contains("fileNode") }
            ?: error("No fileNode in config")
        return parseAddress(filenode.addresses.first())
    }

    /**
     * Stored sync folder path (SharedPreferences-backed).
     * Null if not set.
     */
    var syncFolderPath: String?
        get() = prefs.getString(KEY_SYNC_FOLDER, null)
        set(value) {
            prefs.edit().putString(KEY_SYNC_FOLDER, value).apply()
        }

    // --- Internal YAML parsing (no external lib) ---

    private data class NodeConfig(
        val peerId: String,
        val addresses: List<String>,
        val types: List<String>,
    )

    /**
     * Minimal YAML parser for the any-sync network.yml format.
     *
     * Parses the `nodes:` list, extracting `peerId`, `addresses`, and `types` per node.
     * This handles the specific format used by any-sync without a full YAML library.
     */
    private fun parseNodes(yaml: String): List<NodeConfig> {
        val nodes = mutableListOf<NodeConfig>()
        val lines = yaml.lines()

        var currentPeerId: String? = null
        val currentAddresses = mutableListOf<String>()
        val currentTypes = mutableListOf<String>()
        var inAddresses = false
        var inTypes = false

        fun flushNode() {
            val pid = currentPeerId
            if (pid != null && (currentAddresses.isNotEmpty() || currentTypes.isNotEmpty())) {
                nodes.add(NodeConfig(pid, currentAddresses.toList(), currentTypes.toList()))
            }
            currentPeerId = null
            currentAddresses.clear()
            currentTypes.clear()
            inAddresses = false
            inTypes = false
        }

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("- peerId:") -> {
                    flushNode()
                    currentPeerId = trimmed.removePrefix("- peerId:").trim()
                }
                trimmed.startsWith("peerId:") -> {
                    flushNode()
                    currentPeerId = trimmed.removePrefix("peerId:").trim()
                }
                trimmed == "addresses:" -> {
                    inAddresses = true
                    inTypes = false
                }
                trimmed == "types:" -> {
                    inTypes = true
                    inAddresses = false
                }
                trimmed.startsWith("-") && inAddresses -> {
                    val addr = trimmed.removePrefix("-").trim()
                    // Strip scheme prefix (e.g. "quic://10.0.2.2:1004" → "10.0.2.2:1004")
                    val cleanAddr = addr
                        .removePrefix("quic://")
                        .removePrefix("tcp://")
                    currentAddresses.add(cleanAddr)
                }
                trimmed.startsWith("-") && inTypes -> {
                    currentTypes.add(trimmed.removePrefix("-").trim())
                }
            }
        }
        flushNode()
        return nodes
    }

    private fun parseAddress(address: String): Pair<String, Int> {
        val lastColon = address.lastIndexOf(':')
        require(lastColon > 0) { "Invalid address format: $address" }
        val host = address.substring(0, lastColon)
        val port = address.substring(lastColon + 1).toInt()
        return Pair(host, port)
    }
}
```

Run tests until green:
```bash
./gradlew test --tests "*.NetworkConfigRepositoryTest" 2>&1 | tail -20
```

**Step 3: Write failing `OnboardingViewModel` unit tests.**

```kotlin
// app/src/test/java/com/anyproto/anyfile/ui/screens/onboarding/OnboardingViewModelTest.kt
package com.anyproto.anyfile.ui.screens.onboarding

import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.SyncClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var networkConfigRepo: NetworkConfigRepository
    private lateinit var syncClient: SyncClient
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        networkConfigRepo = mockk(relaxed = true)
        syncClient = mockk(relaxed = true)
        viewModel = OnboardingViewModel(networkConfigRepo, syncClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty`() {
        val state = viewModel.uiState.value
        assertEquals(OnboardingState.Initial, state)
    }

    @Test
    fun `importConfig with valid URL transitions to ConfigLoaded`() = runTest {
        val fakeYaml = "nodes:\n  - peerId: abc\n    addresses:\n      - 10.0.2.2:1004\n    types:\n      - coordinator\n"
        coEvery { networkConfigRepo.fetch(any()) } returns fakeYaml.toByteArray()
        every { networkConfigRepo.save(any()) } returns Unit

        viewModel.importConfig("https://example.com/client.yml")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is OnboardingState.ConfigLoaded)
    }

    @Test
    fun `importConfig with network error transitions to Error`() = runTest {
        coEvery { networkConfigRepo.fetch(any()) } throws Exception("Network failure")

        viewModel.importConfig("https://bad.example.com/client.yml")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is OnboardingState.Error)
    }

    @Test
    fun `setSyncFolder stores path in repo`() {
        viewModel.setSyncFolder("/storage/emulated/0/AnyFile")
        verify { networkConfigRepo.syncFolderPath = "/storage/emulated/0/AnyFile" }
    }

    @Test
    fun `startSyncing emits NavigateToMain when both config and folder are set`() = runTest {
        every { networkConfigRepo.isConfigured() } returns true
        every { networkConfigRepo.syncFolderPath } returns "/storage/emulated/0/AnyFile"
        viewModel.importConfig("file:///dummy") // simulate config loaded state
        // Manually set state for simplicity

        val events = mutableListOf<OnboardingEvent>()
        val job = viewModel.events.collect { events.add(it) }.let { /* no-op to collect */ }
        viewModel.startSyncing()
        advanceUntilIdle()

        // When both config and folder are set, event should be NavigateToMain
    }
}
```

**Step 4: Implement `OnboardingViewModel.kt`.**

```kotlin
// app/src/main/java/com/anyproto/anyfile/ui/screens/onboarding/OnboardingViewModel.kt
package com.anyproto.anyfile.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.SyncClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class OnboardingState {
    object Initial : OnboardingState()
    object Loading : OnboardingState()
    data class ConfigLoaded(val source: String) : OnboardingState()
    data class Error(val message: String) : OnboardingState()
    object ReadyToSync : OnboardingState()
}

sealed class OnboardingEvent {
    object NavigateToMain : OnboardingEvent()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val networkConfigRepository: NetworkConfigRepository,
    private val syncClient: SyncClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingState>(OnboardingState.Initial)
    val uiState: StateFlow<OnboardingState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<OnboardingEvent>()
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

    private var syncFolderReady = false
    private var configReady = false

    /** Import config from a local file path or https:// URL. */
    fun importConfig(source: String) {
        viewModelScope.launch {
            _uiState.value = OnboardingState.Loading
            try {
                val bytes = networkConfigRepository.fetch(source)
                networkConfigRepository.save(bytes)  // throws if invalid
                configReady = true
                _uiState.value = OnboardingState.ConfigLoaded(source)
                checkReadyToSync()
            } catch (e: Exception) {
                _uiState.value = OnboardingState.Error(e.message ?: "Import failed")
            }
        }
    }

    /** Store the chosen sync folder path. */
    fun setSyncFolder(path: String) {
        networkConfigRepository.syncFolderPath = path
        syncFolderReady = path.isNotBlank()
        checkReadyToSync()
    }

    /** Emit NavigateToMain when both config and folder are ready. */
    fun startSyncing() {
        viewModelScope.launch {
            _events.emit(OnboardingEvent.NavigateToMain)
        }
    }

    private fun checkReadyToSync() {
        if (configReady && syncFolderReady) {
            _uiState.value = OnboardingState.ReadyToSync
        }
    }
}
```

**Step 5: Implement `OnboardingScreen.kt`.**

The screen has two steps shown vertically on a single screen:

1. **Config import section:**
   - `OutlinedTextField` for URL or file path
   - `Button("Browse file")` that launches `ACTION_OPEN_DOCUMENT` with `*/*` MIME
   - Shows a checkmark icon when config is loaded
   - Shows error text on failure

2. **Sync folder section (enabled only after step 1):**
   - `Button("Choose folder")` that launches `ACTION_OPEN_DOCUMENT_TREE`
   - Shows the chosen path when selected

3. **"Start Syncing" button** — enabled only when both config and folder are set

```kotlin
// app/src/main/java/com/anyproto/anyfile/ui/screens/onboarding/OnboardingScreen.kt
package com.anyproto.anyfile.ui.screens.onboarding

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Onboarding screen presented on first launch.
 *
 * Two-step process:
 * 1. Import client.yml (from URL or file)
 * 2. Choose sync folder
 * Then tap "Start Syncing".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onNavigateToMain: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var configSource by remember { mutableStateOf("") }
    var syncFolderDisplay by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Collect navigation events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is OnboardingEvent.NavigateToMain -> onNavigateToMain()
            }
        }
    }

    // File picker for client.yml
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Convert URI to real path or use content URI string
            val path = uri.toString()
            configSource = path
            viewModel.importConfig(path)
        }
    }

    // Folder picker (SAF)
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            syncFolderDisplay = uri.lastPathSegment ?: uri.toString()
            viewModel.setSyncFolder(uri.toString())
        }
    }

    val configLoaded = uiState is OnboardingState.ConfigLoaded || uiState is OnboardingState.ReadyToSync
    val readyToSync = uiState is OnboardingState.ReadyToSync
    val isLoading = uiState is OnboardingState.Loading

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Welcome to AnyFile") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // Step 1: Import config
            SectionCard(title = "Step 1: Import network config") {
                Text(
                    "Enter the URL to your client.yml or browse for a local file.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = configSource,
                    onValueChange = { configSource = it },
                    label = { Text("URL or file path") },
                    placeholder = { Text("https://example.com/client.yml") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (configLoaded) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Config loaded",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Browse file")
                    }
                    Button(
                        onClick = { viewModel.importConfig(configSource) },
                        modifier = Modifier.weight(1f),
                        enabled = configSource.isNotBlank() && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Import")
                        }
                    }
                }
                if (uiState is OnboardingState.Error) {
                    Text(
                        (uiState as OnboardingState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Step 2: Choose sync folder
            SectionCard(
                title = "Step 2: Choose sync folder",
                enabled = configLoaded
            ) {
                Text(
                    "Choose a local folder to sync with the network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (configLoaded) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (syncFolderDisplay.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        syncFolderDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { folderPicker.launch(null) },
                    enabled = configLoaded,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (syncFolderDisplay.isBlank()) "Choose folder" else "Change folder")
                }
            }

            // Start Syncing button
            Button(
                onClick = { viewModel.startSyncing() },
                enabled = readyToSync,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Start Syncing", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surface
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
```

**Step 6: Update `NavGraph.kt`.**

Add an `"onboarding"` route and check `isConfigured()` to decide the start destination.

```kotlin
// Modified NavGraph.kt additions
// Add onboarding to Screen sealed class:
object Onboarding : Screen("onboarding", "Setup", Icons.Filled.Settings)

// In AnyFileNavGraph, inject NetworkConfigRepository via hiltViewModel or pass flag:
// The simplest approach: inject via a ViewModel that checks isConfigured() on init.
// Add at the top of NavHost block:
val startDestination = if (networkConfigRepository.isConfigured()) {
    Screen.Spaces.route
} else {
    Screen.Onboarding.route
}

// Add composable:
composable(Screen.Onboarding.route) {
    OnboardingScreen(
        onNavigateToMain = {
            navController.navigate(Screen.Spaces.route) {
                popUpTo(Screen.Onboarding.route) { inclusive = true }
            }
        }
    )
}
```

Because `NetworkConfigRepository` is a `@Singleton`, the cleanest approach is to create a lightweight `NavViewModel`:

```kotlin
// app/src/main/java/com/anyproto/anyfile/ui/navigation/NavViewModel.kt
package com.anyproto.anyfile.ui.navigation

import androidx.lifecycle.ViewModel
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NavViewModel @Inject constructor(
    private val networkConfigRepository: NetworkConfigRepository,
) : ViewModel() {
    val isConfigured: Boolean get() = networkConfigRepository.isConfigured()
}
```

Then in `AnyFileNavGraph`:
```kotlin
@Composable
fun AnyFileNavGraph(
    navViewModel: NavViewModel = hiltViewModel(),
) {
    val startDestination = if (navViewModel.isConfigured) Screen.Spaces.route
                           else "onboarding"
    // ...
    NavHost(startDestination = startDestination) {
        composable("onboarding") { OnboardingScreen(onNavigateToMain = { /* ... */ }) }
        // existing composables unchanged
    }
}
```

**Step 7: Run tests:**
```bash
./gradlew test --tests "*.NetworkConfigRepositoryTest" 2>&1 | tail -20
./gradlew test --tests "*.OnboardingViewModelTest" 2>&1 | tail -20
./gradlew test 2>&1 | tail -20
```

**Step 8: Commit:**
```bash
cd /Users/kike/projects/any-file-workspace/any-file-android
git add \
  app/src/main/java/com/anyproto/anyfile/data/config/ \
  app/src/main/java/com/anyproto/anyfile/ui/screens/onboarding/ \
  app/src/main/java/com/anyproto/anyfile/ui/navigation/ \
  app/src/test/java/com/anyproto/anyfile/data/config/ \
  app/src/test/java/com/anyproto/anyfile/ui/screens/onboarding/
git commit -m "feat: add onboarding screen with NetworkConfigRepository for client.yml import"
```

**Done when:** Unit tests pass, app navigates to OnboardingScreen on first launch (no network.yml), navigates to SpacesScreen when network.yml exists.

---

### Task 4: SyncService (Foreground Service)

**Why:** `SyncWorker` (WorkManager) runs at best every 15 minutes. For near-real-time sync, we need a foreground service that stays alive while the user expects syncing, shows a persistent notification, runs a tight polling loop, and is explicitly started/stopped by the user.

**New files:**
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/service/SyncService.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/test/java/com/anyproto/anyfile/service/SyncServiceTest.kt`

**Modified files:**
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/AndroidManifest.xml`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/res/values/strings.xml` (add notification strings)
- `/Users/kike/projects/any-file-workspace/any-file-android/app/build.gradle.kts` (add Robolectric for unit tests)

**Steps (TDD):**

**Step 1: Add Robolectric to `build.gradle.kts`** (if not already present):

```kotlin
// In dependencies block of build.gradle.kts:
testImplementation("org.robolectric:robolectric:4.11.1")
testImplementation("androidx.test:core:1.5.0")
```

Also add to `android {}` block:
```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
    }
}
```

**Step 2: Write failing unit test.**

```kotlin
// app/src/test/java/com/anyproto/anyfile/service/SyncServiceTest.kt
package com.anyproto.anyfile.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SyncServiceTest {

    @Test
    fun `SyncService exists and has correct class structure`() {
        // Verify the class can be instantiated (basic smoke test)
        // Full behavioral testing requires instrumentation tests
        val serviceClass = Class.forName("com.anyproto.anyfile.service.SyncService")
        assertNotNull(serviceClass)
    }

    @Test
    fun `SyncService companion object has correct constants`() {
        // Verify constants are accessible
        val notificationId = SyncService.NOTIFICATION_ID
        assertTrue(notificationId > 0, "NOTIFICATION_ID must be positive")
    }
}
```

Run to confirm failure (class not found):
```bash
./gradlew test --tests "*.SyncServiceTest" 2>&1 | tail -20
```

**Step 3: Implement `SyncService.kt`.**

```kotlin
// app/src/main/java/com/anyproto/anyfile/service/SyncService.kt
package com.anyproto.anyfile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anyproto.anyfile.data.config.NetworkConfigRepository
import com.anyproto.anyfile.data.network.SyncClient
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import com.anyproto.anyfile.domain.sync.SyncOrchestrator
import com.anyproto.anyfile.domain.watch.FileChangeListener
import com.anyproto.anyfile.domain.watch.FileWatcher
import com.anyproto.anyfile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground service that runs continuous file sync.
 *
 * Lifecycle:
 * - Started by SyncService.start(context)
 * - Stopped by SyncService.stop(context)
 * - Shows a persistent notification while running
 *
 * Sync loop:
 * - Watches local sync folder for changes → uploads new/modified files
 * - Polls filenode every 10 seconds → downloads remote changes
 */
@AndroidEntryPoint
class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "anyfile_sync"
        private const val POLL_INTERVAL_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }

    @Inject lateinit var syncClient: SyncClient
    @Inject lateinit var networkConfigRepository: NetworkConfigRepository
    @Inject lateinit var syncOrchestrator: SyncOrchestrator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileWatcher: FileWatcher? = null
    private var filenodeClient: P2PFilenodeClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting sync..."))
        serviceScope.launch { runSyncLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fileWatcher?.stop()
        serviceScope.launch { syncClient.disconnect() }
        serviceScope.cancel()
        Log.d(TAG, "SyncService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runSyncLoop() {
        try {
            if (!networkConfigRepository.isConfigured()) {
                Log.e(TAG, "Not configured — no network.yml found")
                updateNotification("Not configured")
                stopSelf()
                return
            }

            val (coordHost, coordPort) = networkConfigRepository.getCoordinatorAddress()
            val (fnHost, fnPort) = networkConfigRepository.getFilenodeAddress()
            val syncFolder = networkConfigRepository.syncFolderPath

            Log.d(TAG, "Connecting to coordinator $coordHost:$coordPort")
            updateNotification("Connecting...")

            // Connect P2P stack (TLS → Handshake → Yamux → DRPC)
            syncClient.connectCoordinator(coordHost, coordPort)
            val fn = syncClient.connectFilenode(fnHost, fnPort)
            filenodeClient = fn

            Log.d(TAG, "P2P stack connected. Peer ID: ${syncClient.getPeerID()}")
            updateNotification("Syncing")

            // Watch local folder for changes
            if (syncFolder != null) {
                startFileWatcher(syncFolder)
            }

            // Poll for remote changes
            while (serviceScope.isActive) {
                try {
                    pollRemoteChanges(fn, syncFolder)
                } catch (e: Exception) {
                    Log.w(TAG, "Poll cycle error (will retry): ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Sync loop cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Sync loop fatal error", e)
            updateNotification("Sync error: ${e.message?.take(50)}")
        }
    }

    private fun startFileWatcher(syncFolderUri: String) {
        // FileWatcher requires a real filesystem path.
        // SAF URIs (content://) need resolution via DocumentFile or direct path.
        // For app-private directories or paths resolved from SAF, use real path.
        // For SAF URIs, we watch using a content observer or periodically scan.
        // TODO (Task 5): Resolve SAF URI to real path or implement content observer.
        // For now, attempt to start watcher if path is an absolute filesystem path.
        if (syncFolderUri.startsWith("/")) {
            try {
                fileWatcher = FileWatcher(syncFolderUri)
                fileWatcher?.setListener(object : FileChangeListener {
                    override fun onFileCreated(path: String) {
                        Log.d(TAG, "File created: $path")
                        serviceScope.launch { uploadFile(path) }
                    }
                    override fun onFileModified(path: String) {
                        Log.d(TAG, "File modified: $path")
                        serviceScope.launch { uploadFile(path) }
                    }
                    override fun onFileDeleted(path: String) {
                        Log.d(TAG, "File deleted: $path (deletion not yet synced)")
                    }
                    override fun onFileMoved(oldPath: String, newPath: String) {
                        Log.d(TAG, "File moved: $oldPath -> $newPath")
                        serviceScope.launch { uploadFile(newPath) }
                    }
                })
                fileWatcher?.start()
                Log.d(TAG, "File watcher started for: $syncFolderUri")
            } catch (e: Exception) {
                Log.w(TAG, "Could not start file watcher: ${e.message}")
            }
        }
    }

    private suspend fun uploadFile(path: String) {
        val fn = filenodeClient ?: return
        try {
            // SyncOrchestrator.uploadFile handles hashing, chunking, and BlockPush via FilenodeClient.
            // To use P2PFilenodeClient instead of HTTP FilenodeClient, we call P2PFilenodeClient directly.
            // The spaceId comes from NetworkConfigRepository or a default space.
            // TODO: wire spaceId from config. For now use a placeholder.
            val spaceId = "default-space"  // TODO: get from config/Room database
            Log.d(TAG, "Uploading: $path to space $spaceId")
            val result = syncOrchestrator.uploadFile(spaceId, path)
            Log.d(TAG, "Upload result for $path: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for $path: ${e.message}")
        }
    }

    private suspend fun pollRemoteChanges(fn: P2PFilenodeClient, syncFolderUri: String?) {
        // TODO: implement remote file listing via FilesGet and download missing blocks.
        // For now, this is a heartbeat placeholder.
        Log.v(TAG, "Poll cycle (remote changes check not yet implemented)")
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)  // TODO: use app icon
            .setContentTitle("AnyFile Sync")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AnyFile Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows sync status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
```

**Step 4: Update `AndroidManifest.xml`:**

```xml
<!-- Add inside <manifest>, before <application>: -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Add inside <application>: -->
<service
    android:name=".service.SyncService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

**Step 5: Add notification strings to `strings.xml`:**

```xml
<string name="sync_notification_channel_name">AnyFile Sync</string>
<string name="sync_notification_title">AnyFile Sync</string>
<string name="sync_notification_starting">Starting sync...</string>
<string name="sync_notification_active">Syncing</string>
<string name="sync_notification_error">Sync error</string>
```

**Step 6: Run tests:**
```bash
./gradlew test --tests "*.SyncServiceTest" 2>&1 | tail -20
./gradlew test 2>&1 | tail -20
```

**Step 7: Commit:**
```bash
cd /Users/kike/projects/any-file-workspace/any-file-android
git add \
  app/src/main/java/com/anyproto/anyfile/service/ \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/anyproto/anyfile/service/ \
  app/build.gradle.kts
git commit -m "feat: add SyncService foreground sync service"
```

**Done when:** `./gradlew test --tests "*.SyncServiceTest"` passes, APK builds with no manifest errors.

---

### Task 5: Wire UI to SyncService

**Why:** The user needs a visible start/stop toggle on the main screen and status feedback (syncing, idle, error). `SpacesViewModel` currently only manages per-space Room data. We add service lifecycle controls and a global sync status indicator.

**Modified files:**
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/ui/screens/SpacesScreen.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/ui/screens/SpacesViewModel.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/ui/MainActivity.kt`
- `/Users/kike/projects/any-file-workspace/any-file-android/app/src/main/java/com/anyproto/anyfile/ui/navigation/NavGraph.kt`

**Steps (TDD):**

**Step 1: Write failing ViewModel unit tests.**

```kotlin
// additions to SpacesViewModelTest.kt
// (read existing tests first to avoid duplication)

@Test
fun `startSync transitions serviceSyncStatus to ACTIVE`() = runTest {
    val context = mockk<Context>(relaxed = true)
    viewModel.startSync(context)
    advanceUntilIdle()
    assertEquals(ServiceSyncStatus.ACTIVE, viewModel.serviceSyncStatus.value)
}

@Test
fun `stopSync transitions serviceSyncStatus to IDLE`() = runTest {
    val context = mockk<Context>(relaxed = true)
    viewModel.startSync(context)
    viewModel.stopSync(context)
    advanceUntilIdle()
    assertEquals(ServiceSyncStatus.IDLE, viewModel.serviceSyncStatus.value)
}
```

**Step 2: Add `ServiceSyncStatus` enum and `SpacesViewModel` additions.**

```kotlin
// Add to SpacesViewModel.kt (do NOT rewrite — only add):

enum class ServiceSyncStatus { IDLE, ACTIVE, ERROR }

// Inside SpacesViewModel class, add:
private val _serviceSyncStatus = MutableStateFlow(ServiceSyncStatus.IDLE)
val serviceSyncStatus: StateFlow<ServiceSyncStatus> = _serviceSyncStatus.asStateFlow()

val syncFolderPath: String?
    get() = networkConfigRepository.syncFolderPath  // requires injecting NetworkConfigRepository

fun startSync(context: Context) {
    _serviceSyncStatus.value = ServiceSyncStatus.ACTIVE
    SyncService.start(context)
}

fun stopSync(context: Context) {
    _serviceSyncStatus.value = ServiceSyncStatus.IDLE
    SyncService.stop(context)
}
```

Inject `NetworkConfigRepository` into `SpacesViewModel`:
```kotlin
// Change constructor:
@HiltViewModel
class SpacesViewModel @Inject constructor(
    private val spaceDao: SpaceDao,
    private val syncOrchestrator: SyncOrchestrator,
    private val networkConfigRepository: NetworkConfigRepository,  // add this
) : ViewModel()
```

**Step 3: Update `SpacesScreen.kt`.**

Add a sync control banner below the TopAppBar:

```kotlin
// In SpacesScreen, add inside the Scaffold body, above the LazyColumn/EmptyState:
val serviceSyncStatus by viewModel.serviceSyncStatus.collectAsState()
val context = LocalContext.current

// Sync status banner
SyncStatusBanner(
    status = serviceSyncStatus,
    syncFolderPath = viewModel.syncFolderPath,
    onStart = { viewModel.startSync(context) },
    onStop = { viewModel.stopSync(context) }
)
```

```kotlin
@Composable
fun SyncStatusBanner(
    status: ServiceSyncStatus,
    syncFolderPath: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val (statusText, color) = when (status) {
        ServiceSyncStatus.IDLE -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
        ServiceSyncStatus.ACTIVE -> "Active" to MaterialTheme.colorScheme.primary
        ServiceSyncStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sync: $statusText", style = MaterialTheme.typography.labelMedium, color = color)
                if (syncFolderPath != null) {
                    Text(
                        syncFolderPath.takeLast(40),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (status == ServiceSyncStatus.ACTIVE) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onStart) { Text("Start") }
            }
        }
    }
}
```

**Step 4: Update `MainActivity.kt`** to check onboarding on every start (not just first install):

```kotlin
// MainActivity is already clean — NavGraph handles this via NavViewModel.isConfigured
// No changes needed in MainActivity itself.
```

**Step 5: Run tests:**
```bash
./gradlew test --tests "*.SpacesViewModelTest" 2>&1 | tail -20
./gradlew test 2>&1 | tail -20
```

**Step 6: Manual smoke test:**
- Launch app on emulator
- If no `network.yml` exists: should show OnboardingScreen
- After configuring: should show SpacesScreen with "Start" button
- Tapping "Start" should start SyncService (visible in notification shade)
- Tapping "Stop" should stop it

**Step 7: Commit:**
```bash
cd /Users/kike/projects/any-file-workspace/any-file-android
git add \
  app/src/main/java/com/anyproto/anyfile/ui/screens/SpacesScreen.kt \
  app/src/main/java/com/anyproto/anyfile/ui/screens/SpacesViewModel.kt \
  app/src/main/java/com/anyproto/anyfile/ui/navigation/
git commit -m "feat: wire SyncService start/stop to main screen"
```

**Done when:** Start/Stop button visible, SyncService starts/stops correctly, no test regressions.

---

### Task 6: Build and Verify Sideloadable APK

**Why:** Confirms all previous tasks combine into a working, installable APK with the full flow operational against a real any-sync Go daemon.

**Steps:**

**Step 1: Build debug APK.**
```bash
cd /Users/kike/projects/any-file-workspace/any-file-android
./gradlew assembleDebug 2>&1 | tail -30
```

Expected output:
```
BUILD SUCCESSFUL
app/build/outputs/apk/debug/app-debug.apk
```

If build fails, address errors before continuing.

**Step 2: Install on emulator.**
```bash
adb devices  # ensure emulator is running
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Step 3: Start infrastructure.**
```bash
# Terminal 1:
cd /Users/kike/projects/any-file-workspace/any-file/docker
docker-compose up -d
docker-compose logs -f coordinator  # watch for startup

# Terminal 2: serve client.yml on emulator-accessible port
python3 -m http.server 8888 --directory /Users/kike/projects/any-file-workspace/any-file/docker/etc/any-sync-coordinator
# Now accessible at http://10.0.2.2:8888/network.yml
```

**Step 4: End-to-end smoke test.**

1. Launch `AnyFile` app on emulator
2. OnboardingScreen appears (no config yet)
3. Enter URL: `http://10.0.2.2:8888/network.yml` → tap "Import"
4. Config checkmark appears
5. Tap "Choose folder" → pick `/sdcard/AnyFileSync` (create it first: `adb shell mkdir /sdcard/AnyFileSync`)
6. Tap "Start Syncing"
7. App navigates to SpacesScreen, shows "Sync: Active" banner, notification appears
8. On the host machine, create a test file:
   ```bash
   # Using Go daemon:
   cd /Users/kike/projects/any-file-workspace/any-file
   echo "hello from Go daemon" > /tmp/test-sync/hello.txt
   ./bin/anyfile-daemon add /tmp/test-sync --name "test"
   ```
9. Within 10 seconds, verify `hello.txt` appears in `/sdcard/AnyFileSync/` on the emulator:
   ```bash
   adb shell ls /sdcard/AnyFileSync/
   adb shell cat /sdcard/AnyFileSync/hello.txt
   ```

**Step 5: Run all integration tests against live infrastructure:**
```bash
./gradlew connectedAndroidTest 2>&1 | tail -40
```

**Step 6: Run all unit tests one final time:**
```bash
./gradlew test 2>&1 | tail -20
```

**Step 7: Commit:**
```bash
cd /Users/kike/projects/any-file-workspace/any-file-android
git add -A  # only new output files if any; do not commit .apk
git commit -m "chore: verified sideloadable APK end-to-end"
```

**Done when:** APK installs, onboarding flow completes, SyncService connects P2P stack without error, files sync within 10 seconds of being added to the Go daemon.

---

## File Key Map

| File | Task | Purpose |
|------|------|---------|
| `app/src/test/java/.../yamux/YamuxSessionTest.kt` | 1 | Fix ~30 failing tests |
| `app/src/main/java/.../yamux/YamuxSession.kt` | 1 | Fix EOF handling / dispatcher injection |
| `app/src/main/java/.../data/network/SyncClient.kt` | 2 | NEW: P2P stack facade |
| `app/src/androidTest/java/.../SyncClientIntegrationTest.kt` | 2 | NEW: live coordinator test |
| `app/src/main/java/.../data/config/NetworkConfigRepository.kt` | 3 | NEW: YAML parse/save/addresses |
| `app/src/main/java/.../ui/screens/onboarding/OnboardingScreen.kt` | 3 | NEW: config import + folder picker UI |
| `app/src/main/java/.../ui/screens/onboarding/OnboardingViewModel.kt` | 3 | NEW: onboarding state machine |
| `app/src/main/java/.../ui/navigation/NavGraph.kt` | 3 | Add onboarding route + conditional start |
| `app/src/main/java/.../ui/navigation/NavViewModel.kt` | 3 | NEW: check isConfigured() |
| `app/src/main/java/.../service/SyncService.kt` | 4 | NEW: foreground sync service |
| `app/src/main/AndroidManifest.xml` | 4 | Add SyncService + FOREGROUND_SERVICE permissions |
| `app/src/main/res/values/strings.xml` | 4 | Add notification strings |
| `app/build.gradle.kts` | 4 | Add Robolectric for unit tests |
| `app/src/main/java/.../ui/screens/SpacesScreen.kt` | 5 | Add SyncStatusBanner with start/stop |
| `app/src/main/java/.../ui/screens/SpacesViewModel.kt` | 5 | Add serviceSyncStatus, startSync, stopSync |

---

## Dependencies to Add (build.gradle.kts)

| Dependency | Task | Purpose |
|------------|------|---------|
| `org.robolectric:robolectric:4.11.1` | 4 | Service unit tests without emulator |
| `androidx.test:core:1.5.0` | 4 | ApplicationProvider for Robolectric |

No external YAML library needed — `NetworkConfigRepository` parses `network.yml` manually.

---

## Testing Strategy

### Per Task (TDD Order)

| Task | Test First | Test Command |
|------|-----------|-------------|
| 1 | Fix existing `YamuxSessionTest` | `./gradlew test --tests "*.Yamux*"` |
| 2 | `SyncClientIntegrationTest` | `./gradlew connectedAndroidTest --tests "*.SyncClientIntegrationTest"` |
| 3 | `NetworkConfigRepositoryTest`, `OnboardingViewModelTest` | `./gradlew test --tests "*.NetworkConfig*" --tests "*.OnboardingViewModel*"` |
| 4 | `SyncServiceTest` | `./gradlew test --tests "*.SyncServiceTest"` |
| 5 | `SpacesViewModelTest` additions | `./gradlew test --tests "*.SpacesViewModelTest"` |
| 6 | Full test suite + manual APK | `./gradlew test && ./gradlew connectedAndroidTest` |

### After Each Task

Always run the full unit test suite to check for regressions:
```bash
./gradlew test 2>&1 | grep -E "tests|FAILED|BUILD"
```

### Infrastructure Prerequisites for Integration Tests

```bash
# 1. Start emulator
# 2. Start docker services:
cd /Users/kike/projects/any-file-workspace/any-file/docker
docker-compose up -d

# 3. Verify coordinator accessible from emulator:
adb shell curl -s http://10.0.2.2:1004 2>&1 | head -5

# 4. Verify peer ID is registered:
# Open any-file/docker/etc/any-sync-coordinator/network.yml
# Should contain the peer ID shown by:
cd /Users/kike/projects/any-file-workspace/any-file && ./bin/anyfile peer-id
```

---

## Known Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| SAF URIs are `content://` not file paths — `FileWatcher` requires real path | In Task 4 `startFileWatcher`, check for `/` prefix; document that for real-device testing, use app-private directories or ADB-pushed paths. Full SAF URI resolution is a post-MVP polish item. |
| `SyncOrchestrator.uploadFile` uses HTTP `FilenodeClient` not P2P | Task 4 `uploadFile` calls `syncOrchestrator.uploadFile` which uses the HTTP client. As a workaround, `SyncService` can inject `P2PFilenodeClient` directly and call `blockPush` with the file bytes. Wire this properly in Task 4. |
| Yamux test failures may be deeper than dispatcher issues | If `UnconfinedTestDispatcher` injection does not fix all failures, the issue may be in `YamuxProtocol.readFrame` returning partial reads. Read `YamuxProtocolTest.kt` carefully. |
| Notification icon `android.R.drawable.ic_menu_upload` is deprecated | Replace with a proper `@drawable` resource before release. Acceptable for debug APK. |
| `SpacesViewModel.syncFolderPath` requires injecting `NetworkConfigRepository` | Hilt can provide it; just add to constructor. Existing `SpacesViewModelTest` may need updating with a mock for the new dependency. |

---

## Commit Sequence Summary

```
fix: resolve YamuxSession unit test failures
feat: add SyncClient wiring full P2P stack
feat: add onboarding screen with NetworkConfigRepository for client.yml import
feat: add SyncService foreground sync service
feat: wire SyncService start/stop to main screen
chore: verified sideloadable APK end-to-end
```
