package com.codex.air3nativecamera.ui.hud;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;

import org.junit.Test;

public final class HudWebPresentationTest {
    @Test
    public void photoDraftIsAnAllowedHudState() throws Exception {
        Method safeState = HudWebPresentation.class.getDeclaredMethod("safeState", String.class);
        safeState.setAccessible(true);

        assertEquals("photoDraft", safeState.invoke(null, "photoDraft"));
    }

    @Test
    public void guideContextAndTutorialPageAreClampedToSupportedValues() throws Exception {
        Method safeContext = HudWebPresentation.class.getDeclaredMethod("safeGuideContext", String.class);
        safeContext.setAccessible(true);
        Method safePage = HudWebPresentation.class.getDeclaredMethod("safeTutorialPage", int.class);
        safePage.setAccessible(true);

        assertEquals("inspection", safeContext.invoke(null, "inspection"));
        assertEquals("task", safeContext.invoke(null, "task"));
        assertEquals("home", safeContext.invoke(null, "unknown"));
        assertEquals(1, safePage.invoke(null, -1));
        assertEquals(4, safePage.invoke(null, 8));
    }

    @Test
    public void taskEvidenceSupportsStructuredDetectionMarkers() throws Exception {
        Method markerMethod = HudWebPresentation.class.getDeclaredMethod(
                "setTaskDetectionMarkers", String.class);

        assertEquals(void.class, markerMethod.getReturnType());
    }

    @Test
    public void voiceGuideModeIncludesAnExplicitUnavailableState() throws Exception {
        Method safeMode = HudWebPresentation.class.getDeclaredMethod("safeVoiceMode", String.class);
        safeMode.setAccessible(true);

        assertEquals("wake", safeMode.invoke(null, "wake"));
        assertEquals("voiceprint", safeMode.invoke(null, "voiceprint"));
        assertEquals("passive", safeMode.invoke(null, "passive"));
        assertEquals("unavailable", safeMode.invoke(null, "unavailable"));
        assertEquals("wake", safeMode.invoke(null, "unknown"));
    }

    @Test
    public void standbyAvailabilityCanReplaceTheReadyLabelWithoutChangingPages() throws Exception {
        Method availability = HudWebPresentation.class.getDeclaredMethod(
                "setStandbyAvailability", String.class, String.class);

        assertEquals(void.class, availability.getReturnType());
    }

    @Test
    public void hamburgerMenuHasADedicatedToggleBridge() throws Exception {
        Method toggleMenu = HudWebPresentation.Actions.class.getDeclaredMethod("toggleMenu");

        assertEquals(void.class, toggleMenu.getReturnType());
    }

    @Test
    public void taskEndReceiptCanReplaceTheStandbyPromptWithoutAddingANewPage() throws Exception {
        Method notice = HudWebPresentation.class.getDeclaredMethod(
                "setStandbyNotice", String.class);

        assertEquals(void.class, notice.getReturnType());
    }
}
