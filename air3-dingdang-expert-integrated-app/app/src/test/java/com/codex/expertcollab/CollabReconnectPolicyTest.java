package com.codex.expertcollab;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CollabReconnectPolicyTest {
    @Test
    public void reconnectStartsQuicklyThenBacksOffToLimitBackgroundWork() {
        CollabReconnectPolicy policy = new CollabReconnectPolicy();

        assertEquals(500L, policy.nextDelayMs());
        assertEquals(1_000L, policy.nextDelayMs());
        assertEquals(2_000L, policy.nextDelayMs());
        assertEquals(4_000L, policy.nextDelayMs());
        assertEquals(8_000L, policy.nextDelayMs());
        assertEquals(15_000L, policy.nextDelayMs());
        assertEquals(15_000L, policy.nextDelayMs());
    }

    @Test
    public void successfulConnectionResetsTheBackoff() {
        CollabReconnectPolicy policy = new CollabReconnectPolicy();
        policy.nextDelayMs();
        policy.nextDelayMs();

        policy.reset();

        assertEquals(500L, policy.nextDelayMs());
    }
}
