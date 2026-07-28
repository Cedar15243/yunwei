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
}
