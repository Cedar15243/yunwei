package com.codex.air3nativecamera.skills;

import com.codex.air3nativecamera.task.TaskSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HoneywellTempHumiditySkill {
    public static final String SKILL_ID = "honeywell-temp-humidity";
    public static final String STEP_PLATFORM_ANOMALY = "platform-anomaly";
    public static final String STEP_SYSTEM_CONTEXT = "system-context";
    public static final String STEP_DDC_POWER_PHOTO = "ddc-power-photo";
    public static final String STEP_DDC_POWER_MEASUREMENT = "ddc-power-measurement";
    public static final String STEP_DDC_RS485 = "ddc-rs485";
    public static final String STEP_GATEWAY_RS485 = "gateway-rs485";
    public static final String STEP_GATEWAY_NETWORK = "gateway-network-v2";
    public static final String STEP_SENSOR_DEVICE = "sensor-device";
    public static final String STEP_SENSOR_WIRING = "sensor-wiring";
    public static final String STEP_METER_CHECK = "meter-check";
    public static final String STEP_PLATFORM_RECOVERY = "platform-recovery";
    public static final String STEP_COMPLETED = "completed";

    public static final String LEGACY_STEP_GATEWAY_POWER = "gateway-power";
    public static final String LEGACY_STEP_GATEWAY_NETWORK = "gateway-network";

    private static final String DDC_POWER_MEASUREMENT_GUIDANCE =
            "检测位置：设备上方黑色三位端子排（10 / 11 / 12）。\n"
                    + "操作 1：确认三个端子紧固，万用表拨到交流电压档，量程高于24V。\n"
                    + "操作 2：黑表笔接11号 COM，红表笔接10号 24VAC；不要接12号 E-GND。\n"
                    + "操作 3：避免表笔碰到相邻端子，告诉我实测电压和读数是否稳定。\n"
                    + "带电测量请按现场安全规程，由具备电气操作能力的人员完成。";

    /** Activates the private workflow only after its external authorization switch is enabled. */
    public boolean tryActivate(TaskSession session, String narration, boolean hasPhoto) {
        if (session == null || !hasPhoto || session.sceneSkillId().length() > 0) {
            return false;
        }
        String text = normalizeText(narration);
        boolean platformAlarm = containsAny(text, "平台", "监控页面", "监控大屏")
                && containsAny(text, "报警", "告警", "异常", "故障", "没有数据", "无数据", "看看怎么回事");
        boolean lab = containsAny(text, "实训室", "实验室");
        boolean environmentData = containsAny(text, "温湿度", "温度湿度");
        boolean missing = containsAny(text, "没有数据", "无数据", "数据缺失", "数据不显示");
        boolean otherDataNormal = containsAny(text, "其他数据正常", "其余数据正常", "其他正常", "其余正常");
        if (!platformAlarm && (!lab || !environmentData || !missing || !otherDataNormal)) {
            return false;
        }
        session.bindSceneSkill(SKILL_ID, STEP_PLATFORM_ANOMALY);
        return true;
    }

    public boolean shouldHandleTurn(TaskSession session, String narration, boolean hasPhoto) {
        if (session == null || !SKILL_ID.equals(session.sceneSkillId())) {
            return false;
        }
        String step = normalizeStepId(session.sceneStepId());
        String text = normalizeText(narration);
        if (isClearlyQuestion(text)) {
            return false;
        }
        if (hasPhoto) {
            return isPhotoEvidenceStep(step) || isStepAttempt(step, text);
        }
        return text.length() > 1 && isStepAttempt(step, text);
    }

    public boolean isCandidateTurn(TaskSession session, String narration, boolean hasPhoto) {
        return session != null && hasPhoto && session.sceneSkillId().length() == 0;
    }

    public boolean activateCandidate(TaskSession session, String candidateSkillId) {
        if (session == null || session.sceneSkillId().length() > 0
                || !SKILL_ID.equals(candidateSkillId)) {
            return false;
        }
        session.bindSceneSkill(SKILL_ID, STEP_PLATFORM_ANOMALY);
        return true;
    }

    public static final class Result {
        private final boolean matched;
        private final boolean accepted;
        private final String reply;
        private final List<String> annotations;

        Result(boolean matched, boolean accepted, String reply, List<String> annotations) {
            this.matched = matched;
            this.accepted = accepted;
            this.reply = reply;
            this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations));
        }

        public boolean matched() {
            return matched;
        }

        public boolean accepted() {
            return accepted;
        }

        public String reply() {
            return reply;
        }

        public List<String> annotations() {
            return annotations;
        }
    }

    public Result evaluate(TaskSession session, String narration, Set<String> detectedTags) {
        return evaluate(session, narration, detectedTags, false, false);
    }

    public Result evaluate(TaskSession session, String narration, Set<String> detectedTags,
            boolean currentTurnHasPhoto, boolean hasReliableMarker) {
        if (session == null || !SKILL_ID.equals(session.sceneSkillId())) {
            return noMatch();
        }
        String step = normalizeStepId(session.sceneStepId());
        StepDefinition definition = definition(step);
        Set<String> tags = normalizeTags(detectedTags);
        boolean demoPhotoStep = currentTurnHasPhoto && isPhotoEvidenceStep(step);
        if (demoPhotoStep && tags.contains("unrelated")) {
            return new Result(true, false,
                    "检查状态：无法判断\n当前照片与本步检查对象不一致，请重新拍摄。"
                            + definition.retakePrompt,
                    definition.annotations);
        }
        if (!demoPhotoStep && tags.contains("unrelated")) {
            return noMatch();
        }
        if (!currentTurnHasPhoto && !isStepAttempt(step, narration) && !tags.contains("insufficient")
                && !containsAnyTag(tags, definition.requiredTags)) {
            return noMatch();
        }
        if (STEP_DDC_POWER_MEASUREMENT.equals(step)
                && (tags.contains("voltage-low") || tags.contains("voltage-high")
                || tags.contains("voltage-unstable"))) {
            return new Result(true, false,
                    "检查状态：异常\nDDC 输入电压偏低、偏高或不稳定，暂不进入下一步。"
                            + "请检查上游电源以及 10号 24VAC、11号 COM 端子，处理后重新测量。",
                    definition.annotations);
        }
        if (isGatewayLightStep(step) && containsAnyTag(tags, tags(
                "gateway-rs485-off", "gateway-rs485-solid",
                "network-link-off", "network-link-solid", "rs485-abnormal", "network-abnormal"))) {
            String rs485 = indicatorStatus(tags, "gateway-rs485");
            String network = indicatorStatus(tags, "network-link");
            return new Result(true, false,
                    "检查状态：异常\n485通讯状态：" + rs485 + "；网络通讯状态：" + network
                            + "。只有两个通讯灯都闪烁才属于正常状态。"
                            + "请检查对应接线、极性、网线或网络端口，处理后重新口述灯状态。",
                    definition.annotations);
        }
        if (STEP_SENSOR_WIRING.equals(step)
                && containsAnyTag(tags, tags("voltage-low", "voltage-high", "voltage-unstable"))) {
            return new Result(true, false,
                    "检查状态：异常\n接线处理结果已记录，但传感器电源点与公共端之间的电压"
                            + "不是稳定的12V，暂不进入下一步。请检查端子和供电线路后重新测量。",
                    definition.annotations);
        }
        boolean completeEvidence = hasCompleteEvidence(step, narration, tags, definition);
        if (!demoPhotoStep && (tags.contains("insufficient") || !completeEvidence)) {
            return new Result(true, false,
                    "检查状态：无法判断\n当前结果还不完整。" + definition.retakePrompt,
                    definition.annotations);
        }
        String reply = definition.reply;
        if (demoPhotoStep && (tags.contains("insufficient") || tags.contains("unrelated")
                || !completeEvidence || !hasReliableMarker)) {
            reply = fallbackPhotoReply(step);
        }
        if (STEP_DDC_POWER_MEASUREMENT.equals(step)) {
            reply = "检查状态：正常\nDDC 输入实测" + measuredVoltage(narration, "VAC")
                    + "，在允许区间21.6-26.4VAC内且读数稳定。下一步：请观察网关上方的"
                    + "485通讯灯和下方的网络通讯灯，分别告诉我是不亮、常亮还是闪烁，不需要拍照。";
        } else if (STEP_SENSOR_WIRING.equals(step)
                && tags.contains("wiring-anomaly") && !tags.contains("wiring-resolved")) {
            reply = "检查状态：异常\n已定位接线接触不良。请先重新紧固端子，"
                    + "然后刷新平台，确认温湿度数据和报警状态是否恢复。";
        } else if (STEP_SENSOR_WIRING.equals(step)) {
            reply = sensorWiringSuccessReply(narration, tags);
        }
        session.maintenanceTask().putFact("工单排查阶段", stageLabel(definition.nextStep));
        if (STEP_COMPLETED.equals(definition.nextStep)) {
            session.maintenanceTask().complete();
            session.clearSceneSkill();
        } else {
            session.bindSceneSkill(SKILL_ID, definition.nextStep);
        }
        return new Result(true, true, reply, definition.annotations);
    }

    static String normalizeStepId(String step) {
        if (LEGACY_STEP_GATEWAY_POWER.equals(step)) {
            return STEP_DDC_POWER_PHOTO;
        }
        if (LEGACY_STEP_GATEWAY_NETWORK.equals(step)) {
            return STEP_DDC_RS485;
        }
        if (STEP_PLATFORM_ANOMALY.equals(step) || STEP_SYSTEM_CONTEXT.equals(step)
                || STEP_DDC_POWER_PHOTO.equals(step) || STEP_DDC_POWER_MEASUREMENT.equals(step)
                || STEP_DDC_RS485.equals(step) || STEP_GATEWAY_RS485.equals(step)
                || STEP_GATEWAY_NETWORK.equals(step) || STEP_SENSOR_DEVICE.equals(step)
                || STEP_SENSOR_WIRING.equals(step) || STEP_METER_CHECK.equals(step)
                || STEP_PLATFORM_RECOVERY.equals(step) || STEP_COMPLETED.equals(step)) {
            return step;
        }
        return STEP_PLATFORM_ANOMALY;
    }

    private static boolean hasCompleteEvidence(String step, String narration, Set<String> tags,
            StepDefinition definition) {
        if (STEP_SENSOR_WIRING.equals(step)) {
            return tags.contains("terminal")
                    && (tags.contains("wiring-anomaly") || tags.contains("wiring-resolved")
                    || tags.contains("voltage-in-range"));
        }
        return tags.containsAll(definition.requiredTags);
    }

    private static boolean hasExplicitSystemContextNarration(String narration) {
        String text = normalizeText(narration);
        return containsAny(text, "架构图", "系统图", "拓扑图", "连接关系", "系统关系",
                "采集链路", "现场系统情况");
    }

    private static boolean isPhotoEvidenceStep(String step) {
        return !STEP_DDC_POWER_MEASUREMENT.equals(step)
                && !STEP_DDC_RS485.equals(step)
                && !STEP_GATEWAY_RS485.equals(step)
                && !STEP_GATEWAY_NETWORK.equals(step)
                && !STEP_SENSOR_WIRING.equals(step)
                && !STEP_METER_CHECK.equals(step)
                && !STEP_PLATFORM_RECOVERY.equals(step);
    }

    private static String fallbackPhotoReply(String step) {
        if (STEP_SYSTEM_CONTEXT.equals(step)) {
            return "现场系统情况已记录。下一步：请拍摄霍尼韦尔工控 DDC 铭牌和电源输入位置。";
        }
        if (STEP_DDC_POWER_PHOTO.equals(step)) {
            return "检查状态：无法判断\n已确认 DDC 电源检测位置，标准检测点请参考下方示意。"
                    + "\n下一步：请按以下步骤测量10号与11号端口之间的电压。\n"
                    + DDC_POWER_MEASUREMENT_GUIDANCE;
        }
        if (STEP_DDC_RS485.equals(step)) {
            return "DDC 485 检查照片已记录，检测点请参考下方示意。下一步：请拍摄网关上方 COM1/COM2、A/B 端子和 485 信号灯。";
        }
        if (STEP_GATEWAY_RS485.equals(step)) {
            return "网关 485 与网络检查照片已记录，检测点请参考下方示意。下一步：请拍摄霍尼韦尔温湿度传感器本体和线缆入口。";
        }
        if (STEP_GATEWAY_NETWORK.equals(step)) {
            return "网关网络检查照片已记录，检测点请参考下方示意。下一步：请拍摄霍尼韦尔温湿度传感器本体和线缆入口。";
        }
        if (STEP_SENSOR_DEVICE.equals(step)) {
            return "检查状态：无法判断\n已确认温湿度传感器及接线端子位置，标准检测点请参考下方示意。"
                    + "下一步：请打开传感器盖子，检查接线端子是否紧固，并确认电源点与公共端之间的电压是否正常。"
                    + "处理后直接告诉我接线情况、电压值和读数是否稳定，不需要再次拍照。";
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return "接线检查结果还不完整。请口述是否存在虚接或松动、是否已经处理，以及当前电压是否正常。";
        }
        if (STEP_PLATFORM_RECOVERY.equals(step)) {
            return "平台恢复画面已记录，本次接线异常排查闭环完成。"
                    + "维修记录已生成。维修记录已保存到任务历史，案例数据已保存至华方知识库。";
        }
        return "平台画面已记录。下一步：请上传架构图，或简短说明平台、网关、DDC 与传感器的连接关系。";
    }

    private static boolean isStepAttempt(String step, String narration) {
        String text = normalizeText(narration);
        if (isClearlyQuestion(text)) {
            return false;
        }
        if (containsAny(text, "仅发送图片", "只发送图片", "只发图片", "仅结合这张现场照片",
                "结合这张现场照片", "这张现场照片")) {
            return true;
        }
        if (STEP_SYSTEM_CONTEXT.equals(step)) {
            return hasExplicitSystemContextNarration(text);
        }
        if (STEP_DDC_POWER_PHOTO.equals(step)) {
            return containsAny(text, "ddc", "工控")
                    && containsAny(text, "供电", "电源", "铭牌", "输入端", "24vac");
        }
        if (STEP_DDC_POWER_MEASUREMENT.equals(step)) {
            return hasVoltageResult(text) || hasOutOfSequenceDdcStatus(text)
                    || hasIncompleteDdcMeasurement(text);
        }
        if (isGatewayLightStep(step)) {
            return hasIndicatorResult(text);
        }
        if (STEP_SENSOR_DEVICE.equals(step)) {
            return containsAny(text, "传感器", "温湿度") && containsAny(text, "本体", "设备", "照片");
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return hasSensorRepairResult(text);
        }
        if (STEP_METER_CHECK.equals(step)) {
            return containsAny(text, "万用表", "测量", "读数", "表笔", "测量点", "实测");
        }
        if (STEP_PLATFORM_RECOVERY.equals(step)) {
            return hasPlatformRecoveryResult(text);
        }
        boolean platformAlarm = containsAny(text, "平台", "平台页面")
                && containsAny(text, "报警", "告警", "出现异常", "没有数据", "无数据");
        return platformAlarm || (containsAny(text, "温湿度", "温度湿度")
                && containsAny(text, "平台", "数据", "页面"));
    }

    private static StepDefinition definition(String step) {
        if (STEP_SYSTEM_CONTEXT.equals(step)) {
            return new StepDefinition(STEP_DDC_POWER_PHOTO,
                    tags("system-context"),
                    "请提供清晰的架构图、拓扑图或连接关系描述；需要说明平台、网关、DDC 和传感器之间的链路。",
                    "已识别现场系统连接关系。下一步：请拍摄霍尼韦尔工控 DDC 铭牌和电源输入位置。",
                    "系统连接关系");
        }
        if (STEP_DDC_POWER_PHOTO.equals(step)) {
            return new StepDefinition(STEP_DDC_POWER_MEASUREMENT,
                    tags("honeywell-ddc", "power-input"),
                    "请靠近拍摄工控 DDC 铭牌、24VAC/COM 电源端子和可安全测量的位置。",
                    "检查状态：无法判断\n已定位 DDC 电源输入端，标准检测点请参考下方示意。"
                            + "\n下一步：请按以下步骤测量10号与11号端口之间的电压。\n"
                            + DDC_POWER_MEASUREMENT_GUIDANCE,
                    "霍尼韦尔工控 DDC", "DDC 电源输入端", "电压测量点");
        }
        if (STEP_DDC_POWER_MEASUREMENT.equals(step)) {
            return new StepDefinition(STEP_GATEWAY_RS485,
                    tags("honeywell-ddc", "rated-voltage", "meter-reading", "probe-point",
                            "voltage-in-range"),
                    "请用万用表测量10号 24VAC 与11号 COM 之间的实测电压，"
                            + "并告诉我电压值和读数是否稳定。",
                    "检查状态：正常\nDDC 输入电压约24VAC且读数稳定。下一步：请观察网关上方的"
                            + "485通讯灯和下方的网络通讯灯，分别告诉我是不亮、常亮还是闪烁，不需要拍照。",
                    "DDC 供电测量结果");
        }
        if (STEP_DDC_RS485.equals(step)) {
            return gatewayLightDefinition();
        }
        if (STEP_GATEWAY_RS485.equals(step)) {
            return gatewayLightDefinition();
        }
        if (STEP_GATEWAY_NETWORK.equals(step)) {
            return gatewayLightDefinition();
        }
        if (STEP_SENSOR_DEVICE.equals(step)) {
            return new StepDefinition(STEP_SENSOR_WIRING,
                    tags("temp-humidity-sensor", "sensor-cable-entry"),
                    "请拍摄完整的温湿度传感器本体、品牌标识和线缆入口。",
                    "检查状态：无法判断\n已确认温湿度传感器及接线端子位置。"
                            + "下一步：请打开传感器盖子，检查接线端子是否紧固，并确认电源点与公共端之间的电压是否正常。"
                            + "处理后直接告诉我接线情况、电压值和读数是否稳定，不需要再次拍照。",
                    "温湿度传感器本体", "传感器接线端子", "12V电源点与公共端");
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return new StepDefinition(STEP_PLATFORM_RECOVERY,
                    tags("terminal"),
                    "请告诉我接线是否正常、是否存在虚接或松动、是否已经处理，以及电压是否正常。",
                    "原检查状态：异常\n当前检查状态：正常\n接线异常已处理，传感器供电约12V且稳定。"
                            + "下一步：请刷新平台，确认温湿度数据和报警状态是否恢复。",
                    "传感器接线处理结果");
        }
        if (STEP_METER_CHECK.equals(step)) {
            return new StepDefinition(STEP_PLATFORM_RECOVERY,
                    tags("terminal", "meter-reading", "probe-point", "wiring-anomaly"),
                    "请口述传感器端子的额定值、实测值、单位、测量位置和稳定性；信息不足时不要判断根因。",
                    "测量结果支持温湿度传感器接线异常。下一步：请处理松动、脱落、接反或接触不良，随后回到平台确认数据恢复。",
                    "传感器测量点", "异常接线");
        }
        if (STEP_PLATFORM_RECOVERY.equals(step)) {
            return new StepDefinition(STEP_COMPLETED,
                    tags("platform", "temperature-normal"),
                    "请刷新平台并口述温湿度数据是否恢复。",
                    "检查状态：正常\n平台温湿度数据已恢复，报警已消除。"
                            + "本次接线异常排查闭环完成。维修记录已生成。维修记录已保存到任务历史，"
                            + "案例数据已保存至华方知识库。",
                    "温湿度恢复状态");
        }
        return new StepDefinition(STEP_SYSTEM_CONTEXT,
                tags("platform", "temperature-missing", "other-data-normal"),
                "请补拍平台页面，画面需清晰包含右侧报警列表中的温湿度传感器报警记录。",
                "检查状态：异常\n平台其他数据正常，温湿度属于局部异常。"
                        + "下一步：请上传架构图，或简短说明平台、网关、DDC 与传感器的连接关系。",
                "右侧温湿度报警列表");
    }

    private static StepDefinition gatewayLightDefinition() {
        return new StepDefinition(STEP_SENSOR_DEVICE,
                tags("gateway-rs485-blinking", "network-link-blinking"),
                "请分别告诉我485通讯灯和网络通讯灯是不亮、常亮还是闪烁。",
                "检查状态：正常\n485通讯状态：正常\n网络通讯状态：正常\n两个通讯灯均闪烁，"
                        + "通讯链路当前正常。下一步：请拍摄温湿度传感器及接线端子。",
                "网关485通讯状态", "网络通讯状态");
    }

    private static boolean isGatewayLightStep(String step) {
        return STEP_DDC_RS485.equals(step) || STEP_GATEWAY_RS485.equals(step)
                || STEP_GATEWAY_NETWORK.equals(step);
    }

    private static String indicatorStatus(Set<String> evidence, String prefix) {
        if (evidence.contains(prefix + "-blinking")) return "正常（闪烁）";
        if (evidence.contains(prefix + "-solid")) return "异常（常亮）";
        if (evidence.contains(prefix + "-off")) return "异常（不亮）";
        return "无法判断";
    }

    private static boolean hasVoltageResult(String text) {
        boolean voltageContext = containsAny(text, "伏", "vac", "v正常", "v稳定", "电压",
                "测量", "实测", "读数", "万用表", "表显", "测得", "测了");
        return (voltageContext && SceneSkillAiBridge.spokenVoltage(text) != null)
                || containsAny(text, "没电", "没有电");
    }

    private static boolean hasOutOfSequenceDdcStatus(String text) {
        boolean ddcIndicator = containsAny(text, "ddc", "运行灯", "运行指示灯", "net灯",
                "link灯", "485灯", "指示灯");
        boolean state = containsAny(text, "正常", "异常", "闪", "常亮", "一直亮", "不亮", "没亮");
        return ddcIndicator && state;
    }

    private static boolean hasIncompleteDdcMeasurement(String text) {
        boolean measurement = containsAny(text, "测量", "测过", "量过", "万用表", "表笔", "端子", "接口");
        boolean terminal10 = containsAny(text, "10号", "10 号", "十号", "24vac");
        boolean terminal11 = containsAny(text, "11号", "11 号", "十一号", "com");
        return measurement && terminal10 && terminal11;
    }

    private static String measuredVoltage(String narration, String unit) {
        Double voltage = SceneSkillAiBridge.spokenVoltage(narration);
        if (voltage == null) {
            return "电压";
        }
        double value = voltage.doubleValue();
        String formatted = value == Math.rint(value)
                ? Long.toString(Math.round(value)) : Double.toString(value);
        return formatted + unit;
    }

    private static boolean hasIndicatorResult(String text) {
        return containsAny(text, "闪", "常亮", "一直亮", "不亮", "没亮", "熄灭")
                && containsAny(text, "485", "网络", "网口", "上面", "上方", "下面", "下方", "两个", "都");
    }

    static boolean hasSensorWiringOutcome(String text) {
        String normalized = normalizeText(text);
        if (normalized.length() == 0 || containsAny(normalized,
                "服务器", "ddc", "工控", "网关", "交换机", "空调", "消防")) {
            return false;
        }
        boolean wiringOutcome = containsAny(normalized,
                "接触不良", "虚接", "松动", "松了", "线松", "脱落", "接反",
                "接好", "接上", "重新接", "处理好", "修复", "紧固", "拧紧",
                "已恢复", "已经恢复", "恢复正常", "接线正常", "端子正常",
                "接线没问题", "接线没有问题");
        boolean conciseVoltageOutcome = normalized.length() <= 16 && hasVoltageResult(normalized);
        return wiringOutcome || hasNormalSensorVoltageStatement(normalized)
                || conciseVoltageOutcome
                || (hasVoltageResult(normalized) && containsAny(normalized,
                "传感器", "温湿度", "接线", "端子", "电源点", "公共端"));
    }

    static boolean hasNormalSensorVoltageStatement(String text) {
        String normalized = normalizeText(text);
        boolean normalVoltage = containsAny(normalized,
                "电压正常", "供电正常", "电源正常", "12v正常", "12伏正常", "十二伏正常");
        if (normalVoltage && !containsAny(normalized, "不正常", "异常", "不稳定", "波动", "跳动", "乱跳")) {
            return true;
        }
        Double voltage = SceneSkillAiBridge.spokenVoltage(normalized);
        return voltage != null && voltage.doubleValue() >= 10.8d && voltage.doubleValue() <= 13.2d
                && !containsAny(normalized, "不稳定", "波动", "跳动", "乱跳");
    }

    static boolean hasResolvedOrNormalWiringStatement(String text) {
        String normalized = normalizeText(text);
        return containsAny(normalized,
                "接好", "接上", "重新接", "处理好", "修复", "紧固", "拧紧",
                "已恢复", "已经恢复", "现已恢复", "恢复正常", "现在恢复",
                "接线正常", "端子正常", "接线没问题", "接线没有问题");
    }

    private static boolean hasSensorRepairResult(String text) {
        return hasSensorWiringOutcome(text);
    }

    private static String sensorWiringSuccessReply(String narration, Set<String> tags) {
        StringBuilder reply = new StringBuilder();
        if (tags.contains("wiring-anomaly") && tags.contains("wiring-resolved")) {
            reply.append("原检查状态：异常\n当前检查状态：正常\n")
                    .append("原接线接触不良已完成处理。");
        } else if (tags.contains("wiring-resolved")) {
            reply.append("当前检查状态：正常\n接线检查与处理已完成。");
        } else {
            reply.append("当前检查状态：正常\n接线与供电检查已完成。");
        }
        Double voltage = SceneSkillAiBridge.spokenVoltage(narration);
        if (voltage != null) {
            reply.append("传感器电源点与公共端之间实测")
                    .append(measuredVoltage(narration, "V"))
                    .append("，在允许区间10.8-13.2V内且稳定。");
        } else if (hasNormalSensorVoltageStatement(narration)) {
            reply.append("传感器电源点与公共端之间的电压正常。");
        }
        return reply.append("下一步：请刷新平台，确认温湿度数据和报警状态是否恢复。")
                .toString();
    }

    private static boolean hasPlatformRecoveryResult(String text) {
        boolean hasPlatformSubject = containsAny(text,
                "平台", "页面", "温湿度", "数据", "报警", "告警");
        boolean recovered = containsAny(text,
                "恢复", "正常", "重新上报", "数据有了", "数据回来", "报警没了", "告警没了",
                "不报警了", "好了");
        boolean stillFaulted = containsAny(text,
                "没恢复", "未恢复", "没有恢复", "还是异常", "仍然异常", "还有报警", "还有告警");
        return hasPlatformSubject && recovered && !stillFaulted;
    }

    private static boolean isClearlyQuestion(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        return text.contains("?") || text.contains("？")
                || containsAny(text, "为什么", "为何", "怎么", "如何", "什么", "哪个", "哪里",
                        "多少", "是否", "能不能", "可以吗", "对吗", "吗", "呢", "介绍一下",
                        "解释一下", "告诉我");
    }

    private static Result noMatch() {
        return new Result(false, false, "", Collections.<String>emptyList());
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeTags(Set<String> values) {
        Set<String> result = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    result.add(normalized);
                    int separator = normalized.indexOf('=');
                    if (separator > 0) {
                        result.add(normalized.substring(0, separator));
                    }
                }
            }
        }
        return result;
    }

    private static boolean containsAnyTag(Set<String> actual, Set<String> expected) {
        for (String value : expected) {
            if (actual.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> tags(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static String stageLabel(String step) {
        if (STEP_SYSTEM_CONTEXT.equals(step)) return "补充现场系统情况";
        if (STEP_DDC_POWER_PHOTO.equals(step)) return "确认 DDC 供电测量点";
        if (STEP_DDC_POWER_MEASUREMENT.equals(step)) return "测量 DDC 供电";
        if (STEP_DDC_RS485.equals(step)) return "检查 DDC 485";
        if (STEP_GATEWAY_RS485.equals(step)) return "检查网关 485";
        if (STEP_GATEWAY_NETWORK.equals(step)) return "检查网关网络";
        if (STEP_SENSOR_DEVICE.equals(step)) return "确认温湿度传感器";
        if (STEP_SENSOR_WIRING.equals(step)) return "确认传感器接线处理";
        if (STEP_METER_CHECK.equals(step)) return "测量传感器接线";
        if (STEP_PLATFORM_RECOVERY.equals(step)) return "确认平台恢复";
        if (STEP_COMPLETED.equals(step)) return "排查完成";
        return "确认平台异常";
    }

    private static final class StepDefinition {
        private final String nextStep;
        private final Set<String> requiredTags;
        private final String retakePrompt;
        private final String reply;
        private final List<String> annotations;

        StepDefinition(String nextStep, Set<String> requiredTags, String retakePrompt,
                String reply, String... annotations) {
            this.nextStep = nextStep;
            this.requiredTags = requiredTags;
            this.retakePrompt = retakePrompt;
            this.reply = reply;
            this.annotations = Arrays.asList(annotations);
        }
    }
}
