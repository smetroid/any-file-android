package com.anyproto.anyfile.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import javax.inject.Inject
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Basic connectivity tests to verify the emulator can reach the any-sync infrastructure.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ConnectivityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var tlsProvider: Libp2pTlsProvider

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun testCanConnectToCoordinatorPort() = runTest {
        val host = EmulatorPortForwarding.getCoordinatorHost()
        val port = EmulatorPortForwarding.getCoordinatorPort()

        // Try to establish a TCP connection
        val socket = try {
            Socket(host, port)
        } catch (e: Exception) {
            throw AssertionError("Failed to connect to $host:$port: ${e.message}", e)
        }

        assertNotNull(socket, "Socket should not be null")
        assertTrue(socket.isConnected, "Socket should be connected")
        socket.close()
    }

    @Test
    fun testCanConnectToFilenodePort() = runTest {
        val host = EmulatorPortForwarding.getFilenodeHost()
        val port = EmulatorPortForwarding.getFilenodePort()

        // Try to establish a TCP connection
        val socket = try {
            Socket(host, port)
        } catch (e: Exception) {
            throw AssertionError("Failed to connect to $host:$port: ${e.message}", e)
        }

        assertNotNull(socket, "Socket should not be null")
        assertTrue(socket.isConnected, "Socket should be connected")
        socket.close()
    }

    @Test
    fun testCanConnectToCoordinatorWithTls() = runTest {
        val host = EmulatorPortForwarding.getCoordinatorHost()
        val port = EmulatorPortForwarding.getCoordinatorPort()

        try {
            // Try to establish a TLS connection
            // Note: any-sync uses self-signed certificates, so we need to trust all certs for testing
            val tlsSocket = tlsProvider.createTlsSocket(
                host = host,
                port = port,
                timeoutMs = 10000,
                enableAlpn = false,
                trustAllCerts = true,  // Trust self-signed certificates
                useLibp2pTls = false  // First try without libp2p TLS
            )

            assertNotNull(tlsSocket, "TLS socket should not be null")
            tlsSocket.socket.close()
        } catch (e: Exception) {
            fail("Failed to establish TLS connection to $host:$port: ${e.message}", e)
        }
    }

    @Test
    fun testCanConnectToCoordinatorWithLibp2pTls() = runTest {
        val host = EmulatorPortForwarding.getCoordinatorHost()
        val port = EmulatorPortForwarding.getCoordinatorPort()

        try {
            // Try to establish a libp2p TLS connection (with client certificate)
            val tlsSocket = tlsProvider.createTlsSocket(
                host = host,
                port = port,
                timeoutMs = 10000,
                enableAlpn = false,
                trustAllCerts = true,  // Trust self-signed certificates
                useLibp2pTls = true   // Use libp2p TLS with client certificate
            )

            assertNotNull(tlsSocket, "TLS socket should not be null")
            tlsSocket.socket.close()
        } catch (e: Exception) {
            fail("Failed to establish libp2p TLS connection to $host:$port: ${e.message}", e)
        }
    }
}
