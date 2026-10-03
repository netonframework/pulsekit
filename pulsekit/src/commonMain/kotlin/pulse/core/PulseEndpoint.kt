package pulse.core

/**
 * Where the ingest server lives, written as one URL: `tcp://collect.example.com:6000`.
 *
 * One string rather than host and port so that every place an endpoint is configured — a build
 * property, Info.plist, an environment variable — holds a single value that cannot be half
 * updated, and so that a transport other than TCP can be added later without changing a signature.
 *
 * The rules are strict on purpose. This is a connection address, not an API URL: a path, a query
 * or credentials in it are a configuration mistake, and silently ignoring them would hide that.
 */
class PulseEndpoint private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$scheme://${if (':' in host) "[$host]" else host}:$port"

    override fun equals(other: Any?): Boolean =
        other is PulseEndpoint && other.scheme == scheme && other.host == host && other.port == port

    override fun hashCode(): Int = (scheme.hashCode() * 31 + host.hashCode()) * 31 + port

    companion object {
        /** The ingest server's default port (PULSE_INGEST_PORT on the server side). */
        const val DEFAULT_PORT = 6000

        /** Transports the SDK can open today. msgtrans has more; each needs its client wired in. */
        val SUPPORTED_SCHEMES: Set<String> = setOf("tcp")

        /** Parses [endpoint]; throws [IllegalArgumentException] naming what is wrong with it. */
        fun parse(endpoint: String): PulseEndpoint {
            val text = endpoint.trim()
            val separator = text.indexOf("://")
            require(separator > 0) { "endpoint '$endpoint' must be a URL such as tcp://collect.example.com:$DEFAULT_PORT" }
            val scheme = text.substring(0, separator).lowercase()
            require(scheme in SUPPORTED_SCHEMES) {
                "endpoint '$endpoint' uses unsupported scheme '$scheme'; supported: ${SUPPORTED_SCHEMES.joinToString()}"
            }
            val authority = text.substring(separator + 3)
            require(authority.isNotEmpty()) { "endpoint '$endpoint' has no host" }
            require(authority.none { it == '/' || it == '?' || it == '#' }) {
                "endpoint '$endpoint' must not have a path, query or fragment"
            }
            require('@' !in authority) { "endpoint '$endpoint' must not carry credentials" }

            val host: String
            val portText: String?
            if (authority.startsWith('[')) {
                val close = authority.indexOf(']')
                require(close > 1) { "endpoint '$endpoint' has an unterminated IPv6 address" }
                host = authority.substring(1, close)
                require(host.all { it.isHexDigit() || it == ':' || it == '.' }) {
                    "endpoint '$endpoint' has an invalid IPv6 address"
                }
                val rest = authority.substring(close + 1)
                portText = when {
                    rest.isEmpty() -> null
                    rest.startsWith(':') -> rest.substring(1)
                    else -> throw IllegalArgumentException("endpoint '$endpoint' has text after the IPv6 address")
                }
            } else {
                require(authority.count { it == ':' } <= 1) {
                    "endpoint '$endpoint': an IPv6 address must be in brackets, e.g. tcp://[::1]:$DEFAULT_PORT"
                }
                val colon = authority.indexOf(':')
                host = if (colon < 0) authority else authority.substring(0, colon)
                portText = if (colon < 0) null else authority.substring(colon + 1)
                require(host.isNotEmpty()) { "endpoint '$endpoint' has no host" }
                require(host.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }) {
                    "endpoint '$endpoint' has an invalid host '$host'"
                }
            }

            val port = if (portText == null) DEFAULT_PORT else {
                val value = portText.toIntOrNull()
                require(value != null && value in 1..65535) { "endpoint '$endpoint' has an invalid port '$portText'" }
                value
            }
            return PulseEndpoint(scheme, host, port)
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
