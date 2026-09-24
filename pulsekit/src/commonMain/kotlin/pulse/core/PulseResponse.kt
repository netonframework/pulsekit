package pulse.core

import kotlinx.serialization.Serializable

/** Stable application response carried inside every Pulse msgtrans Response packet. */
@Serializable
data class PulseResponse<T>(
    val code: Int = 0,
    val msg: String? = null,
    val data: T? = null,
) {
    val isSuccess: Boolean get() = code == 0
}

/** A response was received, but the Pulse server did not accept the request. */
class PulseResponseException(
    val code: Int,
    message: String?,
) : Exception(message ?: "Pulse request failed with code=$code")
