package com.codex.air3nativecamera.task;

import org.json.JSONObject;

public final class TaskSession {
    public enum Status {
        ACTIVE,
        PAUSED,
        COMPLETED
    }

    private final String id;
    private final String projectId;
    private final MaintenanceTask maintenanceTask;
    private Status status;
    private String sceneSkillId = "";
    private String sceneStepId = "";

    TaskSession(String id, String projectId, MaintenanceTask maintenanceTask, Status status) {
        this.id = id;
        this.projectId = projectId;
        this.maintenanceTask = maintenanceTask;
        this.status = status;
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public MaintenanceTask maintenanceTask() {
        return maintenanceTask;
    }

    public Status status() {
        return status;
    }

    void setStatus(Status status) {
        this.status = status;
    }

    public String sceneSkillId() {
        return sceneSkillId;
    }

    public String sceneStepId() {
        return sceneStepId;
    }

    public void bindSceneSkill(String skillId, String stepId) {
        sceneSkillId = clean(skillId);
        sceneStepId = clean(stepId);
    }

    public void clearSceneSkill() {
        sceneSkillId = "";
        sceneStepId = "";
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("project_id", projectId);
            json.put("status", status.name());
            json.put("scene_skill_id", sceneSkillId);
            json.put("scene_step_id", sceneStepId);
            json.put("maintenance_task", maintenanceTask.toJson());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize task session", exception);
        }
        return json;
    }

    static TaskSession fromJson(JSONObject json) {
        Status status;
        try {
            status = Status.valueOf(json.optString("status", Status.PAUSED.name()));
        } catch (IllegalArgumentException ignored) {
            status = Status.PAUSED;
        }
        JSONObject taskJson = json.optJSONObject("maintenance_task");
        TaskSession session = new TaskSession(
                clean(json.optString("id", "")),
                clean(json.optString("project_id", "")),
                MaintenanceTask.fromJson(taskJson),
                status);
        session.bindSceneSkill(
                json.optString("scene_skill_id", ""),
                json.optString("scene_step_id", ""));
        return session;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
