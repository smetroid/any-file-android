package com.anyproto.anyfile.data.network.yamux

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Yamux protocol encoder and decoder.
 *
 * Implements the yamux multiplexing protocol frame encoding/decoding.
 * Frame format (12-byte header + payload):
 * - Version (1 byte): 0x00
 * - Type (1 byte): DATA(0x00), WINDOW_UPDATE(0x01), PING(0x02), GO_AWAY(0x03)
 * - Flags (2 bytes): SYN(0x01), ACK(0x02), FIN(0x04), RST(0x08)
 * - Length (4 bytes): payload length for DATA frames
 * - StreamID (4 bytes): stream identifier
 *
 * All multi-byte fields use big-endian (network byte order).
 */
object YamuxProtocol {

    private const val VERSION = 0x00
    private const val HEADER_SIZE = 12

    /**
     * Encode a yamux frame to bytes.
     *
     * @param frame The frame to encode
     * @return Byte array containing the encoded frame
     */
    fun encodeFrame(frame: YamuxFrame): ByteArray {
        return when (frame) {
            is YamuxFrame.Data -> encodeDataFrame(frame)
            is YamuxFrame.WindowUpdate -> encodeWindowUpdateFrame(frame)
            is YamuxFrame.Ping -> encodePingFrame(frame)
            is YamuxFrame.GoAway -> encodeGoAwayFrame(frame)
        }
    }

    /**
     * Read and decode a yamux frame from an input stream.
     *
     * @param inputStream The input stream to read from
     * @return The decoded frame
     * @throws YamuxProtocolException If the frame is invalid or incomplete
     */
    fun readFrame(inputStream: InputStream): YamuxFrame {
        // Read the 12-byte header
        val header = readHeader(inputStream)

        // Parse header fields
        val version = header[0].toInt() and 0xFF
        val typeValue = header[1].toInt() and 0xFF
        val flagsHigh = header[2].toInt() and 0xFF
        val flagsLow = header[3].toInt() and 0xFF
        // Yamux spec (hashicorp/yamux): bytes 4-7 = StreamID, bytes 8-11 = Length
        val streamId = ((header[4].toInt() and 0xFF) shl 24) or
                       ((header[5].toInt() and 0xFF) shl 16) or
                       ((header[6].toInt() and 0xFF) shl 8) or
                       (header[7].toInt() and 0xFF)
        val length = ((header[8].toInt() and 0xFF) shl 24) or
                     ((header[9].toInt() and 0xFF) shl 16) or
                     ((header[10].toInt() and 0xFF) shl 8) or
                     (header[11].toInt() and 0xFF)

        // Validate version
        if (version != VERSION) {
            throw YamuxProtocolException("Invalid yamux version: $version (expected $VERSION)")
        }

        // Parse frame type
        val type = YamuxFrame.Type.fromByte(typeValue)
        val flags = YamuxFrame.Flag.fromBytes(flagsHigh, flagsLow)

        // Decode based on type
        return when (type) {
            YamuxFrame.Type.DATA -> decodeDataFrame(inputStream, streamId, flags, length)
            YamuxFrame.Type.WINDOW_UPDATE -> decodeWindowUpdateFrame(streamId, flags, length)
            YamuxFrame.Type.PING -> decodePingFrame(flags, length)
            YamuxFrame.Type.GO_AWAY -> decodeGoAwayFrame(flags, length)
        }
    }

    /**
     * Encode a DATA frame.
     */
    private fun encodeDataFrame(frame: YamuxFrame.Data): ByteArray {
        val output = ByteArrayOutputStream()

        // Header (12 bytes) — yamux spec: StreamID at 4-7, Length at 8-11
        output.write(VERSION)                                    // Version
        output.write(YamuxFrame.Type.DATA.byteValue)            // Type
        writeFlags(output, frame.flags)                         // Flags
        writeInt32(output, frame.streamId)                      // StreamID (bytes 4-7)
        writeInt32(output, frame.data.size)                     // Length (bytes 8-11)

        // Payload
        output.write(frame.data)

        return output.toByteArray()
    }

    /**
     * Encode a WINDOW_UPDATE frame.
     */
    private fun encodeWindowUpdateFrame(frame: YamuxFrame.WindowUpdate): ByteArray {
        val output = ByteArrayOutputStream()

        // Header (12 bytes) — yamux spec: StreamID at 4-7, Length/delta at 8-11
        output.write(VERSION)                                        // Version
        output.write(YamuxFrame.Type.WINDOW_UPDATE.byteValue)       // Type
        writeFlags(output, frame.flags)                             // Flags
        writeInt32(output, frame.streamId)                          // StreamID (bytes 4-7)
        writeInt32(output, frame.delta)                             // Delta (bytes 8-11)

        return output.toByteArray()
    }

    /**
     * Encode a PING frame.
     */
    private fun encodePingFrame(frame: YamuxFrame.Ping): ByteArray {
        val output = ByteArrayOutputStream()

        // Header (12 bytes) — yamux spec: StreamID at 4-7, ping value at 8-11
        output.write(VERSION)                                   // Version
        output.write(YamuxFrame.Type.PING.byteValue)           // Type
        writeFlags(output, frame.flags)                        // Flags
        writeInt32(output, 0)                                  // StreamID (always 0, bytes 4-7)
        writeInt32(output, frame.value)                        // Ping value (bytes 8-11)

        return output.toByteArray()
    }

    /**
     * Encode a GO_AWAY frame.
     */
    private fun encodeGoAwayFrame(frame: YamuxFrame.GoAway): ByteArray {
        val output = ByteArrayOutputStream()

        // Header (12 bytes) — yamux spec: StreamID at 4-7, error code at 8-11
        output.write(VERSION)                                   // Version
        output.write(YamuxFrame.Type.GO_AWAY.byteValue)        // Type
        writeFlags(output, frame.flags)                        // Flags
        writeInt32(output, 0)                                  // StreamID (always 0, bytes 4-7)
        writeInt32(output, frame.errorCode.code)               // Error code (bytes 8-11)

        return output.toByteArray()
    }

    /**
     * Decode a DATA frame.
     */
    private fun decodeDataFrame(
        inputStream: InputStream,
        streamId: Int,
        flags: Set<YamuxFrame.Flag>,
        length: Int
    ): YamuxFrame.Data {
        // Read payload
        val data = if (length > 0) {
            readExactBytes(inputStream, length)
        } else {
            ByteArray(0)
        }

        return YamuxFrame.Data(streamId, flags, data)
    }

    /**
     * Decode a WINDOW_UPDATE frame.
     */
    private fun decodeWindowUpdateFrame(
        streamId: Int,
        flags: Set<YamuxFrame.Flag>,
        length: Int
    ): YamuxFrame.WindowUpdate {
        // Length field contains the delta value
        return YamuxFrame.WindowUpdate(streamId, flags, length)
    }

    /**
     * Decode a PING frame.
     */
    private fun decodePingFrame(
        flags: Set<YamuxFrame.Flag>,
        length: Int
    ): YamuxFrame.Ping {
        // Length field contains the ping value
        return YamuxFrame.Ping(flags, length)
    }

    /**
     * Decode a GO_AWAY frame.
     */
    private fun decodeGoAwayFrame(
        flags: Set<YamuxFrame.Flag>,
        length: Int
    ): YamuxFrame.GoAway {
        // Length field contains the error code
        val errorCode = YamuxFrame.GoAwayErrorCode.fromCode(length)
        return YamuxFrame.GoAway(flags, errorCode)
    }

    /**
     * Read exactly 12 bytes for the frame header.
     */
    private fun readHeader(inputStream: InputStream): ByteArray {
        val header = readExactBytes(inputStream, HEADER_SIZE)
        return header
    }

    /**
     * Read exactly the specified number of bytes from the input stream.
     *
     * @param inputStream The input stream to read from
     * @param length The number of bytes to read
     * @return Byte array containing the read bytes
     * @throws YamuxProtocolException If unable to read the required bytes
     */
    private fun readExactBytes(inputStream: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var totalRead = 0

        while (totalRead < length) {
            val read = inputStream.read(buffer, totalRead, length - totalRead)
            if (read == -1) {
                throw YamuxProtocolException(
                    "Unexpected end of stream: expected $length bytes, got $totalRead"
                )
            }
            totalRead += read
        }

        return buffer
    }

    /**
     * Write flags to output stream (2 bytes, big-endian).
     */
    private fun writeFlags(output: OutputStream, flags: Set<YamuxFrame.Flag>) {
        var flagsValue = 0
        flags.forEach { flagsValue = flagsValue or it.byteValue }
        output.write((flagsValue shr 8) and 0xFF)
        output.write(flagsValue and 0xFF)
    }

    /**
     * Write a 32-bit integer to output stream (big-endian).
     */
    private fun writeInt32(output: OutputStream, value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    /**
     * Write a frame to an output stream.
     *
     * @param outputStream The output stream to write to
     * @param frame The frame to write
     */
    fun writeFrame(outputStream: OutputStream, frame: YamuxFrame) {
        val encoded = encodeFrame(frame)
        outputStream.write(encoded)
        outputStream.flush()
    }
}
