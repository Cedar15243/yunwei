package com.codex.air3nativecamera.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class TaskSessionManagerTest {
    @Test
    public void returningHomePausesAndUnbindsTheActiveTask() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = manager.startNew("project-1", "服务器无法启动");

        manager.pauseActive();

        assertNull(manager.active());
        assertEquals(TaskSession.Status.PAUSED, manager.find(task.id()).status());
    }

    @Test
    public void homeCaptureStartsANewTaskWhileTaskCaptureKeepsTheCurrentTask() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession first = manager.startNew("project-1", "旧任务");
        manager.pauseActive();

        TaskSession second = manager.beginCapture(
                TaskSessionManager.CaptureOrigin.HOME, "project-2", "新现场问题");
        TaskSession same = manager.beginCapture(
                TaskSessionManager.CaptureOrigin.ACTIVE_TASK, "project-2", "补拍近景");

        assertFalse(first.id().equals(second.id()));
        assertEquals(second.id(), same.id());
        assertEquals(2, manager.sessions().size());
    }

    @Test
    public void pausedTaskOnlyResumesByExplicitId() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession first = manager.startNew("project-1", "服务器无法启动");
        manager.pauseActive();

        assertNull(manager.active());
        assertTrue(manager.resume(first.id()));
        assertEquals(first.id(), manager.active().id());
        assertEquals(TaskSession.Status.ACTIVE, manager.active().status());
    }

    @Test
    public void completedTaskRemainsVisibleButCannotBeResumed() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = manager.startNew("project-1", "服务器无法启动");

        manager.completeActive();

        assertEquals(task.id(), manager.active().id());
        assertEquals(TaskSession.Status.COMPLETED, manager.active().status());
        manager.pauseActive();
        assertNull(manager.active());
        assertFalse(manager.resume(task.id()));
    }

    @Test
    public void selectingAProjectExplicitlyResumesItsPausedTask() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession first = manager.startNew("project-1", "服务器无法启动");
        manager.pauseActive();

        assertTrue(manager.resumeProject("project-1"));
        assertEquals(first.id(), manager.active().id());
        assertEquals(first.id(), manager.findProject("project-1").id());
        assertFalse(manager.resumeProject("missing-project"));
    }

    @Test
    public void projectResumeSkipsCompletedHistoryAndChoosesTheNewestOpenTask() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession completed = manager.startNew("project-1", "旧任务");
        manager.completeActive();
        manager.pauseActive();
        TaskSession newest = manager.startNew("project-1", "最近任务");
        manager.pauseActive();

        assertTrue(manager.resumeProject("project-1"));
        assertEquals(newest.id(), manager.active().id());
        assertEquals(TaskSession.Status.COMPLETED, manager.find(completed.id()).status());
    }

    @Test
    public void sessionRoundTripRetainsActiveBindingAndSceneSkillState() throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = manager.startNew("project-1", "温湿度采集异常");
        task.bindSceneSkill("honeywell-temp-humidity", "gateway-power");
        task.maintenanceTask().addEvidence("网关正面", "photo://gateway-front");

        TaskSessionManager restored = TaskSessionManager.fromJson(
                new JSONObject(manager.toJson().toString()));

        assertEquals(task.id(), restored.active().id());
        assertEquals("honeywell-temp-humidity", restored.active().sceneSkillId());
        assertEquals("gateway-power", restored.active().sceneStepId());
        assertEquals("photo://gateway-front",
                restored.active().maintenanceTask().evidenceReferences().get(0));
    }

    @Test
    public void legacyPreparedWorkOrderFieldsCannotBindANewTask() throws Exception {
        JSONObject legacy = new JSONObject()
                .put("active_task_id", "")
                .put("prepared_problem", "实训室温湿度采集异常排查")
                .put("prepared_skill_id", "honeywell-temp-humidity")
                .put("prepared_step_id", "platform-anomaly")
                .put("sessions", new org.json.JSONArray());

        TaskSessionManager restored = TaskSessionManager.fromJson(legacy);
        TaskSession ordinary = restored.startNew("project-1", "交换机端口异常");

        assertEquals("交换机端口异常", ordinary.maintenanceTask().initialProblem());
        assertEquals("", ordinary.sceneSkillId());
        assertEquals("", ordinary.sceneStepId());
        assertFalse(restored.toJson().has("prepared_skill_id"));
    }

    @Test
    public void newTaskStartsWithAnIndependentConversationSkillAndRepairContext() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession first = manager.startNew("project-a", "控制器报警");
        first.bindConversationStartIndex(2);
        first.bindSceneSkill("skill-hvac", "check-power");
        first.maintenanceTask().setDiagnosis("供电异常", "检查输入电压。", 80);
        first.maintenanceTask().replaceRepairSteps(new String[]{"断电", "测量输入"});
        manager.pauseActive();

        TaskSession second = manager.startNew("project-a", "服务器无法启动");
        second.bindConversationStartIndex(12);

        assertFalse(first.id().equals(second.id()));
        assertEquals(12, second.conversationStartIndex());
        assertEquals("", second.sceneSkillId());
        assertEquals("", second.sceneStepId());
        assertEquals(0, second.maintenanceTask().aiTurnCount());
        assertEquals(0, second.maintenanceTask().repairStepCount());
        assertEquals(MaintenanceTask.Phase.DIAGNOSIS, second.maintenanceTask().phase());
    }

    @Test
    public void conversationBoundarySurvivesTaskSessionPersistence() throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = manager.startNew("project-a", "控制器报警");
        task.bindConversationStartIndex(7);
        manager.pauseActive();

        TaskSessionManager restored = TaskSessionManager.fromJson(manager.toJson());

        assertEquals(7, restored.find(task.id()).conversationStartIndex());
    }
}
