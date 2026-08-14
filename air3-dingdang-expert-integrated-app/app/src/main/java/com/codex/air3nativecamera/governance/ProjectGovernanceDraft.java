package com.codex.air3nativecamera.governance;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable user-reviewed draft for one governed server write. */
public final class ProjectGovernanceDraft {
    public enum Kind {
        TASK_END,
        PROJECT_INSTRUCTION
    }

    private enum InstructionOperation {
        CREATE,
        REVISE,
        DISABLE,
        ENABLE,
        DELETE
    }

    private final Kind kind;
    private final String localProjectId;
    private final String localTaskId;
    private final String taskStatus;
    private final String summary;
    private final int expectedMemoryRevision;
    private final List<String> confirmedFacts;
    private final List<String> excludedFacts;
    private final List<String> risks;
    private final int completedRepairSteps;
    private final int totalRepairSteps;
    private final List<String> evidenceLabels;
    private final String skillVersionId;
    private final List<String> knowledgeVersionIds;
    private final int contentManifestVersion;
    private final List<String> projectInstructionVersions;
    private final String instructionId;
    private final int expectedInstructionVersion;
    private final String instructionStatus;
    private final String instruction;
    private final JSONObject condition;
    private final List<String> instructionExceptions;
    private final InstructionOperation instructionOperation;
    private final String sourceTraceId;
    private final String idempotencyKey;

    private ProjectGovernanceDraft(
            Kind kind,
            String localProjectId,
            String localTaskId,
            String taskStatus,
            String summary,
            int expectedMemoryRevision,
            List<String> confirmedFacts,
            List<String> excludedFacts,
            List<String> risks,
            int completedRepairSteps,
            int totalRepairSteps,
            List<String> evidenceLabels,
            String skillVersionId,
            List<String> knowledgeVersionIds,
            int contentManifestVersion,
            List<String> projectInstructionVersions,
            String instructionId,
            int expectedInstructionVersion,
            String instructionStatus,
            String instruction,
            JSONObject condition,
            List<String> instructionExceptions,
            InstructionOperation instructionOperation,
            String sourceTraceId,
            String idempotencyKey
    ) {
        this.kind = kind;
        this.localProjectId = localProjectId;
        this.localTaskId = localTaskId;
        this.taskStatus = taskStatus;
        this.summary = summary;
        this.expectedMemoryRevision = expectedMemoryRevision;
        this.confirmedFacts = immutable(confirmedFacts);
        this.excludedFacts = immutable(excludedFacts);
        this.risks = immutable(risks);
        this.completedRepairSteps = Math.max(0, completedRepairSteps);
        this.totalRepairSteps = Math.max(this.completedRepairSteps, totalRepairSteps);
        this.evidenceLabels = immutable(evidenceLabels);
        this.skillVersionId = text(skillVersionId);
        this.knowledgeVersionIds = immutable(knowledgeVersionIds);
        this.contentManifestVersion = Math.max(0, contentManifestVersion);
        this.projectInstructionVersions = immutable(projectInstructionVersions);
        this.instructionId = instructionId;
        this.expectedInstructionVersion = expectedInstructionVersion;
        this.instructionStatus = instructionStatus;
        this.instruction = instruction;
        this.condition = copy(condition);
        this.instructionExceptions = immutable(instructionExceptions);
        this.instructionOperation = instructionOperation;
        this.sourceTraceId = sourceTraceId;
        this.idempotencyKey = idempotencyKey;
    }

    public static ProjectGovernanceDraft taskEnd(
            TaskSession session,
            JSONObject projectPayload,
            String taskStatus
    ) {
        requireActiveSession(session);
        if (!("completed".equals(taskStatus) || "closed".equals(taskStatus))) {
            throw new IllegalArgumentException("task status is invalid");
        }
        JSONObject memory = projectPayload == null
                ? null : projectPayload.optJSONObject("projectMemory");
        if (memory == null) throw new IllegalArgumentException("project memory is required");
        MaintenanceTask task = session.maintenanceTask();
        List<String> confirmedFacts = strings(memory.optJSONArray("confirmedFacts"));
        String closure = "任务已人工确认结束：" + task.initialProblem();
        if (!confirmedFacts.contains(closure)) confirmedFacts.add(closure);
        JSONObject taskPayload = taskPayload(projectPayload, session.id());
        int totalRepairSteps = task.repairStepCount();
        int completedRepairSteps = task.phase() == MaintenanceTask.Phase.COMPLETED
                ? totalRepairSteps
                : Math.max(0, task.currentRepairStepNumber() - 1);
        return new ProjectGovernanceDraft(
                Kind.TASK_END,
                session.projectId(),
                session.id(),
                taskStatus,
                ProjectGovernancePolicy.taskEndSummary(task),
                Math.max(0, memory.optInt("revision", 0)),
                confirmedFacts,
                strings(memory.optJSONArray("excludedFacts")),
                strings(memory.optJSONArray("risks")),
                completedRepairSteps,
                totalRepairSteps,
                task.evidenceLabels(),
                text(taskPayload.optString("skillVersionId",
                        taskPayload.optString("activeSkillVersionId", ""))),
                strings(taskPayload.optJSONArray("knowledgeVersionIds")),
                Math.max(0, taskPayload.optInt("contentManifestVersion", 0)),
                projectInstructionVersions(projectPayload),
                "",
                0,
                "",
                "",
                new JSONObject(),
                Collections.<String>emptyList(),
                null,
                "",
                "end-task-" + UUID.randomUUID());
    }

    public static ProjectGovernanceDraft projectInstruction(
            TaskSession session,
            String instruction
    ) {
        requireActiveSession(session);
        String action = text(instruction);
        if (action.isEmpty() || action.length() > 4000) {
            throw new IllegalArgumentException("project instruction is invalid");
        }
        String identifier = UUID.randomUUID().toString();
        return new ProjectGovernanceDraft(
                Kind.PROJECT_INSTRUCTION,
                session.projectId(),
                session.id(),
                "",
                "",
                0,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                0,
                Collections.<String>emptyList(),
                "",
                Collections.<String>emptyList(),
                0,
                Collections.<String>emptyList(),
                "instruction-" + identifier,
                0,
                "active",
                action,
                projectInstructionCondition(session, action),
                Collections.<String>emptyList(),
                InstructionOperation.CREATE,
                "trace-" + identifier,
                "instruction-" + UUID.randomUUID());
    }

    public static ProjectGovernanceDraft projectInstructionRevision(
            String localProjectId,
            String instructionId,
            int expectedVersion,
            String currentStatus,
            JSONObject currentCondition,
            String currentInstruction,
            List<String> currentExceptions,
            String targetStatus,
            String replacementInstruction
    ) {
        String projectId = governedIdentifier(localProjectId, "project id");
        String identifier = governedIdentifier(instructionId, "instruction id");
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("instruction version is invalid");
        }
        String fromStatus = instructionLifecycleStatus(currentStatus, false);
        String toStatus = instructionLifecycleStatus(targetStatus, true);
        String existingAction = governedInstruction(currentInstruction);
        JSONObject existingCondition = governedCondition(currentCondition);
        List<String> exceptions = governedExceptions(currentExceptions);
        String replacement = text(replacementInstruction);
        String action = existingAction;
        JSONObject condition = existingCondition;
        if (!replacement.isEmpty()) {
            ProjectGovernancePolicy.Decision decision =
                    ProjectGovernancePolicy.classify(replacement, false);
            if (decision.intent() != ProjectGovernancePolicy.Intent.PROJECT_INSTRUCTION) {
                throw new IllegalArgumentException("project instruction revision is incomplete");
            }
            action = governedInstruction(decision.instruction());
            condition = projectInstructionCondition(null, action);
        }
        InstructionOperation operation = instructionOperation(
                fromStatus, toStatus, !replacement.isEmpty());
        if (fromStatus.equals(toStatus)
                && existingAction.equals(action)
                && conditionsEqual(existingCondition, condition)) {
            throw new IllegalArgumentException("project instruction has no change");
        }
        String traceIdentifier = UUID.randomUUID().toString();
        return new ProjectGovernanceDraft(
                Kind.PROJECT_INSTRUCTION,
                projectId,
                "",
                "",
                "",
                0,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                0,
                0,
                Collections.<String>emptyList(),
                "",
                Collections.<String>emptyList(),
                0,
                Collections.<String>emptyList(),
                identifier,
                expectedVersion,
                toStatus,
                action,
                condition,
                exceptions,
                operation,
                "trace-" + traceIdentifier,
                "instruction-" + UUID.randomUUID());
    }

    public OperationDetail confirmationDetail(String projectTitle) {
        String title = text(projectTitle);
        if (title.isEmpty()) title = localProjectId;
        if (kind == Kind.PROJECT_INSTRUCTION) {
            int targetVersion = expectedInstructionVersion + 1;
            String heading = instructionConfirmationHeading();
            String description = instructionConfirmationDescription();
            List<String> items = new ArrayList<>();
            items.add("项目：" + title);
            items.add("项目指令：" + instruction);
            if (expectedInstructionVersion > 0) {
                items.add("版本：v" + expectedInstructionVersion + " -> v" + targetVersion);
            }
            items.add("目标状态：" + instructionStatusLabel(instructionStatus));
            items.add(projectInstructionConditionLabel());
            items.add("例外规则：" + joined(instructionExceptions, "无"));
            return new OperationDetail(
                    "二次确认",
                    heading,
                    description,
                    items,
                    "managed_governance_confirm",
                    instructionConfirmationButtonLabel(),
                    "managed_governance_cancel",
                    "取消");
        }
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        addItem(items, actions, "项目：" + title, "");
        addItem(items, actions,
                "结束方式：" + taskStatusLabel(taskStatus)
                        + "\n点击切换为" + taskStatusLabel(oppositeTaskStatus(taskStatus)),
                "managed_governance_switch_end:" + oppositeTaskStatus(taskStatus));
        addItem(items, actions,
                "维修步骤：已完成步骤：" + completedRepairSteps + "/" + totalRepairSteps
                        + "\n未完成步骤：" + Math.max(0, totalRepairSteps - completedRepairSteps),
                "");
        addItem(items, actions,
                "现场证据：" + joined(evidenceLabels, "尚未添加")
                        + "\n缺失证据：当前任务未声明额外必传证据",
                "");
        addItem(items, actions, "确认事实：" + joined(confirmedFacts, "尚未确认"), "");
        addItem(items, actions, "排除事实：" + joined(excludedFacts, "尚未排除"), "");
        addItem(items, actions, "遗留风险：" + joined(risks, "暂无已记录风险"), "");
        addItem(items, actions,
                "执行版本：Skill " + fallback(skillVersionId, "未启用")
                        + "\n知识 " + joined(knowledgeVersionIds, "未关联")
                        + "\n内容清单 v" + contentManifestVersion,
                "");
        addItem(items, actions,
                "项目指令：" + joined(projectInstructionVersions, "暂无生效指令"), "");
        addItem(items, actions,
                "同步状态：本地结束草稿已生成\n云端尚未写入，确认后才提交", "");
        addItem(items, actions,
                "查看或补充项目记忆\n继续任务后可说“以后在这个项目遇到……”",
                "managed_governance_review_memory");
        addItem(items, actions,
                "呼叫专家复核\n退出后返回当前项目与步骤",
                "managed_governance_call_expert");
        return new OperationDetail(
                "二次确认",
                "任务结束摘要",
                "请核对步骤、事实、证据和版本。AI 只生成草稿，必须由人工确认后才写入服务端。",
                items,
                actions,
                "managed_governance_confirm",
                "closed".equals(taskStatus) ? "确认关闭" : "确认完成",
                "managed_governance_cancel",
                "继续当前任务");
    }

    public ProjectGovernanceDraft withTaskStatus(String status) {
        if (kind != Kind.TASK_END
                || !("completed".equals(status) || "closed".equals(status))) {
            throw new IllegalArgumentException("task status is invalid");
        }
        return new ProjectGovernanceDraft(
                kind, localProjectId, localTaskId, status, summary, expectedMemoryRevision,
                confirmedFacts, excludedFacts, risks, completedRepairSteps, totalRepairSteps,
                evidenceLabels, skillVersionId, knowledgeVersionIds, contentManifestVersion,
                projectInstructionVersions, instructionId, expectedInstructionVersion,
                instructionStatus, instruction, condition, instructionExceptions,
                instructionOperation, sourceTraceId, "end-task-" + UUID.randomUUID());
    }

    public OperationDetail memoryReviewDetail(String projectTitle) {
        if (kind != Kind.TASK_END) {
            throw new IllegalStateException("task end draft is required");
        }
        String title = fallback(text(projectTitle), localProjectId);
        return new OperationDetail(
                "项目记忆",
                "结束前核对项目记忆",
                "这里只显示本次结束草稿使用的服务端记录；继续当前任务后可用项目指令补充。",
                Arrays.asList(
                        "项目：" + title,
                        "确认事实：" + joined(confirmedFacts, "尚未确认"),
                        "排除事实：" + joined(excludedFacts, "尚未排除"),
                        "遗留风险：" + joined(risks, "暂无已记录风险"),
                        "项目指令：" + joined(projectInstructionVersions, "暂无生效指令")),
                "managed_governance_return_end",
                "返回结束摘要",
                "managed_governance_cancel",
                "继续当前任务");
    }

    public Kind kind() { return kind; }
    public String localProjectId() { return localProjectId; }
    public String localTaskId() { return localTaskId; }
    public String taskStatus() { return taskStatus; }
    public String summary() { return summary; }
    public int expectedMemoryRevision() { return expectedMemoryRevision; }
    public List<String> confirmedFacts() { return confirmedFacts; }
    public List<String> excludedFacts() { return excludedFacts; }
    public List<String> risks() { return risks; }
    public String instructionId() { return instructionId; }
    public int expectedInstructionVersion() { return expectedInstructionVersion; }
    public String instructionStatus() { return instructionStatus; }
    public String instruction() { return instruction; }
    public List<String> instructionExceptions() { return instructionExceptions; }
    public String sourceTraceId() { return sourceTraceId; }
    public String idempotencyKey() { return idempotencyKey; }

    public JSONObject condition() {
        return copy(condition);
    }

    private static JSONObject projectInstructionCondition(TaskSession session, String instruction) {
        String trigger = triggerText(instruction);
        if (isGenericTrigger(trigger)) {
            trigger = session == null || session.maintenanceTask() == null
                    ? "" : text(session.maintenanceTask().initialProblem());
        }
        trigger = boundedTrigger(trigger);
        if (trigger.isEmpty()) return new JSONObject();
        try {
            return new JSONObject().put("containsAny", new JSONArray().put(trigger));
        } catch (JSONException exception) {
            throw new IllegalStateException("project instruction condition is invalid", exception);
        }
    }

    private static String governedIdentifier(String value, String label) {
        String clean = text(value);
        if (clean.isEmpty() || clean.length() > 200 || containsControlCharacter(clean)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return clean;
    }

    private static String governedInstruction(String value) {
        String clean = text(value);
        if (clean.isEmpty() || clean.length() > 4000 || containsControlCharacter(clean)) {
            throw new IllegalArgumentException("project instruction is invalid");
        }
        return clean;
    }

    private static List<String> governedExceptions(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values == null ? Collections.<String>emptyList() : values) {
            String clean = text(value);
            if (clean.isEmpty() || clean.length() > 1000 || containsControlCharacter(clean)) {
                throw new IllegalArgumentException("project instruction exception is invalid");
            }
            if (!result.contains(clean)) result.add(clean);
        }
        return result;
    }

    private static JSONObject governedCondition(JSONObject value) {
        JSONObject condition = copy(value);
        for (java.util.Iterator<String> keys = condition.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!("containsAny".equals(key) || "systems".equals(key)
                    || "assetIds".equals(key))) {
                throw new IllegalArgumentException("project instruction condition is invalid");
            }
            JSONArray entries = condition.optJSONArray(key);
            if (entries == null) {
                throw new IllegalArgumentException("project instruction condition is invalid");
            }
            for (int index = 0; index < entries.length(); index++) {
                String entry = text(entries.optString(index, ""));
                if (entry.isEmpty() || entry.length() > 200 || containsControlCharacter(entry)) {
                    throw new IllegalArgumentException("project instruction condition is invalid");
                }
            }
        }
        return condition;
    }

    private static String instructionLifecycleStatus(String value, boolean target) {
        String status = text(value);
        if ("active".equals(status) || "disabled".equals(status)
                || (target && "deleted".equals(status))) {
            return status;
        }
        throw new IllegalArgumentException("project instruction status is invalid");
    }

    private static InstructionOperation instructionOperation(
            String currentStatus,
            String targetStatus,
            boolean hasReplacement
    ) {
        if ("deleted".equals(targetStatus)) return InstructionOperation.DELETE;
        if ("active".equals(currentStatus) && "disabled".equals(targetStatus)) {
            if (hasReplacement) {
                throw new IllegalArgumentException("disable cannot revise project instruction");
            }
            return InstructionOperation.DISABLE;
        }
        if ("disabled".equals(currentStatus) && "active".equals(targetStatus)) {
            if (hasReplacement) {
                throw new IllegalArgumentException("enable cannot revise project instruction");
            }
            return InstructionOperation.ENABLE;
        }
        if (currentStatus.equals(targetStatus) && hasReplacement) {
            return InstructionOperation.REVISE;
        }
        throw new IllegalArgumentException("project instruction transition is invalid");
    }

    private static boolean conditionsEqual(JSONObject left, JSONObject right) {
        return canonicalCondition(left).equals(canonicalCondition(right));
    }

    private static String canonicalCondition(JSONObject value) {
        List<String> parts = new ArrayList<>();
        for (String key : Arrays.asList("assetIds", "containsAny", "systems")) {
            List<String> entries = strings(value == null ? null : value.optJSONArray(key));
            Collections.sort(entries);
            parts.add(key + "=" + String.join("\u001f", entries));
        }
        return String.join("\u001e", parts);
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 && character != '\n') return true;
        }
        return false;
    }

    private static String triggerText(String instruction) {
        String value = text(instruction);
        int markerIndex = -1;
        int markerLength = 0;
        for (String marker : Arrays.asList("遇到", "碰到", "出现")) {
            int index = value.indexOf(marker);
            if (index >= 0 && (markerIndex < 0 || index < markerIndex)) {
                markerIndex = index;
                markerLength = marker.length();
            }
        }
        if (markerIndex < 0) return "";
        String candidate = value.substring(markerIndex + markerLength).trim();
        int actionIndex = candidate.length();
        for (String marker : Arrays.asList("先", "必须", "不要", "需要", "应当", "记得", "优先", "按照")) {
            int index = candidate.indexOf(marker);
            if (index >= 2 && index < actionIndex) actionIndex = index;
        }
        candidate = candidate.substring(0, actionIndex).trim();
        candidate = candidate.replaceAll("^[，。！？、,;；:\\s]+|[，。！？、,;；:\\s]+$", "");
        candidate = candidate.replaceFirst("(?:的时候|的情况下|情况下|之后|以后|时|后)$", "").trim();
        return candidate;
    }

    private static boolean isGenericTrigger(String trigger) {
        String value = text(trigger).replaceAll("[，。！？、,;；:\\s]", "");
        return "这个问题".equals(value) || "该问题".equals(value)
                || "上述问题".equals(value) || "这种情况".equals(value)
                || "类似问题".equals(value) || "同类问题".equals(value);
    }

    private static String boundedTrigger(String value) {
        String trigger = text(value);
        if (trigger.length() < 2) return "";
        return trigger.length() <= 200 ? trigger : trigger.substring(0, 200).trim();
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalStateException("project instruction condition is invalid", exception);
        }
    }

    private static void requireActiveSession(TaskSession session) {
        if (session == null || session.status() != TaskSession.Status.ACTIVE
                || session.projectId().isEmpty() || session.id().isEmpty()) {
            throw new IllegalArgumentException("active task is required");
        }
    }

    private static List<String> strings(JSONArray values) {
        List<String> result = new ArrayList<>();
        for (int index = 0; values != null && index < values.length(); index++) {
            String value = text(values.optString(index, ""));
            if (!value.isEmpty() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    private static JSONObject taskPayload(JSONObject projectPayload, String localTaskId) {
        JSONArray tasks = projectPayload == null ? null : projectPayload.optJSONArray("tasks");
        for (int index = 0; tasks != null && index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && localTaskId.equals(task.optString("localTaskId", ""))) {
                return task;
            }
        }
        return new JSONObject();
    }

    private static List<String> projectInstructionVersions(JSONObject projectPayload) {
        List<String> result = new ArrayList<>();
        JSONArray values = projectPayload == null
                ? null : projectPayload.optJSONArray("projectInstructions");
        for (int index = 0; values != null && index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            String id = text(value.optString("instructionId", ""));
            int version = Math.max(0, value.optInt("version", 0));
            String status = text(value.optString("status", ""));
            if (!id.isEmpty()) {
                result.add(id + "@v" + version + (status.isEmpty() ? "" : " · " + status));
            }
        }
        return result;
    }

    private static void addItem(List<String> items, List<String> actions,
            String item, String action) {
        items.add(item);
        actions.add(action);
    }

    private static String joined(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? fallback : String.join("、", values);
    }

    private String projectInstructionConditionLabel() {
        JSONArray containsAny = condition.optJSONArray("containsAny");
        List<String> triggers = strings(containsAny);
        if (triggers.isEmpty()) return "触发条件：本项目后续所有 AI 对话";
        if (triggers.size() == 1) {
            return "触发条件：后续问题包含“" + triggers.get(0) + "”";
        }
        return "触发条件：后续问题包含任一关键词：" + joined(triggers, "未设置");
    }

    private String instructionConfirmationHeading() {
        InstructionOperation operation = instructionOperation;
        if (operation == InstructionOperation.CREATE) return "确认新增项目指令？";
        if (operation == InstructionOperation.REVISE) return "确认发布项目指令新版本？";
        if (operation == InstructionOperation.DISABLE) return "确认停用项目指令？";
        if (operation == InstructionOperation.ENABLE) return "确认启用项目指令？";
        if (operation == InstructionOperation.DELETE) return "确认删除项目指令？";
        return "确认更新项目指令？";
    }

    private String instructionConfirmationDescription() {
        if (instructionOperation == InstructionOperation.DELETE) {
            return "确认后将写入 deleted 墓碑版本；历史版本和审计记录仍会保留，且不能重新启用。";
        }
        if (instructionOperation == InstructionOperation.DISABLE) {
            return "确认后该指令将停止进入后续 AI 执行上下文，历史版本仍会保留。";
        }
        if (instructionOperation == InstructionOperation.ENABLE) {
            return "确认后该指令将重新进入后续匹配的 AI 执行上下文。";
        }
        return "确认后该完整版本会保存到服务端项目记录，并从后续匹配的 AI 对话生效。";
    }

    private String instructionConfirmationButtonLabel() {
        if (instructionOperation == InstructionOperation.DELETE) return "确认删除";
        if (instructionOperation == InstructionOperation.DISABLE) return "确认停用";
        if (instructionOperation == InstructionOperation.ENABLE) return "确认启用";
        if (instructionOperation == InstructionOperation.REVISE) return "确认发布";
        return "确认保存";
    }

    private static String instructionStatusLabel(String status) {
        if ("active".equals(status)) return "生效";
        if ("disabled".equals(status)) return "停用";
        if ("deleted".equals(status)) return "已删除";
        return status;
    }

    private static String fallback(String value, String fallback) {
        String safe = text(value);
        return safe.isEmpty() ? fallback : safe;
    }

    private static String oppositeTaskStatus(String status) {
        return "closed".equals(status) ? "completed" : "closed";
    }

    private static String taskStatusLabel(String status) {
        return "closed".equals(status) ? "关闭" : "完成";
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
