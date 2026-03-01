# E2E Test Infrastructure Fix Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix Android E2E test infrastructure issues so tests can compile and run against any-sync infrastructure.

**Architecture:** Add missing test dependencies (kotlin-test, mockk) to androidTest source set, update InfrastructureTest.kt to use current OkHttp-based client APIs, and fix UI tests with mockk.

**Tech Stack:** Android Gradle, Hilt DI, OkHttp, kotlin-test assertions, mockk

---

## Task 1: Add Test Dependencies to build.gradle.kts

**Files:**
- Modify: `app/build.gradle.kts:170-174`

**Step 1: Add kotlin-test to androidTest**

```kotlin
androidTestImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
```

**Step 2: Add mockk to androidTest**

```kotlin
androidTestImplementation("io.mockk:mockk:1.13.5")
```

**Step 3: Add kotlinx-coroutines-test to androidTest**

```kotlin
androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

**Step 4: Sync Gradle and verify compilation**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "test: add kotlin-test and mockk to androidTest dependencies"
```

---

## Task 2: Fix InfrastructureTest.kt - Imports and DI Setup

**Files:**
- Modify: `app/src/androidTest/java/com/anyproto/anyfile/e2e/InfrastructureTest.kt:1-70`

**Step 1: Fix imports - remove yamux, add OkHttp**

```kotlin
// Remove these imports (lines 8-9):
import com.anyproto.anyfile.data.network.libp2p.Libp2pKeyManager
import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsProvider
import com.anyproto.anyfile.data.network.yamux.YamuxConnectionManager

// Add these imports:
import com.anyproto.anyfile.data.network.CoordinatorClient
import com.anyproto.anyfile.data.network.FilenodeClient
import okhttp3.OkHttpClient
import javax.inject.Inject
```

**Step 2: Change constructor injection to use Hilt**

```kotlin
@HiltAndroidTest
class InfrastructureTest : E2ETestBase() {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var coordinatorClient: CoordinatorClient

    @Inject
    lateinit var filenodeClient: FilenodeClient
```

**Step 3: Update setup() method**

```kotlin
    @Before
    fun setup() {
        hiltRule.inject()
        // No manual client creation - Hilt provides them
    }
```

**Step 4: Update initializeClients() helper**

```kotlin
    // Helper function to initialize both clients (must be called from within runTest)
    private suspend fun initializeClients() {
        val coordinatorUrl = "http://${EmulatorPortForwarding.getCoordinatorHost()}:${EmulatorPortForwarding.getCoordinatorPort()}"
        val filenodeUrl = "http://${EmulatorPortForwarding.getFilenodeHost()}:${EmulatorPortForwarding.getFilenodePort()}"

        coordinatorClient.initialize(coordinatorUrl)
        filenodeClient.initialize(filenodeUrl)
    }
```

**Step 5: Verify compilation**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add app/src/androidTest/java/com/anyproto/anyfile/e2e/InfrastructureTest.kt
git commit -m "test: fix InfrastructureTest to use Hilt DI and OkHttp clients"
```

---

## Task 3: Verify and Fix Libp2pTlsIntegrationTest.kt

**Files:**
- Modify: `app/src/androidTest/java/com/anyproto/anyfile/data/network/libp2p/Libp2pTlsIntegrationTest.kt`

**Step 1: Verify imports are correct**

```kotlin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
```

These should now work after Task 1 adds kotlin-test.

**Step 2: Verify MockLibp2pServer import**

```kotlin
private lateinit var mockServer: MockLibp2pServer
```

MockLibp2pServer.kt exists and is complete.

**Step 3: Verify compilation**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit (if any changes needed)**

```bash
# Only if changes were made
git add app/src/androidTest/java/com/anyproto/anyfile/data/network/libp2p/Libp2pTlsIntegrationTest.kt
git commit -m "test: verify Libp2pTlsIntegrationTest with kotlin-test"
```

---

## Task 4: Fix SpacesScreenTest.kt - mockk Setup

**Files:**
- Modify: `app/src/androidTest/java/com/anyproto/anyfile/ui/screens/SpacesScreenTest.kt`

**Step 1: Read current file and assess issues**

Run: `cat app/src/androidTest/java/com/anyproto/anyfile/ui/screens/SpacesScreenTest.kt`

**Step 2: Fix imports**

Add/fix mockk imports:
```kotlin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
```

**Step 3: Fix mock initialization (if broken)**

Ensure mocks are properly initialized with mockk:
```kotlin
@Before
fun setup() {
    // Fix any broken mockk setup
}
```

**Step 4: Verify compilation**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/androidTest/java/com/anyproto/anyfile/ui/screens/SpacesScreenTest.kt
git commit -m "test: fix SpacesScreenTest with mockk"
```

---

## Task 5: Full Build Verification

**Files:**
- None (verification task)

**Step 1: Clean build**

Run: `./gradlew clean`

**Step 2: Compile all androidTest sources**

Run: `./gradlew compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Run unit tests to ensure no regression**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

**Step 6: Commit build verification**

```bash
# Create a git tag to mark this milestone
git tag -a e2e-test-fix-build-complete -m "E2E test infrastructure build fixed"
```

---

## Task 6: Run E2E Tests

**Files:**
- None (testing task)

**Step 1: Ensure infrastructure is running**

Run: `cd ../any-file/docker && docker compose ps`
Expected: coordinator and filenode running
If not running: `docker compose up -d`

**Step 2: Ensure emulator is running**

Run: `adb devices`
Expected: emulator listed (e.g., emulator-5554)

**Step 3: Run E2E tests**

Run: `./test-emulator-e2e.sh anyfile_emu`
Expected: Tests execute (some may fail, that's OK)

**Step 4: Check results**

Run: `adb logcat | grep -i anyfile`

**Step 7: Update PROGRESS.md with results**

Document test results in PROGRESS.md.

**Step 8: Commit**

```bash
git add PROGRESS.md
git commit -m "test: document E2E test execution results"
```

---

## Task 7: Update Documentation

**Files:**
- Modify: `../PLAN.md`
- Modify: `PROGRESS.md`

**Step 1: Update PLAN.md**

Add section about E2E test infrastructure fix completion:
```markdown
### E2E Test Infrastructure (2026-02-28)
- [x] Add kotlin-test and mockk to androidTest
- [x] Fix InfrastructureTest.kt to use OkHttp clients
- [x] Fix SpacesScreenTest.kt with mockk
- [x] Verify tests can execute
```

**Step 2: Update PROGRESS.md**

Add session log entry:
```markdown
### 2026-02-28 (Session 11 - E2E Test Infrastructure Fix)

#### Completed
1. Fixed test compilation errors
2. Added missing dependencies
3. Updated tests to use current APIs
4. Verified E2E tests can execute
```

**Step 3: Commit documentation**

```bash
git add PLAN.md PROGRESS.md
git commit -m "docs: update PLAN.md and PROGRESS.md for E2E test fix"
```

---

## Success Criteria

After completing all tasks:

- [ ] All androidTest files compile without errors
- [ ] `./gradlew compileDebugAndroidTestKotlin` passes
- [ ] `./test-emulator-e2e.sh` executes without build errors
- [ ] At least 5/7 infrastructure tests can run
- [ ] PLAN.md and PROGRESS.md updated

---

## References

- Design document: `docs/plans/2026-02-28-e2e-test-infrastructure-fix-design.md`
- Related issues: `../../../e2e-test-infrastructure-issues.md`
- Client APIs: `app/src/main/java/com/anyproto/anyfile/data/network/CoordinatorClient.kt`
