package com.codex.air3nativecamera.sync;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/** Keeps short-lived access tokens in memory and refreshes them away from UI work. */
public final class DeviceSessionManager implements DeviceAccessTokenProvider, AutoCloseable {
    public interface SessionIssuer {
        DeviceAccessSession exchange(String bootstrapCredential) throws IOException;
    }

    public interface BootstrapCredentialProvider {
        CredentialSnapshot current() throws IOException;

        void clearIfCurrent(CredentialSnapshot snapshot, String errorCode) throws IOException;
    }

    public static final class CredentialSnapshot {
        private final String credential;

        private CredentialSnapshot(String credential) {
            this.credential = clean(credential);
        }

        public static CredentialSnapshot create(String credential) {
            return new CredentialSnapshot(credential);
        }

        public boolean matches(String candidate) {
            return credential.equals(clean(candidate));
        }

        private boolean isEmpty() {
            return credential.length() == 0;
        }
    }

    interface Clock {
        long now();
    }

    private static final long DEFAULT_REFRESH_SKEW_MILLIS = 120_000L;
    private final BootstrapCredentialProvider credentialProvider;
    private final SessionIssuer issuer;
    private final Clock clock;
    private final Executor executor;
    private final java.util.concurrent.ExecutorService ownedExecutor;
    private final long refreshSkewMillis;
    private DeviceAccessSession session;
    private boolean refreshing;
    private IOException lastFailure;

    public DeviceSessionManager(String bootstrapCredential, SessionIssuer issuer) {
        this(fixedCredentialProvider(bootstrapCredential), issuer);
    }

    public DeviceSessionManager(
            BootstrapCredentialProvider credentialProvider,
            SessionIssuer issuer) {
        this.credentialProvider = credentialProvider;
        this.issuer = issuer;
        this.clock = new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        };
        this.ownedExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "device-session-refresh");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.executor = ownedExecutor;
        this.refreshSkewMillis = DEFAULT_REFRESH_SKEW_MILLIS;
    }

    DeviceSessionManager(
            String bootstrapCredential,
            SessionIssuer issuer,
            Clock clock,
            Executor executor,
            long refreshSkewMillis) {
        this(fixedCredentialProvider(bootstrapCredential), issuer, clock, executor,
                refreshSkewMillis);
    }

    DeviceSessionManager(
            BootstrapCredentialProvider credentialProvider,
            SessionIssuer issuer,
            Clock clock,
            Executor executor,
            long refreshSkewMillis) {
        this.credentialProvider = credentialProvider;
        this.issuer = issuer;
        this.clock = clock;
        this.executor = executor;
        this.ownedExecutor = null;
        this.refreshSkewMillis = Math.max(0L, refreshSkewMillis);
    }

    public void prewarm() {
        synchronized (this) {
            if (refreshing || isFreshBeyondSkew(session, clock.now())) return;
            startBackgroundRefreshLocked();
        }
    }

    @Override
    public String accessToken() throws IOException {
        boolean refreshHere = false;
        synchronized (this) {
            long now = clock.now();
            if (isValid(session, now)) {
                if (!isFreshBeyondSkew(session, now) && !refreshing) {
                    startBackgroundRefreshLocked();
                }
                return session.accessToken();
            }
            while (refreshing) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("device_session_refresh_interrupted", interrupted);
                }
                now = clock.now();
                if (isValid(session, now)) return session.accessToken();
            }
            refreshing = true;
            refreshHere = true;
        }
        if (refreshHere) refreshNow();
        synchronized (this) {
            if (isValid(session, clock.now())) return session.accessToken();
            if (lastFailure != null) throw lastFailure;
            throw new IOException("device_session_unavailable");
        }
    }

    @Override
    public void close() {
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }

    private void startBackgroundRefreshLocked() {
        refreshing = true;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    refreshNow();
                }
            });
        } catch (RejectedExecutionException rejected) {
            refreshing = false;
            lastFailure = new IOException("device_session_refresh_rejected", rejected);
            notifyAll();
        }
    }

    private void refreshNow() {
        DeviceAccessSession issued = null;
        IOException failure = null;
        CredentialSnapshot credential = null;
        boolean credentialChanged = false;
        try {
            if (credentialProvider == null || issuer == null) {
                throw new IOException("device_bootstrap_credential_missing");
            }
            credential = credentialProvider.current();
            if (credential == null || credential.isEmpty()) {
                throw new IOException("device_bootstrap_credential_missing");
            }
            issued = issuer.exchange(credential.credential);
            if (!isValid(issued, clock.now())) {
                throw new IOException("device_session_response_invalid");
            }
            CredentialSnapshot currentCredential = credentialProvider.current();
            if (currentCredential == null || currentCredential.isEmpty()
                    || !credential.matches(currentCredential.credential)) {
                issued = null;
                credentialChanged = true;
                throw new IOException("device_session_credential_changed");
            }
        } catch (IOException error) {
            failure = error;
        } catch (RuntimeException error) {
            failure = new IOException("device_session_refresh_failed", error);
        }
        boolean authoritativeRevocation = failure instanceof DeviceSessionException
                && ((DeviceSessionException) failure).isAuthoritativeRevocation();
        if (authoritativeRevocation && credential != null && credentialProvider != null) {
            try {
                credentialProvider.clearIfCurrent(
                        credential, ((DeviceSessionException) failure).errorCode());
            } catch (IOException clearFailure) {
                failure.addSuppressed(clearFailure);
            }
        }
        synchronized (this) {
            if (authoritativeRevocation || credentialChanged) {
                session = null;
            } else if (issued != null) {
                session = issued;
            }
            lastFailure = failure;
            refreshing = false;
            notifyAll();
        }
    }

    private boolean isFreshBeyondSkew(DeviceAccessSession value, long now) {
        return isValid(value, now) && value.expiresAtMillis() - now > refreshSkewMillis;
    }

    private static boolean isValid(DeviceAccessSession value, long now) {
        return value != null && value.accessToken().length() > 0 && value.expiresAtMillis() > now;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static BootstrapCredentialProvider fixedCredentialProvider(String credential) {
        final CredentialSnapshot snapshot = CredentialSnapshot.create(credential);
        return new BootstrapCredentialProvider() {
            @Override
            public CredentialSnapshot current() {
                return snapshot;
            }

            @Override
            public void clearIfCurrent(CredentialSnapshot ignored, String errorCode) {
                // MDM or legacy credentials remain controlled by their external authority.
            }
        };
    }
}
