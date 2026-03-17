# Architecture: any-file-android

Technical reference for the protocol stack and app architecture.

---

## Overview

The Android client implements a 5-layer protocol stack in pure Kotlin, wire-compatible with the any-sync Go reference implementation.

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 5: Clients                                               │
│  P2PCoordinatorClient  ·  P2PFilenodeClient                     │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: DRPC                                                  │
│  DrpcClient  ·  DrpcMessage  ·  DrpcCodec                       │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Yamux                                                 │
│  YamuxSession  ·  YamuxStream  ·  YamuxConnectionManager        │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: any-sync Handshake                                    │
│  AnySyncHandshake  ·  PeerSignVerifier  ·  NoVerifyChecker      │
├─────────────────────────────────────────────────────────────────┤
│  Layer 1: libp2p TLS                                            │
│  Libp2pTlsProvider  ·  Libp2pKeyManager  ·  Libp2pCertificateGenerator │
├─────────────────────────────────────────────────────────────────┤
│  TCP Socket                                                     │
└─────────────────────────────────────────────────────────────────┘
```

### Connection establishment sequence

```
Client                                      Server (any-sync node)
  │                                              │
  │──── TCP connect ─────────────────────────►  │
  │                                              │
  │  [Layer 1: libp2p TLS]                       │
  │──── TLS ClientHello (ALPN "anysync") ──────► │
  │◄─── TLS ServerHello + cert ──────────────── │
  │──── TLS Finished ──────────────────────────► │
  │                          TLS tunnel active   │
  │                                              │
  │  [Layer 2: any-sync Handshake]               │
  │──── HandshakeFrame(credentials) ───────────► │
  │◄─── HandshakeFrame(credentials) ──────────── │
  │──── HandshakeFrame(ack) ────────────────────► │
  │◄─── HandshakeFrame(final ack) ─────────────── │
  │                       Peer identities verified │
  │                                              │
  │  [Layer 3: Yamux]                            │
  │◄──────── yamux frames ──────────────────────► │
  │          (SYN/ACK/DATA/FIN/GO_AWAY)           │
  │                                              │
  │  [Layer 4: DRPC over yamux stream]           │
  │──── length-prefixed protobuf request ──────► │
  │◄─── length-prefixed protobuf response ─────── │
```

---

## Layer 1: libp2p TLS

**Package:** `data/network/libp2p/`

### `Libp2pTlsProvider`

Creates an `SSLSocket` with:
- ALPN protocol `"anysync"`
- BouncyCastle as the JCE provider
- A trust-all `TrustManager` (peer authentication is deferred to Layer 2)
- The local `Libp2pKeyManager` wired in for key access

Returns a `Libp2pTlsSocket` which carries the local peer ID alongside the raw `SSLSocket`.

### `Libp2pKeyManager`

Manages Ed25519 key pairs and peer ID derivation:

- Generates an Ed25519 key pair on first use (persisted in app storage)
- Derives **peer ID** using the identity multihash format:
  ```
  multihash = 0x00 (identity codec) ‖ 0x24 (36 bytes) ‖ proto-encoded public key
  peer ID   = base58btc(multihash)
  ```
  This is the libp2p identity-hash format (not SHA-256), required by any-sync nodes.

### `Libp2pCertificateGenerator`

Generates a self-signed X.509 certificate used for TLS:
- Key type: **ECDSA P-256** (required by Android's Conscrypt TLS stack)
- Contains the libp2p extension OID `1.3.6.1.4.1.53594.1.1` with the raw Ed25519 public key
- Separates TLS encryption (ECDSA P-256 cert) from peer identity (Ed25519)

### `Libp2pKeyPair.encodePublicKeyProto()`

Encodes the Ed25519 public key as the 34-byte `crypto.Key` proto format expected by any-sync Go nodes:

```
field 2 (wire type 2) = 0x12
length 32             = 0x20
[32 bytes raw Ed25519 public key]
```

The `Type` field (ED25519_PUBLIC = 0) is the proto3 default and is omitted.

---

## Layer 2: any-sync Handshake

**Package:** `data/network/handshake/`

### `AnySyncHandshake`

Singleton object that drives the 6-step outgoing handshake:

1. Send our `HandshakeFrame` with credentials
2. Read the remote `HandshakeFrame` with their credentials
3. Verify the remote credentials (via `CredentialChecker`)
4. Send ack frame (success)
5. Read the final ack from the server
6. Return a `SecureSession` containing the verified remote peer ID

Frames are length-prefixed protobuf over the raw TLS byte stream (before yamux).

### `PeerSignVerifier`

The production `CredentialChecker`. Handles both sides:

**Generating credentials (`makeCredentials`):**
- Signs `(localPeerId.base58 + remotePeerId.base58)` with the local Ed25519 private key
- Encodes the public key as a 34-byte any-sync `crypto.Key` proto payload

**Verifying credentials (`checkCredential`):**

> **Critical:** any-sync nodes have **two separate keys** — `PeerKey` (peer identity) and `SignKey` (signing key). The `credential.identity` field contains the `SignKey`'s public key, which may produce a different peer ID than the node's actual TLS peer ID.

The fix: use the **TLS-established `remotePeerId`** directly in the verification message, not a peer ID derived from the credential's signing key:

```kotlin
// CORRECT — matches Go's checkCredential:
val message = (remotePeerId.base58 + localPeerId.base58).toByteArray(Charsets.UTF_8)
pubKey.verify(message, signature)

// WRONG — would fail for nodes with separate PeerKey / SignKey:
// val derivedId = derivePeerIdFromPublicKey(credentialIdentityKey)
// val message = (derivedId.base58 + localPeerId.base58).toByteArray(...)
```

### `NoVerifyChecker`

A skip-verify `CredentialChecker` that accepts any remote credentials. Used only in tests.

---

## Layer 3: Yamux

**Package:** `data/network/yamux/`

### `YamuxSession`

Implements the [yamux multiplexing protocol](https://github.com/hashicorp/yamux/blob/master/spec.md) over a TCP/TLS byte stream:

- Frame types: `SYN`, `ACK`, `DATA`, `FIN`, `GO_AWAY`
- 12-byte header: `version(1) | type(1) | flags(2) | streamId(4) | length(4)`
- Coroutine-based frame reader loop; each stream backed by a `Channel<ByteArray>`
- **47 unit tests** covering all frame types and edge cases

### `YamuxStream`

An individual bidirectional stream within a `YamuxSession`. Exposes `InputStream`/`OutputStream` interfaces so the DRPC layer can write protobuf without knowing about yamux.

### `YamuxConnectionManager`

The integration point for Layers 1–3:

```kotlin
suspend fun getSession(host: String, port: Int): YamuxSession
```

Implementation:
1. TCP connect to `host:port`
2. Layer 1: `Libp2pTlsProvider.connect()` → `Libp2pTlsSocket`
3. Layer 2: `AnySyncHandshake.performClientHandshake()` → `SecureSession`
4. Layer 3: `YamuxSession(tlsSocket.inputStream, tlsSocket.outputStream, isClient = true)`

Sessions are cached by `"host:port"` key in a `ConcurrentHashMap`. Subsequent calls for the same endpoint reuse the existing session.

---

## Layer 4: DRPC

**Package:** `data/network/drpc/`

### `DrpcClient`

Opens a new yamux stream for each RPC call and exchanges length-prefixed protobuf frames:

```
┌──────────┬───────────────────┐
│ 4 bytes  │ N bytes           │
│ length   │ DrpcMessage proto │
└──────────┴───────────────────┘
```

### `DrpcMessage` / `DrpcCodec`

- `DrpcMessage` — request/response envelope with service name, method name, and payload bytes
- `DrpcCodec` — serialises/deserialises `DrpcMessage` from wire bytes

---

## Layer 5: P2P Clients

**Package:** `data/network/p2p/`

### `P2PCoordinatorClient`

RPC methods over DRPC:
- `SpaceSign` — register a space with the coordinator
- `SpaceStatusCheck` — query space ACL status
- `NetworkConfiguration` — fetch the network peer list

### `P2PFilenodeClient`

RPC methods over DRPC:
- `BlockPush` — upload a content-addressed block
- `SpaceInfo` — query usage/limit stats for a space

Both clients call `YamuxConnectionManager.getSession(host, port)` to get a session, then open a new yamux stream per RPC call via `DrpcClient`.

---

## SyncClient Facade

**File:** `data/network/SyncClient.kt`

Singleton (`@Singleton` via Hilt) that wires all 5 layers together:

```kotlin
@Singleton
class SyncClient @Inject constructor(
    private val connectionManager: YamuxConnectionManager,
    private val coordinatorClient: P2PCoordinatorClient,
    private val filenodeClient: P2PFilenodeClient,
    private val tlsProvider: Libp2pTlsProvider,
)
```

Usage pattern:

```kotlin
val coordinator = syncClient.connectCoordinator("10.0.2.2", 1004)
val spaceInfo = coordinator.spaceStatusCheck(spaceId)

val filenode = syncClient.connectFilenode("10.0.2.2", 1005)
filenode.blockPush(spaceId, blockData)

syncClient.disconnect()  // closes all cached yamux sessions
```

---

## App Architecture

### Onboarding flow

```
App launch
  └─ NavViewModel.isConfigured()
        ├─ false → OnboardingScreen
        │    ├─ Step 1: Import client.yml (URL or file picker)
        │    │    └─ NetworkConfigRepository.save(yaml)
        │    └─ Step 2: Pick sync folder (SAF)
        │         └─ NetworkConfigRepository.syncFolderPath = uri
        └─ true  → SpacesScreen
```

`NavGraph` uses `popUpTo(inclusive=true)` when navigating from onboarding to spaces so the back button does not return to onboarding.

### Sync loop

```
SyncService (foreground, START_STICKY, dataSync)
  └─ runSyncLoop() — coroutine on Dispatchers.IO
        ├─ SyncClient.connectCoordinator()
        ├─ FileWatcher.start(syncFolderPath)
        │    └─ FileUploadCoordinator
        │         └─ P2PFilenodeClient.blockPush() on new/changed files
        └─ repeat every 10s
```

`SyncService` is started/stopped from `SpacesViewModel` via `SyncService.start(context)` / `SyncService.stop(context)`. On `onDestroy`, the service scope is cancelled first, then a fire-and-forget coroutine handles `SyncClient.disconnect()`.

### Config

**`NetworkConfigRepository`** (`data/config/`):
- Parses `client.yml` with a hand-written YAML parser (no library dependency)
- Exposes `getCoordinatorAddress()` and `getFilenodeAddress()` for the sync loop
- Stores the sync folder URI in `SharedPreferences`
- `isConfigured()` returns true when both a network config and a sync folder are set

### Dependency injection

All network singletons are provided by `NetworkModule` (Hilt, `SingletonComponent`):

```
NetworkModule
  ├─ Libp2pKeyManager         (singleton)
  ├─ Libp2pTlsProvider        (singleton, depends on KeyManager)
  ├─ YamuxConnectionManager   (singleton, depends on TlsProvider)
  ├─ P2PCoordinatorClient     (singleton, depends on ConnectionManager)
  ├─ P2PFilenodeClient        (singleton, depends on ConnectionManager)
  └─ SyncClient               (singleton, depends on all above)
```

`SyncModule` provides `SyncService`-scoped components (FileWatcher, FileUploadCoordinator).

---

## Key Design Decisions

### 1. No external libp2p library

All layers are implemented in pure Kotlin using Android's built-in JCE APIs and BouncyCastle. This avoids the JNI complexity of porting Go/Rust libp2p to Android and keeps the APK small (~22 MB).

### 2. ECDSA P-256 for TLS cert, Ed25519 for peer identity

Android's Conscrypt TLS stack requires standard X.509 key types. Ed25519 is not supported as a TLS certificate key on all Android versions. The solution: use ECDSA P-256 for the TLS handshake and carry the Ed25519 identity in a libp2p extension OID inside the cert. Peer authentication is then handled at Layer 2.

### 3. Trust-all TrustManager at Layer 1

The TLS layer does not verify the server's certificate chain. This is intentional — the any-sync protocol performs mutual peer authentication at Layer 2 (handshake) using Ed25519 signatures over peer IDs. Certificate-level trust would add no security benefit and would break with self-signed node certs.

### 4. Separate PeerKey / SignKey (the critical protocol insight)

any-sync Go nodes have two distinct Ed25519 keys:
- `PeerKey` — determines the node's peer ID (used in TLS and peer discovery)
- `SignKey` — signs handshake credentials (the `identity` field in `HandshakeCredentials`)

The `identity` field in the credential is the **SignKey's** public key, not the PeerKey. Deriving a peer ID from `identity` yields a different value than the node's actual peer ID. `PeerSignVerifier.checkCredential` must use the `remotePeerId` established at the TLS layer for signature verification.

### 5. Session caching in YamuxConnectionManager

A single yamux session (and therefore a single TCP/TLS connection) is maintained per `host:port`. DRPC opens a new yamux stream for each RPC call but reuses the underlying session. This minimises connection setup overhead while keeping streams independent.
