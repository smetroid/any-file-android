# Android Client Design

**Date:** 2026-03-11
**Status:** Approved

## Goal

A sideloadable Android APK that lets a user import a `client.yml` config file, pick a folder, and sync files with a self-hosted any-file network — no Play Store, no technical setup beyond installing the APK.

## Current State

| Layer | Status |
|-------|--------|
| Layer 1: libp2p TLS | ✅ 23 unit tests passing |
| Layer 2: any-sync Handshake | ✅ ~95% done, unit + integration tests passing |
| Layer 3: Yamux | ⚠️ Code exists, 40 unit tests failing |
| Layer 4: DRPC | ✅ Implemented |
| Layer 5: Coordinator/Filenode clients | ✅ Implemented |
| UI | ❌ Not built |

## Architecture

Four sequential phases:

1. **Fix Yamux** — resolve 40 failing `YamuxSessionTest` unit tests
2. **Wire the stack** — connect Layer 1→2→3→4→5 into `SyncClient.kt`, prove with an E2E instrumentation test against the Go coordinator
3. **Background sync service** — Android foreground service running the sync loop
4. **Minimal UI** — onboarding screen + main status screen

## Components

### Task 1 — Fix Yamux (Layer 3)
Diagnose and fix the 40 failing `YamuxSessionTest` failures. Likely causes: stream state machine bug, wrong frame byte ordering, or test setup issues. All Yamux tests must pass before proceeding.

### Task 2 — `SyncClient.kt` + integration test
Wire Layer 1→2→3→4→5 into a single `SyncClient` class. Write one Android instrumentation test that:
- Connects to `10.0.2.2:1004` (emulator's host address for the Go coordinator)
- Completes the full handshake
- Uploads a test file via BlockPush
- Downloads it back via BlockGet

This is the proof the full stack works end-to-end.

### Task 3 — `SyncService.kt`
Android foreground service that:
- Holds a `SyncClient` instance
- Watches the sync folder using `FileObserver`
- Uploads new/changed files to the filenode via BlockPush
- Polls for remote files every 10s via FilesGet, downloads missing via BlockGet
- Shows a persistent notification ("Syncing…" / "Up to date")
- Pauses when network is unavailable (via `ConnectivityManager`), resumes when restored

### Task 4 — Onboarding screen
Single activity:
- "Import config" → file picker for `.yml` or text field for HTTPS URL
- Fetches + validates `client.yml` (must have ≥1 coordinator node)
- Saves `network.yml` to app private dir
- POSTs peer ID to `registrationUrl` if present (same flow as `anyfile init --network`)
- "Pick sync folder" → folder picker
- "Start syncing" → launches `SyncService`, transitions to main screen

### Task 5 — Main screen
Single activity showing:
- Sync status (Active / Paused / Error)
- Sync folder path
- File count and last sync time
- Start / Stop button

### Task 6 — Build sideloadable APK
`./gradlew assembleRelease` signed with a debug key. Produces `app-release.apk` with install instructions (`adb install` or direct file transfer).

## Data Flow

```
User imports client.yml
  → validated + saved as network.yml in app private dir
  → peer ID registered with sidecar POST /register (if registrationUrl present)
  → user picks sync folder
  → SyncService starts

SyncService loop (every 10s):
  → FileObserver detects new/changed local file
    → SyncClient.upload() → BlockPush to filenode
  → FilesGet polls filenode for remote file list
    → download missing files via BlockGet
    → write to sync folder
```

## Error Handling

| Error | Behavior |
|-------|----------|
| `client.yml` invalid / no coordinator | Inline error on onboarding, don't proceed |
| Registration sidecar unreachable | Warning shown, proceed (network config still saved) |
| Handshake fails | Retry 3× with backoff; show "Connection failed" in notification |
| Upload fails | Mark pending, retry on next poll cycle |
| Download fails | Retry 3× with backoff; continue with other files |
| No network | Service pauses; resumes on connectivity restored |
| Sync folder missing | Notification "Sync folder missing", pause service |

**No conflict resolution in v1** — remote wins on conflict (overwrite). Conflict copy logic deferred.

## Testing

| Test | Type |
|------|------|
| All Yamux unit tests pass | Unit (JVM) |
| Full handshake with Go coordinator | Android instrumentation |
| Upload file → filenode has it | Android instrumentation |
| Download file → appears in folder | Android instrumentation |
| Valid `client.yml` → saves `network.yml` | Unit (JVM) |
| Invalid YAML → error shown | Unit (JVM) |
| No coordinator node → error shown | Unit (JVM) |
| `SyncService` starts/stops cleanly | Unit (JVM, Robolectric) |
| Install APK, import config, sync a file | Manual smoke test |

Instrumentation tests require docker-compose services running on host, reachable from emulator at `10.0.2.2:1004` (coordinator) and `10.0.2.2:1005` (filenode).

## Out of Scope (v1)

- Conflict resolution (remote wins)
- Multiple sync folders
- Play Store distribution
- Background sync when app is force-stopped (requires WorkManager — deferred)
- File browser UI
