package com.anyproto.anyfile.data.network.yamux

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Represents a single multiplexed stream within a yamux session.
 *
 * A stream is an independent virtual channel that operates over the shared
 * yamux session connection. Each stream has its own data flow, state, and lifecycle.
 *
 * Stream states:
 * - INIT: Stream is being initialized
 * - SYN_SENT: SYN flag sent, waiting for ACK
 * - OPEN: Stream is open and ready for data transfer
 * - RECEIVING: FIN received, still can send data
 * - SENDING: FIN sent, still can receive data
 * - CLOSED: Stream is closed (both FIN received and sent)
 * - RESET: Stream was reset abnormally
 *
 * Flow control:
 * - Each stream has a receive window that limits the amount of data
 *   that can be sent without acknowledgment
 * - Window updates are sent as data is consumed
 *
 * @property streamId The unique identifier for this stream
 * @property session The parent yamux session
 * @property initialWindowSize The initial receive window size
 */
class YamuxStream(
    val streamId: Int,
    private val session: YamuxSession,
    private val initialWindowSize: Int = DEFAULT_WINDOW_SIZE
) {
    companion object {
        /**
         * Default window size for flow control (256KB).
         * Matches the default in hashicorp/yamux.
         */
        const val DEFAULT_WINDOW_SIZE = 256 * 1024

        /**
         * Maximum buffer size for received data.
         */
        private const val MAX_BUFFER_SIZE = 1024 * 1024 // 1MB

        /**
         * Channel capacity for received data frames.
         */
        private const val DATA_CHANNEL_CAPACITY = 16
    }

    /**
     * Stream state enumeration.
     */
    enum class State {
        INIT,
        SYN_SENT,
        OPEN,
        RECEIVING,  // FIN received, can still send
        SENDING,    // FIN sent, can still receive
        CLOSED,     // Both FIN sent and received
        RESET       // Abnormally closed
    }

    // State management
    private val lock = ReentrantLock()
    @Volatile
    private var _state = State.INIT
    val state: State get() = _state

    // Flow control
    @Volatile
    private var receiveWindow = initialWindowSize
    @Volatile
    private var sendWindow = initialWindowSize

    // Data channels for async I/O
    private val dataChannel = Channel<ByteArray>(DATA_CHANNEL_CAPACITY)

    // Signal when stream becomes OPEN (for outbound streams waiting for ACK)
    private val openDeferred = CompletableDeferred<Unit>()

    // Remote close flag
    private var remoteFinReceived = false
    private var localFinSent = false

    // Closed flag
    @Volatile
    private var closed = false

    /**
     * Initialize the stream as an outbound stream (initiator).
     * Sends a SYN frame to open the stream.
     */
    suspend fun initialize() {
        lock.withLock {
            check(_state == State.INIT) { "Stream already initialized" }
            _state = State.SYN_SENT
        }

        // Send SYN frame
        val synFrame = YamuxFrame.Data(
            streamId = streamId,
            flags = setOf(YamuxFrame.Flag.SYN),
            data = ByteArray(0)
        )
        session.sendFrame(synFrame)
    }

    /**
     * Accept the stream as an inbound stream (receiver).
     * Called when a SYN frame is received from the remote.
     */
    suspend fun accept() {
        lock.withLock {
            check(_state == State.INIT) { "Stream already initialized" }
            _state = State.OPEN
        }

        // Signal that the stream is now open (for inbound streams, it's open immediately)
        openDeferred.complete(Unit)

        // Send ACK frame
        val ackFrame = YamuxFrame.Data(
            streamId = streamId,
            flags = setOf(YamuxFrame.Flag.ACK),
            data = ByteArray(0)
        )
        session.sendFrame(ackFrame)
    }

    /**
     * Wait for the stream to transition to OPEN state.
     * For outbound streams, this waits for the ACK to be received.
     * For inbound streams, this returns immediately as they're already OPEN.
     *
     * @throws YamuxStreamException if the stream is closed before becoming OPEN
     */
    suspend fun waitForOpen() {
        try {
            openDeferred.await()
        } catch (e: Exception) {
            if (closed) {
                throw YamuxStreamException("Stream closed before becoming OPEN")
            }
            throw YamuxStreamException("Failed to wait for stream to open", e)
        }
    }

    /**
     * Handle an incoming data frame for this stream.
     *
     * @param frame The data frame to handle
     */
    suspend fun handleDataFrame(frame: YamuxFrame.Data) {
        lock.withLock {
            when {
                closed -> {
                    // Stream is closed, ignore data
                    return
                }
                frame.flags.contains(YamuxFrame.Flag.RST) -> {
                    // Stream reset
                    _state = State.RESET
                    closed = true
                    dataChannel.close(YamuxStreamException("Stream reset by remote"))
                    return
                }
                else -> {
                    // Update state if this is an ACK to our SYN
                    if (_state == State.SYN_SENT && frame.flags.contains(YamuxFrame.Flag.ACK)) {
                        _state = State.OPEN
                        // Signal that the stream is now open
                        openDeferred.complete(Unit)
                    }

                    // Check for FIN flag
                    if (frame.flags.contains(YamuxFrame.Flag.FIN)) {
                        remoteFinReceived = true
                        when {
                            localFinSent -> {
                                _state = State.CLOSED
                                dataChannel.close()
                            }
                            else -> {
                                _state = State.RECEIVING
                                // Don't close channel here - let it drain naturally
                                // The channel will be closed when all data is consumed
                            }
                        }
                    }
                }
            }
        }

        // Queue data if present and not closed
        if (frame.data.isNotEmpty() && !closed) {
            try {
                dataChannel.send(frame.data)

                // Update receive window
                val dataConsumed = frame.data.size
                if (dataConsumed > 0) {
                    receiveWindow -= dataConsumed
                    // Send window update if we've consumed enough data
                    if (receiveWindow < initialWindowSize / 2) {
                        sendWindowUpdate(initialWindowSize - receiveWindow)
                        receiveWindow = initialWindowSize
                    }
                }
            } catch (e: Exception) {
                // Channel closed or failed
                close(YamuxStreamException("Failed to queue received data", e))
            }
        }

        // If FIN received and no data in frame, close the channel
        // This signals that no more data will be sent
        if (frame.flags.contains(YamuxFrame.Flag.FIN) &&
            frame.data.isEmpty() &&
            !localFinSent) {
            dataChannel.close()
        }
    }

    /**
     * Handle a window update frame for this stream.
     *
     * @param delta The number of bytes to add to the send window
     */
    suspend fun handleWindowUpdate(delta: Int) {
        lock.withLock {
            sendWindow += delta
        }
    }

    /**
     * Handle a WINDOW_UPDATE+ACK frame — this is how the Go yamux server acknowledges
     * a client SYN (hashicorp/yamux sends typeWindowUpdate|flagACK as the SYN-ACK).
     *
     * Transitions SYN_SENT → OPEN and completes openDeferred so waitForOpen() unblocks.
     *
     * @param delta The initial send window granted by the remote
     */
    suspend fun handleWindowUpdateAck(delta: Int) {
        val shouldSignal = lock.withLock {
            sendWindow += delta
            if (_state == State.SYN_SENT) {
                _state = State.OPEN
                true
            } else {
                false
            }
        }
        if (shouldSignal) {
            openDeferred.complete(Unit)
        }
    }

    /**
     * Handle a reset frame for this stream.
     */
    suspend fun handleReset() {
        lock.withLock {
            _state = State.RESET
            closed = true
        }
        dataChannel.close(YamuxStreamException("Stream reset"))
    }

    /**
     * Write data to the stream.
     *
     * @param data The data to write
     * @throws YamuxStreamException if write fails or stream is closed
     */
    suspend fun write(data: ByteArray) {
        check(!closed) { "Stream is closed" }
        check(data.isNotEmpty()) { "Cannot write empty data" }

        lock.withLock {
            check(_state == State.OPEN || _state == State.RECEIVING) {
                "Cannot write in state ${_state}"
            }
        }

        // Check send window
        lock.withLock {
            if (sendWindow < data.size) {
                throw YamuxStreamException("Send window exhausted (need ${data.size}, have $sendWindow)")
            }
            sendWindow -= data.size
        }

        // Send data frame
        val frame = YamuxFrame.Data(
            streamId = streamId,
            flags = emptySet(),
            data = data
        )
        session.sendFrame(frame)
    }

    /**
     * Read data from the stream.
     *
     * @param timeoutMs Timeout in milliseconds (0 for no timeout)
     * @return The data read, or null if stream is closed
     * @throws YamuxStreamException if read fails
     */
    suspend fun read(timeoutMs: Long = 0): ByteArray? {
        check(!closed) { "Stream is closed" }

        return try {
            if (timeoutMs > 0) {
                // Use withTimeout for timeout support
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    dataChannel.receive()
                }
            } else {
                dataChannel.receive()
            }
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            // Stream closed
            null
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw YamuxStreamException("Read timeout", e)
        } catch (e: Exception) {
            throw YamuxStreamException("Read failed", e)
        }
    }

    /**
     * Close the stream for writing.
     * Sends a FIN frame to indicate no more data will be sent.
     */
    suspend fun closeWrite() {
        lock.withLock {
            if (localFinSent || closed) return
            localFinSent = true

            when (_state) {
                State.OPEN -> _state = State.SENDING
                State.RECEIVING -> {
                    _state = State.CLOSED
                    dataChannel.close()
                }
                else -> {
                    // Already in terminal state
                }
            }
        }

        // Send FIN frame
        val finFrame = YamuxFrame.Data(
            streamId = streamId,
            flags = setOf(YamuxFrame.Flag.FIN),
            data = ByteArray(0)
        )
        session.sendFrame(finFrame)
    }

    /**
     * Close the stream for reading.
     * Discards any buffered received data.
     */
    suspend fun closeRead() {
        lock.withLock {
            if (remoteFinReceived || closed) return
            remoteFinReceived = true

            when (_state) {
                State.OPEN -> _state = State.RECEIVING
                State.SENDING -> {
                    _state = State.CLOSED
                }
                else -> {
                    // Already in terminal state
                }
            }
        }
    }

    /**
     * Close the stream completely.
     * Sends a RST frame to immediately terminate the stream.
     *
     * @param cause Optional exception that caused the close
     */
    suspend fun close(cause: Throwable? = null) {
        lock.withLock {
            if (closed) return
            closed = true
            _state = if (cause != null) State.RESET else State.CLOSED
        }

        // Close data channel
        dataChannel.close(cause)

        // Send RST frame
        val rstFrame = YamuxFrame.Data(
            streamId = streamId,
            flags = setOf(YamuxFrame.Flag.RST),
            data = ByteArray(0)
        )
        try {
            session.sendFrame(rstFrame)
        } catch (e: Exception) {
            // Ignore errors when sending RST
        }
    }

    /**
     * Send a window update for this stream.
     *
     * @param delta The number of bytes to add to the window
     */
    private suspend fun sendWindowUpdate(delta: Int) {
        val windowUpdateFrame = YamuxFrame.WindowUpdate(
            streamId = streamId,
            flags = emptySet(),
            delta = delta
        )
        session.sendFrame(windowUpdateFrame)
    }

    /**
     * Get the current receive window size.
     */
    fun getReceiveWindowSize(): Int {
        return lock.withLock { receiveWindow }
    }

    /**
     * Get the current send window size.
     */
    fun getSendWindowSize(): Int {
        return lock.withLock { sendWindow }
    }

    /**
     * Check if the stream is closed.
     */
    fun isClosed(): Boolean = closed

    /**
     * Check if the stream is open for reading.
     */
    fun canRead(): Boolean {
        return lock.withLock { !closed && !remoteFinReceived }
    }

    /**
     * Check if the stream is open for writing.
     */
    fun canWrite(): Boolean {
        return lock.withLock { !closed && !localFinSent }
    }
}

/**
 * Exception thrown when yamux stream operations fail.
 */
class YamuxStreamException(message: String, cause: Throwable? = null) : Exception(message, cause)
