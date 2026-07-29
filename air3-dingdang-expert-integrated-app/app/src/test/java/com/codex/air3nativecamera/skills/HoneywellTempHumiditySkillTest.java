package com.codex.air3nativecamera.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class HoneywellTempHumiditySkillTest {
    private final HoneywellTempHumiditySkill skill = new HoneywellTempHumiditySkill();

    @Test
    public void naturalLabPhotoDescriptionActivatesOnlyTheCurrentTask() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "现场照片待描述");

        assertTrue(skill.tryActivate(task,
                "实训室平台温湿度没有数据，其他数据正常", true));
        assertEquals(HoneywellTempHumiditySkill.SKILL_ID, task.sceneSkillId());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY, task.sceneStepId());

        TaskSession ordinary = new TaskSessionManager().startNew("project-2", "交换机端口异常");
        assertEquals("", ordinary.sceneSkillId());
    }

    @Test
    public void shortPlatformAlarmPromptActivatesTheEnabledDemoTask() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "现场照片待描述");

        assertTrue(skill.tryActivate(task, "平台报警，看看怎么回事", true));
        assertEquals(HoneywellTempHumiditySkill.SKILL_ID, task.sceneSkillId());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY, task.sceneStepId());
    }

    @Test
    public void candidateRequiresANewPhotoAndAiConfirmation() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "平台报警");

        assertTrue(skill.isCandidateTurn(task, "平台报警，看看怎么回事", true));
        assertFalse(skill.isCandidateTurn(task, "平台报警，看看怎么回事", false));
        assertFalse(skill.activateCandidate(task, "none"));
        assertTrue(skill.activateCandidate(task, HoneywellTempHumiditySkill.SKILL_ID));
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY, task.sceneStepId());
    }

    @Test
    public void ordinaryTaskNeverTriggersThePrivateWorkflow() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "机房温度偏高");

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "霍尼韦尔温湿度没有数据", tags("platform", "temperature-missing"));

        assertFalse(result.matched());
        assertEquals("", task.sceneSkillId());
    }

    @Test
    public void platformPhotoAdvancesWithEitherRealEvidenceOrTheReferenceFallback() {
        TaskSession task = preparedTask();

        HoneywellTempHumiditySkill.Result lowResolutionButComplete = skill.evaluate(task,
                "平台其他数据正常，只有温湿度没有数据",
                tags("platform", "temperature-missing", "other-data-normal"), true, false);
        assertTrue(lowResolutionButComplete.matched());
        assertTrue(lowResolutionButComplete.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT, task.sceneStepId());

        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY);
        HoneywellTempHumiditySkill.Result incomplete = skill.evaluate(task,
                "平台页面有报警",
                tags("platform"), true, false);
        assertTrue(incomplete.matched());
        assertTrue(incomplete.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT, task.sceneStepId());
        assertTrue(incomplete.reply().contains("下一步"));

        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY);
        HoneywellTempHumiditySkill.Result accepted = skill.evaluate(task,
                "平台其他数据正常，只有温湿度没有数据",
                tags("platform", "temperature-missing", "other-data-normal"), true, true);
        assertTrue(accepted.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT, task.sceneStepId());
        assertTrue(accepted.reply().contains("架构图"));
    }

    @Test
    public void completeLowResolutionDeviceEvidenceAdvancesWithoutInventingAFieldMarker() {
        TaskSession task = atDdcPowerPhoto();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "拍好了",
                tags("honeywell-ddc", "power-input"), true, false);

        assertTrue(result.matched());
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                task.sceneStepId());
    }

    @Test
    public void architectureDiagramAdvancesOnConfirmedContextWithoutInventingDevices() {
        TaskSession task = atSystemContext();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "是现场设备架构图，请帮我分析设备的连接关系。",
                tags("system-context"), true, true);

        assertTrue(result.matched());
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO, task.sceneStepId());
        assertTrue(result.reply().contains("工控 DDC"));
        assertTrue(result.reply().contains("电源输入位置"));
        assertFalse(result.reply().contains("已确认霍尼韦尔"));
    }

    @Test
    public void clearSpokenSystemDescriptionCanAdvanceWithoutAnotherPhoto() {
        TaskSession task = atSystemContext();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "现场是平台通过网络连接网关，网关通过485连接工控DDC，DDC连接温湿度传感器",
                tags("system-context", "platform", "honeywell-gateway", "honeywell-ddc",
                        "temp-humidity-sensor"), false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO, task.sceneStepId());
    }

    @Test
    public void vagueContextPhotoAdvancesInTheBoundDemoTask() {
        TaskSession task = atSystemContext();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "你看看这个图", tags(), true, true);

        assertTrue(result.matched());
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO, task.sceneStepId());
        assertTrue(result.reply().contains("下一步"));
    }

    @Test
    public void everyPhotoTurnAdvancesOneStepWhenEvidenceIsMissing() {
        TaskSession task = atDdcPowerPhoto();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "拍好了", tags(), true, false);

        assertTrue(result.matched());
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT, task.sceneStepId());
        assertTrue(result.reply().contains("DDC"));
        assertTrue(result.reply().contains("下一步"));
    }

    @Test
    public void unrelatedModelTagCannotBlockABoundDemoPhotoStep() {
        TaskSession task = atDdcPowerPhoto();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "拍好了", tags("unrelated", "insufficient"), true, false);

        assertTrue(result.matched());
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT, task.sceneStepId());
        assertTrue(result.reply().contains("DDC"));
    }

    @Test
    public void ddcPowerPhotoOnlyIdentifiesTheMeasurementPoint() {
        TaskSession task = atDdcPowerPhoto();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "这是霍尼韦尔工控DDC的铭牌和电源输入端",
                tags("honeywell-ddc", "power-input"),
                true, true);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT, task.sceneStepId());
        assertTrue(result.reply().contains("万用表"));
        assertTrue(result.reply().contains("口述"));
        assertFalse(result.reply().contains("供电正常"));
    }

    @Test
    public void ddcPowerMeasurementCanBeSpokenAndLowVoltageDoesNotAdvance() {
        TaskSession task = atDdcPowerMeasurement();
        assertTrue(skill.shouldHandleTurn(task,
                "额定24伏，DDC输入端实测16伏，电压偏低", false));

        HoneywellTempHumiditySkill.Result low = skill.evaluate(task,
                "额定24伏，DDC输入端实测16伏，电压偏低",
                tags("honeywell-ddc", "meter-reading", "probe-point", "voltage-low"),
                false, false);

        assertTrue(low.matched());
        assertFalse(low.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                task.sceneStepId());
        assertTrue(low.reply().contains("不能推进"));
    }

    @Test
    public void normalDdcVoltageAdvancesToDdcRs485() {
        TaskSession task = atDdcPowerMeasurement();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "额定24伏，DDC的24VAC和COM之间实测24.1伏且稳定",
                tags("honeywell-ddc", "rated-voltage", "meter", "meter-reading", "probe-point",
                        "voltage-in-range"), false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_RS485, task.sceneStepId());
        assertTrue(result.reply().contains("DDC"));
        assertTrue(result.reply().contains("485"));
    }

    @Test
    public void oneGatewayPhotoChecksRs485AndNetworkTogether() {
        TaskSession task = atDdcRs485();

        acceptPhoto(task, "DDC的485端子和状态灯",
                "honeywell-ddc", "rs485-terminal", "rs485-led");
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, task.sceneStepId());

        acceptPhoto(task, "网关上方485灯和下方网络口都在这张照片里",
                "honeywell-gateway", "gateway-rs485-led", "network-port", "link-led");
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE, task.sceneStepId());
    }

    @Test
    public void ddcRs485ReusesTheDdcIdentityConfirmedByThePreviousStep() {
        TaskSession task = atDdcRs485();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "看下485", tags("rs485-terminal", "rs485-led"), true, true);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, task.sceneStepId());
    }

    @Test
    public void gatewayNetworkIsCheckedOnlyAfterRs485() {
        TaskSession task = atGatewayNetwork();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "这是网关下方网络接口和网络状态灯",
                tags("honeywell-gateway", "network-port", "network-cable", "link-led"),
                true, true);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE, task.sceneStepId());
        assertTrue(result.reply().contains("温湿度传感器"));
    }

    @Test
    public void sensorPhotoThenSpokenRepairAdvancesToPlatformRecovery() {
        TaskSession task = atSensorDevice();

        acceptPhoto(task, "这是霍尼韦尔温湿度传感器本体",
                "temp-humidity-sensor", "sensor-cable-entry");
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, task.sceneStepId());

        assertTrue(skill.shouldHandleTurn(task,
                "接线点虚接，已经接好，现在电压正常", false));
        acceptSpoken(task, "接线点虚接，已经接好，现在电压正常",
                "terminal", "wiring-anomaly=loose", "voltage-in-range");
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
    }

    @Test
    public void sensorWiringPhotoAloneCannotClaimTheRepairIsComplete() {
        TaskSession task = atSensorWiring();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "请检查里面的接线是否正常",
                tags("temp-humidity-sensor", "sensor-cable-entry", "insufficient"),
                true, true);

        assertTrue(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, task.sceneStepId());
    }

    @Test
    public void spokenRepairNeedsBothFaultAndNormalVoltage() {
        TaskSession task = atSensorWiring();

        HoneywellTempHumiditySkill.Result incomplete = skill.evaluate(task,
                "发现接线点虚接，已经重新接好",
                tags("terminal", "wiring-anomaly=loose"), false, false);

        assertFalse(incomplete.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, task.sceneStepId());
    }

    @Test
    public void sensorMeterResultCanBeSpokenButNeedsAnActualAnomaly() {
        TaskSession task = atMeterCheck();
        assertTrue(skill.shouldHandleTurn(task,
                "传感器供电端实测16伏，接线电压异常", false));

        HoneywellTempHumiditySkill.Result incomplete = skill.evaluate(task,
                "已经测量了", tags("meter-reading", "probe-point"), false, false);
        assertFalse(incomplete.accepted());
        assertFalse(incomplete.reply().contains("最终根因"));

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "传感器供电端额定24伏，实测16伏且稳定，确认接线异常",
                tags("terminal", "meter", "meter-reading", "probe-point",
                        "wiring-anomaly"), false, false);
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
        assertTrue(result.reply().contains("测量结果支持"));
        assertTrue(result.reply().contains("下一步"));
    }

    @Test
    public void parameterizedEvidenceTagsAdvanceTheLegacyMeterStep() {
        TaskSession task = atMeterCheck();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "指定端子实测0毫安，确认接线松动",
                tags("terminal", "meter-reading=0ma", "probe-point=指定端子",
                        "wiring-anomaly=松动"), false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
    }

    @Test
    public void spokenPlatformRecoveryCompletesAndReleasesTheTask() {
        TaskSession task = fullWorkflowAtRecovery(new TaskSessionManager());

        assertTrue(skill.shouldHandleTurn(task, "平台数据已恢复", false));
        HoneywellTempHumiditySkill.Result completed = skill.evaluate(task,
                "平台数据已恢复", tags("platform", "temperature-normal"), false, false);

        assertTrue(completed.accepted());
        assertEquals("", task.sceneSkillId());
        assertEquals("", task.sceneStepId());
    }

    @Test
    public void completedWorkflowReleasesTheSkillAndLeavesTheNextTaskOrdinary() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = fullWorkflowAtRecovery(manager);

        HoneywellTempHumiditySkill.Result completed = skill.evaluate(task,
                "平台温湿度数据已经恢复正常",
                tags("platform", "temperature-normal"), false, false);

        assertTrue(completed.accepted());
        assertEquals("", task.sceneSkillId());
        assertEquals("", task.sceneStepId());
        assertEquals("排查完成", task.maintenanceTask().facts().get("工单排查阶段"));

        TaskSession ordinary = manager.startNew("project-ordinary", "服务器无法启动");
        assertEquals("", ordinary.sceneSkillId());
        assertEquals("", SceneSkillAiBridge.instruction(ordinary));
    }

    @Test
    public void legacyPersistedGatewayPowerStepMigratesToDdcPowerPhoto() {
        TaskSession task = preparedTask();
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.LEGACY_STEP_GATEWAY_POWER);

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "这是霍尼韦尔工控DDC和电源端",
                tags("honeywell-ddc", "power-input", "rated-voltage", "probe-point"),
                true, true);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                task.sceneStepId());
    }

    private TaskSession fullWorkflowAtRecovery(TaskSessionManager manager) {
        TaskSession task = manager.startNew("project-full", "实训室温湿度采集异常排查");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY);
        acceptPhoto(task, "平台温湿度缺失，其他数据正常",
                "platform", "temperature-missing", "other-data-normal");
        acceptPhoto(task, "这是现场架构图和连接关系", "system-context");
        acceptPhoto(task, "DDC铭牌与电源输入", "honeywell-ddc", "power-input",
                "rated-voltage", "probe-point");
        acceptSpoken(task, "DDC额定24伏，输入端实测24伏且稳定", "honeywell-ddc",
                "rated-voltage", "meter", "meter-reading", "probe-point", "voltage-in-range");
        acceptPhoto(task, "DDC 485端子与状态灯", "honeywell-ddc", "rs485-terminal",
                "rs485-led");
        acceptPhoto(task, "网关485与网络接口", "honeywell-gateway",
                "gateway-rs485-led", "network-port", "link-led");
        acceptPhoto(task, "温湿度传感器", "temp-humidity-sensor", "sensor-cable-entry");
        acceptSpoken(task, "接线点虚接，已经接好，现在电压正常", "terminal",
                "wiring-anomaly", "voltage-in-range");
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
        return task;
    }

    private TaskSession atSystemContext() {
        TaskSession task = preparedTask();
        acceptPhoto(task, "平台温湿度异常，其他数据正常",
                "platform", "temperature-missing", "other-data-normal");
        return task;
    }

    private TaskSession atDdcPowerPhoto() {
        TaskSession task = atSystemContext();
        acceptPhoto(task, "这是现场架构图和连接关系", "system-context");
        return task;
    }

    private TaskSession atDdcPowerMeasurement() {
        TaskSession task = atDdcPowerPhoto();
        acceptPhoto(task, "DDC铭牌和电源输入端", "honeywell-ddc", "power-input",
                "rated-voltage", "probe-point");
        return task;
    }

    private TaskSession atDdcRs485() {
        TaskSession task = atDdcPowerMeasurement();
        acceptSpoken(task, "DDC额定24伏，输入端实测24伏且稳定", "honeywell-ddc",
                "rated-voltage", "meter", "meter-reading", "probe-point", "voltage-in-range");
        return task;
    }

    private TaskSession atGatewayNetwork() {
        TaskSession task = atDdcRs485();
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK);
        return task;
    }

    private TaskSession atSensorDevice() {
        TaskSession task = atDdcRs485();
        acceptPhoto(task, "DDC 485端子与状态灯", "honeywell-ddc", "rs485-terminal",
                "rs485-led");
        acceptPhoto(task, "网关485与网络接口", "honeywell-gateway",
                "gateway-rs485-led", "network-port", "link-led");
        return task;
    }

    private TaskSession atMeterCheck() {
        TaskSession task = atSensorWiring();
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_METER_CHECK);
        return task;
    }

    private TaskSession atSensorWiring() {
        TaskSession task = atSensorDevice();
        acceptPhoto(task, "温湿度传感器本体", "temp-humidity-sensor",
                "sensor-cable-entry");
        return task;
    }

    private TaskSession preparedTask() {
        TaskSession task = new TaskSessionManager().startNew(
                "project-honeywell", "实训室温湿度采集异常排查");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY);
        return task;
    }

    private void acceptPhoto(TaskSession task, String narration, String... evidence) {
        HoneywellTempHumiditySkill.Result result = skill.evaluate(
                task, narration, tags(evidence), true, true);
        assertTrue("Expected photo evidence to advance from " + task.sceneStepId(),
                result.accepted());
        if (HoneywellTempHumiditySkill.SKILL_ID.equals(task.sceneSkillId())) {
            assertTrue("Intermediate reply must expose the next action", result.reply().contains("下一步"));
            assertFalse(result.reply().contains("演示"));
            assertFalse(result.reply().contains("预设"));
        }
    }

    private void acceptSpoken(TaskSession task, String narration, String... evidence) {
        HoneywellTempHumiditySkill.Result result = skill.evaluate(
                task, narration, tags(evidence), false, false);
        assertTrue("Expected spoken evidence to advance from " + task.sceneStepId(),
                result.accepted());
        if (HoneywellTempHumiditySkill.SKILL_ID.equals(task.sceneSkillId())) {
            assertTrue("Intermediate reply must expose the next action", result.reply().contains("下一步"));
            assertFalse(result.reply().contains("演示"));
            assertFalse(result.reply().contains("预设"));
        }
    }

    private static Set<String> tags(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
