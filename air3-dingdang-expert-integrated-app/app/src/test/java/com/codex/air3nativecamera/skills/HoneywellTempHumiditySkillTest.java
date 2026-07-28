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
    }

    @Test
    public void simplePlatformAlarmPhraseOnlyCreatesAnAiVerifiedCandidate() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "现场照片待描述");

        assertTrue(skill.isCandidateTurn(task, "平台报警，看看怎么回事", true));
        assertTrue(skill.isCandidateTurn(task,
                "这个是平台页面，现场出现报警，看看怎么回事", true));
        assertFalse(skill.isCandidateTurn(task, "平台报警，看看怎么回事", false));
        assertEquals("", task.sceneSkillId());
    }

    @Test
    public void freshPhotoUsesAiSemanticCandidateEvenWhenOperatorWordingIsUnexpected() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "现场照片待描述");

        assertTrue(skill.isCandidateTurn(task, "这个页面看起来不太对，你帮我看一下", true));
        assertFalse(skill.isCandidateTurn(task, "这个页面看起来不太对，你帮我看一下", false));
        assertEquals("", task.sceneSkillId());
    }

    @Test
    public void onlyTheAiConfirmedCandidateCanBindThePrivateWorkflow() {
        TaskSession rejected = new TaskSessionManager().startNew("project-1", "平台报警");
        TaskSession accepted = new TaskSessionManager().startNew("project-2", "平台报警");

        assertFalse(skill.activateCandidate(rejected, "none"));
        assertTrue(skill.activateCandidate(accepted, HoneywellTempHumiditySkill.SKILL_ID));
        assertEquals("", rejected.sceneSkillId());
        assertEquals(HoneywellTempHumiditySkill.SKILL_ID, accepted.sceneSkillId());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY, accepted.sceneStepId());
    }

    @Test
    public void naturalTriggerRequiresBothTheSpecificSymptomAndAPhoto() {
        TaskSession noPhoto = new TaskSessionManager().startNew("project-1", "现场描述");
        TaskSession genericQuestion = new TaskSessionManager().startNew("project-2", "现场照片");

        assertFalse(skill.tryActivate(noPhoto,
                "实训室平台温湿度没有数据，其他数据正常", false));
        assertFalse(skill.tryActivate(genericQuestion,
                "机房温湿度偏高应该怎么处理", true));
        assertEquals("", noPhoto.sceneSkillId());
        assertEquals("", genericQuestion.sceneSkillId());
    }

    @Test
    public void aNewConversationDoesNotInheritTheActivatedSceneSkill() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession demonstration = manager.startNew("project-1", "现场照片待描述");
        assertTrue(skill.tryActivate(demonstration,
                "实验室平台温湿度无数据，其余数据正常", true));

        TaskSession ordinary = manager.startNew("project-2", "交换机端口异常");

        assertEquals(HoneywellTempHumiditySkill.SKILL_ID, demonstration.sceneSkillId());
        assertEquals("", ordinary.sceneSkillId());
    }

    @Test
    public void ordinaryTaskNeverTriggersTheInvestorDemoSkill() {
        TaskSession task = new TaskSessionManager().startNew("project-1", "机房温度偏高");

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "霍尼韦尔温湿度没有数据", tags("platform", "temperature-missing"));

        assertFalse(result.matched());
        assertEquals("", task.sceneSkillId());
    }

    @Test
    public void attemptedSceneStepStillRequiresMatchingPhotoEvidence() {
        TaskSession task = preparedTask();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "平台其他数据正常，只有温湿度没有数据",
                tags("server", "power-led"));

        assertTrue(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY, task.sceneStepId());
        assertTrue(result.reply().contains("平台"));
        assertTrue(result.reply().contains("补拍"));
    }

    @Test
    public void validEvidenceAdvancesExactlyOneStep() {
        TaskSession task = preparedTask();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "平台其他数据正常，只有温湿度没有数据",
                tags("platform", "temperature-missing", "other-data-normal", "refresh-time"));

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT, task.sceneStepId());
        assertTrue(result.reply().contains("局部"));
        assertTrue(result.reply().contains("架构图"));
        assertTrue(result.reply().contains("描述"));
        assertFalse(result.reply().contains("最终根因"));
    }

    @Test
    public void gatewayPowerLightAloneNeverAdvancesToNetwork() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "网关电源灯正常，请继续分析",
                tags("honeywell-gateway", "power-input", "power-led"));

        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_POWER, task.sceneStepId());
        assertTrue(result.reply().contains("万用表"));
        assertTrue(result.reply().contains("额定"));
    }

    @Test
    public void ratedVoltageAndNormalMeterReadingAdvanceToNetwork() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "已按额定值测量网关输入电压，读数正常",
                tags("honeywell-gateway", "power-input", "power-led", "rated-voltage",
                        "meter", "meter-reading", "probe-point", "voltage-in-range"));

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK, task.sceneStepId());
        assertTrue(result.reply().contains("额定值"));
    }

    @Test
    public void gatewayPowerMeasurementCanBeSpokenAfterThePowerPhoto() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");

        assertTrue(skill.shouldHandleTurn(task,
                "网关额定电压24伏，输入端实测16伏，电压偏低", false));
    }

    @Test
    public void LowGatewayVoltageBlocksEvenWhenPowerLightIsOn() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "电源灯亮但万用表测得输入电压偏低",
                tags("honeywell-gateway", "power-input", "power-led", "rated-voltage",
                        "meter", "meter-reading", "probe-point", "voltage-low"));

        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_POWER, task.sceneStepId());
        assertTrue(result.reply().contains("电压"));
        assertTrue(result.reply().contains("供电"));
    }

    @Test
    public void systemContextAcceptsNaturalDescriptionWithoutRequiringAnotherPhoto() {
        TaskSession task = preparedTask();
        accept(task, "平台这一块数据没上来", "platform", "temperature-missing", "other-data-normal");

        assertTrue(skill.shouldHandleTurn(task,
                "现场是平台通过网络连接霍尼韦尔网关，下面接温湿度传感器", false));
        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "现场是平台通过网络连接霍尼韦尔网关，下面接温湿度传感器",
                tags("system-context", "platform", "network-link", "honeywell-gateway",
                        "temp-humidity-sensor"));

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_POWER, task.sceneStepId());
        assertTrue(result.reply().contains("采集链路"));
    }

    @Test
    public void anyNewPhotoCanAttemptTheCurrentStepWithoutPresetNarration() {
        TaskSession task = preparedTask();

        assertTrue(skill.shouldHandleTurn(task, "你看看这个位置", true));
    }

    @Test
    public void verifiedPhotoCanAdvanceWithoutARepeatedSpokenDescription() {
        TaskSession task = preparedTask();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "请仅结合这张现场照片识别设备状态和异常区域",
                tags("platform", "temperature-missing", "other-data-normal", "refresh-time"));

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT, task.sceneStepId());
    }

    @Test
    public void unrelatedMaintenanceQuestionFallsThroughWithoutLosingTheCurrentStep() {
        TaskSession task = preparedTask();

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "交换机端口灯不亮，请分析网络链路",
                tags("network-switch", "port-led"));

        assertFalse(result.matched());
        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY, task.sceneStepId());
        assertEquals(HoneywellTempHumiditySkill.SKILL_ID, task.sceneSkillId());
    }

    @Test
    public void fixedRootCauseAppearsOnlyAfterAllPriorEvidenceAndMeterReading() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "这是现场系统关系", "system-context", "platform", "network-link",
                "honeywell-gateway", "temp-humidity-sensor");
        accept(task, "这是网关供电测量结果", "honeywell-gateway", "power-input",
                "rated-voltage", "meter", "meter-reading", "probe-point",
                "voltage-in-range");
        accept(task, "这是网口和网络状态灯", "honeywell-gateway", "network-port", "link-led");
        accept(task, "这是温湿度传感器和接线", "temp-humidity-sensor", "terminal", "signal-wire");

        HoneywellTempHumiditySkill.Result beforeMeter = skill.evaluate(task,
                "已经拆开了", tags("terminal"));
        assertFalse(beforeMeter.accepted());
        assertFalse(beforeMeter.reply().contains("最终根因"));

        HoneywellTempHumiditySkill.Result meter = skill.evaluate(task,
                "万用表正在测量，前端没有有效数据",
                tags("terminal", "meter", "dc-voltage-mode", "meter-reading", "probe-point",
                        "wiring-anomaly"));

        assertTrue(meter.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
        assertTrue(meter.reply().contains("最终根因：温湿度传感器接线异常"));
    }

    @Test
    public void sensorDiagnosisRequiresWiringPhotoBeforeMeterGuidance() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");
        accept(task, "网关供电测量正常", "honeywell-gateway", "power-input",
                "rated-voltage", "meter", "meter-reading", "probe-point",
                "voltage-in-range");
        accept(task, "网关网络状态正常", "honeywell-gateway", "network-port", "link-led");

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task,
                "AI判断问题在温湿度传感器，请检查接线",
                tags("temp-humidity-sensor", "terminal", "signal-wire"));

        assertTrue(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_METER_CHECK, task.sceneStepId());
        assertTrue(result.reply().contains("拆线"));
        assertTrue(result.reply().contains("拍摄"));
        assertTrue(result.reply().contains("万用表"));
    }

    @Test
    public void meterResultCanBeSpokenWithoutAMeterPhoto() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");
        accept(task, "网关供电测量正常", "honeywell-gateway", "power-input",
                "rated-voltage", "meter", "meter-reading", "probe-point",
                "voltage-in-range");
        accept(task, "网关网络状态正常", "honeywell-gateway", "network-port", "link-led");
        accept(task, "拆线后接线照片", "temp-humidity-sensor", "terminal", "signal-wire");

        assertTrue(skill.shouldHandleTurn(task,
                "额定电压24伏，测量点为传感器供电端，实测16伏，电压偏低", false));
    }

    @Test
    public void meterPhotoWithoutWiringEvidenceNeverProducesTheFixedRootCause() {
        TaskSession task = preparedTask();
        accept(task, "平台数据有问题", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场采集关系", "system-context", "platform", "network-link",
                "honeywell-gateway", "temp-humidity-sensor");
        accept(task, "网关供电测量正常", "honeywell-gateway", "power-input",
                "rated-voltage", "meter", "meter-reading", "probe-point",
                "voltage-in-range");
        accept(task, "网络状态", "honeywell-gateway", "network-port", "link-led");
        accept(task, "前端设备", "temp-humidity-sensor", "terminal", "signal-wire");

        HoneywellTempHumiditySkill.Result result = skill.evaluate(task, "这是现场测量结果",
                tags("terminal", "meter", "dc-voltage-mode", "meter-reading", "probe-point"));

        assertFalse(result.accepted());
        assertEquals(HoneywellTempHumiditySkill.STEP_METER_CHECK, task.sceneStepId());
        assertFalse(result.reply().contains("最终根因"));
    }

    @Test
    public void completedWorkOrderReleasesTheSceneSkillAndRestoresGeneralAi() {
        TaskSession task = preparedTask();
        accept(task, "平台温湿度局部异常", "platform", "temperature-missing", "other-data-normal");
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");
        accept(task, "网关供电测量正常", "honeywell-gateway", "power-input",
                "rated-voltage", "meter", "meter-reading", "probe-point",
                "voltage-in-range");
        accept(task, "网口和网络状态灯", "honeywell-gateway", "network-port", "link-led");
        accept(task, "温湿度传感器接线端子", "temp-humidity-sensor", "terminal", "signal-wire");
        accept(task, "万用表测量读数", "terminal", "meter", "meter-reading", "probe-point",
                "dc-voltage-mode", "wiring-anomaly");

        HoneywellTempHumiditySkill.Result completed = skill.evaluate(task,
                "平台温湿度数据已经恢复正常",
                tags("platform", "temperature-normal", "refresh-time"));

        assertTrue(completed.accepted());
        assertEquals("", task.sceneSkillId());
        assertEquals("", task.sceneStepId());
        assertEquals("", SceneSkillAiBridge.instruction(task));
        assertEquals("排查完成",
                task.maintenanceTask().facts().get("工单排查阶段"));
        assertFalse(task.maintenanceTask().facts().containsKey("霍尼韦尔演示步骤"));
    }

    @Test
    public void naturalEntryCompletesTheEvidenceWorkflowThenLeavesTheNextTaskOrdinary() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = manager.startNew("project-1", "现场照片待描述");
        assertTrue(skill.tryActivate(task,
                "实训室平台温湿度没有数据，其他数据正常", true));

        accept(task, "仅发送图片", "platform", "temperature-missing", "other-data-normal");
        assertEquals(HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT, task.sceneStepId());
        accept(task, "现场系统情况", "system-context", "honeywell-gateway",
                "temp-humidity-sensor");
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_POWER, task.sceneStepId());
        accept(task, "仅发送图片", "honeywell-gateway", "power-input", "rated-voltage",
                "meter", "meter-reading", "probe-point", "voltage-in-range");
        assertEquals(HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK, task.sceneStepId());
        accept(task, "仅发送图片", "honeywell-gateway", "network-port", "link-led");
        assertEquals(HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, task.sceneStepId());
        accept(task, "仅发送图片", "temp-humidity-sensor", "terminal", "signal-wire");
        assertEquals(HoneywellTempHumiditySkill.STEP_METER_CHECK, task.sceneStepId());
        accept(task, "工程师已口述测量结果", "terminal", "meter", "dc-voltage-mode",
                "meter-reading", "probe-point", "wiring-anomaly");
        assertEquals(HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, task.sceneStepId());
        accept(task, "仅发送图片", "platform", "temperature-normal", "refresh-time");
        assertEquals("", task.sceneSkillId());

        TaskSession ordinary = manager.startNew("project-2", "交换机端口灯异常");
        assertEquals("", ordinary.sceneSkillId());
    }

    private TaskSession preparedTask() {
        TaskSession task = new TaskSessionManager().startNew("project-honeywell", "实训室温湿度采集异常排查");
        task.bindSceneSkill(HoneywellTempHumiditySkill.SKILL_ID,
                HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY);
        return task;
    }

    private void accept(TaskSession task, String narration, String... evidence) {
        HoneywellTempHumiditySkill.Result result = skill.evaluate(task, narration, tags(evidence));
        assertTrue(result.accepted());
    }

    private static Set<String> tags(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
