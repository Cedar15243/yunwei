package com.codex.air3nativecamera.governance;

import com.codex.air3nativecamera.task.MaintenanceTask;

import java.util.Map;

/** Deterministic, local-only classification for high-risk project governance requests. */
public final class ProjectGovernancePolicy {
    public enum Intent {
        NONE,
        END_TASK_COMPLETED,
        END_TASK_CLOSED,
        PROJECT_INSTRUCTION,
        CONFIRM_PENDING,
        CANCEL_PENDING,
        CANCEL_PENDING_HOME
    }

    public static final class Decision {
        private final Intent intent;
        private final String instruction;

        private Decision(Intent intent, String instruction) {
            this.intent = intent;
            this.instruction = instruction;
        }

        public Intent intent() {
            return intent;
        }

        public String instruction() {
            return instruction;
        }
    }

    private ProjectGovernancePolicy() { }

    public static Decision classify(String text, boolean confirmationPending) {
        String display = display(text);
        String normalized = normalize(display);
        if (confirmationPending) {
            if (equalsAny(normalized, "返回首页", "回首页", "回到首页", "返回主页")) {
                return decision(Intent.CANCEL_PENDING_HOME, "");
            }
            if (equalsAny(normalized, "确认", "确认执行", "确定", "确定执行")) {
                return decision(Intent.CONFIRM_PENDING, "");
            }
            if (equalsAny(normalized, "取消", "取消操作", "不确认", "返回")) {
                return decision(Intent.CANCEL_PENDING, "");
            }
            return decision(Intent.NONE, "");
        }
        if (equalsAny(normalized, "关闭当前任务并返回首页", "关闭任务并返回首页")) {
            return decision(Intent.END_TASK_CLOSED, "");
        }
        if (equalsAny(normalized, "结束当前任务", "完成当前任务", "结束任务", "完成任务")) {
            return decision(Intent.END_TASK_COMPLETED, "");
        }
        if (isProjectInstruction(display, normalized)) {
            return decision(Intent.PROJECT_INSTRUCTION, display);
        }
        return decision(Intent.NONE, "");
    }

    public static String taskEndSummary(MaintenanceTask task) {
        if (task == null) return "人工确认结束当前现场任务。";
        StringBuilder summary = new StringBuilder();
        append(summary, "现场问题", task.initialProblem());
        append(summary, "当前判断", task.diagnosisTitle());
        if (task.repairStepCount() > 0) {
            append(summary, "维修进度", task.currentRepairStepNumber() + "/"
                    + task.repairStepCount() + " · " + task.currentRepairStep());
        }
        if (!task.evidenceLabels().isEmpty()) {
            append(summary, "现场证据", join(task.evidenceLabels()));
        }
        for (Map.Entry<String, String> fact : task.facts().entrySet()) {
            if ("当前判断".equals(fact.getKey())) continue;
            append(summary, fact.getKey(), fact.getValue());
        }
        String value = summary.length() == 0 ? "人工确认结束当前现场任务。" : summary.toString();
        return value.length() <= 8000 ? value : value.substring(0, 8000);
    }

    private static boolean isProjectInstruction(String display, String normalized) {
        if (display.isEmpty() || display.length() > 4000 || normalized.length() < 10) return false;
        if (display.endsWith("？") || display.endsWith("?") || display.endsWith("吗")
                || display.endsWith("呢")) return false;
        if (normalized.contains("为什么") || normalized.contains("是什么")
                || normalized.contains("会怎么") || normalized.contains("该怎么")
                || normalized.contains("如何")) return false;
        boolean projectScoped = normalized.startsWith("以后在这个项目")
                || normalized.startsWith("以后本项目")
                || normalized.startsWith("以后这个项目遇到")
                || normalized.startsWith("下次在这个项目")
                || normalized.startsWith("以后遇到")
                || normalized.startsWith("以后你要先")
                || normalized.startsWith("以后先");
        if (!projectScoped) return false;
        return normalized.contains("先") || normalized.contains("必须")
                || normalized.contains("不要") || normalized.contains("需要")
                || normalized.contains("应当") || normalized.contains("记得")
                || normalized.contains("优先") || normalized.contains("按照");
    }

    private static Decision decision(Intent intent, String instruction) {
        return new Decision(intent, instruction == null ? "" : instruction);
    }

    private static void append(StringBuilder value, String label, String text) {
        String clean = display(text);
        if (clean.isEmpty()) return;
        if (value.length() > 0) value.append('\n');
        value.append(label).append("：").append(clean);
    }

    private static String join(Iterable<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            String clean = display(value);
            if (clean.isEmpty()) continue;
            if (result.length() > 0) result.append("、");
            result.append(clean);
        }
        return result.toString();
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }

    private static String display(String value) {
        String clean = value == null ? "" : value.trim();
        StringBuilder result = new StringBuilder(clean.length());
        for (int index = 0; index < clean.length(); index++) {
            char character = clean.charAt(index);
            if (character >= 32 || character == '\n') result.append(character);
        }
        return result.toString();
    }

    private static String normalize(String value) {
        return display(value)
                .replace("小叮当", "")
                .replace("小叮", "")
                .replace("小丁", "")
                .replaceAll("[\\s，。！？,.!?]", "");
    }
}
