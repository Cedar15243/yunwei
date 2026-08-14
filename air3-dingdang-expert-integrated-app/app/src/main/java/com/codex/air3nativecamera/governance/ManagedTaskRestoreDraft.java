package com.codex.air3nativecamera.governance;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Immutable, user-reviewed request to resume one exact server-backed local task. */
public final class ManagedTaskRestoreDraft {
    private final String localProjectId;
    private final String localTaskId;
    private final String projectTitle;
    private final String projectSummary;
    private final String taskTitle;
    private final String activeSkillVersionId;
    private final String localProgress;
    private final String risks;

    private ManagedTaskRestoreDraft(
            String localProjectId,
            String localTaskId,
            String projectTitle,
            String projectSummary,
            String taskTitle,
            String activeSkillVersionId,
            String localProgress,
            String risks
    ) {
        this.localProjectId = localProjectId;
        this.localTaskId = localTaskId;
        this.projectTitle = projectTitle;
        this.projectSummary = projectSummary;
        this.taskTitle = taskTitle;
        this.activeSkillVersionId = activeSkillVersionId;
        this.localProgress = localProgress;
        this.risks = risks;
    }

    public static ManagedTaskRestoreDraft create(
            TaskSessionManager manager,
            JSONObject projectPayload,
            String requestedProjectId,
            String requestedTaskId
    ) {
        String projectId = identifier(requestedProjectId, "project_id_invalid");
        String taskId = identifier(requestedTaskId, "task_id_invalid");
        if (manager == null || projectPayload == null) {
            throw new IllegalArgumentException("task_not_found");
        }
        if (!projectId.equals(identifier(
                projectPayload.optString("localProjectId", ""), "project_id_invalid"))) {
            throw new IllegalArgumentException("project_mismatch");
        }
        if (!"active".equalsIgnoreCase(text(projectPayload.optString("status", "")))) {
            throw new IllegalArgumentException("project_not_active");
        }

        TaskSession localTask = manager.find(taskId);
        if (localTask == null || !projectId.equals(localTask.projectId())) {
            throw new IllegalArgumentException("task_not_found");
        }
        if (localTask.status() == TaskSession.Status.COMPLETED
                || localTask.maintenanceTask().phase() == MaintenanceTask.Phase.COMPLETED) {
            throw new IllegalArgumentException("task_not_recoverable");
        }

        JSONObject serverTask = findTask(projectPayload.optJSONArray("tasks"), taskId);
        if (serverTask == null) throw new IllegalArgumentException("task_not_found");
        String serverStatus = text(serverTask.optString("status", ""));
        if ("completed".equalsIgnoreCase(serverStatus)
                || "closed".equalsIgnoreCase(serverStatus)) {
            throw new IllegalArgumentException("task_not_recoverable");
        }
        if (!"active".equalsIgnoreCase(serverStatus)) {
            throw new IllegalArgumentException("task_state_conflict");
        }

        JSONObject memory = projectPayload.optJSONObject("projectMemory");
        String diagnosis = text(localTask.maintenanceTask().diagnosisTitle());
        String progress = diagnosis.isEmpty()
                ? "等待继续采集现场信息"
                : diagnosis;
        return new ManagedTaskRestoreDraft(
                projectId,
                taskId,
                text(projectPayload.optString("title", projectId)),
                memory == null ? "" : text(memory.optString("summary", "")),
                text(serverTask.optString("title", taskId)),
                text(serverTask.optString("activeSkillVersionId", "")),
                progress,
                memory == null ? "" : join(memory.optJSONArray("risks")));
    }

    public boolean matchesFreshState(TaskSessionManager manager, JSONObject freshProjectPayload) {
        try {
            ManagedTaskRestoreDraft fresh = create(
                    manager, freshProjectPayload, localProjectId, localTaskId);
            return localProjectId.equals(fresh.localProjectId)
                    && localTaskId.equals(fresh.localTaskId);
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    public OperationDetail confirmationDetail() {
        List<String> items = new ArrayList<>();
        items.add("项目：" + fallback(projectTitle, localProjectId));
        items.add("任务：" + fallback(taskTitle, localTaskId) + "\n服务端状态：进行中");
        items.add("项目摘要：" + fallback(projectSummary, "尚未形成项目摘要"));
        items.add("本机进度：" + localProgress);
        if (!activeSkillVersionId.isEmpty()) items.add("当前 Skill：" + activeSkillVersionId);
        if (!risks.isEmpty()) items.add("风险：" + risks);
        return new OperationDetail(
                "二次确认",
                "确认恢复这个任务？",
                "确认时会重新核对服务端任务状态；核对通过后才恢复本机维修步骤和对话。",
                items,
                "managed_task_restore_confirm",
                "确认恢复",
                "managed_task_restore_cancel",
                "取消");
    }

    public String localProjectId() { return localProjectId; }
    public String localTaskId() { return localTaskId; }

    private static JSONObject findTask(JSONArray tasks, String taskId) {
        for (int index = 0; tasks != null && index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task != null && taskId.equals(text(task.optString("localTaskId", "")))) {
                return task;
            }
        }
        return null;
    }

    private static String join(JSONArray values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; values != null && index < values.length(); index++) {
            String value = text(values.optString(index, ""));
            if (value.isEmpty()) continue;
            if (result.length() > 0) result.append("；");
            result.append(value);
        }
        return result.toString();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String identifier(String value, String error) {
        String clean = text(value);
        if (!clean.matches("^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,199}$")) {
            throw new IllegalArgumentException(error);
        }
        return clean;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
