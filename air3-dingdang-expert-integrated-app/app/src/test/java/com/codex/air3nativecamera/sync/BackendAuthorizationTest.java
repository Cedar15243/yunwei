package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public final class BackendAuthorizationTest {
    @Test
    public void sessionAuthorizationReadsTheCurrentInMemoryTokenPerRequest() throws Exception {
        final String[] token = { "access-a" };
        BackendAuthorization authorization = BackendAuthorization.session(new DeviceAccessTokenProvider() {
            @Override
            public String accessToken() {
                return token[0];
            }
        });
        HeaderConnection first = new HeaderConnection();
        HeaderConnection second = new HeaderConnection();

        authorization.apply(first);
        token[0] = "access-b";
        authorization.apply(second);

        assertEquals("Bearer access-a", first.getRequestProperty("Authorization"));
        assertEquals("Bearer access-b", second.getRequestProperty("Authorization"));
        assertNull(first.getRequestProperty("x-ops-glasses-key"));
    }

    @Test
    public void legacyAuthorizationKeepsTheExistingServerKeyHeaders() throws Exception {
        BackendAuthorization authorization = BackendAuthorization.legacy("legacy-key");
        HeaderConnection connection = new HeaderConnection();

        authorization.apply(connection);

        assertEquals("Bearer legacy-key", connection.getRequestProperty("Authorization"));
        assertEquals("legacy-key", connection.getRequestProperty("x-ops-glasses-key"));
    }

    @Test(expected = IOException.class)
    public void missingSessionAuthorizationFailsExplicitly() throws Exception {
        BackendAuthorization.session(null).bearerToken();
    }

    private static final class HeaderConnection extends HttpURLConnection {
        HeaderConnection() throws Exception {
            super(new URL("https://ops.example.com"));
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
