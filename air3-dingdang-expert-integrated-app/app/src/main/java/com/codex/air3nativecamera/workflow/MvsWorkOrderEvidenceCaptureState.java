package com.codex.air3nativecamera.workflow;

/** Guards the one-shot camera handoff for an active MVS work order. */
public final class MvsWorkOrderEvidenceCaptureState {
    private String pendingOrderId = "";

    public synchronized boolean begin(String activeOrderId, String requestedOrderId) {
        String active = clean(activeOrderId);
        String requested = clean(requestedOrderId);
        if (active.isEmpty() || requested.isEmpty() || !active.equals(requested)
                || !requested.matches("^[1-9][0-9]{0,18}$") || !pendingOrderId.isEmpty()) {
            return false;
        }
        pendingOrderId = requested;
        return true;
    }

    public synchronized String consume(String orderId) {
        String requested = clean(orderId);
        if (pendingOrderId.isEmpty() || !pendingOrderId.equals(requested)) return "";
        String accepted = pendingOrderId;
        pendingOrderId = "";
        return accepted;
    }

    public synchronized String pendingOrderId() {
        return pendingOrderId;
    }

    public synchronized void cancel() {
        pendingOrderId = "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
