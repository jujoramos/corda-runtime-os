package net.corda.httprpc.exception

import net.corda.httprpc.ResponseCode
import net.corda.v5.base.exceptions.CordaRuntimeException

/**
 * Base class for HTTP exceptions.
 *
 * Inherit from this class and override the status code to create a HTTP response with a certain status code ([ResponseCode.statusCode]).
 *
 * @param responseCode HTTP error response code
 * @param message the response message
 * @param details additional problem details
 * @param exceptionDetails optional exception information to be converted to cause and reason properties in details map
 */
abstract class HttpApiException(
    val responseCode: ResponseCode,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
    val exceptionDetails: ExceptionDetails? = null
) : CordaRuntimeException(message)