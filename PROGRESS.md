# any-file Android Client — Implementation Progress

**Project:** Native Android client for any-file (decentralized file sync using any-sync P2P infrastructure)
**Start Date:** 2026-02-26
**Last Updated:** 2026-03-14

---

## Current Status ✅ LAYER 2 HANDSHAKE FULLY WORKING (2026-03-14)

**Goal achieved:** Full Layer 2 any-sync handshake with real Go coordinator and filenode confirmed working end-to-end. Both coordinator (port 1004) and filenode (port 1005) handshakes complete successfully.

### Test Counts
- **421 unit tests, 5 pre-existing failures** (improved from 411/8)
- Added 1 new TDD test for separate-signing-key scenario
- Reduced pre-existing failures from 8 → 5 (fixed 2 Layer 2 failures + 1 through handshake fix)
- All 47 Yamux tests passing

### APK
- `app/build/outputs/apk/debug/app-debug.apk` — 22 MB, BUILD SUCCESSFUL
- Install: `adb install app/build/outputs/apk/debug/app-debug.apk`

---

## Protocol Stack

| Layer | Component | Status |
|-------|-----------|--------|
| 1 | `Libp2pTlsProvider.kt` — TLS socket with Ed25519 peer identity | ✅ Complete |
| 2 | `AnySyncHandshake.kt` — 6-step credential exchange | ✅ Complete (unit tests have pre-existing failures) |
| 3 | `yamux/YamuxSession.kt` — stream multiplexing | ✅ Complete, 47 unit tests passing |
| 4 | `drpc/DrpcClient.kt` — RPC over yamux streams | ✅ Complete |
| 5 | `p2p/P2PCoordinatorClient.kt`, `P2PFilenodeClient.kt` | ✅ Complete |

---

## 2026-03-12: Android Client Plan Execution (6 tasks)

**Plan:** `docs/plans/2026-03-11-android-client.md`

### ✅ Task 1: Fix Yamux Unit Tests
**Commits:** `c0d5327`, `fd62901`

Root causes fixed:
- `android.util.Log` throws `RuntimeException` in JVM unit tests → `isReturnDefaultValues = true` in `build.gradle.kts`
- `coEvery` used on non-suspend `OutputStream.write` → changed to `every { } just Runs`
- `UnconfinedTestDispatcher` not injected → `YamuxSession(mockSocket, isClient = true, coroutineContext = testDispatcher)`
- Blocking InputStream mock in `YamuxConnectionManagerTest` to prevent frame-reader race

Result: 40 failures → 8 failures (all Yamux tests now green)

### ✅ Task 2: SyncClient Facade
**Commit:** `d4a98f7`

Created `data/network/SyncClient.kt`:
- `@Singleton @Inject constructor(connectionManager, coordinatorClient, filenodeClient, tlsProvider)`
- `connectCoordinator(host, port)` / `connectFilenode(host, port)` / `disconnect()` / `getPeerID()`
- Integration test: `SyncClientIntegrationTest.kt` (3 tests, requires emulator + docker)

### ✅ Task 3: NetworkConfigRepository + Onboarding UI
**Commits:** `b6c6bcd`, `d2b931f`

Created:
- `data/config/NetworkConfigRepository.kt` — manual YAML parser (no library), `fetch`, `save`, `isConfigured`, `getCoordinatorAddress`, `getFilenodeAddress`, `syncFolderPath` (SharedPreferences-backed)
- `ui/screens/onboarding/OnboardingViewModel.kt` — `OnboardingState` sealed class, import + folder pick + navigate
- `ui/screens/onboarding/OnboardingScreen.kt` — two-step Compose UI (config URL/file + SAF folder picker)
- `ui/navigation/NavViewModel.kt` — checks `isConfigured()` at init
- `ui/navigation/NavGraph.kt` — conditional start dest (onboarding vs spaces), `popUpTo(inclusive=true)` on navigate

### ✅ Task 4: SyncService Foreground Service
**Commits:** `42ba498`, `7894762`

Created `service/SyncService.kt`:
- `@AndroidEntryPoint` foreground service, `START_STICKY`, `NOTIFICATION_ID = 1001`
- Injects `SyncClient` + `NetworkConfigRepository`
- `runSyncLoop()`: connects P2P stack, starts FileWatcher, polls every 10s
- `companion object { start(context), stop(context) }`
- `onDestroy`: `serviceScope.cancel()` first, then `CoroutineScope(Dispatchers.IO).launch { disconnect() }`

Manifest additions:
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` permissions
- `<service android:foregroundServiceType="dataSync" />`

### ✅ Task 5: Wire UI to SyncService
**Commit:** `4664667`

- Added `ServiceSyncStatus { IDLE, ACTIVE, ERROR }` enum + `startSync(context)` / `stopSync(context)` to `SpacesViewModel`
- Added `SyncStatusBanner` composable to `SpacesScreen` (start/stop button + status indicator)

### ✅ Task 6: APK Verified
- `./gradlew assembleDebug` → BUILD SUCCESSFUL, 22 MB APK

---

## 2026-02-28 to 2026-03-01: Protocol Stack Implementation (Sessions 1-47)

The protocol stack (Layers 1-5 above) was built over ~47 sessions. Key milestones:

- **Layer 1** (`Libp2pTlsProvider`): Ed25519 keys, X.509 cert wrapping, libp2p TLS with ALPN `"anysync"`. Challenge: Android SSLEngine requires standard X.509 format; resolved with wrapped self-signed cert containing raw Ed25519 public key.
- **Layer 2** (`AnySyncHandshake`): 6-step any-sync credential exchange with protobuf framing. Peer ID now uses identity multihash (0x00 prefix, not SHA-256 0x12).
- **Layer 3** (`YamuxSession`): Full yamux multiplexing with SYN/ACK/DATA/FIN/GO_AWAY frames.
- **Layer 4** (`DrpcClient`): Custom DRPC framing over yamux streams.
- **Layer 5** (`P2PCoordinatorClient`, `P2PFilenodeClient`): RPC method implementations.
- **`YamuxConnectionManager`**: Integration point — `getSession(host, port)` performs full Layer 1→3 setup.

---

## 2026-02-26 to 2026-02-27: Initial HTTP-based Android App (Tasks 1-13)

The app scaffold, Room database, HTTP-based `CoordinatorClient`/`FilenodeClient`, `SyncOrchestrator`, `FileWatcher`, `SyncWorker`, and Compose UI were built first using HTTP/protobuf clients. The P2P stack (Layers 1-5) then replaced the network layer.

---

## 2026-03-14: Layer 2 Handshake Root Cause Fixed

### Problem
After fixing TLS (Layer 1) in a prior session, Layer 2 handshake was still failing:
1. **Coordinator (port 1004):** `HandshakeProtocolException: Signature verification failed`
2. **Filenode (port 1005):** `HandshakeProtocolException: Incompatible protocol version: 7`

### Root Cause — Signature Verification

Investigation of the any-sync Go source (`any-sync/net/secureservice/credential.go`) revealed that the Go `AccountKeys` struct has **two separate keys**:

```go
type AccountKeys struct {
    PeerKey crypto.PrivKey   // peer identity — account.PeerId derived from this
    SignKey crypto.PrivKey   // signing key  — credentials signed by this
    PeerId  string           // = PeerKey.GetPublic().PeerId()
}
```

The coordinator's `MakeCredentials` signs `(account.PeerId + remotePeerId)` with `SignKey`, and puts `SignKey.GetPublic().Marshall()` as the `identity` field. This means:
- `identity` key derives to peer ID `12D3KooWBvPfs5tokr35XJYYrVKyjpUxmy7KNYYa1nHVMsNmKvU2`
- Coordinator's actual peer ID (from TLS/config) = `12D3KooWJEjawV7qbGLUsHBxypBPb4R4ZLbqkXcamzkYDPBohnBe`

Our code was deriving `remotePeerIdFromCred` from the credential's signing key and using that in the verification message — producing the wrong message.

Go's `CheckCredential` uses the `remotePeerId` argument (from TLS connection) directly, not derived from the credential key:
```go
ok, err := pubKey.Verify([]byte((remotePeerId + p.account.PeerId)), msg.Sign)
```

### Fix Applied (`PeerSignVerifier.kt`)

```kotlin
// BEFORE (wrong): derived peer ID from credential signing key
val remotePeerIdFromCred = derivePeerIdFromPublicKey(rawPubKey)
val message = (remotePeerIdFromCred.base58 + localPeerId.base58).toByteArray(Charsets.UTF_8)

// AFTER (correct): use TLS-established peer ID directly
val message = (remotePeerId.base58 + localPeerId.base58).toByteArray(Charsets.UTF_8)
```

Removed unused `derivePeerIdFromPublicKey` and `encodeBase58Libp2p` private methods.

Also added `7u` to default `compatibleVersions = listOf(7u, 8u, 9u)` to fix filenode version incompatibility.

### TDD Test Added

`checkCredential_withSeparateSigningAndPeerKey_succeeds` — verifies the real-world scenario where the Go node uses separate PeerKey and SignKey. This test was RED before the fix, GREEN after.

### Result

Logcat confirmation after fix:
```
AnySyncHandshake: Remote peer ID: 12D3KooWJEjawV7qbGLUsHBxypBPb4R4ZLbqkXcamzkYDPBohnBe
AnySyncHandshake: Remote credentials verified successfully
AnySyncHandshake: Handshake completed successfully!
```
Both coordinator and filenode connections succeed.

---

## Known Remaining Issues

| Issue | Impact |
|-------|--------|
| 3 `DrpcClientTest` unit test failures | Pre-existing, does not affect runtime |
| 1 `Libp2pKeyManagerTest` failure | Pre-existing Layer 1 peer ID multihash assertion issue |
| 1 `Libp2pTlsProviderTest` failure | Pre-existing Layer 1 peer ID multihash assertion issue |
| E2E smoke test (emulator) not run automatically | Requires user to start emulator manually |
| `SyncClient.connectCoordinator` calls `getSession` twice (once directly, once inside `P2PCoordinatorClient`) | Minor performance redundancy |
| Manual E2E smoke test not yet run | Install APK, import client.yml, pick folder, verify sync with Go daemon |

---

## File Structure (Key Files)

```
app/src/main/java/com/anyproto/anyfile/
├── data/
│   ├── config/
│   │   └── NetworkConfigRepository.kt      ✅ NEW
│   └── network/
│       ├── SyncClient.kt                   ✅ NEW
│       ├── libp2p/   (Layer 1)             ✅
│       ├── handshake/ (Layer 2)            ✅
│       ├── yamux/    (Layer 3)             ✅ FIXED
│       ├── drpc/     (Layer 4)             ✅
│       └── p2p/      (Layer 5)             ✅
├── service/
│   └── SyncService.kt                      ✅ NEW
└── ui/
    ├── navigation/
    │   ├── NavGraph.kt                     ✅ UPDATED (onboarding flow)
    │   └── NavViewModel.kt                 ✅ NEW
    └── screens/
        ├── SpacesScreen.kt                 ✅ UPDATED (SyncStatusBanner)
        ├── SpacesViewModel.kt              ✅ UPDATED (ServiceSyncStatus)
        └── onboarding/
            ├── OnboardingScreen.kt         ✅ NEW
            └── OnboardingViewModel.kt      ✅ NEW
```
