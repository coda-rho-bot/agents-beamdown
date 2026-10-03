package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-registration output mapping (audit fix 6): the tailer's line →
 * status/notification table lives in the companion object so it is
 * JVM-testable without instrumenting the service. Auth-rejection
 * detection is the contract under test — a bad key must surface as
 * "Key rejected by Letta Cloud", never a generic exit code.
 */
class RegistrationLineTest {

    // ---- auth rejection detection --------------------------------------------

    @Test
    fun detectsHttp401() {
        assertTrue(LettaEnvironmentService.isAuthRejection("Error: request failed with status 401"))
    }

    @Test
    fun detectsUnauthorizedCaseInsensitively() {
        assertTrue(LettaEnvironmentService.isAuthRejection("HTTP 401 UNAUTHORIZED"))
        assertTrue(LettaEnvironmentService.isAuthRejection("unauthorized"))
        assertTrue(LettaEnvironmentService.isAuthRejection("UnAuthorized"))
    }

    @Test
    fun detectsInvalidKeyWordingVariants() {
        assertTrue(LettaEnvironmentService.isAuthRejection("invalid api key"))
        assertTrue(LettaEnvironmentService.isAuthRejection("INVALID API KEY provided"))
        assertTrue(LettaEnvironmentService.isAuthRejection("error: invalid_api_key"))
        assertTrue(LettaEnvironmentService.isAuthRejection("invalid api-key format"))
        assertTrue(LettaEnvironmentService.isAuthRejection("authentication failed"))
        assertTrue(LettaEnvironmentService.isAuthRejection("Auth Failed"))
    }

    @Test
    fun ordinaryOutputIsNotAuthRejection() {
        // False positives are worse than misses here: an ordinary log line
        // misread as rejection would tell the user to re-key a working key.
        assertFalse(LettaEnvironmentService.isAuthRejection("Registering with Letta Cloud"))
        assertFalse(LettaEnvironmentService.isAuthRejection("Registered successfully"))
        assertFalse(LettaEnvironmentService.isAuthRejection("[Listen V2] connected"))
        assertFalse(LettaEnvironmentService.isAuthRejection("pong"))
        assertFalse(LettaEnvironmentService.isAuthRejection(""))
    }

    // ---- full line mapping ----------------------------------------------------

    @Test
    fun authRejectionMapsToKeyRejectedStatus() {
        val rs = LettaEnvironmentService.classifyRegistrationLine(
            "Error: registration failed — 401 Unauthorized"
        )!!
        assertEquals("key rejected by Letta Cloud — check your API key", rs.status)
        assertEquals("Key rejected by Letta Cloud", rs.notification)
    }

    @Test
    fun registeringAndRegisteredStillMap() {
        assertEquals(
            "registering with Letta Cloud",
            LettaEnvironmentService.classifyRegistrationLine("Registering with Letta Cloud...")!!.status
        )
        val ok = LettaEnvironmentService.classifyRegistrationLine("Registered successfully")!!
        assertEquals("registered with Letta Cloud", ok.status)
        assertEquals("Registered — online", ok.notification)
    }

    @Test
    fun listenV2MapsToOnlineWithComposedNotification() {
        val rs = LettaEnvironmentService.classifyRegistrationLine("[Listen V2] recv")!!
        assertEquals("online — listener active", rs.status)
        // null notification = the tailer composes "Online — <env name>" at
        // runtime; the mapping itself carries no env-name coupling.
        assertNull(rs.notification)
    }

    @Test
    fun authRejectionBeatsProgressLinesInClassifier() {
        // A line carrying BOTH signals (registration in flight + 401 in the
        // message) must classify as rejection — the key is dead, telling the
        // user "registering…" would hang the status at a lie.
        val rs = LettaEnvironmentService.classifyRegistrationLine(
            "Registering with Letta Cloud failed: 401 Unauthorized"
        )!!
        assertTrue(rs.status.startsWith("key rejected by Letta Cloud"))
    }

    @Test
    fun plainTrafficLinesMapToNull() {
        assertNull(LettaEnvironmentService.classifyRegistrationLine("pong"))
        assertNull(LettaEnvironmentService.classifyRegistrationLine("debug: recv"))
        assertNull(LettaEnvironmentService.classifyRegistrationLine(""))
    }
}
