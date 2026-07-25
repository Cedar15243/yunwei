package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.task.MaintenanceTask;

import org.junit.Test;

public final class OperationDetailFactoryTest {
    @Test
    public void inspectionDetailShowsOnlyLocalChecklistProgressAndOneSafeNextAction() {
        OperationDetailFactory factory = OperationDetailFactory.defaultFactory();
        InspectionChecklist checklist = InspectionChecklist.defaultChecklist();

        OperationDetail detail = factory.create("inspection", null, checklist);

        assertEquals("巡检任务", detail.title());
        assertTrue(detail.items().get(0).contains("0/3"));
        assertEquals("complete_inspection:server_status", detail.primaryAction());
        assertTrue(detail.primaryLabel().contains("检查设备运行状态"));
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
    public void knowledgeDetailStaysReadOnlyAndDoesNotOfferDeviceOrAiExecution() {
        OperationDetail detail = OperationDetailFactory.defaultFactory().create(
                "knowledge", null, InspectionChecklist.defaultChecklist());

        assertEquals("华方知识库", detail.title());
        assertTrue(detail.items().get(0).contains("只读目录"));
        assertEquals("", detail.primaryAction());
    }
}
