package com.codex.air3nativecamera.voice;

import android.content.Context;

import java.lang.reflect.Constructor;

public final class LocalAsrEngineFactory {
    private static final String SHERPA_ENGINE_CLASS =
            "com.codex.air3nativecamera.voice.SherpaLocalAsrEngine";

    private LocalAsrEngineFactory() {
    }

    public static LocalAsrEngine create(Context context, boolean enabled) {
        if (!enabled || context == null) {
            return new LocalAsrEngine.Unavailable();
        }
        try {
            Class<?> engineClass = Class.forName(SHERPA_ENGINE_CLASS);
            Constructor<?> constructor = engineClass.getConstructor(Context.class);
            return (LocalAsrEngine) constructor.newInstance(context.getApplicationContext());
        } catch (Exception error) {
            return new LocalAsrEngine.Unavailable();
        }
    }
}
