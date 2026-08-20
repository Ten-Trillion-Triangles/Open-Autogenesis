package structs.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeveloperModeDetectorTest
{
    @Test fun `explicit env value true forces developer mode`() {
        assertTrue(DeveloperModeDetector.detect(
            platformDebugMode = false,
            loopbackPortActive = false,
            envValue = "true"
        ))
    }

    @Test fun `explicit env value false with no other signal returns false`() {
        assertFalse(DeveloperModeDetector.detect(
            platformDebugMode = false,
            loopbackPortActive = false,
            envValue = "false"
        ))
    }

    @Test fun `truthy env value variations all force developer mode`() {
        for (v in listOf("1", "yes", "on", "TRUE", "  True  ")) {
            assertTrue(
                DeveloperModeDetector.detect(false, false, v),
                "env value '$v' must be truthy"
            )
        }
    }

    @Test fun `loopback signal alone is sufficient`() {
        assertTrue(DeveloperModeDetector.detect(
            platformDebugMode = false,
            loopbackPortActive = true,
            envValue = null
        ))
    }

    @Test fun `platform debug mode alone is sufficient`() {
        assertTrue(DeveloperModeDetector.detect(
            platformDebugMode = true,
            loopbackPortActive = false,
            envValue = null
        ))
    }

    @Test fun `hostname hint localhost alone is sufficient`() {
        assertTrue(DeveloperModeDetector.detect(
            platformDebugMode = false,
            loopbackPortActive = false,
            envValue = null,
            hostnameHint = "localhost"
        ))
    }

    @Test fun `hostname hint with local suffix is sufficient`() {
        assertTrue(DeveloperModeDetector.detect(
            platformDebugMode = false,
            loopbackPortActive = false,
            envValue = null,
            hostnameHint = "my-mac.local"
        ))
    }

    @Test fun `production hostname with no other signal returns false`() {
        assertFalse(DeveloperModeDetector.detect(
            platformDebugMode = false,
            loopbackPortActive = false,
            envValue = null,
            hostnameHint = "app.example.com"
        ))
    }

    @Test fun `no signals at all returns false`() {
        assertFalse(DeveloperModeDetector.detect(
            platformDebugMode = false,
            loopbackPortActive = false,
            envValue = null
        ))
    }

    @Test fun `signal precedence - env value trumps everything else`() {
        // Even when all other signals are false, a truthy env value wins.
        assertTrue(DeveloperModeDetector.detect(false, false, "1", hostnameHint = "app.example.com"))
    }

    @Test fun `cost class includes DEVELOPER at top of declaration order`() {
        assertEquals(CostClass.DEVELOPER, CostClass.entries.first())
        assertEquals(CostClass.DEVELOPER.rank, 0)
    }
}