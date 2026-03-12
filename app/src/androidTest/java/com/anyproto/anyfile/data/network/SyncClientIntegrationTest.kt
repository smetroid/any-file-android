package com.anyproto.anyfile.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for [SyncClient].
 *
 * Requires docker services to be running:
 * - Coordinator at 10.0.2.2:1004  (ADB reverse-forwarded from host)
 * - Filenode   at 10.0.2.2:1005
 *
 * Run the test infrastructure first:
 *   cd any-file/docker && docker-compose up -d
 *   adb reverse tcp:1004 tcp:1004
 *   adb reverse tcp:1005 tcp:1005
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SyncClientIntegrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var syncClient: SyncClient

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun testConnectToCoordinator() = runTest {
        val coordinatorClient: P2PCoordinatorClient =
            syncClient.connectCoordinator("10.0.2.2", 1004)
        assertNotNull(coordinatorClient)
        val peerId = syncClient.getPeerID()
        assertTrue(
            peerId.startsWith("12D3KooW"),
            "Expected peer ID starting with 12D3KooW, got: $peerId"
        )
    }

    @Test
    fun testConnectToFilenode() = runTest {
        val filenodeClient: P2PFilenodeClient =
            syncClient.connectFilenode("10.0.2.2", 1005)
        assertNotNull(filenodeClient)
    }

    @Test
    fun testPeerIDIsStable() = runTest {
        val id1 = syncClient.getPeerID()
        val id2 = syncClient.getPeerID()
        assertTrue(id1 == id2, "Peer ID should be stable across calls")
    }
}
