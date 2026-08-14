package com.codex.air3nativecamera.governance;

/** Deterministic routing rules for governed project-instruction lifecycle actions. */
public final class ProjectInstructionLifecyclePolicy {
    public enum EditIntent {
        REVISE,
        CANCEL,
        CANCEL_HOME,
        INVALID
    }

    public static final class EditDecision {
        private final EditIntent intent;
        private final String instruction;

        private EditDecision(EditIntent intent, String instruction) {
            this.intent = intent;
            this.instruction = instruction;
        }

        public EditIntent intent() { return intent; }
        public String instruction() { return instruction; }
    }

    public static final class Identity {
        private final String projectId;
        private final String instructionId;

        private Identity(String projectId, String instructionId) {
            this.projectId = projectId;
            this.instructionId = instructionId;
        }

        public String projectId() { return projectId; }
        public String instructionId() { return instructionId; }
    }

    private ProjectInstructionLifecyclePolicy() { }

    public static EditDecision classifyEditVoice(String text) {
        String normalized = normalize(text);
        if (equalsAny(normalized, "返回首页", "回首页", "回到首页", "返回主页")) {
            return new EditDecision(EditIntent.CANCEL_HOME, "");
        }
        if (equalsAny(normalized, "取消", "取消修改", "取消操作", "返回")) {
            return new EditDecision(EditIntent.CANCEL, "");
        }
        ProjectGovernancePolicy.Decision decision =
                ProjectGovernancePolicy.classify(text, false);
        if (decision.intent() == ProjectGovernancePolicy.Intent.PROJECT_INSTRUCTION) {
            return new EditDecision(EditIntent.REVISE, decision.instruction());
        }
        return new EditDecision(EditIntent.INVALID, "");
    }

    public static Identity parseIdentity(String value) {
        String clean = text(value);
        int separator = clean.indexOf(':');
        if (separator < 1 || separator >= clean.length() - 1) {
            throw new IllegalArgumentException("project instruction identity is invalid");
        }
        String projectId = identifier(clean.substring(0, separator));
        String instructionId = identifier(clean.substring(separator + 1));
        return new Identity(projectId, instructionId);
    }

    public static boolean isVersionConflict(String errorCode) {
        return "project_instruction_version_conflict".equals(text(errorCode));
    }

    private static String identifier(String value) {
        String clean = text(value);
        if (!clean.matches("^[A-Za-z0-9][A-Za-z0-9_.@-]{0,199}$")) {
            throw new IllegalArgumentException("project instruction identity is invalid");
        }
        return clean;
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return text(value)
                .replace("小叮当", "")
                .replace("小叮", "")
                .replace("小丁", "")
                .replaceAll("[\\s，。！？,.!?]", "");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
