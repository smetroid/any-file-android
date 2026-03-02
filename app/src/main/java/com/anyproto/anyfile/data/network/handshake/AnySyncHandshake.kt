package com.anyproto.anyfile.data.network.handshake

import android.util.Log
import com.anyproto.anyfile.data.network.libp2p.Libp2pTlsSocket
import com.anyproto.anyfile.data.network.libp2p.PeerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream

/**
 * Main orchestrator for the any-sync handshake protocol.
 *
 * Handles both client (outgoing) and server (incoming) handshake flows.
 *
 * Based on the Go implementation in any-sync/net/secureservice/handshake/credential.go
 */
object AnySyncHandshake {

    private const val TAG = "AnySyncHandshake"

    /** Default handshake timeout in milliseconds */
    const val DEFAULT_TIMEOUT_MS = 30000L

    /**
     * Perform client-side (outgoing) handshake.
     *
     * Client flow:
     * 1. Send our credentials
     * 2. Read remote credentials
     * 3. Verify remote credentials
     * 4. Send ack (success)
     * 5. Read final ack
     * 6. Return result with identity
     *
     * @param socket TLS socket to perform handshake on
     * @param checker Credential checker for generating and validating credentials
     * @param timeoutMs Handshake timeout in milliseconds
     * @return Handshake result with remote peer's identity
     * @throws HandshakeProtocolException if handshake fails
     * @throws HandshakeTimeoutException if timeout expires
     */
    suspend fun performOutgoingHandshake(
        socket: Libp2pTlsSocket,
        checker: CredentialChecker,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HandshakeResult {
        return withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                val output = socket.socket.outputStream
                val input = socket.socket.inputStream

                val remotePeerId = socket.remotePeerId ?: PeerId("", byteArrayOf(), byteArrayOf())
                Log.d(TAG, "=== Starting any-sync Handshake (Client) ===")
                Log.d(TAG, "Remote peer ID: ${remotePeerId.base58}")
                Log.d(TAG, "Local peer ID: ${socket.localPeerId.base58}")

                // 1. Send our credentials
                Log.d(TAG, "Step 1: Creating and sending local credentials...")
                val localCred = checker.makeCredentials(remotePeerId)
                writeCredentials(output, localCred)
                Log.d(TAG, "Local credentials sent: type=${localCred.type}, " +
                        "payloadSize=${localCred.payload?.size ?: 0}, version=${localCred.version}")

                // 2. Read remote credentials or ack
                // Coordinator can respond with either:
                // - Credentials (type 1) - if accepting our credentials
                // - Ack (type 2) - if rejecting our credentials
                Log.d(TAG, "Step 2: Reading remote response (expecting Credentials or Ack)...")
                val remoteFrame = readMessage(
                    input,
                    setOf(HandshakeFrame.MSG_TYPE_CRED, HandshakeFrame.MSG_TYPE_ACK)
                )
                Log.d(TAG, "Remote frame received: type=${remoteFrame.type}, payloadSize=${remoteFrame.payload.size}")

                // Check if coordinator sent an error ack
                if (remoteFrame.type == HandshakeFrame.MSG_TYPE_ACK) {
                    val ack = parseAck(remoteFrame.payload)
                    if (ack.error != HandshakeError.NULL) {
                        Log.e(TAG, "Coordinator rejected credentials with error: ${ack.error}")
                        throw HandshakeProtocolException(
                            "Coordinator rejected credentials: ${ack.error}"
                        )
                    }
                    // Ack with error=NULL - unexpected at this point
                    throw HandshakeProtocolException(
                        "Unexpected ACK with NULL error at step 2"
                    )
                }

                // Parse credentials
                val remoteCred = parseCredentials(remoteFrame.payload)
                Log.d(TAG, "Remote credentials parsed: type=${remoteCred.type}, " +
                        "payloadSize=${remoteCred.payload?.size ?: 0}, version=${remoteCred.version}")

                // 3. Verify remote credentials
                Log.d(TAG, "Step 3: Verifying remote credentials...")
                val result = try {
                    checker.checkCredential(remotePeerId, remoteCred)
                } catch (e: HandshakeProtocolException) {
                    Log.e(TAG, "Credential verification failed: ${e.message}", e)
                    // Send ack with error
                    writeAck(output, Ack(HandshakeError.INVALID_CREDENTIALS))
                    throw e
                }
                Log.d(TAG, "Remote credentials verified successfully")

                // 4. Send ack (success)
                Log.d(TAG, "Step 4: Sending success ack...")
                writeAck(output, Ack(HandshakeError.NULL))

                // 5. Read final ack
                Log.d(TAG, "Step 5: Reading final ack...")
                val ackFrame = readMessage(input, setOf(HandshakeFrame.MSG_TYPE_ACK))
                val ack = parseAck(ackFrame.payload)

                if (ack.error != HandshakeError.NULL) {
                    Log.e(TAG, "Remote peer returned error: ${ack.error}")
                    throw HandshakeProtocolException(
                        "Remote peer returned error: ${ack.error}"
                    )
                }

                Log.d(TAG, "Step 6: Handshake completed successfully!")
                Log.d(TAG, "=== any-sync Handshake Complete ===")
                // 6. Return result with identity
                result
            }
        }
    }

    /**
     * Perform server-side (incoming) handshake.
     *
     * Server flow:
     * 1. Read remote credentials
     * 2. Verify remote credentials
     * 3. If invalid, send ack with error and close
     * 4. Send our credentials
     * 5. Read ack from client
     * 6. If error, close connection
     * 7. Send final ack (success)
     * 8. Return result with identity
     *
     * @param socket TLS socket to perform handshake on
     * @param remotePeerId Peer ID of the remote peer
     * @param checker Credential checker for generating and validating credentials
     * @param timeoutMs Handshake timeout in milliseconds
     * @return Handshake result with remote peer's identity
     * @throws HandshakeProtocolException if handshake fails
     * @throws HandshakeTimeoutException if timeout expires
     */
    suspend fun performIncomingHandshake(
        socket: Libp2pTlsSocket,
        remotePeerId: PeerId,
        checker: CredentialChecker,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): HandshakeResult {
        return withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                val output = socket.socket.outputStream
                val input = socket.socket.inputStream

                // 1. Read remote credentials
                val remoteFrame = readMessage(input, setOf(HandshakeFrame.MSG_TYPE_CRED))
                val remoteCred = parseCredentials(remoteFrame.payload)

                // 2. Verify remote credentials
                val result = try {
                    checker.checkCredential(remotePeerId, remoteCred)
                } catch (e: HandshakeProtocolException) {
                    // 3. Send ack with error and close
                    writeAck(output, Ack(HandshakeError.INVALID_CREDENTIALS))
                    throw e
                }

                // 4. Send our credentials
                val localCred = checker.makeCredentials(remotePeerId)
                writeCredentials(output, localCred)

                // 5. Read ack from client
                val ackFrame = readMessage(input, setOf(HandshakeFrame.MSG_TYPE_ACK))
                val ack = parseAck(ackFrame.payload)

                // 6. If error, close connection
                if (ack.error != HandshakeError.NULL) {
                    throw HandshakeProtocolException(
                        "Remote peer returned error: ${ack.error}"
                    )
                }

                // 7. Send final ack (success)
                writeAck(output, Ack(HandshakeError.NULL))

                // 8. Return result with identity
                result
            }
        }
    }

    /**
     * Write credentials to the output stream.
     */
    private fun writeCredentials(output: OutputStream, cred: HandshakeCredentials) {
        val payload = cred.toProto().toByteArray()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_CRED, payload)
    }

    /**
     * Write ack to the output stream.
     */
    private fun writeAck(output: OutputStream, ack: Ack) {
        val payload = ack.toProto().toByteArray()
        HandshakeFrame.writeMessage(output, HandshakeFrame.MSG_TYPE_ACK, payload)
    }

    /**
     * Read a message from the input stream.
     */
    private suspend fun readMessage(input: InputStream, allowedTypes: Set<Byte>): Frame {
        return HandshakeFrame.readMessage(input, allowedTypes)
    }

    /**
     * Parse credentials from protobuf payload.
     */
    private fun parseCredentials(payload: ByteArray): HandshakeCredentials {
        return try {
            val proto = com.anyproto.anyfile.protos.Credentials.parseFrom(
                com.google.protobuf.ByteString.copyFrom(payload)
            )
            HandshakeCredentials.fromProto(proto)
        } catch (e: Exception) {
            throw HandshakeProtocolException("Failed to parse Credentials", e)
        }
    }

    /**
     * Parse ack from protobuf payload.
     */
    private fun parseAck(payload: ByteArray): Ack {
        return try {
            val proto = com.anyproto.anyfile.protos.Ack.parseFrom(
                com.google.protobuf.ByteString.copyFrom(payload)
            )
            Ack.fromProto(proto)
        } catch (e: Exception) {
            throw HandshakeProtocolException("Failed to parse Ack", e)
        }
    }
}

/**
 * Exception thrown when handshake timeout expires.
 */
class HandshakeTimeoutException(
    message: String
) : Exception(message)
