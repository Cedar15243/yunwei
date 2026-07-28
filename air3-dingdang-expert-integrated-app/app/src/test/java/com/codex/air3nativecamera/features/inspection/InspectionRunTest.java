package com.codex.air3nativecamera.features.inspection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class InspectionRunTest {
    @Test
    public void pointCannotCompleteBeforePhotoAiObservationAndHumanConfirmation() {
        InspectionRun run = InspectionRun.start(
                InspectionCatalog.defaultCatalog().find("lab-training-room"));

        assertFalse(run.completeCurrentPoint());
        run.attachPhoto("photo://switch-front");
        assertFalse(run.completeCurrentPoint());
        run.recordAiObservation("SYS 绿灯常亮，ALM 未亮", "SYS 绿灯常亮", "SYS 绿灯常亮");
        assertFalse(run.completeCurrentPoint());
        run.confirmCurrentPoint(InspectionRun.Outcome.NORMAL, "现场确认正常");

        assertTrue(run.completeCurrentPoint());
        assertEquals(2, run.currentPointNumber());
    }

    @Test
    public void runRoundTripRetainsEvidenceComparisonOutcomeAndProgress() throws Exception {
        InspectionRun run = InspectionRun.start(
                InspectionCatalog.defaultCatalog().find("lab-training-room"));
        run.attachPhoto("photo://switch-front");
        run.recordAiObservation("端口灯有收发闪烁", "端口灯闪烁", "端口灯稳定闪烁");
        run.confirmCurrentPoint(InspectionRun.Outcome.NORMAL, "已确认");
        run.completeCurrentPoint();

        InspectionRun restored = InspectionRun.fromJson(
                new JSONObject(run.toJson().toString()), InspectionCatalog.defaultCatalog());

        assertEquals("lab-training-room", restored.definition().id());
        assertEquals(2, restored.currentPointNumber());
        assertEquals(1, restored.completedPointCount());
        assertEquals("photo://switch-front", restored.records().get(0).photoReference());
        assertEquals("端口灯闪烁", restored.records().get(0).previousValue());
    }

    @Test
    public void finalPointCompletionMarksRunCompletedWithoutAdvancingPastLastPoint() {
        InspectionTaskDefinition definition = new InspectionTaskDefinition(
                "single", "单点巡检", "测试", InspectionTaskDefinition.Scope.MY_TASK,
                java.util.Collections.<String>emptySet(),
                java.util.Collections.singletonList(new InspectionTaskDefinition.Point(
                        "point", "点位", "检查点位", "正常", true)));
        InspectionRun run = InspectionRun.start(definition);

        run.attachPhoto("photo://point");
        run.recordAiObservation("状态正常", "正常", "正常");
        run.confirmCurrentPoint(InspectionRun.Outcome.NORMAL, "确认正常");

        assertTrue(run.completeCurrentPoint());
        assertTrue(run.isCompleted());
        assertEquals(1, run.currentPointNumber());
        assertEquals(1, run.completedPointCount());
    }
}
