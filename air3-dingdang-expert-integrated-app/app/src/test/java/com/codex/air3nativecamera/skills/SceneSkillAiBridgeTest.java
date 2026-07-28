package com.codex.air3nativecamera.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.junit.Test;

public final class SceneSkillAiBridgeTest {
    @Test
    public void ordinaryTasksReceiveNoSceneProtocol() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "服务器无法启动");

        assertEquals("", SceneSkillAiBridge.instruction(task));
    }

    @Test
    public void everyNewPhotoCanRequestVisibleDetectionMarkersWithoutSceneEvidenceTags() {
        String instruction = SceneSkillAiBridge.imageMarkerInstruction();

        assertTrue(instruction.contains("本轮实际图片"));
        assertTrue(instruction.contains("scene-marker"));
        assertTrue(instruction.contains("不受单步回复限制"));
        assertTrue(instruction.contains("必须至少输出一个标记"));
        assertTrue(instruction.contains("无法可靠定位时不要输出标记"));
        assertFalse(instruction.contains("scene-evidence"));
        assertFalse(instruction.contains("honeywell"));
    }

    @Test
    public void boundTaskRequestsRealImageEvidenceForOnlyTheCurrentStep() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_POWER);

        String instruction = SceneSkillAiBridge.instruction(task);

        assertTrue(instruction.contains("当前步骤：检查霍尼韦尔网关供电"));
        assertTrue(instruction.contains("honeywell-gateway"));
        assertTrue(instruction.contains("power-input"));
        assertTrue(instruction.contains("rated-voltage"));
        assertTrue(instruction.contains("meter-reading"));
        assertTrue(instruction.contains("voltage-in-range"));
        assertTrue(instruction.contains("电源灯只能作辅助证据"));
        assertTrue(instruction.contains("实际照片"));
        assertFalse(instruction.contains("投资"));
        assertFalse(instruction.contains("固定根因"));
    }

    @Test
    public void meterStepAcceptsSpokenResultsWithoutAMeterPhoto() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_METER_CHECK);

        String instruction = SceneSkillAiBridge.instruction(task);

        assertTrue(instruction.contains("可以没有万用表照片"));
        assertTrue(instruction.contains("工程师口述"));
        assertTrue(instruction.contains("额定值"));
        assertTrue(instruction.contains("实测值"));
        assertTrue(instruction.contains("dc-voltage-mode"));
    }

    @Test
    public void spokenGatewayMeasurementDoesNotDemandANewPhoto() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_POWER);

        String instruction = SceneSkillAiBridge.instruction(task, false);

        assertTrue(instruction.contains("本轮没有新照片"));
        assertTrue(instruction.contains("任务中已确认的照片证据"));
        assertTrue(instruction.contains("只口述结果"));
        assertFalse(instruction.contains("请只判断本轮实际照片"));
    }

    @Test
    public void machineEvidenceLineIsParsedAndRemovedFromOperatorText() {
        String response = "网关电源灯正常，请继续检查网络。\n"
                + "[[scene-evidence:honeywell-gateway,power-input,power-led]]";

        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(response);

        assertEquals("网关电源灯正常，请继续检查网络。", parsed.visibleText());
        assertTrue(parsed.evidenceTags().contains("honeywell-gateway"));
        assertTrue(parsed.evidenceTags().contains("power-led"));
        assertFalse(parsed.visibleText().contains("scene-evidence"));
    }

    @Test
    public void structuredDetectionMarkersAreParsedAndRemovedFromOperatorText() {
        String response = "运行灯已点亮。\n"
                + "[[scene-evidence:honeywell-gateway,power-led]]\n"
                + "[[scene-marker:运行灯|normal|0.62|0.24|0.08|0.12|0.93]]";

        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(response);

        assertEquals("运行灯已点亮。", parsed.visibleText());
        assertEquals(1, parsed.markers().size());
        SceneSkillAiBridge.DetectionMarker marker = parsed.markers().get(0);
        assertEquals("运行灯", marker.label());
        assertEquals("normal", marker.status());
        assertEquals(0.62d, marker.x(), 0.001d);
        assertEquals(93, marker.confidence());
        assertFalse(parsed.visibleText().contains("scene-marker"));
    }

    @Test
    public void outOfRangeOrMalformedMarkersAreIgnored() {
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "请补拍。\n[[scene-marker:运行灯|normal|1.7|0.2|0.1|0.1|0.9]]\n"
                        + "[[scene-marker:端子|unknown|bad|0.2|0.1|0.1|0.9]]");

        assertTrue(parsed.markers().isEmpty());
        assertEquals("请补拍。", parsed.visibleText());
    }

    @Test
    public void systemContextProtocolAcceptsDrawingDescriptionOrEquipmentPhoto() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT);

        String instruction = SceneSkillAiBridge.instruction(task);

        assertTrue(instruction.contains("架构图"));
        assertTrue(instruction.contains("现场描述"));
        assertTrue(instruction.contains("设备照片"));
        assertTrue(instruction.contains("system-context"));
    }

    @Test
    public void candidateProtocolRequiresRealPlatformEvidenceAndStaysInvisible() {
        String instruction = SceneSkillAiBridge.candidateInstruction();
        assertTrue(instruction.contains("实际照片"));
        assertTrue(instruction.contains("temperature-missing"));
        assertTrue(instruction.contains("other-data-normal"));
        assertFalse(instruction.contains("演示"));

        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "平台温湿度区域无数据，其他监测项正常。\n"
                        + "[[scene-candidate:honeywell-temp-humidity]]\n"
                        + "[[scene-evidence:platform,temperature-missing,other-data-normal]]");

        assertEquals(HoneywellTempHumiditySkill.SKILL_ID, parsed.candidateSkillId());
        assertFalse(parsed.visibleText().contains("scene-candidate"));
        assertFalse(parsed.visibleText().contains("scene-evidence"));
        assertTrue(parsed.evidenceTags().contains("temperature-missing"));
    }
}
