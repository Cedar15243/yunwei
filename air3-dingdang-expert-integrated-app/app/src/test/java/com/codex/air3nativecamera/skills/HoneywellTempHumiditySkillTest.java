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
    public void unrelatedPhotoDoesNotAdvanceABoundDemoStep() {
        TaskSession task = atDdcPowerPhoto();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "拍好了", tags("unrelated", "insufficient"), true, false);

        assertTrue(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO, task.sceneStepId());
        assertTrue(result.reply().contains("检查状态：无法判断"));
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
        assertTrue(result.reply().contains("检查状态：无法判断"));
        assertTrue(result.reply().contains("10号 24VAC"));
        assertTrue(result.reply().contains("11号 COM"));
        assertTrue(result.reply().contains("万用表"));
        assertTrue(result.reply().contains("10 / 11 / 12"));
        assertTrue(result.reply().contains("黑表笔"));
        assertTrue(result.reply().contains("红表笔"));
        assertTrue(result.reply().contains("12号 E-GND"));
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
        assertTrue(low.reply().contains("检查状态：异常"));
        assertTrue(low.reply().contains("暂不进入下一步"));
    }

    @Test
    public void normalDdcVoltageAdvancesToSpokenGatewayLightCheck() {
        TaskSession task = atDdcPowerMeasurement();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "额定24伏，DDC的24VAC和COM之间实测24.1伏且稳定",
                tags("honeywell-ddc", "rated-voltage", "meter", "meter-reading", "probe-point",
                        "voltage-in-range"), false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, task.sceneStepId());
        assertTrue(result.reply().contains("检查状态：正常"));
        assertTrue(result.reply().contains("485通讯灯"));
        assertTrue(result.reply().contains("网络通讯灯"));
        assertTrue(result.reply().contains("不需要拍照"));
    }

    @Test
    public void chineseTwentySixVoltReportAdvancesToTheFixedDoubleLightQuestion() {
        TaskSession task = atDdcPowerMeasurement();
        String report = "刚才测量十号和十一号接口有二十六伏电压，读数稳定";

        assertTrue(skill.shouldHandleTurn(task, report, false));
        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, report, SceneSkillAiBridge.parse(""), false);
        HoneywellTempHumiditySkill.Result result = skill.evaluate(
                task, report, evidence, false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, task.sceneStepId());
        assertTrue(result.reply().contains("485通讯灯"));
        assertTrue(result.reply().contains("网络通讯灯"));
        assertTrue(result.reply().contains("不亮、常亮还是闪烁"));
        assertTrue(result.reply().contains("21.6-26.4VAC"));
        assertTrue(result.reply().contains("26V"));
        assertFalse(result.reply().contains("同步"));
        assertFalse(result.reply().contains("每秒"));
    }

    @Test
    public void outOfSequenceDdcLightReportRepeatsTheVoltageMeasurement() {
        TaskSession task = atDdcPowerMeasurement();
        String report = "DDC运行指示灯状态正常";

        assertTrue(skill.shouldHandleTurn(task, report, false));
        HoneywellTempHumiditySkill.Result result = skill.evaluate(
                task, report, tags(), false, false);

        assertTrue(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                task.sceneStepId());
        assertTrue(result.reply().contains("10号"));
        assertTrue(result.reply().contains("11号"));
        assertTrue(result.reply().contains("实测电压"));
    }

    @Test
    public void ddcTerminalNumbersWithoutVoltageStayOnTheMeasurementStep() {
        TaskSession task = atDdcPowerMeasurement();
        String report = "已经测量10号和11号端子，读数稳定";

        assertTrue(skill.shouldHandleTurn(task, report, false));
        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, report, SceneSkillAiBridge.parse(""), false);
        HoneywellTempHumiditySkill.Result result = skill.evaluate(
                task, report, evidence, false, false);

        assertTrue(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                task.sceneStepId());
        assertTrue(result.reply().contains("实测电压"));
    }

    @Test
    public void bothGatewayLightsMustBeReportedAsBlinkingBeforeAdvancing() {
        TaskSession task = atGatewayRs485();

        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(task,
                "上面的485灯一闪一闪，下面网口灯也在闪",
                SceneSkillAiBridge.parse(""), false);
        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "上面的485灯一闪一闪，下面网口灯也在闪",
                evidence, false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE, task.sceneStepId());
        assertTrue(result.reply().contains("485通讯状态：正常"));
        assertTrue(result.reply().contains("网络通讯状态：正常"));
    }

    @Test
    public void gatewayPhotoWithModelBlinkingTagsStillRequiresSpokenStates() {
        TaskSession task = atGatewayRs485();
        SceneSkillAiBridge.ParsedResponse parsed = SceneSkillAiBridge.parse(
                "两个灯闪烁。\n"
                        + "[[scene-evidence:gateway-rs485-blinking,network-link-blinking]]");
        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, "这是网关指示灯照片", parsed, true);

        HoneywellTempHumiditySkill.Result result = skill.evaluate(
                task, "这是网关指示灯照片", evidence, true, false);

        assertTrue(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, task.sceneStepId());
        assertTrue(result.reply().contains("不亮、常亮还是闪烁"));
    }

    @Test
    public void solidOrDarkGatewayLightBlocksTheWorkflow() {
        String[] reports = {
                "485灯常亮，网络灯在闪",
                "485没亮，网口灯闪烁",
                "上面一直亮，下面也在闪"
        };
        for (String report : reports) {
            TaskSession task = atGatewayRs485();
            Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                    task, report, SceneSkillAiBridge.parse(""), false);
            HoneywellTempHumiditySkill.Result result = skill.evaluate(
                    task, report, evidence, false, false);

            assertTrue(report, result.matched());
            assertFalse(report, result.accepted());
            assertEquals(report, HoneywellTempHumiditySkill.STEP_GATEWAY_RS485,
                    task.sceneStepId());
            assertTrue(report, result.reply().contains("检查状态：异常"));
        }
    }

    @Test
    public void gatewayNetworkIsCheckedOnlyAfterRs485() {
        TaskSession task = atGatewayNetwork();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "485灯闪烁，网络灯也闪烁",
                tags("gateway-rs485-blinking", "network-link-blinking"),
                false, false);

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
        assertTrue(skill.evaluate(atSensorDevice(), "这是传感器",
                tags("temp-humidity-sensor", "sensor-cable-entry"), true, true)
                .reply().contains("电压是否正常"));

        assertTrue(skill.shouldHandleTurn(task,
                "端子有点松，我拧紧了，测了十二伏，很稳", false));
        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(task,
                "端子有点松，我拧紧了，测了十二伏，很稳",
                SceneSkillAiBridge.parse(""), false);
        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "端子有点松，我拧紧了，测了十二伏，很稳",
                evidence, false, false);
        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
        assertTrue(result.reply().contains("原检查状态：异常"));
        assertTrue(result.reply().contains("当前检查状态：正常"));
    }

    @Test
    public void lowSensorVoltageBlocksEvenAfterTheTerminalWasTightened() {
        TaskSession task = atSensorWiring();
        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(task,
                "线松了已经接好，不过现在只有9伏",
                SceneSkillAiBridge.parse(""), false);

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "线松了已经接好，不过现在只有9伏", evidence, false, false);

        assertTrue(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, task.sceneStepId());
        assertTrue(result.reply().contains("检查状态：异常"));
        assertTrue(result.reply().contains("12V"));
    }

    @Test
    public void restoredLoosePowerConnectionAtElevenPointEightVoltsAdvancesToPlatformRefresh() {
        TaskSession task = atSensorWiring();
        String report = "因为电源虚接，现在已恢复，电压11.8伏，读数稳定";

        assertTrue(skill.shouldHandleTurn(task, report, false));
        Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                task, report, SceneSkillAiBridge.parse(""), false);
        HoneywellTempHumiditySkill.Result result = skill.evaluate(
                task, report, evidence, false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
        assertTrue(result.reply().contains("接线"));
        assertTrue(result.reply().contains("11.8V"));
        assertTrue(result.reply().contains("10.8-13.2V"));
        assertTrue(result.reply().contains("刷新平台"));
    }

    @Test
    public void naturalSensorWiringOutcomesAdvanceWithoutRepeatingTheTwelveVoltCheck() {
        String[] reports = {
                "接线接触不良",
                "原来接线虚接，现在已经恢复",
                "接线正常，电压正常",
                "12V正常",
                "十二伏正常"
        };

        for (String report : reports) {
            TaskSession task = atSensorWiring();
            assertTrue(report, skill.shouldHandleTurn(task, report, false));
            Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                    task, report, SceneSkillAiBridge.parse("[[scene-evidence:insufficient]]"), false);
            HoneywellTempHumiditySkill.Result result = skill.evaluate(
                    task, report, evidence, false, false);

            assertTrue(report, result.accepted());
            assertEquals(report, HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY,
                    task.sceneStepId());
            assertTrue(report, result.reply().contains("刷新平台"));
            assertFalse(report, result.reply().contains("重新测量"));
        }
    }

    @Test
    public void sensorPhotoPromptTellsTheEngineerToOpenTheCoverBeforeCheckingWiring() {
        TaskSession task = atSensorDevice();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "这是温湿度传感器",
                tags("temp-humidity-sensor", "sensor-cable-entry"), true, true);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, task.sceneStepId());
        assertTrue(result.reply().contains("打开"));
        assertTrue(result.reply().contains("盖"));
        assertTrue(result.reply().contains("接线"));
        assertTrue(result.reply().contains("电压"));
    }

    @Test
    public void questionsAndSideQuestionsDoNotEnterTheFixedWorkflowTemplate() {
        TaskSession measurement = atDdcPowerMeasurement();
        assertFalse(skill.shouldHandleTurn(measurement, "为什么要测10号和11号？", false));
        assertFalse(skill.shouldHandleTurn(measurement, "这个DDC是做什么用的", false));
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                measurement.sceneStepId());

        TaskSession sensor = atSensorWiring();
        assertFalse(skill.shouldHandleTurn(sensor, "万用表应该调到什么档位？", false));
        assertFalse(skill.shouldHandleTurn(sensor, "今天星期几", false));
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, sensor.sceneStepId());
    }

    @Test
    public void unrelatedStatementsDoNotEnterTheFixedWorkflowTemplate() {
        TaskSession measurement = atDdcPowerMeasurement();
        assertFalse(skill.shouldHandleTurn(measurement,
                "这套系统已经连续24小时运行正常", false));
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                measurement.sceneStepId());

        TaskSession lights = atGatewayRs485();
        assertFalse(skill.shouldHandleTurn(lights, "这个网络协议我不了解", false));
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, lights.sceneStepId());

        TaskSession sensor = atSensorWiring();
        assertFalse(skill.shouldHandleTurn(sensor, "服务器电源是12伏，运行正常", false));
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, sensor.sceneStepId());

        TaskSession recovery = fullWorkflowAtRecovery(new TaskSessionManager());
        assertFalse(skill.shouldHandleTurn(recovery, "网络连接已经恢复正常", false));
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY,
                recovery.sceneStepId());
    }

    @Test
    public void shortNaturalFieldReportsAreAcceptedWithoutOneScriptedSentence() {
        String[] ddcReports = {"24伏，挺稳的", "二十四伏，没跳", "测出来24V正常", "测了24，读数稳定"};
        for (String report : ddcReports) {
            TaskSession task = atDdcPowerMeasurement();
            assertTrue(report, skill.shouldHandleTurn(task, report, false));
            Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                    task, report, SceneSkillAiBridge.parse(""), false);
            assertTrue(report, skill.evaluate(task, report, evidence, false, false).accepted());
        }

        String[] lightReports = {"两个都闪", "485和网口都是闪烁", "上面闪，下面也闪"};
        for (String report : lightReports) {
            TaskSession task = atGatewayRs485();
            assertTrue(report, skill.shouldHandleTurn(task, report, false));
            Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                    task, report, SceneSkillAiBridge.parse(""), false);
            assertTrue(report, skill.evaluate(task, report, evidence, false, false).accepted());
        }
    }

    @Test
    public void aQuestionContainingAReadingStillDoesNotAdvanceTheStep() {
        TaskSession task = atDdcPowerMeasurement();

        assertFalse(skill.shouldHandleTurn(task, "我测的是24伏，为什么还要看稳定性？", false));
        assertEquals(HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT, task.sceneStepId());
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
    public void spokenResolvedWiringAdvancesWithoutRepeatingTheVoltageCheck() {
        TaskSession task = atSensorWiring();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "发现接线点虚接，已经重新接好",
                tags("terminal", "wiring-anomaly=loose", "wiring-resolved"), false, false);

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
        assertTrue(result.reply().contains("刷新平台"));
        assertFalse(result.reply().contains("重新测量"));
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
        assertEquals(com.codex.air3nativecamera.task.MaintenanceTask.Phase.COMPLETED,
                task.maintenanceTask().phase());
        assertTrue(completed.reply().contains("维修记录已生成"));
        assertTrue(completed.reply().contains("任务历史"));
        assertTrue(completed.reply().contains("知识库"));
        assertTrue(completed.reply().contains("已保存至华方知识库"));
        assertEquals("", task.sceneSkillId());
        assertEquals("", task.sceneStepId());
    }

    @Test
    public void naturalPlatformRecoveryReportsCompleteWithoutAScriptedSentence() {
        String[] reports = {"数据回来了，报警没了", "页面正常了"};
        for (String report : reports) {
            TaskSession task = fullWorkflowAtRecovery(new TaskSessionManager());
            assertTrue(report, skill.shouldHandleTurn(task, report, false));
            Set<String> evidence = SceneSkillAiBridge.reconcileEvidence(
                    task, report, SceneSkillAiBridge.parse(""), false);
            HoneywellTempHumiditySkill.Result result = skill.evaluate(
                    task, report, evidence, false, false);

            assertTrue(report, result.accepted());
            assertEquals(report, "", task.sceneSkillId());
            assertEquals(report, "", task.sceneStepId());
        }
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
        acceptSpoken(task, "485灯闪烁，网络灯也闪烁",
                "gateway-rs485-blinking", "network-link-blinking");
        acceptPhoto(task, "温湿度传感器", "temp-humidity-sensor", "sensor-cable-entry");
        acceptSpoken(task, "接线点虚接，已经接好，现在测量12伏且稳定", "terminal",
                "wiring-anomaly", "wiring-resolved", "meter-reading", "probe-point",
                "voltage-in-range");
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

    private TaskSession atGatewayRs485() {
        TaskSession task = atDdcPowerMeasurement();
        acceptSpoken(task, "输入端测得24伏，读数稳定", "honeywell-ddc",
                "rated-voltage", "meter-reading", "probe-point", "voltage-in-range");
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, task.sceneStepId());
        return task;
    }

    private TaskSession atGatewayNetwork() {
        TaskSession task = atDdcRs485();
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK);
        return task;
    }

    private TaskSession atSensorDevice() {
        TaskSession task = atGatewayRs485();
        acceptSpoken(task, "485灯闪，网络灯也闪",
                "gateway-rs485-blinking", "network-link-blinking");
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
