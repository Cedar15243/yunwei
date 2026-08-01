package com.codex.air3nativecamera.sync;

import java.io.IOException;

/** Supplies a short-lived device access token without exposing the bootstrap credential. */
public interface DeviceAccessTokenProvider {
    String accessToken() throws IOException;
}
