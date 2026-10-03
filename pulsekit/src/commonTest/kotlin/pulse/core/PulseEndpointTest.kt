package pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PulseEndpointTest {

    @Test
    fun hostAndPort() {
        val e = PulseEndpoint.parse("tcp://collect.example.com:7000")
        assertEquals("tcp", e.scheme)
        assertEquals("collect.example.com", e.host)
        assertEquals(7000, e.port)
    }

    @Test
    fun portDefaultsToTheIngestServers() {
        assertEquals(PulseEndpoint.DEFAULT_PORT, PulseEndpoint.parse("tcp://collect.example.com").port)
        assertEquals(6000, PulseEndpoint.DEFAULT_PORT)
    }

    @Test
    fun ipv4AndBracketedIpv6() {
        assertEquals("43.154.96.16", PulseEndpoint.parse("tcp://43.154.96.16:6000").host)
        val v6 = PulseEndpoint.parse("tcp://[2001:db8::1]:6001")
        assertEquals("2001:db8::1", v6.host)
        assertEquals(6001, v6.port)
        assertEquals("tcp://[2001:db8::1]:6001", v6.toString())
        assertEquals(PulseEndpoint.DEFAULT_PORT, PulseEndpoint.parse("tcp://[::1]").port)
    }

    @Test
    fun schemeIsCaseInsensitiveAndWhitespaceIsTrimmed() {
        val e = PulseEndpoint.parse("  TCP://Collect.Example.com:6000 \n")
        assertEquals("tcp", e.scheme)
        assertEquals("tcp://Collect.Example.com:6000", e.toString())
    }

    @Test
    fun rejectsWhatIsNotAConnectionAddress() {
        val bad = listOf(
            "collect.example.com",              // no scheme
            "collect.example.com:6000",         // no scheme
            "udp://collect.example.com:6000",   // unsupported transport
            "http://collect.example.com:6000",  // not a transport at all
            "tcp://",                           // no host
            "tcp://:6000",                      // no host
            "tcp://collect.example.com:0",      // port out of range
            "tcp://collect.example.com:65536",  // port out of range
            "tcp://collect.example.com:abc",    // port not a number
            "tcp://collect.example.com:6000/",  // path
            "tcp://collect.example.com/ingest", // path
            "tcp://collect.example.com?x=1",    // query
            "tcp://user:pw@collect.example.com",// credentials
            "tcp://2001:db8::1",                // unbracketed IPv6
            "tcp://[2001:db8::1",               // unterminated IPv6
            "tcp://[2001:db8::1]x",             // text after IPv6
            "tcp://bad host:6000",              // invalid host
        )
        for (text in bad) {
            assertFailsWith<IllegalArgumentException>(text) { PulseEndpoint.parse(text) }
        }
    }

    @Test
    fun theErrorSaysWhatIsWrong() {
        val e = assertFailsWith<IllegalArgumentException> { PulseEndpoint.parse("udp://h:1") }
        assertTrue("unsupported scheme 'udp'" in e.message.orEmpty(), e.message)
    }

    @Test
    fun configParsesItsEndpointUpFront() {
        assertEquals(7000, PulseConfig(projectId = "t", endpoint = "tcp://h:7000").server.port)
        assertFailsWith<IllegalArgumentException> { PulseConfig(projectId = "t", endpoint = "h:7000") }
    }
}
