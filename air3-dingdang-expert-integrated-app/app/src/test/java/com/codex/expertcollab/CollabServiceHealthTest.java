package com.codex.expertcollab;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CollabServiceHealthTest {
    @Test
    public void mapsOnlySuccessfulHttpResponsesToAvailable() {
        assertEquals(CollabServiceHealth.State.AVAILABLE, CollabServiceHealth.fromHttpStatus(200));
        assertEquals(CollabServiceHealth.State.UNAVAILABLE, CollabServiceHealth.fromHttpStatus(503));
    }

    @Test
    public void derivesOneHealthEndpointWithoutDuplicateSlashes() {
        assertEquals("https://bb.chinacedar.top:2305/health",
                CollabServiceHealth.healthUrl("https://bb.chinacedar.top:2305/"));
    }
}
