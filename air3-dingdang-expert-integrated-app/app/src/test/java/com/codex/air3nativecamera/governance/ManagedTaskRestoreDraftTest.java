package com.codex.air3nativecamera.governance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ManagedTaskRestoreDraftTest {
    @Test
    public void restoreDraftShowsTheServerSummaryWithoutActivatingTheLocalTask() throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession session = manager.startNew("project-a", "控制器报警");
        session.maintenanceTask().setDiagnosis("供电异常", "需要检查输入电压。", 82);
        session.bindSceneSkill("skill-hvac", "check-power");
        manager.pauseActive();

        ManagedTaskRestoreDraft draft = ManagedTaskRestoreDraft.create(
                manager, project("active", session.id(), "active"),
                "project-a", session.id());
        OperationDetail detail = draft.confirmationDetail();

        assertNull(manager.active());
        assertEquals("managed_task_restore_confirm", detail.primaryAction());
        assertEquals("managed_task_restore_cancel", detail.secondaryAction());
        assertTrue(detail.items().toString().contains("控制器已恢复供电"));
        assertTrue(detail.items().toString().contains("skill-hvac"));
        assertTrue(detail.items().toString().contains("供电异常"));
    }

    @Test
    public void completedAndClosedServerTasksCannotProduceARestoreDraft() throws Exception {
        TaskSessionManager manager = pausedTaskManager();
        String taskId = manager.sessions().get(0).id();

        assertRejected(manager, project("active", taskId, "completed"), taskId,
                "task_not_recoverable");
        assertRejected(manager, project("active", taskId, "closed"), taskId,
                "task_not_recoverable");
        assertNull(manager.active());
    }

    @Test
    public void unsupportedServerTaskStateAndProjectMismatchAreRejected() throws Exception {
        TaskSessionManager manager = pausedTaskManager();
        String taskId = manager.sessions().get(0).id();

        assertRejected(manager, project("active", taskId, "paused"), taskId,
                "task_state_conflict");
        assertRejected(manager, project("closed", taskId, "active"), taskId,
                "project_not_active");
        assertRejected(manager, project("active", "task-other", "active"), taskId,
                "task_not_found");
        assertNull(manager.active());
    }

    @Test
    public void confirmationMustRevalidateTheSameOpenTaskAgainstFreshServerState() throws Exception {
        TaskSessionManager manager = pausedTaskManager();
        String taskId = manager.sessions().get(0).id();
        ManagedTaskRestoreDraft draft = ManagedTaskRestoreDraft.create(
                manager, project("active", taskId, "active"), "project-a", taskId);

        assertTrue(draft.matchesFreshState(
                manager, project("active", taskId, "active")));
        assertFalse(draft.matchesFreshState(
                manager, project("active", taskId, "completed")));
        assertFalse(draft.matchesFreshState(
                manager, project("active", "task-other", "active")));
        assertNull(manager.active());
    }

    private static TaskSessionManager pausedTaskManager() {
        TaskSessionManager manager = new TaskSessionManager();
        manager.startNew("project-a", "控制器报警");
        manager.pauseActive();
        return manager;
    }

    private static JSONObject project(String projectStatus, String taskId, String taskStatus)
            throws Exception {
        return new JSONObject()
                .put("localProjectId", "project-a")
                .put("title", "一号机房")
                .put("status", projectStatus)
                .put("projectMemory", new JSONObject()
                        .put("summary", "控制器已恢复供电，仍需观察报警。")
                        .put("confirmedFacts", new JSONArray().put("电源指示灯常亮"))
                        .put("excludedFacts", new JSONArray())
                        .put("risks", new JSONArray().put("带电操作风险"))
                        .put("revision", 3))
                .put("tasks", new JSONArray().put(new JSONObject()
                        .put("localTaskId", taskId)
                        .put("title", "控制器报警")
                        .put("status", taskStatus)
                        .put("activeSkillVersionId", "skill-hvac@1.0.0")
                        .put("endSummary", "")))
                .put("projectInstructions", new JSONArray());
    }

    private static void assertRejected(
            TaskSessionManager manager,
            JSONObject project,
            String taskId,
            String expectedError
    ) {
        try {
            ManagedTaskRestoreDraft.create(manager, project, "project-a", taskId);
            fail("Expected " + expectedError);
        } catch (IllegalArgumentException expected) {
            assertEquals(expectedError, expected.getMessage());
        }
    }
}
