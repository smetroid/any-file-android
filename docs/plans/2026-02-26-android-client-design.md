# any-file Android Client Design

**Date:** 2026-02-26
**Status:** Draft
**Type:** New Feature Design

## Context

This document describes the design for a native Android client for **any-file** - a decentralized file synchronization daemon using the any-sync P2P infrastructure.

The any-file project currently supports desktop environments (Linux, macOS) and uses:
- **any-sync-coordinator** - Manages peer registration and space creation
- **any-sync-consensusnode** - Handles ACL synchronization
- **any-sync-filenode** - Stores content-addressed file blocks
- **P2P networking** - LAN-first routing with internet fallback

This Android client will bring full bi-directional file sync to Android devices.

## Problem Statement

Users with Android devices need to synchronize files with their desktop/laptop machines running any-file. The Android client must:

1. Participate as a full peer in the any-sync P2P network
2. Support bi-directional sync (changes on Android sync to desktop, and vice versa)
3. Handle Android-specific constraints (battery, background execution, network types)
4. Provide a native Android user experience

## Solution Overview

### Architecture: Pure Kotlin with P2P Reimplementation

We chose to reimplement the any-sync P2P stack in pure Kotlin rather than using gomobile bindings. This gives:

**Pros:**
- Native Android performance
- Smaller APK size (no Go runtime)
- Full control over P22 behavior
- No Go/Kotlin boundary overhead

**Cons:**
- Significant P2P code to rewrite
- Longer initial development time
- Maintenance burden (keep in sync with Go any-sync)

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    any-file Android App                     │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────┐  │
│  │  UI Layer (Jetpack Compose)                          │  │
│  └────────────────────┬─────────────────────────────────┘  │
│                       ▼                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Domain Layer (Sync, File Watching, Conflicts)      │  │
│  └────────────────────┬─────────────────────────────────┘  │
│                       ▼                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  any-sync Kotlin Layer (Coordinator, Filenode,       │  │
│  │                       Transport, Consensus)        │  │
│  └────────────────────┬─────────────────────────────────┘  │
│                       ▼                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Storage (Room DB, File System, Keystore)          │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                           │ HTTP/gRPC
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              any-sync Network (coordinator, filenode)       │
└─────────────────────────────────────────────────────────────┘
```

## Component Design

### 1. any-sync Kotlin Layer

#### CoordinatorClient
```kotlin
interface CoordinatorClient {
    suspend fun registerPeer(): PeerId
    suspend fun createSpace(request: SpaceCreateRequest): SpaceInfo
    suspend fun signSpace(request: SpaceSignRequest): SpaceReceipt
    suspend fun getNetworkConfig(): NetworkConfiguration
}
```
Implements peer registration and space creation via HTTP/gRPC to coordinator.

#### FilenodeClient
```kotlin
interface FilenodeClient {
    suspend fun blockPush(spaceId: String, fileId: String, cid: ByteArray, data: ByteArray)
    suspend fun blockGet(spaceId: String, cid: ByteArray): ByteArray
    suspend fun filesList(spaceId: String): List<String>
    suspend fun fileGet(spaceId: String, fileId: String): FileMetadata
}
```
Implements content-addressed block storage with blake3 hashing.

#### Transport Layer
- **MVP:** gRPC over HTTP
- **Future:** QUIC (via cronet/quiche) for LAN-first P2P

### 2. Domain Layer

#### SyncOrchestrator
Coordinates bi-directional sync, handles conflicts, manages sync state.

#### FileWatcher
Detects local file changes using Android FileObserver.

#### ConflictResolver
Handles sync conflicts with strategies: LATEST_WINS, MANUAL, BOTH_KEEP.

### 3. Storage Layer

#### Room Database
- `Space` - Sync spaces
- `SyncedFile` - File metadata and sync state
- `Peer` - Known network peers

#### File Storage
- Android scoped storage for synced files
- Content-addressed cache for frequently accessed blocks

## Data Flow

### Upload Flow
1. FileWatcher detects local file change
2. Calculate blake3 hash of file
3. Split file into blocks (chunking)
4. Upload blocks to Filenode (skipping duplicates)
5. Create file metadata
6. Update local database
7. Notify other devices via Consensus

### Download Flow
1. Receive notification from Consensus (ACL update)
2. Query Filenode for updated file metadata
3. Compare local vs remote versions
4. Download missing blocks
5. Reassemble file from blocks
6. Write to local storage
7. Update local database

## Background Sync

Uses Android WorkManager for reliable background sync:
- Periodic sync every 15 minutes
- Only runs when network is available
- Respects battery and data saver preferences
- Exponential backoff on failures

## Key Dependencies

```kotlin
// Core
androidx.core:core-ktx:1.12.0
androidx.room:room-runtime:2.6.0
androidx.work:work-runtime-ktx:2.9.0

// UI
androidx.compose.ui:ui:1.5.0
androidx.compose.material3:material3:1.1.0

// Network
com.squareup.okhttp3:okhttp:4.11.0
io.grpc:grpc-okhttp:1.56.1

// Crypto
network.bytefiddler:crypt:0.1.0  // Ed25519
com.github.blake3:blake3:0.9.0

// DI
com.google.dagger:hilt-android:2.48
```

## Implementation Phases

### Phase 1: Foundation (Weeks 1-3)
- Project setup (Android Studio, Gradle, Compose)
- Room database schema
- Key storage (Android Keystore)
- Proto buffer definitions from any-sync
- gRPC client stubs

### Phase 2: Core P2P (Weeks 4-7)
- CoordinatorClient implementation
- FilenodeClient implementation
- HTTP/gRPC transport layer
- Basic error handling and retry logic

### Phase 3: Sync Engine (Weeks 8-11)
- FileWatcher implementation
- SyncOrchestrator
- Upload/download flows
- Conflict resolution
- WorkManager integration

### Phase 4: UI (Weeks 12-15)
- Main screen (spaces, sync status)
- File browser
- Settings (account, sync preferences, network)

### Phase 5: Polish (Weeks 16-18)
- Error reporting (Sentry)
- Analytics
- Performance optimization
- Security audit
- QUIC transport (optional)

## Success Criteria

1. ✅ Can register peer with coordinator
2. ✅ Can create/join sync spaces
3. ✅ Can upload files and download on another device
4. ✅ Background sync works reliably
5. ✅ Handles conflicts gracefully
6. ✅ Works on Android 8+ (API 26+)

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| P2P stack reimplementation takes longer | High | Start with HTTP/gRPC, add QUIC later |
| Android background execution limits | High | Use WorkManager, handle Doze mode |
| Large file handling | Medium | Chunked uploads with progress tracking |
| Network changes during sync | Medium | Robust error handling, auto-retry |
| Storage quota issues | Medium | Pre-upload checks, user notifications |

## Next Steps

This design provides the foundation for implementing the any-file Android client. The next phase is to create a detailed implementation plan using the writing-plans skill.
