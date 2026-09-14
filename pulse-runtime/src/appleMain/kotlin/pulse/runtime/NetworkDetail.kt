@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package pulse.runtime

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.*

/**
 * Reads the destination of a network request, in a form that is safe to store.
 *
 * A full URL routinely carries session tokens, signed parameters and personal data in its query
 * string. This module's own rule is that attributes hold metadata and never secrets, so query
 * values and fragments are dropped. Request method, field names, content type and byte counts are
 * retained because they describe the transfer without copying its contents into telemetry.
 */
internal object NetworkDetail {

    /**
     * Bounded, value-free request evidence from the task being resumed.
     *
     * Field names are evidence of the shape of the transfer, while field values may be the exact
     * secret we are trying to protect. Keep the former and never copy the latter.
     */
    fun ofTask(self: COpaquePointer?): String? {
        val ptr = self ?: return null
        val task = interpretObjCPointer<NSURLSessionTask>(ptr.rawValue)
        val request = task.originalRequest ?: task.currentRequest ?: return null
        return describe(request)
    }

    internal fun describe(request: NSURLRequest): String? {
        val url = request.URL ?: return null
        val destination = sanitize(url) ?: return null
        val method = request.HTTPMethod?.uppercase() ?: "GET"
        val queryKeys = fieldNames(url.query)
        val headerNames = request.allHTTPHeaderFields?.keys
            ?.map { boundedName(it.toString()) }
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.sorted()
            ?.take(MAX_FIELDS)
            .orEmpty()
        val contentType = request.valueForHTTPHeaderField("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.take(MAX_NAME_LENGTH)
        val bodyLength = request.HTTPBody?.length?.toLong()
        val streamed = request.HTTPBodyStream != null

        return buildString {
            append(method); append(' '); append(destination)
            if (queryKeys.isNotEmpty()) append(" query_keys=").append(queryKeys.joinToString(","))
            if (headerNames.isNotEmpty()) append(" header_names=").append(headerNames.joinToString(","))
            if (!contentType.isNullOrEmpty()) append(" content_type=").append(contentType)
            when {
                bodyLength != null -> append(" body_bytes=").append(bodyLength)
                streamed -> append(" body_stream=true")
            }
        }.take(MAX_DETAIL_LENGTH)
    }

    /**
     * Rebuilt from components rather than truncated at the first `?`: a URL can carry a fragment
     * with no query, and string surgery on user-supplied input is how the odd case gets missed.
     */
    fun sanitize(url: NSURL): String? {
        val host = url.host ?: return url.scheme?.let { "$it://" }
        val scheme = url.scheme ?: "http"
        val path = url.path.orEmpty()
        // Port only when it is explicit; the default port adds nothing and makes two records of
        // the same endpoint look different.
        val port = url.port?.intValue()?.let { ":$it" } ?: ""
        return "$scheme://$host$port$path"
    }

    /** Parse names only. Values are deliberately never decoded or retained. */
    private fun fieldNames(query: String?): List<String> = query
        ?.split('&')
        ?.asSequence()
        // A bare query component has no separable name/value boundary. Reporting it as a name
        // could upload a token from URLs shaped like `?eyJ...`, so only named fields are kept.
        ?.filter { '=' in it }
        ?.map { it.substringBefore('=') }
        ?.filter(String::isNotBlank)
        ?.map(::boundedName)
        ?.distinct()
        ?.sorted()
        ?.take(MAX_FIELDS)
        ?.toList()
        .orEmpty()

    private fun boundedName(value: String): String = value.take(MAX_NAME_LENGTH)

    private const val MAX_FIELDS = 32
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_DETAIL_LENGTH = 2_048
}
