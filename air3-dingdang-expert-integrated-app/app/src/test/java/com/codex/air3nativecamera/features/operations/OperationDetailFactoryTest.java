package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSessionManager;
import com.codex.air3nativecamera.features.inspection.InspectionCatalog;
import com.codex.air3nativecamera.features.inspection.InspectionRun;

import org.junit.Test;

public final class OperationDetailFactoryTest {
    @Test
    public void inspectionDetailShowsSelectableLocalAndIndustryTasks() {
        OperationDetailFactory factory = OperationDetailFactory.defaultFactory();
        InspectionChecklist checklist = InspectionChecklist.defaultChecklist();

        OperationDetail detail = factory.create("inspection", null, checklist);

        assertEquals("巡检任务", detail.title());
        assertTrue(detail.items().get(0).contains("实训室设备巡检"));
        assertEquals("", detail.primaryAction());
        assertEquals("start_inspection:lab-training-room", detail.itemActions().get(0));
    }

    @Test
    public void perceptionDetailUsesRealCurrentTaskEvidenceInsteadOfClaimingVideoAnalysis() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.addEvidence("现场照片 1", "local-photo");

        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "perception", task, InspectionChecklist.defaultChecklist());

        assertEquals("现场拍照", detail.title());
        assertTrue(detail.items().contains("当前任务证据：1项"));
        assertTrue(detail.items().contains("现场照片 1"));
        assertEquals("capture_photo", detail.primaryAction());
    }

    @Test
    public void knowledgeDetailShowsLocalAndEnterpriseConnectionStates() {
        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "knowledge", null, InspectionChecklist.defaultChecklist());

        assertEquals("华方知识库", detail.title());
        assertTrue(detail.description().contains("本机资料已连接"));
        assertTrue(detail.description().contains("企业知识服务未连接"));
        assertEquals(8, detail.items().size());
        assertEquals("", detail.primaryAction());
    }

    @Test
    public void deviceMemoryKeepsTheSummaryOutsideThePagedDeviceCards() {
        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "device_brain", null, InspectionChecklist.defaultChecklist());

        assertEquals("设备记忆", detail.title());
        assertTrue(detail.description().contains("28 台"));
        assertEquals(8, detail.items().size());
    }

    @Test
    public void maintenanceTasksDoNotExposeThePrivateSceneTrigger() {
        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "tasks", null, InspectionChecklist.defaultChecklist());

        String visibleText = detail.description() + " " + String.join(" ", detail.items())
                + " " + detail.primaryLabel() + " " + detail.secondaryLabel();
        assertEquals("start_diagnosis", detail.primaryAction());
        assertFalse(visibleText.contains("霍尼韦尔工单"));
        assertFalse(visibleText.contains("实训室工单"));
        assertFalse(detail.description().contains("演示"));
        assertFalse(visibleText.contains("预设"));
    }

    @Test
    public void environmentAgentCardActsAsARealEnableDisableSwitch() {
        OperationDetailFactory factory = OperationDetailFactory.defaultFactory();
        OperationDetail disabled = factory.create(
                "agent_center", null, InspectionChecklist.defaultChecklist());
        int index = -1;
        for (int i = 0; i < disabled.items().size(); i++) {
            if (disabled.items().get(i).contains("环境诊断技能")) index = i;
        }
        assertTrue(index >= 0);
        assertFalse(disabled.description().contains("演示"));
        assertTrue(disabled.items().get(index).contains("本机技能已停用"));
        assertTrue(disabled.items().get(index).contains("可说：启用环境诊断技能"));
        assertTrue(disabled.items().get(index).contains("平台报警，看看怎么回事"));
        assertFalse(disabled.items().get(index).contains("操作："));
        assertEquals("set_agent:environment_ops:enabled", disabled.itemActions().get(index));

        assertTrue(factory.toggleAgentPackage("environment_ops"));
        assertTrue(factory.isAgentPackageAuthorized("environment_ops"));
        OperationDetail enabled = factory.create(
                "agent_center", null, InspectionChecklist.defaultChecklist());
        assertTrue(enabled.items().get(index).contains("本机技能已启用"));
        assertTrue(enabled.items().get(index).contains("可说：停用环境诊断技能"));
        assertFalse(enabled.items().get(index).contains("操作："));
        assertEquals("set_agent:environment_ops:disabled", enabled.itemActions().get(index));
    }

    @Test
    public void restoringDisabledAuthorizationsClearsAnEnabledAgent() throws Exception {
        OperationDetailFactory factory = OperationDetailFactory.defaultFactory();
        assertTrue(factory.setAgentPackageAuthorized("environment_ops", true));

        factory.restoreAgentAuthorizations(new org.json.JSONObject()
                .put("authorized", new org.json.JSONArray()));

        assertFalse(factory.isAgentPackageAuthorized("environment_ops"));
    }

    @Test
    public void inspectionCatalogExposesEveryTaskAsAnIndependentSelectableItem() {
        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "inspection", null, InspectionChecklist.defaultChecklist());

        assertEquals(InspectionCatalog.defaultCatalog().tasks().size(), detail.items().size());
        assertEquals(detail.items().size(), detail.itemActions().size());
        assertEquals("start_inspection:lab-training-room", detail.itemActions().get(0));
        assertTrue(detail.items().get(0).contains("可说：开始实训室设备巡检"));
        assertTrue(detail.description().contains("进入第一个选项"));
        assertTrue(detail.itemActions().contains("start_inspection:water-power-heating"));
        assertTrue(detail.itemActions().contains("start_inspection:air-conditioning"));
        assertTrue(detail.itemActions().contains("start_inspection:fire-safety"));
    }

    @Test
    public void activeInspectionShowsOnePointAndRequiresPhotoBeforeConfirmation() {
        InspectionRun run = InspectionRun.start(
                InspectionCatalog.defaultCatalog().find("lab-training-room"));

        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "inspection", null, InspectionChecklist.defaultChecklist(), run);

        assertEquals("实训室设备巡检", detail.title());
        assertTrue(detail.items().get(0).contains("1 / 4"));
        assertTrue(detail.items().get(1).contains("交换机指示灯"));
        assertEquals("capture_inspection_photo", detail.primaryAction());
        assertEquals("cancel_inspection", detail.secondaryAction());
    }

    @Test
    public void recognizedInspectionPointStillOffersRetakeBeforeHumanConfirmation() {
        InspectionRun run = InspectionRun.start(
                InspectionCatalog.defaultCatalog().find("lab-training-room"));
        run.attachPhoto("photo://switch");
        run.recordAiObservation("画面未覆盖全部指示灯，请补拍正面。", "正常", "待确认");

        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "inspection", null, InspectionChecklist.defaultChecklist(), run);

        assertEquals("capture_inspection_photo", detail.itemActions().get(2));
    }

    @Test
    public void inspectionComparisonDoesNotRepeatTheFullAiObservation() {
        InspectionRun run = InspectionRun.start(
                InspectionCatalog.defaultCatalog().find("lab-training-room"));
        String observation = "未拍到交换机正面指示灯区域，无法确认电源、SYS/ALM 和端口状态，请补拍正面特写。";
        run.attachPhoto("photo://switch");
        run.recordAiObservation(observation, "SYS 绿灯常亮，ALM 未亮", observation);

        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "inspection", null, InspectionChecklist.defaultChecklist(), run);

        assertEquals("上次：SYS 绿灯常亮，ALM 未亮\n本次：见 AI 识别结果", detail.items().get(4));
    }

    @Test
    public void maintenanceTasksExposePausedSessionsAsRecoverableItems() {
        TaskSessionManager sessions = new TaskSessionManager();
        sessions.startNew("project-1", "服务器无法启动")
                .maintenanceTask().addEvidence("现场照片 1", "local-photo");
        sessions.pauseActive();

        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "tasks", null, InspectionChecklist.defaultChecklist(), null, sessions.sessions());

        assertTrue(detail.items().get(0).contains("服务器无法启动"));
        assertTrue(detail.items().get(0).contains("已暂停"));
        assertTrue(detail.itemActions().get(0).startsWith("resume_task:"));
    }

    @Test
    public void completedInspectionShowsSummaryWithoutRepeatConfirmationAction() {
        InspectionRun run = InspectionRun.start(
                InspectionCatalog.defaultCatalog().find("lab-training-room"));
        while (!run.isCompleted()) {
            run.attachPhoto("photo://point-" + run.currentPointNumber());
            run.recordAiObservation("状态正常", "正常", "正常");
            run.confirmCurrentPoint(InspectionRun.Outcome.NORMAL, "现场确认正常");
            assertTrue(run.completeCurrentPoint());
        }

        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "inspection", null, InspectionChecklist.defaultChecklist(), run);

        assertEquals("巡检已完成", detail.tag());
        assertEquals("", detail.primaryAction());
        assertEquals("", detail.secondaryAction());
        assertTrue(detail.items().get(0).contains("4 / 4"));
    }
}
