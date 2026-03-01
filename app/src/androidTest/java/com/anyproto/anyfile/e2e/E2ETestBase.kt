package com.anyproto.anyfile.e2e

import org.junit.After
import org.junit.Before

/**
 * Base class for E2E tests that handles port forwarding setup/teardown.
 *
 * Extend this class for E2E tests that need to communicate with
 * any-sync infrastructure running on the host machine.
 *
 * Example usage:
 * ```
 * @RunWith(AndroidJUnit4::class)
 * class MyE2ETest : E2ETestBase() {
 *     @Test
 *     fun testSomething() = runTest {
 *         // Port forwarding is already set up
 *         val addr = EmulatorPortForwarding.getCoordinatorAddress()
 *         // Use addr to connect to coordinator
 *     }
 * }
 * ```
 */
abstract class E2ETestBase {

    @Before
    fun setupPortForwarding() {
        EmulatorPortForwarding.setup()
    }

    @After
    fun cleanupPortForwarding() {
        EmulatorPortForwarding.cleanup()
    }
}
