package com.codex.expertcollab;

/** Maps the collaboration health endpoint to the user-visible availability state. */
public final class CollabServiceHealth {
    public enum State {
        CHECKING("正在确认"),
        AVAILABLE("在线"),
        UNAVAILABLE("暂不可用 · 正在恢复连接");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private CollabServiceHealth() {
    }

    public static State fromHttpStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300 ? State.AVAILABLE : State.UNAVAILABLE;
    }

    public static String healthUrl(String serverUrl) {
        String normalized = serverUrl == null ? "" : serverUrl.trim().replaceAll("/+$", "");
        if (normalized.length() == 0) {
            throw new IllegalArgumentException("serverUrl is required");
        }
        return normalized + "/health";
    }
}
