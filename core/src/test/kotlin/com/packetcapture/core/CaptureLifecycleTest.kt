package com.packetcapture.core
import org.junit.Assert.*
import org.junit.Test

class CaptureLifecycleTest {
    @Test fun lifecycleRejectsSkippingStartupAndSupportsRecovery() {
        val lifecycle = CaptureLifecycle()
        assertThrows(IllegalArgumentException::class.java) { lifecycle.transition(CapturePhase.CAPTURING) }
        lifecycle.transition(CapturePhase.STARTING)
        lifecycle.transition(CapturePhase.FAILED)
        lifecycle.transition(CapturePhase.STARTING)
        lifecycle.transition(CapturePhase.CAPTURING)
        lifecycle.transition(CapturePhase.STOPPING)
        lifecycle.transition(CapturePhase.IDLE)
        assertEquals(CapturePhase.IDLE, lifecycle.phase)
    }
    @Test fun bypassRequiresDomainBoundary() {
        assertTrue(matchesBypass("API.example.com.", setOf("*.example.com")))
        assertFalse(matchesBypass("evil-example.com", setOf("*.example.com")))
        assertFalse(matchesBypass("example.com", setOf("*.example.com")))
        assertTrue(matchesBypass("example.com", setOf("example.com")))
    }
}
