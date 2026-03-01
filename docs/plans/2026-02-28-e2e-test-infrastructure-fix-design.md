# E2E Test Infrastructure Fix Design

**Date:** 2026-02-28
**Status:** Approved
**Related:** [e2e-test-infrastructure-issues.md](../../../e2e-test-infrastructure-issues.md)

---

## Overview

Fix Android E2E test infrastructure issues that prevent execution of end-to-end tests against any-sync infrastructure. The tests will be updated to work with the current OkHttp-based client APIs, while a separate migration plan will be created for future yamux/DRPC integration.

---

## Problem Statement

The Android E2E tests cannot execute due to pre-existing test infrastructure issues:

1. **Missing test dependencies** - `kotlin.test` and `mockk` not available in `androidTest` source set
2. **API mismatch** - Tests expect yamux-based client APIs, but actual clients use OkHttp
3. **MockLibp2pServer** - Was missing (now fixed)

### Current Client APIs (Actual)

```kotlin
// CoordinatorClient.kt
class CoordinatorClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    fun initialize(baseUrl: String) { ... }
}

// FilenodeClient.kt
class FilenodeClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    fun initialize(baseUrl: String) { ... }
}
```

### Test Expectations (Incorrect)

```kotlin
// InfrastructureTest.kt - WRONG
coordinatorClient = CoordinatorClient(yamuxConnectionManager)
coordinatorClient.initialize(host, port)  // Wrong signature
```

---

## Design Decisions

### Decision 1: Use Current OkHttp-based API (Quick Fix)

**Rationale:**
- The CoordinatorClient and FilenodeClient already work with OkHttp
- Minimal code changes required
- Gets tests passing quickly
- Allows verification of protocol stack implementation

**Trade-off:**
- Doesn't validate yamux/DRPC integration
- Separate migration planned for future

### Decision 2: Add kotlin.test and mockk to androidTest

**Rationale:**
- Consistent assertion library across test code
- Better test readability with kotlin.test assertions
- mockk needed for UI tests (SpacesScreenTest)

**Dependencies to Add:**
```kotlin
androidTestImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
androidTestImplementation("io.mockk:mockk:1.13.5")
androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

---

## Architecture

### Test Layer Structure

```
androidTest/
├── java/com/anyproto/anyfile/
│   ├── e2e/
│   │   ├── InfrastructureTest.kt      (Fix: use OkHttp DI)
│   │   ├── E2ETestBase.kt             (No changes)
│   │   └── TestSetup.kt               (No changes)
│   ├── data/network/libp2p/
│   │   ├── Libp2pTlsIntegrationTest.kt (Fix: assertions work with kotlin-test)
│   │   └── MockLibp2pServer.kt         (Already complete ✅)
│   └── ui/screens/
│       └── SpacesScreenTest.kt        (Fix: mockk setup)
```

### Component Changes

#### 1. build.gradle.kts
Add missing test dependencies for androidTest source set.

#### 2. InfrastructureTest.kt
```kotlin
// Before (WRONG):
val yamuxConnectionManager = YamuxConnectionManager(tlsProvider)
coordinatorClient = CoordinatorClient(yamuxConnectionManager)
coordinatorClient.initialize(host, port)

// After (CORRECT):
// CoordinatorClient and FilenodeClient injected via Hilt
coordinatorClient.initialize("http://10.0.2.2:1004")
filenodeClient.initialize("http://10.0.2.2:1005")
```

#### 3. SpacesScreenTest.kt
Fix mockk initialization and test assertions.

---

## Data Flow

### E2E Test Execution Flow

```
1. Start infrastructure (Docker)
   ├── Coordinator on 127.0.0.1:1004
   └── Filenode on 127.0.0.1:1005

2. Start Android emulator
   └── Can access host via 10.0.2.2

3. Run E2E tests
   ├── TestSetup provides emulator addresses (10.0.2.2:1004-1005)
   ├── Hilt injects CoordinatorClient/FilenodeClient with OkHttp
   ├── Clients initialize with base URLs
   └── Tests verify connectivity and basic operations
```

---

## Error Handling

### Build Failures
- Add dependencies incrementally
- Verify each compiles before moving to next

### Runtime Failures
- Keep existing try/catch patterns
- Use Result<> types for error propagation
- Log connection errors for debugging

---

## Testing Strategy

### Unit Tests
- No changes needed (already passing)

### Integration Tests (androidTest)
- Fix compilation errors
- Verify Hilt DI works correctly
- Test against real infrastructure

### Success Criteria
- All androidTest files compile without errors
- Infrastructure tests can execute
- At least 5/7 infrastructure tests pass
  (2 may fail due to protocol issues, which is expected)

---

## Future Work: Yamux/DRPC Migration

A separate plan will be created to:
1. Create Yamux-based client implementations
2. Update CoordinatorClient/FilenodeClient to use yamux
3. Implement full DRPC protocol
4. Migrate E2E tests to use yamux APIs

This is **out of scope** for the current fix.

---

## Files to Modify

| File | Changes | Lines |
|------|---------|-------|
| `app/build.gradle.kts` | Add 3 dependencies | ~5 |
| `InfrastructureTest.kt` | Fix client construction/init | ~30 |
| `SpacesScreenTest.kt` | Fix mockk setup | ~20 |

---

## Exit Criteria

- [ ] All androidTest files compile successfully
- [ ] `./gradlew compileDebugAndroidTestKotlin` passes
- [ ] `./test-emulator-e2e.sh` executes without build errors
- [ ] At least 5/7 infrastructure tests can run
- [ ] Design document committed to git
