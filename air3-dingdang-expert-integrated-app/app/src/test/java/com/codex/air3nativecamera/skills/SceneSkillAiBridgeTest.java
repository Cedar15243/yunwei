package com.codex.air3nativecamera.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.junit.Test;

import java.util.Set;

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
        assertTrue(instruction.contains("不要框选整台设备"));
        assertFalse(instruction.contains("scene-evidence"));
        assertFalse(instruction.contains("honeywell"));
    }

    @Test
    public void platformAlarmTargetsTheRightAlarmListInsteadOfTheLowerStatusPanel() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "平台报警");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY);

        String instruction = SceneSkillAiBridge.instruction(task);

        assertTrue(instruction.contains("右侧报警列表"));
        assertTrue(instruction.contains("温湿度传感器报警记录"));
        assertTrue(instruction.contains("只输出一个检测框"));
        assertTrue(instruction.contains("不要框选下方机房环境检测区"));
    }

    @Test
    public void ddcPowerPhotoRequestsOnlyDdcAndMeasurementPointEvidence() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO);

        String instruction = SceneSkillAiBridge.instruction(task);

        assertTrue(instruction.contains("当前步骤：确认霍尼韦尔工控 DDC 供电测量点"));
        assertTrue(instruction.contains("honeywell-ddc"));
        assertTrue(instruction.contains("power-input"));
        assertTrue(instruction.contains("rated-voltage"));
        assertTrue(instruction.contains("24VAC/COM"));
        assertFalse(instruction.contains("meter-reading"));
        assertTrue(instruction.contains("实际照片"));
        assertTrue(instruction.contains("请尽量为当前检查对象输出"));
        assertTrue(instruction.contains("不要伪造坐标"));
        assertFalse(instruction.contains("投资"));
        assertFalse(instruction.contains("固定根因"));
    }

    @Test
    public void ddcPowerPhotoAcceptsVisibleDeviceIdentityWithoutASeparateNameplateSticker() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO);

        String instruction = SceneSkillAiBridge.instruction(task);

        assertTrue(instruction.contains("Honeywell 品牌或工控 DDC 本体标识"));
        assertTrue(instruction.contains("不要求另有独立铭牌或型号贴纸"));
        assertTrue(instruction.contains("必须输出 honeywell-ddc、power-input"));
        assertTrue(instruction.contains("检测框紧贴 24VAC/COM 电源端子"));
        assertTrue(instruction.contains("10号 24VAC"));
        assertTrue(instruction.contains("11号 COM"));
        assertTrue(instruction.contains("上方黑色三位端子区"));
        assertTrue(instruction.contains("不要框选黄色设备名称标签"));
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
        assertTrue(instruction.contains("meter-reading"));
        assertTrue(instruction.contains("probe-point"));
    }

    @Test
    public void spokenDdcMeasurementDoesNotDemandANewPhoto() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT);

        String instruction = SceneSkillAiBridge.instruction(task, false);

        assertTrue(instruction.contains("本轮没有新照片"));
        assertTrue(instruction.contains("任务中已确认的照片证据"));
        assertTrue(instruction.contains("只口述结果"));
        assertFalse(instruction.contains("请只判断本轮实际照片"));
    }

    @Test
    public void gatewayProtocolChecksRs485AndEthernetInOnePhoto() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485);

        String rs485 = SceneSkillAiBridge.instruction(task);
        assertTrue(rs485.contains("COM1/COM2"));
        assertTrue(rs485.contains("gateway-rs485-led"));
        assertTrue(rs485.contains("RJ45"));
        assertTrue(rs485.contains("link-led"));
        assertFalse(rs485.contains("gateway-rs485-terminal"));

        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK);
        String network = SceneSkillAiBridge.instruction(task);
        assertTrue(network.contains("RJ45"));
        assertTrue(network.contains("network-cable"));
        assertFalse(network.contains("gateway-rs485-terminal"));
    }

    @Test
    public void rs485ProtocolsIdentifyThePhysicalTerminalsAndExcludeNearbyStatusAreas() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_DDC_RS485);

        String ddc = SceneSkillAiBridge.instruction(task);
        assertTrue(ddc.contains("3号端子"));
        assertTrue(ddc.contains("4号端子"));
        assertTrue(ddc.contains("包含“485”文字与上方指示灯的局部区域"));
        assertTrue(ddc.contains("不要框选 S-BUS"));
        assertTrue(ddc.contains("不要求状态灯必须点亮"));
        assertTrue(ddc.contains("设备身份沿用上一阶段"));
        assertTrue(ddc.contains("必须输出 rs485-terminal、rs485-led"));
        assertFalse(ddc.contains("必须输出 honeywell-ddc、rs485-terminal、rs485-led"));
        assertFalse(ddc.contains("紧贴其正上方灰色方形指示灯框选"));

        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485);
        String gateway = SceneSkillAiBridge.instruction(task);
        assertTrue(gateway.contains("COM1/COM2"));
        assertTrue(gateway.contains("不要框选 PWR/RUN"));
        assertTrue(gateway.contains("从上到下依次为 PWR、RUN、COM1、COM2"));
        assertTrue(gateway.contains("第三、第四个"));
        assertTrue(gateway.contains("只输出两个检测框"));
        assertTrue(gateway.contains("同时覆盖 COM1 和 COM2 两个通讯灯"));
        assertTrue(gateway.contains("下方 RJ45 网络口通讯灯区"));
        assertTrue(gateway.contains("不要框选 A/B 端子排"));

        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_SENSOR_WIRING);
        String sensor = SceneSkillAiBridge.instruction(task, false);
        assertTrue(sensor.contains("接线异常"));
        assertTrue(sensor.contains("wiring-anomaly"));
        assertTrue(sensor.contains("voltage-in-range"));
    }

    @Test
    public void confirmedInspectionStepsHideMarkersOutsideTheUserApprovedZones() {
        TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY);
        SceneSkillAiBridge.ParsedResponse platform = SceneSkillAiBridge.parse(
                "[[scene-marker:右侧温湿度报警列表|abnormal|0.68|0.43|0.18|0.18|0.96]]\n"
                        + "[[scene-marker:下方机房环境检测区|abnormal|0.65|0.66|0.28|0.20|0.94]]\n"
                        + "[[scene-marker:整个监控屏幕|unknown|0.05|0.05|0.90|0.90|0.92]]");
        String platformMarkers = SceneSkillAiBridge.markersJson(
                SceneSkillAiBridge.markersForStep(task, platform));
        assertTrue(platformMarkers.contains("右侧温湿度报警列表"));
        assertFalse(platformMarkers.contains("机房环境检测区"));
        assertFalse(platformMarkers.contains("整个监控屏幕"));

        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO);
        SceneSkillAiBridge.ParsedResponse ddc = SceneSkillAiBridge.parse(
                "[[scene-marker:10号24VAC与11号COM测量端|unknown|0.18|0.28|0.20|0.10|0.95]]\n"
                        + "[[scene-marker:黄色设备名称标签|unknown|0.20|0.53|0.45|0.10|0.93]]\n"
                        + "[[scene-marker:S-BUS 1号2号端子|unknown|0.18|0.67|0.20|0.10|0.91]]");
        String ddcMarkers = SceneSkillAiBridge.markersJson(
                SceneSkillAiBridge.markersForStep(task, ddc));
        assertTrue(ddcMarkers.contains("10号24VAC与11号COM测量端"));
        assertFalse(ddcMarkers.contains("黄色设备名称标签"));
        assertFalse(ddcMarkers.contains("S-BUS"));

        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485);
        SceneSkillAiBridge.ParsedResponse gateway = SceneSkillAiBridge.parse(
                "[[scene-marker:COM1通讯灯|normal|0.64|0.28|0.04|0.05|0.92]]\n"
                        + "[[scene-marker:COM2通讯灯|normal|0.64|0.34|0.04|0.05|0.91]]\n"
                        + "[[scene-marker:RJ45网络口|normal|0.64|0.58|0.08|0.10|0.93]]\n"
                        + "[[scene-marker:网口通讯灯|normal|0.70|0.58|0.04|0.06|0.94]]\n"
                        + "[[scene-marker:A/B端子排|unknown|0.62|0.38|0.10|0.18|0.96]]\n"
                        + "[[scene-marker:PWR RUN状态灯|normal|0.64|0.17|0.04|0.08|0.95]]");
        java.util.List<SceneSkillAiBridge.DetectionMarker> gatewayMarkers =
                SceneSkillAiBridge.markersForStep(task, gateway);
        String gatewayJson = SceneSkillAiBridge.markersJson(gatewayMarkers);
        assertEquals(2, gatewayMarkers.size());
        assertTrue(gatewayJson.contains("COM1/COM2 485通讯灯"));
        assertTrue(gatewayJson.contains("RJ45网口通讯灯"));
        assertFalse(gatewayJson.contains("A/B"));
        assertFalse(gatewayJson.contains("PWR"));
    }

    @Test
    public void sensorRepairProtocolAcceptsSpokenResultWithoutAnotherPhoto() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_SENSOR_WIRING);

        String instruction = SceneSkillAiBridge.instruction(task, false);

        assertTrue(instruction.contains("本轮没有新照片"));
        assertTrue(instruction.contains("terminal"));
        assertTrue(instruction.contains("wiring-anomaly"));
        assertTrue(instruction.contains("voltage-in-range"));
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
    public void multipleEvidenceLinesAreMergedInsteadOfDeletingLaterTags() {
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "已识别检查位置。\n"
                        + "[[scene-evidence:honeywell-ddc]]\n"
                        + "[[scene-evidence:power-input,rated-voltage]]");

        assertTrue(parsed.evidenceTags().contains("honeywell-ddc"));
        assertTrue(parsed.evidenceTags().contains("power-input"));
        assertTrue(parsed.evidenceTags().contains("rated-voltage"));
        assertFalse(parsed.visibleText().contains("scene-evidence"));
    }

    @Test
    public void modelDiagramAliasesAreNormalizedToSystemContext() {
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "[[scene-evidence:electrical-control-system-diagram]]");

        assertTrue(parsed.evidenceTags().contains("system-context"));
        assertFalse(parsed.evidenceTags().contains("electrical-control-system-diagram"));
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
    public void internalEnglishMarkerLabelsAreLocalizedForTheHud() {
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "[[scene-marker:rs485-terminal|normal|0.1|0.1|0.2|0.2|0.9]]\n"
                        + "[[scene-marker:exposed-conductor|suspect|0.4|0.4|0.1|0.1|0.95]]");

        assertEquals("DDC 485端子", parsed.markers().get(0).label());
        assertEquals("裸露导体", parsed.markers().get(1).label());
    }

    @Test
    public void hudMarkersAreCappedAndKeepTheHighestRiskEvidence() {
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "[[scene-marker:terminal|normal|0.05|0.05|0.1|0.1|0.70]]\n"
                        + "[[scene-marker:signal-wire|normal|0.20|0.05|0.1|0.1|0.80]]\n"
                        + "[[scene-marker:network-port|normal|0.35|0.05|0.1|0.1|0.90]]\n"
                        + "[[scene-marker:link-led|normal|0.50|0.05|0.1|0.1|0.95]]\n"
                        + "[[scene-marker:exposed-conductor|abnormal|0.65|0.05|0.1|0.1|0.75]]");

        assertEquals(4, parsed.markers().size());
        assertTrue(parsed.markers().stream().anyMatch(
                marker -> "裸露导体".equals(marker.label())));
        assertFalse(parsed.markers().stream().anyMatch(
                marker -> "接线端子".equals(marker.label())));
    }

    @Test
    public void evidenceReconciliationUsesValidMarkersEvenWhenHudDisplayIsCapped() {
        TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO);
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "[[scene-marker:无关异常一|abnormal|0.01|0.01|0.08|0.08|0.99]]\n"
                        + "[[scene-marker:无关异常二|abnormal|0.12|0.01|0.08|0.08|0.98]]\n"
                        + "[[scene-marker:无关异常三|abnormal|0.23|0.01|0.08|0.08|0.97]]\n"
                        + "[[scene-marker:霍尼韦尔DDC控制器|normal|0.10|0.20|0.70|0.60|0.76]]\n"
                        + "[[scene-marker:24VAC/COM电源输入端|normal|0.20|0.65|0.20|0.12|0.75]]");

        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, "拍好了", parsed, true);

        assertEquals(4, parsed.markers().size());
        assertTrue(evidence.contains("honeywell-ddc"));
        assertTrue(evidence.contains("power-input"));
    }

    @Test
    public void insufficientOrUnrelatedEvidenceCannotDisplayDetectionMarkers() {
        SceneSkillAiBridge.ParsedResponse insufficient = SceneSkillAiBridge.parse(
                "[[scene-marker:terminal|unknown|0.4|0.6|0.1|0.1|0.3]]\n"
                        + "[[scene-evidence:insufficient]]");
        SceneSkillAiBridge.ParsedResponse unrelated = SceneSkillAiBridge.parse(
                "[[scene-marker:network-port|normal|0.4|0.6|0.1|0.1|0.9]]\n"
                        + "[[scene-evidence:unrelated]]");

        assertTrue(insufficient.markers().isEmpty());
        assertTrue(unrelated.markers().isEmpty());
    }

    @Test
    public void lowConfidenceGeometryIsKeptOutOfTheHudAndDoesNotClaimPreciseLocation() {
        SceneSkillAiBridge.ParsedResponse uncertain = SceneSkillAiBridge.parse(
                "[[scene-marker:network-port|unknown|0.2|0.3|0.2|0.2|0.45]]\n"
                        + "[[scene-evidence:honeywell-gateway,network-port]]");

        assertTrue(uncertain.markers().isEmpty());
        assertFalse(SceneSkillAiBridge.hasReliableEvidenceMarker(uncertain));

        SceneSkillAiBridge.ParsedResponse reliable = SceneSkillAiBridge.parse(
                "[[scene-marker:network-port|normal|0.2|0.3|0.2|0.2|0.75]]\n"
                        + "[[scene-evidence:honeywell-gateway,network-port]]");
        assertEquals(1, reliable.markers().size());
        assertTrue(SceneSkillAiBridge.hasReliableEvidenceMarker(reliable));
    }

    @Test
    public void highConfidencePowerTerminalMarkerCanCompleteTheMatchingEvidenceTag() {
        SceneSkillAiBridge.ParsedResponse confirmed = SceneSkillAiBridge.parse(
                "[[scene-marker:24VAC/COM 电源端子|normal|0.2|0.3|0.1|0.1|0.95]]\n"
                        + "[[scene-evidence:honeywell-ddc]]");
        SceneSkillAiBridge.ParsedResponse uncertain = SceneSkillAiBridge.parse(
                "[[scene-marker:24VAC/COM 电源端子|unknown|0.2|0.3|0.1|0.1|0.60]]\n"
                        + "[[scene-evidence:honeywell-ddc]]");

        assertTrue(confirmed.evidenceTags().contains("power-input"));
        assertFalse(uncertain.evidenceTags().contains("power-input"));
    }

    @Test
    public void highConfidenceKnownProtocolMarkersCompleteTheirMatchingEvidenceTags() {
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "[[scene-marker:rs485-terminal|normal|0.2|0.3|0.1|0.1|0.95]]\n"
                        + "[[scene-marker:rs485-led|unknown|0.4|0.3|0.1|0.1|0.85]]\n"
                        + "[[scene-evidence:honeywell-ddc]]");

        assertTrue(parsed.evidenceTags().contains("rs485-terminal"));
        assertTrue(parsed.evidenceTags().contains("rs485-led"));
        assertTrue(parsed.evidenceTags().contains("honeywell-ddc"));
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
        assertTrue(instruction.contains("不得为了满足协议臆造品牌"));
        assertFalse(instruction.contains("只有确认链路包含霍尼韦尔网关和温湿度传感器时才算通过"));
    }

    @Test
    public void fieldArchitectureMarkersRecoverMissingSystemContextEvidence() {
        TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT);
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "已识别图中设备节点。\n"
                        + "[[scene-marker:霍尼韦尔DDC控制器|unknown|0.24|0.65|0.38|0.28|0.75]]\n"
                        + "[[scene-marker:霍尼韦尔网关|unknown|0.55|0.65|0.18|0.25|0.70]]\n"
                        + "[[scene-marker:智慧工业平台服务器|unknown|0.39|0.32|0.20|0.12|0.65]]\n"
                        + "[[scene-marker:交换机|unknown|0.39|0.48|0.58|0.08|0.80]]");

        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, "这是架构图。", parsed, true);
        HoneywellTempHumiditySkill.Result result = new HoneywellTempHumiditySkill().evaluate(
                task, "这是架构图。", evidence, true, !parsed.markers().isEmpty());

        assertTrue(evidence.contains("system-context"));
        assertTrue(evidence.contains("honeywell-ddc"));
        assertTrue(evidence.contains("honeywell-gateway"));
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO, task.sceneStepId());
        assertTrue(result.reply().contains("下一步"));
    }

    @Test
    public void systemContextAcceptsShortNaturalPhrasesInsteadOfOneScriptedSentence() {
        String response = "[[scene-marker:霍尼韦尔DDC|unknown|0.12|0.55|0.25|0.25|0.78]]\n"
                + "[[scene-marker:霍尼韦尔网关|unknown|0.45|0.55|0.20|0.25|0.76]]\n"
                + "[[scene-marker:交换机|unknown|0.30|0.35|0.40|0.08|0.82]]";
        String[] phrases = {"这是现场图", "系统就这么接", "看这个连接", "就是这张图", "仅发送图片"};

        for (String phrase : phrases) {
            TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT);
            SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(response);
            Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                    task, phrase, parsed, true);
            assertTrue("Missing system context for: " + phrase,
                    evidence.contains("system-context"));
        }
    }

    @Test
    public void explicitSpokenSystemRelationshipOverridesModelInsufficientPhotoEvidence() {
        TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT);
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "没有架构图，请补充。\n[[scene-evidence:insufficient]]");

        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, "平台接网关，网关接DDC和传感器", parsed, false);
        HoneywellTempHumiditySkill.Result result = new HoneywellTempHumiditySkill().evaluate(
                task, "平台接网关，网关接DDC和传感器", evidence, false, false);

        assertTrue(evidence.contains("system-context"));
        assertFalse(evidence.contains("insufficient"));
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO, task.sceneStepId());
    }

    @Test
    public void vagueSpokenContextCannotOverrideModelInsufficientEvidence() {
        TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT);
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "需要补充。\n[[scene-evidence:insufficient]]");

        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, "这是现场设备", parsed, false);

        assertTrue(evidence.contains("insufficient"));
        assertFalse(evidence.contains("system-context"));
    }

    @Test
    public void oneUnrelatedObjectCannotBePromotedToSystemContext() {
        TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT);
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "[[scene-marker:普通电源插座|normal|0.30|0.30|0.20|0.20|0.95]]");

        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, "看这个", parsed, true);

        assertFalse(evidence.contains("system-context"));
    }

    @Test
    public void fieldMarkerAliasesRecoverObservableEvidenceForEveryPhotoStep() {
        assertEvidence(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY,
                "这个平台有问题",
                "[[scene-marker:监控平台|unknown|0.05|0.05|0.90|0.90|0.88]]\n"
                        + "[[scene-marker:温湿度异常区域|abnormal|0.15|0.30|0.25|0.20|0.92]]\n"
                        + "[[scene-marker:其他正常数据|normal|0.55|0.30|0.25|0.20|0.90]]",
                "platform", "temperature-missing", "other-data-normal");
        assertEvidence(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO,
                "就是这个",
                "[[scene-marker:霍尼韦尔DDC控制器|unknown|0.10|0.10|0.70|0.70|0.82]]\n"
                        + "[[scene-marker:24VAC/COM电源输入端|unknown|0.20|0.65|0.25|0.15|0.86]]",
                "honeywell-ddc", "power-input");
        assertEvidence(HoneywellTempHumiditySkill.STEP_DDC_RS485,
                "拍好了",
                "[[scene-marker:DDC控制器|unknown|0.05|0.05|0.90|0.90|0.84]]\n"
                        + "[[scene-marker:DDC的485+和485-端子|normal|0.12|0.62|0.30|0.12|0.90]]\n"
                        + "[[scene-marker:DDC 485通讯灯|normal|0.48|0.30|0.12|0.12|0.88]]",
                "honeywell-ddc", "rs485-terminal", "rs485-led");
        assertEvidence(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485,
                "看这里",
                "[[scene-marker:霍尼韦尔网关|unknown|0.10|0.10|0.75|0.80|0.84]]\n"
                        + "[[scene-marker:COM1 COM2通讯指示灯|normal|0.62|0.20|0.12|0.22|0.88]]\n"
                        + "[[scene-marker:RJ45网络口通讯灯|normal|0.62|0.70|0.12|0.12|0.90]]",
                "honeywell-gateway", "gateway-rs485-led", "network-port", "link-led");
        assertEvidence(HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK,
                "这个位置",
                "[[scene-marker:霍尼韦尔网关|unknown|0.10|0.10|0.75|0.80|0.86]]\n"
                        + "[[scene-marker:RJ45网络口|normal|0.18|0.65|0.20|0.15|0.92]]\n"
                        + "[[scene-marker:网线插头|normal|0.38|0.66|0.22|0.14|0.90]]\n"
                        + "[[scene-marker:网络Link灯|normal|0.62|0.58|0.12|0.12|0.88]]",
                "honeywell-gateway", "network-port", "network-cable", "link-led");
        assertEvidence(HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE,
                "这个传感器",
                "[[scene-marker:霍尼韦尔温湿度传感器|unknown|0.20|0.10|0.55|0.70|0.90]]\n"
                        + "[[scene-marker:传感器线缆入口|normal|0.38|0.70|0.20|0.12|0.88]]",
                "temp-humidity-sensor", "sensor-cable-entry");
        assertEvidence(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING,
                "已经打开了",
                "[[scene-marker:传感器接线端子|unknown|0.18|0.28|0.50|0.25|0.91]]\n"
                        + "[[scene-marker:接入端子的信号线|suspect|0.25|0.52|0.42|0.18|0.89]]",
                "terminal", "signal-wire");
        assertEvidence(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY,
                "现在恢复了",
                "[[scene-marker:监控平台|normal|0.05|0.05|0.90|0.90|0.90]]\n"
                        + "[[scene-marker:温湿度恢复区域|normal|0.15|0.30|0.30|0.20|0.93]]\n"
                        + "[[scene-marker:页面刷新时间|normal|0.60|0.12|0.20|0.10|0.88]]",
                "platform", "temperature-normal", "refresh-time");
    }

    @Test
    public void shortSpokenMeasurementsAreReconciledByTheCurrentStep() {
        TaskSession ddcTask = taskAt(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT);
        Set<String> normalVoltage = SceneSkillAiBridge.reconcileEvidence(
                ddcTask, "24伏，稳定", SceneSkillAiBridge.parse(""), false);
        assertTrue(normalVoltage.contains("meter-reading"));
        assertTrue(normalVoltage.contains("probe-point"));
        assertTrue(normalVoltage.contains("voltage-in-range"));

        Set<String> lowVoltage = SceneSkillAiBridge.reconcileEvidence(
                ddcTask, "16伏，不稳定", SceneSkillAiBridge.parse(""), false);
        assertTrue(lowVoltage.contains("voltage-low"));
        assertTrue(lowVoltage.contains("voltage-unstable"));

        TaskSession sensorTask = taskAt(HoneywellTempHumiditySkill.STEP_METER_CHECK);
        Set<String> wiringFault = SceneSkillAiBridge.reconcileEvidence(
                sensorTask, "测得16伏，接线不正常", SceneSkillAiBridge.parse(""), false);
        assertTrue(wiringFault.contains("meter-reading"));
        assertTrue(wiringFault.contains("probe-point"));
        assertTrue(wiringFault.contains("wiring-anomaly"));

        Set<String> vague = SceneSkillAiBridge.reconcileEvidence(
                sensorTask, "量过了", SceneSkillAiBridge.parse(""), false);
        assertFalse(vague.contains("wiring-anomaly"));
    }

    @Test
    public void explicitInsufficientEvidenceCannotBeRecoveredFromMarkers() {
        TaskSession task = taskAt(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT);
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "[[scene-marker:霍尼韦尔DDC|unknown|0.10|0.10|0.20|0.20|0.95]]\n"
                        + "[[scene-marker:霍尼韦尔网关|unknown|0.40|0.10|0.20|0.20|0.95]]\n"
                        + "[[scene-evidence:insufficient]]");

        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, "这是架构图", parsed, true);

        assertTrue(evidence.contains("insufficient"));
        assertFalse(evidence.contains("system-context"));
    }

    @Test
    public void candidateProtocolRequiresRealPlatformEvidenceAndStaysInvisible() {
        String instruction = SceneSkillAiBridge.candidateInstruction();
        assertTrue(instruction.contains("实际照片"));
        assertTrue(instruction.contains("temperature-missing"));
        assertTrue(instruction.contains("other-data-normal"));
        assertTrue(instruction.contains("必须包含 platform 标签"));
        assertTrue(instruction.contains("右侧报警列表"));
        assertTrue(instruction.contains("不要框选下方机房环境检测区"));
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

    private static TaskSession taskAt(String step) {
        TaskSession task = new TaskSessionManager().startNew("project-reconcile", "温湿度异常");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID, step);
        return task;
    }

    private static void assertEvidence(String step, String narration, String response,
            String... expectedTags) {
        TaskSession task = taskAt(step);
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(response);
        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, narration, parsed, true);
        for (String expected : expectedTags) {
            assertTrue("Missing " + expected + " at " + step, evidence.contains(expected));
        }
    }
}
