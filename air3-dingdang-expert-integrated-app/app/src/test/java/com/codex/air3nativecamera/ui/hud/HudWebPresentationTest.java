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
}
