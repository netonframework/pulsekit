@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package pulse.runtime

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLSessionTask

/**
 * Reads the destination of a network request, in a form that is safe to store.
 *
 * A full URL routinely carries session tokens, signed parameters and personal data in its query
 * string. This module's own rule is that attributes hold metadata and never secrets, so the query
 * and fragment are dropped and only `scheme://host/path` is kept. That still answers the question
 * an audit asks — which SDK talked to whom, and about what kind of endpoint — without turning the
 * telemetry store into a place where credentials accumulate.
 */
internal object NetworkDetail {

    /** Sanitised destination of the task being resumed, or null if it cannot be read. */
    fun ofTask(self: COpaquePointer?): String? {
        val ptr = self ?: return null
        val task = interpretObjCPointer<NSURLSessionTask>(ptr.rawValue)
        val url = task.originalRequest?.URL ?: task.currentRequest?.URL ?: return null
        return sanitize(url)
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
}
