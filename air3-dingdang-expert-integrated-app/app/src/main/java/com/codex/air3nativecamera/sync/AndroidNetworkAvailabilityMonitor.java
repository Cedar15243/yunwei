package com.codex.air3nativecamera.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Emits only transitions to a validated default network. */
public final class AndroidNetworkAvailabilityMonitor
        implements WorkflowDeliveryController.NetworkMonitor {
    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback callback;
    private Runnable listener;
    private boolean started;
    private boolean closed;
    private boolean validated;

    public AndroidNetworkAvailabilityMonitor(Context context) {
        if (context == null) throw new IllegalArgumentException("network monitor context is required");
        connectivityManager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            throw new IllegalStateException("connectivity service is unavailable");
        }
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                boolean usable = capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                updateAvailability(usable);
            }

            @Override
            public void onLost(Network network) {
                updateAvailability(false);
            }

            @Override
            public void onUnavailable() {
                updateAvailability(false);
            }
        };
    }

    @Override
    public void start(Runnable onNetworkAvailable) {
        if (onNetworkAvailable == null) {
            throw new IllegalArgumentException("network availability listener is required");
        }
        synchronized (this) {
            if (started || closed) return;
            listener = onNetworkAvailable;
            started = true;
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException exception) {
            synchronized (this) {
                listener = null;
                started = false;
            }
            throw exception;
        }
    }

    private void updateAvailability(boolean nextValidated) {
        Runnable notify = null;
        synchronized (this) {
            if (!started || closed || validated == nextValidated) return;
            validated = nextValidated;
            if (validated) notify = listener;
        }
        if (notify != null) notify.run();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            listener = null;
            validated = false;
            if (!started) return;
            started = false;
        }
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
