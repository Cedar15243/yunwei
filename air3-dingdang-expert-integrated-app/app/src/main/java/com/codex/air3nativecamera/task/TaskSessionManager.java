package com.codex.air3nativecamera.task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TaskSessionManager {
    public enum CaptureOrigin {
        HOME,
        ACTIVE_TASK
    }

    private final Map<String, TaskSession> sessions = new LinkedHashMap<>();
    private String activeTaskId = "";

    public TaskSession startNew(String projectId, String problem) {
        pauseActive();
        String id = "task-" + UUID.randomUUID();
        TaskSession session = new TaskSession(
                id,
                projectId == null ? "" : projectId.trim(),
                MaintenanceTask.start(problem),
                TaskSession.Status.ACTIVE);
        sessions.put(id, session);
        activeTaskId = id;
        return session;
    }

    public TaskSession beginCapture(CaptureOrigin origin, String projectId, String problem) {
        if (origin == CaptureOrigin.ACTIVE_TASK && active() != null) {
            return active();
        }
        return startNew(projectId, problem);
    }

    public void pauseActive() {
        TaskSession active = active();
        if (active != null && active.status() != TaskSession.Status.COMPLETED) {
            active.setStatus(TaskSession.Status.PAUSED);
        }
        activeTaskId = "";
    }

    public boolean resume(String taskId) {
        TaskSession session = find(taskId);
        if (session == null || session.status() == TaskSession.Status.COMPLETED) {
            return false;
        }
        pauseActive();
        session.setStatus(TaskSession.Status.ACTIVE);
        activeTaskId = session.id();
        return true;
    }

    public boolean resumeProject(String projectId) {
        TaskSession session = findProject(projectId);
        return session != null && resume(session.id());
    }

    public TaskSession findProject(String projectId) {
        if (projectId != null) {
            for (TaskSession session : sessions.values()) {
                if (projectId.equals(session.projectId())) {
                    return session;
                }
            }
        }
        return null;
    }

    public TaskSession active() {
        return activeTaskId.length() == 0 ? null : sessions.get(activeTaskId);
    }

    public TaskSession find(String taskId) {
        return taskId == null ? null : sessions.get(taskId);
    }

    public List<TaskSession> sessions() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray sessionArray = new JSONArray();
        try {
            for (TaskSession session : sessions.values()) {
                sessionArray.put(session.toJson());
            }
            json.put("active_task_id", activeTaskId);
            json.put("sessions", sessionArray);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize task sessions", exception);
        }
        return json;
    }

    public static TaskSessionManager fromJson(JSONObject json) {
        TaskSessionManager manager = new TaskSessionManager();
        if (json == null) {
            return manager;
        }
        JSONArray sessionArray = json.optJSONArray("sessions");
        for (int i = 0; sessionArray != null && i < sessionArray.length(); i++) {
            JSONObject item = sessionArray.optJSONObject(i);
            if (item == null) {
                continue;
            }
            TaskSession session = TaskSession.fromJson(item);
            if (session.id().length() > 0) {
                manager.sessions.put(session.id(), session);
            }
        }
        String activeId = json.optString("active_task_id", "");
        TaskSession active = manager.sessions.get(activeId);
        if (active != null && active.status() == TaskSession.Status.ACTIVE) {
            manager.activeTaskId = activeId;
        }
        return manager;
    }
}
