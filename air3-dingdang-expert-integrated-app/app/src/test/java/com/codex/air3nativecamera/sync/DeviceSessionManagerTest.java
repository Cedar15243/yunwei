package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

public final class DeviceSessionManagerTest {
    @Test
    public void prewarmIsNonBlockingAndConcurrentCallsShareOneRefresh() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        CountingIssuer issuer = new CountingIssuer(new DeviceAccessSession("access-a", 901_000L));
        DeviceSessionManager manager = new DeviceSessionManager(
                "bootstrap-a", issuer, clock, executor, 120_000L);

        manager.prewarm();
        manager.prewarm();

        assertEquals(0, issuer.calls);
        assertEquals(1, executor.pendingCount());
        executor.runNext();
        assertEquals(1, issuer.calls);
        assertEquals("access-a", manager.accessToken());
    }

    @Test
    public void returnsValidTokenImmediatelyWhileRefreshingBeforeExpiry() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        CountingIssuer issuer = new CountingIssuer(
                new DeviceAccessSession("access-a", 901_000L),
                new DeviceAccessSession("access-b", 1_801_000L));
        DeviceSessionManager manager = new DeviceSessionManager(
                "bootstrap-a", issuer, clock, executor, 120_000L);

        assertEquals("access-a", manager.accessToken());
        clock.now = 800_000L;

        assertEquals("access-a", manager.accessToken());
        assertEquals("access-a", manager.accessToken());
        assertEquals(1, executor.pendingCount());
        executor.runNext();
        assertEquals("access-b", manager.accessToken());
        assertEquals(2, issuer.calls);
    }

    @Test
    public void keepsAnUnexpiredTokenWhenBackgroundRefreshFails() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        CountingIssuer issuer = new CountingIssuer(new DeviceAccessSession("access-a", 901_000L));
        DeviceSessionManager manager = new DeviceSessionManager(
                "bootstrap-a", issuer, clock, executor, 120_000L);
        assertEquals("access-a", manager.accessToken());
        issuer.failure = new IOException("offline");
        clock.now = 800_000L;

        assertEquals("access-a", manager.accessToken());
        executor.runNext();

        assertEquals("access-a", manager.accessToken());
    }

    @Test
    public void rejectsExpiredSessionsWhenRefreshFails() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        CountingIssuer issuer = new CountingIssuer(new DeviceAccessSession("access-a", 2_000L));
        DeviceSessionManager manager = new DeviceSessionManager(
                "bootstrap-a", issuer, clock, executor, 120_000L);
        assertEquals("access-a", manager.accessToken());
        issuer.failure = new IOException("offline");
        clock.now = 2_001L;

        try {
            manager.accessToken();
            fail("expected expired access to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("offline"));
        }
    }

    @Test
    public void authoritativeRevocationClearsTheCurrentCredentialAndAccessToken()
            throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        MutableCredentialProvider credentials = new MutableCredentialProvider("bootstrap-a");
        CountingIssuer issuer = new CountingIssuer(new DeviceAccessSession("access-a", 901_000L));
        DeviceSessionManager manager = new DeviceSessionManager(
                credentials, issuer, clock, executor, 120_000L);

        assertEquals("access-a", manager.accessToken());
        issuer.failure = new DeviceSessionException("device_revoked", 403, true);
        clock.now = 800_000L;

        assertEquals("access-a", manager.accessToken());
        executor.runNext();

        assertEquals(1, credentials.clearCalls);
        assertEquals("", credentials.value);
        try {
            manager.accessToken();
            fail("revoked access token must not remain available");
        } catch (IOException expected) {
            assertEquals("device_bootstrap_credential_missing", expected.getMessage());
        }
    }

    @Test
    public void timeoutAndServerFailuresKeepTheCredentialAndUnexpiredToken()
            throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        MutableCredentialProvider credentials = new MutableCredentialProvider("bootstrap-a");
        CountingIssuer issuer = new CountingIssuer(new DeviceAccessSession("access-a", 901_000L));
        DeviceSessionManager manager = new DeviceSessionManager(
                credentials, issuer, clock, executor, 120_000L);
        assertEquals("access-a", manager.accessToken());
        issuer.failure = new DeviceSessionException("device_session_http_503", 503, false);
        clock.now = 800_000L;

        assertEquals("access-a", manager.accessToken());
        executor.runNext();

        assertEquals(0, credentials.clearCalls);
        assertEquals("bootstrap-a", credentials.value);
        assertEquals("access-a", manager.accessToken());
    }

    @Test
    public void staleRevocationCannotDeleteAReplacementCredential() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        MutableCredentialProvider credentials = new MutableCredentialProvider("bootstrap-a");
        DeviceSessionManager.SessionIssuer issuer = new DeviceSessionManager.SessionIssuer() {
            int calls;

            @Override
            public DeviceAccessSession exchange(String bootstrapCredential) throws IOException {
                calls += 1;
                if (calls == 1) return new DeviceAccessSession("access-a", 901_000L);
                credentials.value = "bootstrap-b";
                throw new DeviceSessionException("bootstrap_revoked", 403, true);
            }
        };
        DeviceSessionManager manager = new DeviceSessionManager(
                credentials, issuer, clock, executor, 120_000L);
        assertEquals("access-a", manager.accessToken());
        clock.now = 800_000L;

        assertEquals("access-a", manager.accessToken());
        executor.runNext();

        assertEquals(0, credentials.clearCalls);
        assertEquals("bootstrap-b", credentials.value);
    }

    @Test
    public void newActivationCanIssueANewSessionAfterRevocation() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        MutableCredentialProvider credentials = new MutableCredentialProvider("bootstrap-a");
        CountingIssuer issuer = new CountingIssuer(new DeviceAccessSession("access-a", 901_000L));
        DeviceSessionManager manager = new DeviceSessionManager(
                credentials, issuer, clock, executor, 120_000L);
        assertEquals("access-a", manager.accessToken());
        issuer.failure = new DeviceSessionException("device_binding_revoked", 403, true);
        clock.now = 800_000L;
        assertEquals("access-a", manager.accessToken());
        executor.runNext();

        credentials.value = "bootstrap-b";
        issuer.failure = null;
        issuer.sessions.add(new DeviceAccessSession("access-b", 1_801_000L));

        assertEquals("access-b", manager.accessToken());
        assertEquals(3, issuer.calls);
    }

    @Test
    public void staleSuccessfulRefreshCannotInstallATokenForAReplacedCredential()
            throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        ManualExecutor executor = new ManualExecutor();
        MutableCredentialProvider credentials = new MutableCredentialProvider("bootstrap-a");
        DeviceSessionManager.SessionIssuer issuer = new DeviceSessionManager.SessionIssuer() {
            int calls;

            @Override
            public DeviceAccessSession exchange(String bootstrapCredential) {
                calls += 1;
                if (calls == 1) return new DeviceAccessSession("access-a", 901_000L);
                if (calls == 2) {
                    credentials.value = "bootstrap-b";
                    return new DeviceAccessSession("access-stale", 1_801_000L);
                }
                assertEquals("bootstrap-b", bootstrapCredential);
                return new DeviceAccessSession("access-b", 1_801_000L);
            }
        };
        DeviceSessionManager manager = new DeviceSessionManager(
                credentials, issuer, clock, executor, 120_000L);
        assertEquals("access-a", manager.accessToken());
        clock.now = 800_000L;

        assertEquals("access-a", manager.accessToken());
        executor.runNext();

        assertEquals("access-b", manager.accessToken());
    }

    private static final class MutableClock implements DeviceSessionManager.Clock {
        long now;

        MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long now() {
            return now;
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> pending = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        int pendingCount() {
            return pending.size();
        }

        void runNext() {
            pending.remove().run();
        }
    }

    private static final class CountingIssuer implements DeviceSessionManager.SessionIssuer {
        final Queue<DeviceAccessSession> sessions = new ArrayDeque<>();
        int calls;
        IOException failure;

        CountingIssuer(DeviceAccessSession... sessions) {
            for (DeviceAccessSession session : sessions) this.sessions.add(session);
        }

        @Override
        public DeviceAccessSession exchange(String bootstrapCredential) throws IOException {
            calls++;
            if (failure != null) throw failure;
            return sessions.remove();
        }
    }

    private static final class MutableCredentialProvider
            implements DeviceSessionManager.BootstrapCredentialProvider {
        String value;
        int clearCalls;

        MutableCredentialProvider(String value) {
            this.value = value;
        }

        @Override
        public DeviceSessionManager.CredentialSnapshot current() {
            return DeviceSessionManager.CredentialSnapshot.create(value);
        }

        @Override
        public void clearIfCurrent(
                DeviceSessionManager.CredentialSnapshot snapshot,
                String errorCode) {
            if (!snapshot.matches(value)) return;
            clearCalls += 1;
            value = "";
        }
    }
}
