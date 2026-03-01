# Claude Code Context: any-file-android

Android client implementation of the any-sync file synchronization protocol.

## Project Structure

```
any-file-android/
├── app/
│   └── src/
│       ├── main/java/com/anyproto/anyfile/
│       │   ├── data/
│       │   │   ├── network/
│       │   │   │   ├── libp2p/              # Layer 1: libp2p TLS
│       │   │   │   │   ├── Libp2pKeyManager.kt       # Ed25519 keys, peer IDs
│       │   │   │   │   ├── Libp2pTlsProvider.kt      # TLS with peer identity
│       │   │   │   │   └── Libp2pTlsSocket.kt        # Socket wrapper
│       │   │   │   ├── yamux/               # Layer 3: Stream multiplexing
│       │   │   │   │   ├── YamuxMultiplexer.kt       # Multiplexer implementation
│       │   │   │   │   ├── YamuxStream.kt            # Stream abstraction
│       │   │   │   │   └── YamuxConfig.kt            # Configuration
│       │   │   │   ├── drpc/                # Layer 4: RPC protocol
│       │   │   │   │   ├── DrpcClient.kt             # RPC client
│       │   │   │   │   ├── DrpcServer.kt             # RPC server
│       │   │   │   │   ├── DrpcMessage.kt            # Message format
│       │   │   │   │   └── DrpcCodec.kt              # Encoding/decoding
│       │   │   │   └── clients/             # Layer 5: Service clients
│       │   │   │       ├── CoordinatorClient.kt      # Coordinator RPC client
│       │   │   │       └── FilenodeClient.kt         # Filenode RPC client
│       │   │   └── ...
│       │   ├── di/                          # Dependency Injection (Hilt)
│       │   │   └── NetworkModule.kt         # Network providers
│       │   └── ...
│       ├── test/                            # Unit tests (JVM)
│       │   └── java/com/anyproto/anyfile/
│       │       └── data/network/
│       │           └── libp2p/
│       │               ├── Libp2pKeyManagerTest.kt
│       │               └── Libp2pTlsProviderTest.kt
│       └── androidTest/                     # Instrumentation tests
│           └── java/com/anyproto/anyfile/
│               └── data/network/
│                   └── libp2p/
│                       ├── MockLibp2pServer.kt
│                       └── Libp2pTlsIntegrationTest.kt
│       └── AndroidManifest.xml
├── build.gradle.kts         # Project build config
├── app/build.gradle.kts     # App module build config
├── settings.gradle.kts      # Gradle settings
├── gradle.properties        # Gradle properties (versions, etc)
├── gradlew                  # Gradle wrapper
├── test-emulator-e2e.sh     # E2E test runner for emulator
└── PROGRESS.md              # Implementation progress tracking
```

## Key Technologies

| Technology | Purpose |
|------------|---------|
| Kotlin | Primary language |
| Hilt | Dependency injection |
| Coroutines | Async operations |
| Java Security | Ed25519, SHA-256, TLS |
| Okio | Buffered I/O |
| Protobuf | Serialization (via DRPC) |
| JUnit | Unit testing |
| MockK | Mocking framework |

## Protocol Stack Implementation

```
┌─────────────────────────────────────────┐
│  Layer 5: Clients                       │  ✅ CoordinatorClient, FilenodeClient
├─────────────────────────────────────────┤
│  Layer 4: DRPC                          │  ✅ Custom RPC over yamux streams
├─────────────────────────────────────────┤
│  Layer 3: Yamux                         │  ✅ Stream multiplexing
├─────────────────────────────────────────┤
│  Layer 2: any-sync Handshake            │  🚧 IN PROGRESS
├─────────────────────────────────────────┤
│  Layer 1: libp2p TLS                    │  ✅ Ed25519 keys, peer IDs, TLS 1.3
├─────────────────────────────────────────┤
│  TCP Connection                         │
└─────────────────────────────────────────┘
```

## Build Commands

```bash
# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug to connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run Android instrumentation tests
./gradlew connectedAndroidTest

# Run specific test class
./gradlew test --tests Libp2pKeyManagerTest

# Run with coverage
./gradlew testDebugUnitTestCoverage
```

## Testing

### Unit Tests
```bash
# Run all unit tests
./gradlew test

# Run specific test
./gradlew test --tests Libp2pKeyManagerTest.testKeyGeneration

# Run with coverage report
./gradlew testDebugUnitTestCoverage
# Report: app/build/reports/coverage/test/debug/index.html
```

### Instrumentation Tests
```bash
# Requires connected device or emulator
adb devices

./gradlew connectedAndroidTest

# Run specific test
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.anyproto.anyfile.data.network.libp2p.Libp2pTlsIntegrationTest
```

### E2E Tests (Emulator)
```bash
# Start emulator first, then:
./test-emulator-e2e.sh
```

## Implementation Details

### Layer 1: libp2p TLS ✅

**Files:**
- `Libp2pKeyManager.kt` (~370 lines) - Ed25519 key generation, peer ID derivation
- `Libp2pTlsProvider.kt` (~290 lines) - TLS configuration with peer identity
- `Libp2pTlsSocket.kt` - Socket wrapper with peer information

**Key Features:**
- Pure Kotlin Ed25519 implementation (no external dependencies)
- libp2p-compatible peer IDs (SHA-256 multihash + base58 encoding)
- TLS 1.3 with ALPN protocol "anysync"
- SNI support for server identification

**Tests:** 23 unit tests passing (15 Libp2pKeyManager + 8 Libp2pTlsProvider)

### Layer 3: Yamux ✅

**Files:**
- `YamuxMultiplexer.kt` - Main multiplexer implementation
- `YamuxStream.kt` - Stream abstraction
- `YamuxConfig.kt` - Configuration constants

**Key Features:**
- Compatible with go-libp2p yamux
- Stream multiplexing over TCP/TLS
- Header compression
- Backpressure handling

### Layer 4: DRPC ✅

**Files:**
- `DrpcClient.kt` - RPC client implementation
- `DrpcServer.kt` - RPC server implementation
- `DrpcMessage.kt` - Message format (request/response)
- `DrpcCodec.kt` - Protobuf encoding/decoding

**Key Features:**
- Request/response pattern over yamux streams
- Protobuf serialization
- Service registration
- Coroutine-based async handling

### Layer 5: Clients ✅

**Files:**
- `CoordinatorClient.kt` - Coordinator service client
- `FilenodeClient.kt` - Filenode service client

**Key Features:**
- gRPC-compatible protocol over DRPC
- Space management
- File block operations

## Dependency Injection

All network components provided via Hilt in `NetworkModule.kt`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideLibp2pKeyManager(): Libp2pKeyManager

    @Provides
    @Singleton
    fun provideLibp2pTlsProvider(keyManager: Libp2pKeyManager): Libp2pTlsProvider
}
```

## Network Configuration

### Default Addresses
- Coordinator: `127.0.0.1:1004` (host) → `10.0.2.2:1004` (emulator)
- Filenode: `127.0.0.1:1005` (host) → `10.0.2.2:1005` (emulator)

### ALPN Protocols
- Primary: `anysync`
- Fallback: `h2`, `http/1.1`

### TLS Configuration
- Min version: TLS 1.2
- Preferred: TLS 1.3
- Cipher suites: System defaults

## Testing Notes

### Unit Tests (JVM)
- Run on JVM (no Android device required)
- Fast execution
- Mock network operations

### Integration Tests (Android)
- Require device or emulator
- Test real network operations
- Use mock servers for isolation

### E2E Tests (Emulator)
- Full roundtrip with real coordinator
- Requires any-sync infrastructure running
- Special network config (`10.0.2.2` for host access)

## Common Issues

### Emulator Networking
```bash
# Emulator cannot reach localhost
# Use 10.0.2.2 instead of 127.0.0.1

# Test from emulator shell
adb shell
$ ping 10.0.2.2
$ curl http://10.0.2.2:1004
```

### Test Failures
```bash
# Clean and rebuild
./gradlew clean
./gradlew build

# Clear app data
adb shell pm clear com.anyproto.anyfile
```

### Dependency Issues
```bash
# Update dependencies
./gradlew dependencies --refresh-dependencies

# View dependency tree
./gradlew app:dependencies
```

## Design Decisions

1. **No external libp2p library** - Pure Kotlin implementation for Android compatibility
2. **Separation of concerns** - TLS provides encryption, Layer 2 provides authentication
3. **Standard Android APIs** - Uses built-in Ed25519 support (API 26+)
4. **Protocol compatibility** - Matches go-libp2p behavior exactly

## Next Steps

**Current Focus:** Layer 2 (any-sync Handshake) implementation

See [PROGRESS.md](PROGRESS.md) for detailed progress tracking.

## Reference Implementations

- `github.com/anyproto/any-sync` - Go reference implementation
- `github.com/libp2p/go-libp2p` - libp2p Go implementation
- `github.com/hashicorp/yamux` - Yamux spec

## Notes for Claude

1. **API level 26+ required** - Uses Ed25519 key generation
2. **Kotlin-first** - Prefer Kotlin idioms over Java patterns
3. **Coroutines for async** - Use structured concurrency
4. **Test before implementing** - Follow TDD workflow
5. **Protocol compatibility is critical** - Must match Go implementation exactly
6. **Emulator networking** - Use `10.0.2.2` not `localhost` for host services
