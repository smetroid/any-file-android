package com.anyproto.anyfile.data.network.drpc

/**
 * Base exception for all DRPC-related errors.
 */
sealed class DrpcException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when a DRPC call times out.
 *
 * @param message The error message
 * @param cause Optional underlying cause
 */
class DrpcTimeoutException(message: String = "DRPC call timed out", cause: Throwable? = null) :
    DrpcException(message, cause)

/**
 * Exception thrown when DRPC message parsing fails.
 *
 * @param message The error message
 * @param cause Optional underlying cause
 */
class DrpcParseException(message: String, cause: Throwable? = null) :
    DrpcException(message, cause)

/**
 * Exception thrown when a DRPC RPC call fails on the server.
 *
 * @property code The error code returned by the server
 * @property message The error message
 * @property cause Optional underlying cause
 */
class DrpcRpcException(
    val code: Int,
    message: String,
    cause: Throwable? = null
) : DrpcException("RPC error [$code]: $message", cause)

/**
 * Exception thrown when DRPC connection or stream fails.
 *
 * @param message The error message
 * @param cause Optional underlying cause
 */
class DrpcConnectionException(message: String, cause: Throwable? = null) :
    DrpcException(message, cause)

/**
 * Exception thrown when DRPC message encoding fails.
 *
 * @param message The error message
 * @param cause Optional underlying cause
 */
class DrpcEncodingException(message: String, cause: Throwable? = null) :
    DrpcException(message, cause)

/**
 * Exception thrown when an invalid response is received.
 *
 * @param message The error message
 * @param cause Optional underlying cause
 */
class DrpcInvalidResponseException(message: String, cause: Throwable? = null) :
    DrpcException(message, cause)

/**
 * Exception thrown when a DRPC stream is closed unexpectedly.
 *
 * @param message The error message
 * @param cause Optional underlying cause
 */
class DrpcStreamClosedException(message: String = "DRPC stream closed unexpectedly", cause: Throwable? = null) :
    DrpcException(message, cause)
