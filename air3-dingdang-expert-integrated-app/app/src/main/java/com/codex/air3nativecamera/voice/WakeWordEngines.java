package com.codex.air3nativecamera.voice;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;

public final class WakeWordEngines {
    private static final String TAG = "DingdangWake";
    private static final String IFLYTEK_ENGINE =
            "com.codex.air3nativecamera.voice.IflytekWakeWordEngine";

    private WakeWordEngines() {
    }

    public static WakeWordEngine create(
            Context context,
            boolean enabled,
            String appId,
            String apiKey,
            String apiSecret) {
        if (!enabled) {
            return null;
        }
        try {
            Class<?> type = Class.forName(IFLYTEK_ENGINE);
            Constructor<?> constructor = type.getConstructor(
                    Context.class, String.class, String.class, String.class);
            return (WakeWordEngine) constructor.newInstance(context, appId, apiKey, apiSecret);
        } catch (Exception error) {
            Log.e(TAG, "Offline wake engine unavailable", error);
            return null;
        }
    }
}
