package com.anyproto.anyfile.data.network

import com.anyproto.anyfile.data.network.model.NodeInfo
import com.anyproto.anyfile.data.network.model.NodeType
import com.anyproto.anyfile.data.network.model.SpacePermissions
import com.anyproto.anyfile.data.network.model.SpaceReceipt
import com.anyproto.anyfile.data.network.model.SpaceSignResult
import com.anyproto.anyfile.data.network.model.SpaceStatus
import com.anyproto.anyfile.data.network.model.SpaceStatusInfo
import com.anyproto.anyfile.protos.NetworkConfigurationResponse
import com.anyproto.anyfile.protos.Node
import com.anyproto.anyfile.protos.SpaceReceipt as ProtoSpaceReceipt
import com.anyproto.anyfile.protos.SpaceSignResponse
import com.anyproto.anyfile.protos.SpaceStatusCheckResponse
import com.anyproto.anyfile.protos.SpaceStatusPayload
import com.anyproto.anyfile.protos.SpaceReceiptWithSignature
import com.anyproto.anyfile.protos.SpaceStatus as ProtoSpaceStatus
import com.anyproto.anyfile.protos.SpacePermissions as ProtoSpacePermissions
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for CoordinatorClient.
 *
 * Uses MockWebServer to simulate HTTP responses from the coordinator service.
 */
class CoordinatorClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var coordinatorClient: CoordinatorClient
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        okHttpClient = OkHttpClient()
        coordinatorClient = CoordinatorClient(okHttpClient)
        coordinatorClient.initialize(mockWebServer.url("/").toString())
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `signSpace should return receipt and signature`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val header = byteArrayOf(1, 2, 3, 4, 5)

        val protoReceipt = ProtoSpaceReceipt.newBuilder()
            .setSpaceId(spaceId)
            .setPeerId("test-peer-id")
            .setAccountIdentity(com.google.protobuf.ByteString.copyFrom(byteArrayOf(10, 20, 30)))
            .setNetworkId("test-network-id")
            .setValidUntil(1234567890L)
            .build()

        val receiptWithSig = SpaceReceiptWithSignature.newBuilder()
            .setSpaceReceiptPayload(com.google.protobuf.ByteString.copyFrom(protoReceipt.toByteArray()))
            .setSignature(com.google.protobuf.ByteString.copyFrom(byteArrayOf(5, 4, 3, 2, 1)))
            .build()

        val signResponse = SpaceSignResponse.newBuilder()
            .setReceipt(receiptWithSig)
            .build()

        val responseBytes = signResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = coordinatorClient.signSpace(spaceId, header)

        // Assert
        assertTrue(result.isSuccess)
        val signResult = result.getOrThrow()
        assertEquals(spaceId, signResult.receipt.spaceId)
        assertEquals("test-peer-id", signResult.receipt.peerId)
        assertEquals("test-network-id", signResult.receipt.networkId)
        assertEquals(1234567890L, signResult.receipt.validUntil)
    }

    @Test
    fun `checkSpaceStatus should return status info`() = runTest {
        // Arrange
        val spaceId = "test-space-id"

        val statusPayload = SpaceStatusPayload.newBuilder()
            .setStatus(ProtoSpaceStatus.SpaceStatusCreated)
            .setDeletionTimestamp(0L)
            .setPermissions(ProtoSpacePermissions.SpacePermissionsOwner)
            .setIsShared(false)
            .build()

        val statusResponse = SpaceStatusCheckResponse.newBuilder()
            .setPayload(statusPayload)
            .build()

        val responseBytes = statusResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = coordinatorClient.checkSpaceStatus(spaceId)

        // Assert
        assertTrue(result.isSuccess)
        val statusInfo = result.getOrThrow()
        assertEquals(SpaceStatus.CREATED, statusInfo.status)
        assertEquals(0L, statusInfo.deletionTimestamp)
        assertEquals(SpacePermissions.OWNER, statusInfo.permissions)
        assertEquals(false, statusInfo.isShared)
    }

    @Test
    fun `getNetworkConfiguration should return network config`() = runTest {
        // Arrange
        val node1 = Node.newBuilder()
            .setPeerId("peer-1")
            .addAddresses("addr1.example.com:8080")
            .addTypes(com.anyproto.anyfile.protos.NodeType.CoordinatorAPI)
            .build()

        val node2 = Node.newBuilder()
            .setPeerId("peer-2")
            .addAddresses("addr2.example.com:8080")
            .addTypes(com.anyproto.anyfile.protos.NodeType.FileAPI)
            .build()

        val networkConfigResponse = NetworkConfigurationResponse.newBuilder()
            .setConfigurationId("config-123")
            .setNetworkId("network-456")
            .addNodes(node1)
            .addNodes(node2)
            .setCreationTimeUnix(1234567890L)
            .build()

        val responseBytes = networkConfigResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = coordinatorClient.getNetworkConfiguration()

        // Assert
        assertTrue(result.isSuccess)
        val networkConfig = result.getOrThrow()
        assertEquals("config-123", networkConfig.configurationId)
        assertEquals("network-456", networkConfig.networkId)
        assertEquals(1234567890L, networkConfig.creationTimeUnix)
        assertEquals(2, networkConfig.nodes.size)

        val coordinatorNode = networkConfig.nodes.first { it.types.contains(NodeType.COORDINATOR_API) }
        assertEquals("peer-1", coordinatorNode.peerId)
    }

    @Test
    fun `registerPeer should return network configuration`() = runTest {
        // Arrange
        val node = Node.newBuilder()
            .setPeerId("peer-test")
            .addAddresses("test.example.com:8080")
            .addTypes(com.anyproto.anyfile.protos.NodeType.CoordinatorAPI)
            .build()

        val networkConfigResponse = NetworkConfigurationResponse.newBuilder()
            .setConfigurationId("config-test")
            .setNetworkId("network-test")
            .addNodes(node)
            .setCreationTimeUnix(1234567890L)
            .build()

        val responseBytes = networkConfigResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = coordinatorClient.registerPeer()

        // Assert
        assertTrue(result.isSuccess)
        val networkConfig = result.getOrThrow()
        assertEquals("config-test", networkConfig.configurationId)
        assertEquals("network-test", networkConfig.networkId)
    }

    @Test
    fun `createSpace should call signSpace`() = runTest {
        // Arrange
        val spaceId = "new-space-id"
        val header = byteArrayOf(10, 20, 30)

        val protoReceipt = ProtoSpaceReceipt.newBuilder()
            .setSpaceId(spaceId)
            .setPeerId("new-peer-id")
            .setAccountIdentity(com.google.protobuf.ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setNetworkId("new-network-id")
            .setValidUntil(9876543210L)
            .build()

        val receiptWithSig = SpaceReceiptWithSignature.newBuilder()
            .setSpaceReceiptPayload(com.google.protobuf.ByteString.copyFrom(protoReceipt.toByteArray()))
            .setSignature(com.google.protobuf.ByteString.copyFrom(byteArrayOf(9, 8, 7)))
            .build()

        val signResponse = SpaceSignResponse.newBuilder()
            .setReceipt(receiptWithSig)
            .build()

        val responseBytes = signResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = coordinatorClient.createSpace(spaceId, header)

        // Assert
        assertTrue(result.isSuccess)
        val signResult = result.getOrThrow()
        assertEquals(spaceId, signResult.receipt.spaceId)
        assertEquals("new-peer-id", signResult.receipt.peerId)
    }

    @Test
    fun `getCoordinatorNodes should filter coordinator nodes`() = runTest {
        // Arrange
        val coordinatorNode = Node.newBuilder()
            .setPeerId("coordinator-peer")
            .addAddresses("coordinator.example.com:8080")
            .addTypes(com.anyproto.anyfile.protos.NodeType.CoordinatorAPI)
            .build()

        val fileNode = Node.newBuilder()
            .setPeerId("file-peer")
            .addAddresses("file.example.com:8080")
            .addTypes(com.anyproto.anyfile.protos.NodeType.FileAPI)
            .build()

        val networkConfigResponse = NetworkConfigurationResponse.newBuilder()
            .setConfigurationId("config-123")
            .setNetworkId("network-456")
            .addNodes(coordinatorNode)
            .addNodes(fileNode)
            .setCreationTimeUnix(1234567890L)
            .build()

        val responseBytes = networkConfigResponse.toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(responseBytes))
                .addHeader("Content-Type", "application/x-protobuf")
        )

        // Act
        val result = coordinatorClient.getCoordinatorNodes()

        // Assert
        assertTrue(result.isSuccess)
        val coordinatorNodes = result.getOrThrow()
        assertEquals(1, coordinatorNodes.size)
        assertEquals("coordinator-peer", coordinatorNodes[0].peerId)
        assertTrue(coordinatorNodes[0].types.contains(NodeType.COORDINATOR_API))
    }

    @Test
    fun `signSpace should return failure on HTTP error`() = runTest {
        // Arrange
        val spaceId = "test-space-id"
        val header = byteArrayOf(1, 2, 3)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        // Act
        val result = coordinatorClient.signSpace(spaceId, header)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CoordinatorException)
    }

    @Test
    fun `checkSpaceStatus should return failure on HTTP error`() = runTest {
        // Arrange
        val spaceId = "test-space-id"

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("Not Found")
        )

        // Act
        val result = coordinatorClient.checkSpaceStatus(spaceId)

        // Assert
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is CoordinatorException)
    }

    @Test
    fun `signSpace should throw exception when not initialized`() = runTest {
        // Arrange
        val uninitializedClient = CoordinatorClient(okHttpClient)
        val spaceId = "test-space-id"
        val header = byteArrayOf(1, 2, 3)

        // Act & Assert
        val result = uninitializedClient.signSpace(spaceId, header)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not initialized") == true)
    }
}
