# Design: CI/CD Pipeline + Test Fixes

**Date:** 2026-03-16
**Status:** Approved

---

## Problem

1. Five pre-existing unit test failures block the pre-push hook, requiring `--no-verify` on every push.
2. No CI/CD pipeline exists — there is no automated verification on PRs or merges.

---

## Test Fixes

All 5 failures are stale tests; production code is correct. No tests are removed.

### DrpcClientTest (3 failures)

**Root cause:** `DrpcClient.callAsync` calls `stream.waitForOpen()` after opening the yamux stream. This method was added to the implementation after the tests were written. MockK throws `MockKException` on unstubbed suspend methods.

**Fix:** Add `coEvery { mockStream.waitForOpen() } just Runs` to the mock setup of the 3 affected tests:
- `test successful RPC call`
- `test RPC call with server error`
- `test coordinatorCall extension function`

### Libp2pKeyManagerTest (1 failure)

**Root cause:** `derivePeerId produces correct multihash format` asserts SHA-256 multihash format (`[0] == 0x12`, `[1] == 32`, `size == 34`). The implementation was updated to use **identity multihash** (`0x00` codec) to match the any-sync protocol requirement. The assertions were not updated.

**Fix:** Update assertions:
- `multihash[0] == 0x00` (identity codec)
- `multihash[1] == 0x24` (36 — length of proto-encoded public key)
- `multihash.size == 38` (2-byte header + 36-byte proto key)

### Libp2pTlsProviderTest (1 failure)

**Root cause:** Same stale SHA-256 multihash assertion in `getPeerIdentity returns valid peer ID`.

**Fix:** Same update as above (`0x00`, `0x24`, size 38).

---

## CI/CD Pipeline

**Platform:** GitHub Actions
**Config location:** `.github/workflows/ci.yml`

### Triggers

```yaml
on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:
```

### Job 1: `build` (fast, ~3-5 min)

Runs on all triggers. Steps:

1. `actions/checkout@v4`
2. `actions/setup-java@v4` — JDK 17, Temurin distribution
3. Gradle cache — `~/.gradle/caches`, `~/.gradle/wrapper`
4. `./gradlew test` — 421 unit tests (~30s)
5. `./gradlew lint` — Android lint
6. `./gradlew assembleDebug` — verify APK builds
7. `actions/upload-artifact@v4` — upload `app-debug.apk`

### Job 2: `emulator-tests` (slow, ~15 min)

Runs on `push` to `main` and `workflow_dispatch` only (not on every PR).
Depends on `build` passing (`needs: build`).

Steps:

1. `actions/checkout@v4`
2. `actions/setup-java@v4` — JDK 17
3. Gradle cache
4. Enable KVM for hardware acceleration (Linux runner)
5. `reactivecircus/android-emulator-runner@v2`
   - `api-level: 29`
   - `arch: x86_64`
   - `profile: Nexus 6`
   - `script: ./gradlew connectedAndroidTest`

### Gradle cache key

```yaml
key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
restore-keys: ${{ runner.os }}-gradle-
```

---

## Success Criteria

- `./gradlew test` passes 421/421 (no `--no-verify` needed)
- PRs show green `build` check within ~5 min
- `emulator-tests` job runs on merge to main and on demand
- APK artifact available for download from every successful `main` build
