package com.anyproto.anyfile.data.network

import java.math.BigInteger

/**
 * Base58btc encoder/decoder using the Bitcoin/IPFS alphabet.
 *
 * Alphabet: 123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz
 *
 * Used to encode CID bytes as fileId strings for cross-device sync.
 */
object Base58Btc {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val ALPHABET_MAP: Map<Char, Int> = ALPHABET.mapIndexed { i, c -> c to i }.toMap()
    private val BASE = BigInteger.valueOf(58)
    private val ZERO = BigInteger.ZERO

    /**
     * Encode bytes to a base58btc string.
     */
    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // Count leading zero bytes
        var leadingZeros = 0
        for (b in input) {
            if (b == 0.toByte()) leadingZeros++ else break
        }

        // Convert to BigInteger (treat as unsigned big-endian)
        var num = BigInteger(1, input)
        val sb = StringBuilder()
        while (num > ZERO) {
            val (quotient, remainder) = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[remainder.intValueExact()])
            num = quotient
        }

        // Add '1' for each leading zero byte
        repeat(leadingZeros) { sb.append('1') }

        return sb.reverse().toString()
    }

    /**
     * Decode a base58btc string to bytes.
     *
     * @throws IllegalArgumentException if the input contains invalid characters
     */
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        // Count leading '1' chars (represent zero bytes)
        var leadingZeros = 0
        for (c in input) {
            if (c == '1') leadingZeros++ else break
        }

        // Decode to BigInteger
        var num = ZERO
        for (c in input) {
            val digit = ALPHABET_MAP[c]
                ?: throw IllegalArgumentException("Invalid base58 character: '$c'")
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        // Convert to byte array
        val bytes = num.toByteArray()
        // Remove sign byte if present
        val stripped = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes

        // Prepend leading zero bytes
        return ByteArray(leadingZeros) + stripped
    }
}
