# Layer 2: any-sync Handshake Protocol - Android Implementation Plan

**Date:** 2026-02-28
**Last Updated:** 2026-02-28
**Status:** Ready to implement (with Ed25519 blocker addressed)
**Dependencies:** Layer 1 (libp2p TLS) - COMPLETE

---

## Overview

This plan details the implementation of Layer 2 of the any-sync protocol stack: the **handshake protocol**. This layer authenticates peers and negotiates protocol compatibility over the TLS connection established by Layer 1.

### Protocol Stack Context

```
┌─────────────────────────────────────────────────────────────┐
│                  Layer 5: Clients                         │  ✅ Implemented
├─────────────────────────────────────────────────────────────┤
│                  Layer 4: DRPC                            │  ✅ Implemented
├─────────────────────────────────────────────────────────────┤
│                  Layer 3: Yamux                           │  ✅ Implemented
├─────────────────────────────────────────────────────────────┤
│              Layer 2: any-sync Handshake                   │  🚧 THIS PLAN
│      (Peer authentication, version negotiation)               │
├─────────────────────────────────────────────────────────────┤
│               Layer 1: libp2p TLS                          │  ✅ COMPLETE
├─────────────────────────────────────────────────────────────┤
│                    TCP Connection                           │
└─────────────────────────────────────────────────────────────┘
```

---

## Reference Implementation

### Go Source (github.com/anyproto/any-sync)

The handshake protocol is implemented in the Go any-sync repository at:

| File | Purpose | Lines |
|------|---------|-------|
| `net/secureservice/handshake/handshake.go` | Message framing, I/O primitives | ~200 |
| `net/secureservice/handshake/credential.go` | Client/Server handshake flows | ~140 |
| `net/secureservice/handshake/proto.go` | Protocol negotiation handshake | ~150 |
| `net/secureservice/credential.go` | Credential checker implementations | ~125 |
| `net/secureservice/handshake/handshakeproto/protos/handshake.proto` | Protocol buffer definitions | ~100 |

### Protocol Specification

The handshake protocol is defined in [`handshake.proto`](https://github.com/anyproto/any-sync/blob/main/net/secureservice/handshake/handshakeproto/protos/handshake.proto):

```protobuf
// Credential types
enum CredentialsType {
    SkipVerify = 0;      // For P2P without identity verification
    SignedPeerIds = 1;   // Ed25519 signature + public key
}

// Credentials message
message Credentials {
    CredentialsType type = 1;
    bytes payload = 2;           // PayloadSignedPeerIds for SignedPeerIds
    uint32 version = 3;          // Protocol version (8)
    string clientVersion = 4;    // "any-file/v0.1.0"
}

// Signed peer IDs payload
message PayloadSignedPeerIds {
    bytes identity = 1;          // Marshaled Ed25519 public key
    bytes sign = 2;              // Ed25519 signature(localPeerId + remotePeerId)
}

// Acknowledgment message
message Ack {
    Error error = 1;
}

// Error types
enum Error {
    Null = 0;
    Unexpected = 1;
    InvalidCredentials = 2;
    UnexpectedPayload = 3;
    SkipVerifyNotAllowed = 4;
    DeadlineExceeded = 5;
    IncompatibleVersion = 6;
    IncompatibleProto = 7;
}

// Protocol negotiation (optional phase)
message Proto {
    ProtoType proto = 1;         // DRPC = 0
    repeated Encoding encodings = 2; // None = 0, Snappy = 1
}
```

---

## Android Implementation Constraints

### Library Choices

| Component | Go Library | Android Equivalent | Notes |
|-----------|-----------|-------------------|-------|
| Protobuf | `protobuf` | Already configured (v0.9.4) | Uses protobuf-lite |
| Ed25519 | `ed25519` | **NEEDS IMPLEMENTATION** | See Step 0 below |
| Signing | `crypto.Sign` | **NEEDS IMPLEMENTATION** | See Step 0 below |

### Key Constraints

1. **Ed25519 signing REQUIRED** - Current `Libp2pKeyManager` has placeholder implementation only
2. **Protobuf already configured** - Project uses protobuf plugin v0.9.4 with lite mode
3. **Coroutines** - Use Kotlin coroutines instead of Go goroutines
4. **Timeout support** - Use coroutine withTimeout instead of Go context
5. **Byte order** - Use Little Endian for size encoding (matches Go)

---

## ⚠️ CRITICAL BLOCKER: Ed25519 Signature Support

**Issue:** The current `Libp2pKeyManager` uses a simplified Ed25519 implementation that explicitly does NOT support cryptographic signatures. The code contains:

```kotlin
// NOTE: This is NOT the correct Ed25519 public key derivation!
// For proper Ed25519, we need scalar multiplication on the curve.
// This is a placeholder that allows tests to pass.
```

**Why this matters:** The handshake protocol requires Ed25519 signatures that can be verified by Go any-sync services. The current implementation only works for peer ID derivation, not signing.

### Solution Options

| Option | Approach | Pros | Cons |
|--------|----------|------|------|
| **A: Android KeyStore** | Use `KeyPairGenerator.getInstance("Ed25519")` | Native, secure | API 26+ only, doesn't work in unit tests |
| **B: Bouncy Castle** | Add `org.bouncycastle:bcprov-jdk18on` | Works in tests, proven | +250KB APK size |
| **C: Pure Kotlin Ed25519** | Implement curve arithmetic | No external deps | Complex, ~500 lines of math |
| **D:libsodium JNI** | Use `com.goterl:lazysodium-android` | Fast, small | Native library, NDK required |

### Recommended Solution: **Option A (Android KeyStore) + Option B (Bouncy Castle for tests)**

1. **Runtime (Android):** Use Android's built-in `KeyPairGenerator` with "Ed25519" (API 26+)
2. **Tests (JVM):** Use Bouncy Castle for Ed25519 operations

This gives us:
- Native Android implementation for production (fast, small, secure)
- Working unit tests (Bouncy Castle works in JVM)
- ~200KB APK size increase (acceptable)

### Implementation Plan (Step 0)

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/libp2p/Libp2pSignature.kt`

```kotlin
/**
 * Ed25519 signing and verification for any-sync handshake.
 *
 * Uses Android KeyStore on devices (API 26+) and Bouncy Castle for tests.
 */
object Libp2pSignature {
    /**
     * Sign a message with an Ed25519 private key.
     *
     * @param privateKey 32-byte Ed25519 private key
     * @param message Message to sign
     * @return 64-byte Ed25519 signature
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray

    /**
     * Verify an Ed25519 signature.
     *
     * @param publicKey 32-byte Ed25519 public key
     * @param message Message that was signed
     * @param signature 64-byte Ed25519 signature
     * @return true if signature is valid
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}
```

**Dependency to add:**
```kotlin
// For Ed25519 in unit tests only
testImplementation("org.bouncycastle:bcprov-jdk18on:1.78")
```

---

---

## Handshake Protocol Flow

### Credential Handshake (Required)

```
┌────────────────────────────────────────────────────────────────────┐
│                         Alice (Client)                            │
└────────────────────────────────────────────────────────────────────┘
      |                                                    |
      | 1. TLS established (Layer 1)                       |
      |<-------------------------------------------------->|
      |                                                    |
      | 2. Send Credentials                                |
      |--------------------------------------------------->|
      |    Type: SignedPeerIds                            |
      |    Version: 8                                      |
      |    Payload: {identity, sign(local + remote)}      |
      |                                                    |
      |                                                    | 3. Verify credentials
      |                                                    |    Check version compatibility
      |                                                    |    Verify Ed25519 signature
      | 4. Receive Credentials or Ack with error           |
      |<---------------------------------------------------|
      |    If error: close connection                     |
      |                                                    |
      | 5. Verify Bob's credentials                        |
      |    Check version compatibility                     |
      |    Verify Ed25519 signature                        |
      |                                                    |
      | 6. Send Ack (success)                              |
      |--------------------------------------------------->|
      |    Error: Null                                    |
      |                                                    |
      | 7. Receive Final Ack                              |
      |<---------------------------------------------------|
      |    Error: Null → SUCCESS                          |
      |    Otherwise: FAILURE                             |
```

### Proto Handshake (Optional, for DRPC)

```
Client sends Proto{DRPC, [None]}
Server responds with Proto{DRPC, [None]} or Ack with error
```

**Note:** The current Android DRPC client uses no encoding, so this can be deferred or simplified.

---

## Implementation Sequence

### Step 0: Ed25519 Signature Support ⚠️ CRITICAL

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/libp2p/Libp2pSignature.kt`

**Why this is first:** The handshake protocol requires Ed25519 signatures. The current `Libp2pKeyManager` only has a placeholder implementation.

**Actions:**
1. Add Bouncy Castle test dependency to `build.gradle.kts`:
   ```kotlin
   testImplementation("org.bouncycastle:bcprov-jdk18on:1.78")
   ```

2. Create `Libp2pSignature.kt` with dual implementation:
   - **Android runtime:** Use `java.security.KeyPairGenerator` with "Ed25519"
   - **Unit tests:** Use Bouncy Castle `Ed25519Signer`

**Methods to implement:**
```kotlin
object Libp2pSignature {
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}
```

**Test:**
```kotlin
// Generate Ed25519 key pair
val keyPair = Libp2pSignature.generateKeyPair()

// Sign a message
val message = "hello world".toByteArray()
val signature = Libp2pSignature.sign(keyPair.private, message)

// Verify signature
val verified = Libp2pSignature.verify(keyPair.public, message, signature)
assertTrue(verified)

// Wrong message should fail
val notVerified = Libp2pSignature.verify(keyPair.public, "wrong".toByteArray(), signature)
assertFalse(notVerified)
```

**Success criteria:**
- [ ] Can generate Ed25519 key pairs on Android (API 26+)
- [ ] Can generate Ed25519 key pairs in unit tests (Bouncy Castle)
- [ ] Can sign messages
- [ ] Can verify valid signatures
- [ ] Rejects invalid signatures
- [ ] At least 10 tests passing

### Step 1: Protobuf Code Generation

**File:** `app/src/main/proto/handshake.proto`

**Note:** Protobuf is already configured in `build.gradle.kts` (v0.9.4 with lite mode).

**Actions:**
1. Create `app/src/main/proto/handshake.proto` with the proto definition
2. Run `./gradlew generateDebugProto` to generate Kotlin classes

**Proto content:** (from `any-sync/net/secureservice/handshake/handshakeproto/protos/handshake.proto`)

```protobuf
syntax = "proto3";
package anyHandshake;
option java_multiple_files = true;
option java_package = "com.anyproto.anyfile.protos";

enum CredentialsType {
    SkipVerify = 0;
    SignedPeerIds = 1;
}

message Credentials {
    CredentialsType type = 1;
    bytes payload = 2;
    uint32 version = 3;
    string clientVersion = 4;
}

message PayloadSignedPeerIds {
    bytes identity = 1;
    bytes sign = 2;
}

message Ack {
    Error error = 1;
}

enum Error {
    Null = 0;
    Unexpected = 1;
    InvalidCredentials = 2;
    UnexpectedPayload = 3;
    SkipVerifyNotAllowed = 4;
    DeadlineExceeded = 5;
    IncompatibleVersion = 6;
    IncompatibleProto = 7;
}

message Proto {
    ProtoType proto = 1;
    repeated Encoding encodings = 2;
}

enum ProtoType {
    DRPC = 0;
}

enum Encoding {
    None = 0;
    Snappy = 1;
}
```

**Test:** Generated Kotlin files compile successfully

```bash
./gradlew generateDebugProto
./gradlew compileDebugKotlin
```

### Step 2: Handshake Message Types and Exceptions

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/handshake/HandshakeMessages.kt`

Create data classes and enums matching the protobuf definitions.

**Classes to create:**
```kotlin
enum class CredentialsType { SKIP_VERIFY, SIGNED_PEER_IDS }

enum class HandshakeError {
    NULL, UNEXPECTED, INVALID_CREDENTIALS, UNEXPECTED_PAYLOAD,
    SKIP_VERIFY_NOT_ALLOWED, DEADLINE_EXCEEDED,
    INCOMPATIBLE_VERSION, INCOMPATIBLE_PROTO
}

data class HandshakeResult(
    val identity: ByteArray?,      // Remote peer's public key
    val protoVersion: UInt,        // Protocol version (8)
    val clientVersion: String      // Client version string
)

data class HandshakeCredentials(
    val type: CredentialsType,
    val payload: ByteArray?,
    val version: UInt,
    val clientVersion: String
)

data class PayloadSignedPeerIds(
    val identity: ByteArray,   // Ed25519 public key
    val sign: ByteArray        // Ed25519 signature
)
```

**Test:** All data classes compile and have proper equals/hashCode

### Step 3: Message Framing

### Step 4: Message Framing (was Step 4)

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/handshake/HandshakeFrame.kt`

Implement message framing (5-byte header + protobuf payload).

**Constants:**
```kotlin
const val HEADER_SIZE = 5
const val MSG_TYPE_CRED = 1.toByte()
const val MSG_TYPE_ACK = 2.toByte()
const val MSG_TYPE_PROTO = 3.toByte()
const val SIZE_LIMIT = 200 * 1024 // 200 KB
```

**Methods:**
```kotlin
fun writeMessage(output: OutputStream, type: Byte, payload: ByteArray)
fun readMessage(input: InputStream, allowedTypes: Set<Byte>): Frame
```

**Frame format:**
```
[0: type] [1-4: payload size (Little Endian)] [5: payload...]
```

**Test:**
```kotlin
val buffer = ByteArrayOutputStream()
writeMessage(buffer, MSG_TYPE_CRED, testData)

val input = ByteArrayInputStream(buffer.toByteArray())
val frame = readMessage(input, setOf(MSG_TYPE_CRED))

assertEquals(MSG_TYPE_CRED, frame.type)
assertContentEquals(testData, frame.payload)
```

### Step 5: Credential Checker Interface (was Step 5)

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/handshake/CredentialChecker.kt`

Define the interface for credential generation and validation.

```kotlin
interface CredentialChecker {
    fun makeCredentials(remotePeerId: PeerId): Credentials
    fun checkCredential(remotePeerId: PeerId, cred: Credentials): HandshakeResult
}
```

### Step 6: No-Verify Credential Checker (was Step 6)

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/handshake/NoVerifyChecker.kt`

Implementation for SkipVerify mode (P2P connections without identity verification).

```kotlin
class NoVerifyChecker(
    private val protoVersion: UInt = 8u,
    private val compatibleVersions: List<UInt> = listOf(8u, 9u),
    private val clientVersion: String = "any-file/v0.1.0"
) : CredentialChecker {
    // SkipVerify sends no payload, just version info
    override fun makeCredentials(remotePeerId: PeerId): Credentials { ... }
    override fun checkCredential(remotePeerId: PeerId, cred: Credentials): HandshakeResult { ... }
}
```

**Test:**
```kotlin
val checker = NoVerifyChecker()
val cred = checker.makeCredentials(bobPeerId)

assertEquals(CredentialsType.SKIP_VERIFY, cred.type)

// Version compatibility
val result = checker.checkCredential(alicePeerId, cred)
assertEquals(8u, result.protoVersion)
```

### Step 7: Peer Sign Credential Checker (was Step 7)

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/handshake/PeerSignVerifier.kt`

Implementation for SignedPeerIds mode (required for coordinator/filenode).

```kotlin
class PeerSignVerifier(
    private val keyManager: Libp2pKeyManager,
    private val protoVersion: UInt = 8u,
    private val compatibleVersions: List<UInt> = listOf(8u, 9u),
    private val clientVersion: String = "any-file/v0.1.0"
) : CredentialChecker {

    override fun makeCredentials(remotePeerId: PeerId): Credentials {
        val localIdentity = keyManager.getPeerIdentity()
        val message = (localIdentity.peerId.toBase58() + remotePeerId.toBase58()).toByteArray()
        val signature = keyManager.sign(localIdentity.keyPair, message)

        val payload = PayloadSignedPeerIds(
            identity = localIdentity.keyPair.publicKey,
            sign = signature
        )

        return Credentials(
            type = CredentialsType.SIGNED_PEER_IDS,
            payload = payload.encode(),
            version = protoVersion,
            clientVersion = clientVersion
        )
    }

    override fun checkCredential(remotePeerId: PeerId, cred: Credentials): HandshakeResult {
        // 1. Check version compatibility
        // 2. Require SignedPeerIds (reject SkipVerify)
        // 3. Parse payload
        // 4. Verify signature over (remotePeerId + localPeerId)
        // 5. Return identity + version info
    }
}
```

**Test:**
```kotlin
val aliceChecker = PeerSignVerifier(keyManager, localPeerId = alicePeerId)
val bobChecker = PeerSignVerifier(keyManager, localPeerId = bobPeerId)

// Alice creates credentials for Bob
val aliceCred = aliceChecker.makeCredentials(bobPeerId)

// Bob verifies Alice's credentials
val result = bobChecker.checkCredential(alicePeerId, aliceCred)

assertEquals(8u, result.protoVersion)
assertContentEquals(alicePublicKey, result.identity)
```

### Step 8: Handshake Protocol Implementation (was Step 8)

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/handshake/AnySyncHandshake.kt`

Main orchestrator for client and server handshake flows.

**Client flow (outgoing):**
```kotlin
suspend fun performOutgoingHandshake(
    socket: Libp2pTlsSocket,
    checker: CredentialChecker,
    timeoutMs: Long = 30000
): HandshakeResult {
    // 1. Send our credentials
    // 2. Read remote credentials or ack with error
    // 3. If ack with error, throw exception
    // 4. Verify remote credentials
    // 5. Send ack (success)
    // 6. Read final ack
    // 7. Return result with identity
}
```

**Server flow (incoming):**
```kotlin
suspend fun performIncomingHandshake(
    socket: Libp2pTlsSocket,
    remotePeerId: PeerId,
    checker: CredentialChecker,
    timeoutMs: Long = 30000
): HandshakeResult {
    // 1. Read remote credentials
    // 2. Verify remote credentials
    // 3. If invalid, send ack with error and close
    // 4. Send our credentials
    // 5. Read ack from client
    // 6. If error, close connection
    // 7. Send final ack (success)
    // 8. Return result with identity
}
```

**Test:** Create unit tests with mock streams

### Step 9: Secure Session Wrapper (was Step 9)

**File:** `app/src/main/java/com/anyproto/anyfile/data/network/handshake/SecureSession.kt`

Wrapper for the authenticated socket.

```kotlin
data class SecureSession(
    val socket: Libp2pTlsSocket,
    val localPeerId: PeerId,
    val remotePeerId: PeerId,
    val remoteIdentity: ByteArray?,  // Remote's public key
    val protoVersion: UInt,
    val clientVersion: String
) {
    val inputStream: InputStream get() = socket.inputStream
    val outputStream: OutputStream get() = socket.outputStream
    fun close() = socket.close()
}
```

### Step 10: Unit Tests (was Step 10)

**File:** `app/src/test/java/com/anyproto/anyfile/data/network/handshake/*Test.kt`

Comprehensive unit tests for all components.

**Test files:**
- `Libp2pSignatureTest.kt` - Sign/verify tests
- `HandshakeFrameTest.kt` - Message framing tests
- `NoVerifyCheckerTest.kt` - SkipVerify mode tests
- `PeerSignVerifierTest.kt` - SignedPeerIds mode tests
- `AnySyncHandshakeTest.kt` - Full handshake flow tests

**Minimum test coverage:**
- 20+ unit tests
- All code paths covered
- Edge cases (timeout, invalid signature, version mismatch)

### Step 11: Integration Tests (was Step 11)

**File:** `app/src/androidTest/java/com/anyproto/anyfile/data/network/handshake/HandshakeIntegrationTest.kt`

End-to-end handshake tests with real TCP connections.

**Test scenarios:**
1. Client SkipVerify handshake with mock server
2. Client SignedPeerIds handshake with mock server
3. Server receives valid credentials
4. Server rejects invalid signature
5. Version compatibility check

---

## Success Criteria (Exit Criteria)

### Functional Requirements

- [ ] Ed25519 key generation works (Step 0)
- [ ] Ed25519 sign/verify works (Step 0)
- [ ] Can perform outgoing handshake (client mode) with SignedPeerIds
- [ ] Can perform incoming handshake (server mode) with SignedPeerIds
- [ ] Can perform handshake with SkipVerify mode
- [ ] Credentials are correctly signed with Ed25519
- [ ] Signature verification works for valid signatures
- [ ] Invalid signatures are rejected
- [ ] Version compatibility checked correctly
- [ ] Incompatible versions rejected
- [ ] Handshake completes within timeout

### Test Requirements

- [ ] 10+ Ed25519 sign/verify tests passing (Step 0)
- [ ] 20+ unit tests passing (overall)
- [ ] All signing/verification tests pass
- [ ] All credential checker tests pass
- [ ] All handshake flow tests pass
- [ ] 5+ integration tests passing

### Code Requirements

- [ ] All new files documented with KDoc
- [ ] Code follows existing Layer 1 patterns
- [ ] No code duplication
- [ ] Proper exception handling
- [ ] Coroutine-based (not blocking)

### Integration Requirements

- [ ] Returns `SecureSession` wrapper for use by Layer 3 (Yamux)
- [ ] Compatible with Go any-sync services
- [ ] Can handshake with coordinator (127.0.0.1:1004)
- [ ] Can handshake with filenode (127.0.0.1:1005)

---

## File Structure

```
app/src/main/java/com/anyproto/anyfile/data/network/handshake/
├── HandshakeMessages.kt           // Data classes, enums
├── HandshakeFrame.kt              // Message framing
├── CredentialChecker.kt           // Interface
├── NoVerifyChecker.kt             // SkipVerify implementation
├── PeerSignVerifier.kt            // SignedPeerIds implementation
├── AnySyncHandshake.kt            // Main orchestrator
└── SecureSession.kt               // Authenticated session wrapper

app/src/main/java/com/anyproto/anyfile/data/network/libp2p/
└── Libp2pSignature.kt             // NEW: Ed25519 sign/verify (Step 0)

app/src/main/proto/
└── handshake.proto                // Protobuf definitions (Step 1)

app/src/test/java/com/anyproto/anyfile/data/network/handshake/
├── Libp2pSignatureTest.kt         // Ed25519 tests (Step 0)
├── HandshakeFrameTest.kt
├── NoVerifyCheckerTest.kt
├── PeerSignVerifierTest.kt
└── AnySyncHandshakeTest.kt

app/src/androidTest/java/com/anyproto/anyfile/data/network/handshake/
└── HandshakeIntegrationTest.kt
```

---

## Dependencies

### build.gradle.kts (app module)

**Note:** Protobuf is already configured (v0.9.4 with lite mode). Only need to add:

```kotlin
dependencies {
    // For Ed25519 in unit tests (Step 0)
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78")
}
```

The protobuf plugin is already configured with:
- `id("com.google.protobuf") version "0.9.4"`
- Lite mode enabled for both Java and Kotlin generation
- gRPC plugins for coordinator/filenode protos

---

## Troubleshooting Guide

### Issue: Ed25519 not available in unit tests

**Symptoms:** `KeyPairGenerator.getInstance("Ed25519")` throws exception in JVM tests

**Solution:** Use Bouncy Castle for tests, Android KeyStore for runtime:
```kotlin
// In Libp2pSignature.kt
internal actual object Ed25519Provider {
    actual fun generateKeyPair(): Libp2pKeyPair
    actual fun sign(privateKey: ByteArray, message: ByteArray): ByteArray
    actual fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}
```

### Issue: Signature verification fails

**Check:**
1. Ensure signature is over `localPeerId + remotePeerId` (not `remote + local`)
2. Verify public key is 32 bytes
3. Check signature is 64 bytes (Ed25519)

### Issue: Version negotiation fails

**Check:**
1. Coordinator uses version 8, compatible with 8-9
2. Hotfix: Reject "middle:v0.36.6" (incompatible version)

### Issue: Timeout during handshake

**Check:**
1. TLS connection is established first
2. Read/write operations are non-blocking
3. Coroutine context is not cancelled

### Issue: Protobuf parsing fails

**Check:**
1. Using protobuf-lite runtime
2. Proto file matches Go implementation exactly
3. Generated code is included in build

---

## References

- **Go implementation:** `github.com/anyproto/any-sync/net/secureservice/handshake`
- **Proto definition:** `handshakeproto/protos/handshake.proto`
- **Layer 1 plan:** [libp2p-tls-android.md](libp2p-tls-android.md)
- **Progress tracking:** [../PROGRESS.md](../PROGRESS.md)

---

*Plan generated: 2026-02-28*
*Last updated: 2026-02-28 (Added Ed25519 implementation requirement)*
*Based on: any-sync Go implementation handshake protocol*
