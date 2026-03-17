# Testing Guide — any-file-android

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | `brew install openjdk@17` |
| Android Studio | Hedgehog+ | Or just Android SDK CLI tools |
| Android SDK | API 34 | Install via SDK Manager |
| `adb` | any | Comes with Android SDK platform-tools |
| Go daemon | latest | Required for E2E tests only |

Verify setup:
```bash
java -version          # should show 17+
adb devices            # should show 'List of devices attached'
./gradlew --version    # should show Gradle version
```

---

## Build the APK

```bash
# Debug APK (for development/testing)
make build
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (unsigned)
make build-release
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Install on Emulator or Device

```bash
# Install debug APK on connected device/emulator
make install
# or: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

One-step build + install:
```bash
make install-build
```

---

## Unit Tests

Unit tests run on the JVM — no Android device needed. They test cryptographic primitives, protocol logic, and data processing.

```bash
make test
# or: ./gradlew test
```

Expected: all tests pass in ~30 seconds. There are 5 known pre-existing failures:
- `DrpcClientTest` x3 — RPC concurrency edge cases (non-blocking)
- `Libp2pKeyManagerTest` x2 — multihash format assertions (non-blocking)

Run a specific test class:
```bash
./gradlew test --tests "com.anyproto.anyfile.data.network.libp2p.Libp2pKeyManagerTest"
```

With coverage report:
```bash
make test-coverage
# Opens: app/build/reports/coverage/test/debug/index.html
```

---

## Instrumentation Tests (Android Device/Emulator)

These tests run on a real Android device or emulator and test integration between components.

### Setup

1. Start an emulator (API 34 recommended):
   ```bash
   # List available AVDs
   emulator -list-avds

   # Start an AVD
   emulator -avd Pixel_6_API_34 &

   # Verify it's up
   adb devices
   ```

2. Run tests:
   ```bash
   make test-android
   # or: ./gradlew connectedAndroidTest
   ```

Run a specific instrumentation test:
```bash
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.anyproto.anyfile.data.network.libp2p.Libp2pTlsIntegrationTest
```

---

## E2E Tests (Emulator + Go Daemon)

The E2E test validates the full protocol stack against a real any-sync coordinator and filenode.

### What you need

1. **Go daemon running** — the any-file Go daemon with infrastructure up
2. **Android emulator running** — API 26+, x86_64 recommended
3. **Network connectivity** — emulator reaches host via `10.0.2.2`

### Step 1: Start the Go infrastructure

```bash
cd ../any-file
make docker-up        # Start coordinator, filenode, consensusnode, minio
make build-daemon     # Build the Go daemon
```

Verify coordinator is up:
```bash
curl http://localhost:1004/v1/network/config
```

### Step 2: Start the Android emulator

```bash
emulator -avd Pixel_6_API_34 &
adb wait-for-device
```

Test emulator → host connectivity:
```bash
adb shell curl http://10.0.2.2:1004/v1/network/config
```

Should return JSON config. If not, check the emulator's network settings or restart Docker.

### Step 3: Run the E2E test

```bash
make test-e2e
# or: ./test-emulator-e2e.sh
```

The script:
1. Installs the debug APK
2. Pushes a test `client.yml` to the emulator
3. Triggers the SyncClient to connect to the coordinator
4. Verifies the handshake completes and space info is retrieved
5. Reports PASS or FAIL with logs

---

## Manual Smoke Test

To manually verify the app works end-to-end after changes:

1. Start Go infrastructure: `cd ../any-file && make docker-up`
2. Build and install APK: `make install-build`
3. Open app on emulator
4. Import `any-file/docker/etc/client.yml` via the onboarding screen
5. Pick a local folder to sync
6. The `SyncService` should start and connect
7. Check logcat for connection confirmation:
   ```bash
   adb logcat -s SyncClient:D CoordinatorClient:D
   ```
   Look for: `"SpaceInfo received"` or `"Handshake complete"`

---

## Pre-commit Hooks

### Install

```bash
make setup
# or:
pre-commit install
pre-commit install --hook-type pre-push
```

### What runs when

| Event | Hooks |
|-------|-------|
| `git commit` | trailing-whitespace, end-of-file-fixer, check-yaml, check-added-large-files (500KB), gitleaks |
| `git push` | `./gradlew test` (unit tests) |

The 500 KB limit blocks APKs, compiled `.so` files, and other build artifacts from being committed accidentally.

### Run manually

```bash
pre-commit run --all-files
```

---

## Lint

```bash
make lint
# Opens: app/build/reports/lint-results-debug.html
```

---

## Common Issues

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` when installing APK**
Clear the app first: `adb shell pm clear com.anyproto.anyfile`, then reinstall.

**Emulator can't reach coordinator (`Connection refused` on 10.0.2.2:1004)**
- Verify Docker is running: `cd ../any-file && docker-compose -f docker/docker-compose.yml ps`
- Verify coordinator is listening: `curl http://localhost:1004/v1/network/config`
- Try restarting the emulator

**`Forbidden` from coordinator during E2E**
The Android peer ID may not be registered. Check the test script — it should auto-register, or manually add the peer ID to `../any-file/docker/etc/any-sync-coordinator/network.yml` and restart coordinator.

**Gradle build fails with `SDK not found`**
Set `ANDROID_HOME`:
```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```
Or create `local.properties` in the project root:
```
sdk.dir=/Users/YOUR_NAME/Library/Android/sdk
```
