package pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionRegistrationTest {
    @Test
    fun registrationReusesDurableBatchIdentity() {
        val config = PulseConfig(
            projectId = "app-1",
            host = "127.0.0.1",
            platform = "ios",
            deviceType = "iPhone",
            packageName = "com.example.app",
            appVersion = "1.2.3",
            buildNumber = 42,
            runtime = true,
        )
        val request = sessionRegisterRequest(config, Identity("app-1", "install-1", "device-1"))

        assertEquals("app-1", request.appId)
        assertEquals("device-1", request.deviceId)
        assertEquals("install-1", request.installationId)
        assertEquals("ios", request.platform)
        assertEquals("iPhone", request.deviceType)
        assertEquals("com.example.app", request.packageName)
        assertEquals("1.2.3", request.appVersion)
        assertEquals(42, request.buildNumber)
        assertTrue("runtime_audit" in request.capabilities)
        assertTrue("bidirectional_request" in request.capabilities)
    }
}
