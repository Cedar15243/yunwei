package com.codex.expertcollab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CollabStateMachineTest {
    @Test
    public void primaryButtonStartsCallingAndEndsAnActiveCall() {
        CollabStateMachine machine = new CollabStateMachine();

        assertEquals(CollabStateMachine.State.CALLING, machine.onPrimaryAction());
        assertEquals(CollabStateMachine.State.CONNECTING, machine.onAccepted("expert-wang"));
        assertEquals(CollabStateMachine.State.IN_CALL, machine.onMediaConnected());
        assertEquals(CollabStateMachine.State.ENDED, machine.onPrimaryAction());
    }

    @Test
    public void activeCallCanReconnectWithoutCreatingAnotherCall() {
        CollabStateMachine machine = new CollabStateMachine();
        machine.onPrimaryAction();
        machine.onAccepted("expert-wang");
        machine.onMediaConnected();

        assertEquals(CollabStateMachine.State.RECONNECTING, machine.onConnectionLost());
        assertEquals(CollabStateMachine.State.IN_CALL, machine.onMediaConnected());
        assertEquals("expert-wang", machine.getExpertId());
    }

    @Test
    public void acceptingBeforeCallingIsRejected() {
        CollabStateMachine machine = new CollabStateMachine();

        assertThrows(IllegalStateException.class, () -> machine.onAccepted("expert-wang"));
    }

    @Test
    public void reconnectWhileCallingResendsTheInvitationForServerDeduplication() {
        CollabStateMachine machine = new CollabStateMachine();
        machine.onPrimaryAction();

        assertEquals(true, machine.shouldReplayPendingCallOnSignalingConnected());
    }

    @Test
    public void activeMediaSessionNeverCreatesAnotherInvitationOnReconnect() {
        CollabStateMachine machine = new CollabStateMachine();
        machine.onPrimaryAction();
        machine.onAccepted("expert-wang");
        machine.onMediaConnected();

        assertEquals(false, machine.shouldReplayPendingCallOnSignalingConnected());
    }
}
