package com.anyproto.anyfile.data.network.yamux

/**
 * Sealed class hierarchy representing yamux protocol frames.
 *
 * Yamux is a multiplexing protocol that allows multiple streams over a single TCP connection.
 * Frame format (12-byte header + payload):
 * - Version (1 byte): 0x00
 * - Type (1 byte): DATA(0x00), WINDOW_UPDATE(0x01), PING(0x02), GO_AWAY(0x03)
 * - Flags (2 bytes): SYN(0x01), ACK(0x02), FIN(0x04), RST(0x08)
 * - Length (4 bytes): payload length for DATA frames
 * - StreamID (4 bytes): stream identifier
 */
sealed class YamuxFrame {
    /**
     * Common stream ID for all frame types (except PING and GO_AWAY which use 0)
     */
    abstract val streamId: Int

    /**
     * Set of flags applied to this frame
     */
    abstract val flags: Set<Flag>

    /**
     * Frame type enumeration
     */
    enum class Type(val byteValue: Int) {
        DATA(0x00),
        WINDOW_UPDATE(0x01),
        PING(0x02),
        GO_AWAY(0x03);

        companion object {
            fun fromByte(value: Int): Type {
                return entries.find { it.byteValue == value }
                    ?: throw YamuxProtocolException("Invalid frame type: $value")
            }
        }
    }

    /**
     * Frame flags (can be combined)
     */
    enum class Flag(val byteValue: Int) {
        SYN(0x01),
        ACK(0x02),
        FIN(0x04),
        RST(0x08);

        companion object {
            fun fromBytes(flagsHigh: Int, flagsLow: Int): Set<Flag> {
                val flagsValue = (flagsHigh shl 8) or flagsLow
                return entries.filter { (it.byteValue and flagsValue) != 0 }.toSet()
            }
        }
    }

    /**
     * DATA frame - carries application data for a stream
     *
     * @param streamId The stream identifier
     * @param flags Flags for this frame
     * @param data The payload data
     */
    data class Data(
        override val streamId: Int,
        override val flags: Set<Flag>,
        val data: ByteArray
    ) : YamuxFrame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return streamId == other.streamId &&
                    flags == other.flags &&
                    data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = streamId
            result = 31 * result + flags.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * WINDOW_UPDATE frame - updates the receive window size
     *
     * @param streamId The stream identifier (0 for session-wide update)
     * @param flags Flags for this frame
     * @param delta The number of bytes to add to the window
     */
    data class WindowUpdate(
        override val streamId: Int,
        override val flags: Set<Flag>,
        val delta: Int
    ) : YamuxFrame()

    /**
     * PING frame - used for measuring round-trip time and connection liveness
     *
     * @param flags Flags for this frame (SYN for ping, ACK for pong)
     * @param value Opaque value to echo back in the response
     */
    data class Ping(
        override val flags: Set<Flag>,
        val value: Int
    ) : YamuxFrame() {
        // PING frames always use stream ID 0
        override val streamId: Int = 0
    }

    /**
     * GO_AWAY frame - signals session termination
     *
     * @param flags Flags for this frame
     * @param errorCode The reason for termination
     */
    data class GoAway(
        override val flags: Set<Flag>,
        val errorCode: GoAwayErrorCode
    ) : YamuxFrame() {
        // GO_AWAY frames always use stream ID 0
        override val streamId: Int = 0
    }

    /**
     * Error codes for GO_AWAY frames
     */
    enum class GoAwayErrorCode(val code: Int) {
        NORMAL_TERMINATION(0),
        PROTOCOL_ERROR(1),
        RECEIVED_GO_AWAY(2),
        INTERNAL_ERROR(3);

        companion object {
            fun fromCode(value: Int): GoAwayErrorCode {
                return entries.find { it.code == value }
                    ?: throw YamuxProtocolException("Invalid GO_AWAY error code: $value")
            }
        }
    }
}

/**
 * Exception thrown when yamux protocol operations fail
 */
class YamuxProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
