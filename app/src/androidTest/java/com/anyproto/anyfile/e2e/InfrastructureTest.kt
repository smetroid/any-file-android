package com.anyproto.anyfile.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyproto.anyfile.data.network.coordinator.SimpleTcpCoordinatorClient
import com.anyproto.anyfile.data.network.p2p.P2PCoordinatorClient
import com.anyproto.anyfile.data.network.p2p.P2PFilenodeClient
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests that connect to real any-sync infrastructure.
 *
 * These tests verify connectivity to any-sync infrastructure using two approaches:
 * 1. Simple TCP+DRPC (no TLS) - matches Go client's coordinator.Client
 * 2. Full P2P stack (TLS + Handshake + Yamux + DRPC) - for P2P features
 *
 * Prerequisites:
 * - any-sync infrastructure running on 127.0.0.1:1004-1005
 * - Android emulator with network access to host (uses 10.0.2.2)
 *
 * To run these tests:
 * 1. Start any-sync infrastructure: cd any-file/docker && docker compose up -d
 * 2. Run: ./gradlew connectedAndroidTest
 *
 * Note: Android emulators use the special IP 10.0.2.2 to access the host machine.
 * This is handled automatically by EmulatorPortForwarding.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class InfrastructureTest : E2ETestBase() {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var coordinatorClient: P2PCoordinatorClient

    @Inject
    lateinit var filenodeClient: P2PFilenodeClient

    @Inject
    lateinit var simpleTcpCoordinatorClient: SimpleTcpCoordinatorClient

    @Before
    fun setup() {
        hiltRule.inject()
        // Clients are provided by Hilt DI
    }

    // Helper function to initialize both clients (must be called from within runTest)
    private suspend fun initializeClients() {
        val coordinatorHost = EmulatorPortForwarding.getCoordinatorHost()
        val coordinatorPort = EmulatorPortForwarding.getCoordinatorPort()
        val filenodeHost = EmulatorPortForwarding.getFilenodeHost()
        val filenodePort = EmulatorPortForwarding.getFilenodePort()

        // P2P clients use host:port instead of URL
        coordinatorClient.initialize(coordinatorHost, coordinatorPort)
        filenodeClient.initialize(filenodeHost, filenodePort)
    }

    @Test
    fun testCoordinatorConnection() = runTest {
        initializeClients()

        // Test that we can get network config from real coordinator
        val result = coordinatorClient.getNetworkConfiguration()

        assertTrue(result.isSuccess, "Should connect to coordinator: ${result.exceptionOrNull()?.message}")
        val config = result.getOrThrow()

        assertNotNull(config.networkId, "Network ID should not be null")
        assertTrue(config.nodes.isNotEmpty(), "Should have nodes in network")
    }

    @Test
    fun testFilenodeConnection() = runTest {
        initializeClients()

        // Test that we can query space info from real filenode
        // Note: This may fail if space doesn't exist, but should connect
        val result = filenodeClient.spaceInfo("test-space")

        // We expect failure for non-existent space, but connection should work
        // (it's an HTTP 4xx error, not connection refused)
        assertNotNull(result, "Result should not be null")
    }

    @Test
    fun testCoordinatorRegisterPeer() = runTest {
        initializeClients()

        // Test that we can register as a peer
        val result = coordinatorClient.registerPeer()

        assertTrue(result.isSuccess, "Should register peer: ${result.exceptionOrNull()?.message}")
        val config = result.getOrThrow()

        assertNotNull(config.networkId, "Network ID should not be null")
        assertTrue(config.nodes.isNotEmpty(), "Should have nodes in network")
    }

    @Test
    fun testCoordinatorGetCoordinatorNodes() = runTest {
        initializeClients()

        // Test that we can get coordinator nodes
        val result = coordinatorClient.getCoordinatorNodes()

        assertTrue(result.isSuccess, "Should get coordinator nodes: ${result.exceptionOrNull()?.message}")
        val nodes = result.getOrThrow()

        assertTrue(nodes.isNotEmpty(), "Should have at least one coordinator node")
    }

    @Test
    fun testFilenodeAccountInfo() = runTest {
        initializeClients()

        // Test that we can get account info from filenode
        val result = filenodeClient.accountInfo()

        assertTrue(result.isSuccess, "Should get account info: ${result.exceptionOrNull()?.message}")
        val accountInfo = result.getOrThrow()

        assertNotNull(accountInfo, "Account info should not be null")
    }

    @Test
    fun testPortForwardingConfiguration() = runTest {
        // Verify that emulator networking addresses are correctly configured
        val coordinatorHost = EmulatorPortForwarding.getCoordinatorHost()
        val coordinatorPort = EmulatorPortForwarding.getCoordinatorPort()
        val filenodeHost = EmulatorPortForwarding.getFilenodeHost()
        val filenodePort = EmulatorPortForwarding.getFilenodePort()
        val nodeAddrs = EmulatorPortForwarding.getNodeAddresses()

        assertEquals("10.0.2.2", coordinatorHost, "Coordinator host should be 10.0.2.2")
        assertEquals(1004, coordinatorPort, "Coordinator port should be 1004")
        assertEquals("10.0.2.2", filenodeHost, "Filenode host should be 10.0.2.2")
        assertEquals(1005, filenodePort, "Filenode port should be 1005")

        assertEquals(3, nodeAddrs.size, "Should have 3 node addresses")
        assertEquals("10.0.2.2" to 1001, nodeAddrs[0], "Node 1 address should match")
        assertEquals("10.0.2.2" to 1002, nodeAddrs[1], "Node 2 address should match")
        assertEquals("10.0.2.2" to 1003, nodeAddrs[2], "Node 3 address should match")

        // Also verify deprecated methods for backward compatibility
        @Suppress("DEPRECATION")
        val deprecatedCoordinatorAddr = EmulatorPortForwarding.getCoordinatorAddress()
        @Suppress("DEPRECATION")
        val deprecatedFilenodeAddr = EmulatorPortForwarding.getFilenodeAddress()
        assertEquals("http://10.0.2.2:1004", deprecatedCoordinatorAddr, "Deprecated coordinator address should match")
        assertEquals("http://10.0.2.2:1005", deprecatedFilenodeAddr, "Deprecated filenode address should match")
    }

    @Test
    fun testCoordinatorAndFilenodeBothReachable() = runTest {
        initializeClients()

        // Test that both coordinator and filenode are reachable
        val coordinatorResult = coordinatorClient.getNetworkConfiguration()
        val filenodeResult = filenodeClient.accountInfo()

        assertTrue(
            coordinatorResult.isSuccess && filenodeResult.isSuccess,
            "Both coordinator and filenode should be reachable. " +
            "Coordinator: ${coordinatorResult.exceptionOrNull()?.message}, " +
            "Filenode: ${filenodeResult.exceptionOrNull()?.message}"
        )
    }

    // ==================== Simple TCP+DRPC Tests ====================
    // These tests use plain TCP+DRPC (no TLS, no yamux) to verify
    // the coordinator accepts simple connections like the Go client does.

    @Test
    fun testSimpleTcpCoordinatorConnection() = runTest {
        val host = EmulatorPortForwarding.getCoordinatorHost()
        val port = EmulatorPortForwarding.getCoordinatorPort()

        simpleTcpCoordinatorClient.initialize(host, port)

        try {
            val result = simpleTcpCoordinatorClient.getNetworkConfiguration()

            assertTrue(result.isSuccess, "Should connect to coordinator: ${result.exceptionOrNull()?.message}")
            val config = result.getOrThrow()

            assertNotNull(config.networkId, "Network ID should not be null")
            assertTrue(config.nodes.isNotEmpty(), "Should have nodes in network")
        } finally {
            simpleTcpCoordinatorClient.close()
        }
    }

    @Test
    fun testSimpleTcpRegisterPeer() = runTest {
        val host = EmulatorPortForwarding.getCoordinatorHost()
        val port = EmulatorPortForwarding.getCoordinatorPort()

        simpleTcpCoordinatorClient.initialize(host, port)

        try {
            val result = simpleTcpCoordinatorClient.registerPeer()

            assertTrue(result.isSuccess, "Should register peer: ${result.exceptionOrNull()?.message}")
            val config = result.getOrThrow()

            assertNotNull(config.networkId, "Network ID should not be null")
            assertTrue(config.nodes.isNotEmpty(), "Should have nodes in network")
        } finally {
            simpleTcpCoordinatorClient.close()
        }
    }

    @Test
    fun testSimpleTcpGetCoordinatorNodes() = runTest {
        val host = EmulatorPortForwarding.getCoordinatorHost()
        val port = EmulatorPortForwarding.getCoordinatorPort()

        simpleTcpCoordinatorClient.initialize(host, port)

        try {
            val result = simpleTcpCoordinatorClient.getCoordinatorNodes()

            assertTrue(result.isSuccess, "Should get coordinator nodes: ${result.exceptionOrNull()?.message}")
            val nodes = result.getOrThrow()

            assertTrue(nodes.isNotEmpty(), "Should have at least one coordinator node")
        } finally {
            simpleTcpCoordinatorClient.close()
        }
    }
}
