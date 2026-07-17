package com.codex.air3nativecamera.mode;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class IntegratedModeControllerTest {
    @Test
    public void expertEntryReleasesLegacyMediaBeforeShowingExpert() {
        FakeHooks hooks = new FakeHooks();
        IntegratedModeController controller = new IntegratedModeController(hooks);

        controller.enterExpert();

        assertEquals(Arrays.asList("persist", "stopVoice", "closeCamera", "showExpert"), hooks.events);
        assertEquals(IntegratedModeController.Mode.EXPERT, controller.mode());
    }

    @Test
    public void expertExitReleasesTrtcBeforeRestoringChat() {
        FakeHooks hooks = new FakeHooks();
        IntegratedModeController controller = new IntegratedModeController(hooks);
        controller.enterExpert();
        hooks.events.clear();

        controller.exitExpert();

        assertEquals(Arrays.asList("releaseExpert", "showChat", "startCamera", "resumeVoice"), hooks.events);
        assertEquals(IntegratedModeController.Mode.CHAT, controller.mode());
    }

    @Test
    public void repeatedEntryAndExitAreIdempotent() {
        FakeHooks hooks = new FakeHooks();
        IntegratedModeController controller = new IntegratedModeController(hooks);

        controller.enterExpert();
        controller.enterExpert();
        controller.exitExpert();
        controller.exitExpert();

        assertEquals(Arrays.asList(
                "persist", "stopVoice", "closeCamera", "showExpert",
                "releaseExpert", "showChat", "startCamera", "resumeVoice"), hooks.events);
    }

    private static final class FakeHooks implements IntegratedModeController.Hooks {
        private final List<String> events = new ArrayList<>();

        @Override
        public void persistLegacyState() {
            events.add("persist");
        }

        @Override
        public void stopLegacyVoice() {
            events.add("stopVoice");
        }

        @Override
        public void closeLegacyCamera() {
            events.add("closeCamera");
        }

        @Override
        public void showExpert() {
            events.add("showExpert");
        }

        @Override
        public void releaseExpert() {
            events.add("releaseExpert");
        }

        @Override
        public void showChat() {
            events.add("showChat");
        }

        @Override
        public void startLegacyCamera() {
            events.add("startCamera");
        }

        @Override
        public void resumeLegacyVoice() {
            events.add("resumeVoice");
        }
    }
}
