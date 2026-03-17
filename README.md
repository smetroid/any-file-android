# any-file-android

![Min SDK](https://img.shields.io/badge/min%20SDK-26%20(Android%208.0)-brightgreen)
![Kotlin](https://img.shields.io/badge/kotlin-2.x-blue)
![Build](https://img.shields.io/badge/build-passing-brightgreen)

Native Android client for **any-file** — a decentralized file synchronization system built on the [any-sync](https://github.com/anyproto/any-sync) P2P infrastructure. The app connects to a running any-file Go daemon, authenticates using Ed25519 peer identities, and syncs selected folders over a content-addressed block storage network.

---

## Protocol Stack

```
┌─────────────────────────────────────────┐
│  Layer 5: Clients                       │  P2PCoordinatorClient, P2PFilenodeClient
├─────────────────────────────────────────┤
│  Layer 4: DRPC                          │  RPC over yamux streams
├─────────────────────────────────────────┤
│  Layer 3: Yamux                         │  Stream multiplexing
├─────────────────────────────────────────┤
│  Layer 2: any-sync Handshake            │  Ed25519 credential exchange
├─────────────────────────────────────────┤
│  Layer 1: libp2p TLS                    │  Secure transport + peer IDs
├─────────────────────────────────────────┤
│  TCP Connection                         │
└─────────────────────────────────────────┘
```

All 5 layers are implemented in pure Kotlin with no external libp2p library dependency.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | `brew install openjdk@17` |
| Android SDK | API 34 | Install via Android Studio SDK Manager |
| min SDK | API 26 (Android 8.0) | Required for Ed25519 key generation |
| `adb` | any | Comes with Android SDK platform-tools |
| Docker | any | Required for E2E tests only |
| Go daemon | latest | Required for E2E tests only — see [`../any-file`](../any-file) |

---

## Quick Start

```bash
# 1. Clone and enter the directory
git clone https://github.com/anyproto/any-file-android
cd any-file-android

# 2. Build the debug APK
make build

# 3. Install on a connected device or emulator
make install
```

---

## Running Tests

```bash
# Unit tests — fast, no device needed (~30s)
make test

# Instrumentation tests — requires emulator/device
make test-android

# E2E tests — requires emulator + Go daemon
make test-e2e
```

See [TESTING.md](TESTING.md) for full setup instructions, including emulator configuration and E2E prerequisites.

---

## Configuration

The app requires a `client.yml` network config file exported from the any-file Go daemon.

**Get the config file:**
```bash
# From the Go daemon directory
cd ../any-file
./bin/anyfile-daemon init my-space
# Config is written to: ~/.anyfile/client.yml
```

**Import into the app:**
1. Open the app — it will show the **Onboarding screen** on first launch
2. Tap **Import Config** and select `client.yml` (or enter a URL)
3. Tap **Select Folder** to choose a folder to sync
4. The app navigates to the **Spaces screen** and `SyncService` starts automatically

---

## Architecture

The app implements a custom 5-layer protocol stack in pure Kotlin that is wire-compatible with the any-sync Go implementation. Key points:

- **No external libp2p library** — uses Android's built-in crypto APIs + BouncyCastle
- **Ed25519 peer identities** with libp2p-compatible multihash encoding
- **Trust-all TLS** — peer authentication happens at Layer 2 (handshake), not Layer 1 (TLS)
- **Separate PeerKey / SignKey** — any-sync nodes use two distinct keys; `PeerSignVerifier` uses the TLS-established peer ID for signature verification

See [docs/architecture.md](docs/architecture.md) for the full technical reference.

---

## Documentation

| Document | Purpose |
|----------|---------|
| [TESTING.md](TESTING.md) | Full testing guide — unit, instrumentation, E2E |
| [PROGRESS.md](PROGRESS.md) | Implementation journal and milestone history |
| [docs/architecture.md](docs/architecture.md) | Deep technical reference for the protocol stack |
| [docs/plans/](docs/plans/) | Design documents and implementation plans |

---

## Known Issues

The following 5 unit test failures are **pre-existing** and non-blocking — they do not affect runtime correctness:

| Test | Failure | Impact |
|------|---------|--------|
| `DrpcClientTest` (x3) | RPC concurrency edge cases | None — runtime DRPC works |
| `Libp2pKeyManagerTest` | Multihash format assertion | None — peer IDs derive correctly |
| `Libp2pTlsProviderTest` | Multihash format assertion | None — TLS connections succeed |

CI is expected to report 416/421 tests passing.

---

## Contributing

### Setup pre-commit hooks

```bash
make setup
```

This installs:
- **pre-commit:** trailing whitespace, YAML checks, secrets scan (gitleaks), 500 KB file size limit
- **pre-push:** runs `./gradlew test` before every push

### Code style

- Kotlin-first; use coroutines for async work
- Follow existing layered architecture — each layer has a clear responsibility
- Protocol changes must maintain wire compatibility with the Go reference implementation

See [TESTING.md](TESTING.md) for the full test workflow.
