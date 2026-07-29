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
        boolean hasNarration = narration != null && narration.trim().length() > 1;
        return hasPhoto || (hasNarration && (STEP_SYSTEM_CONTEXT.equals(step)
                || STEP_DDC_POWER_MEASUREMENT.equals(step)
                || STEP_SENSOR_WIRING.equals(step)
                || STEP_METER_CHECK.equals(step)
                || STEP_PLATFORM_RECOVERY.equals(step)));
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
                    "DDC 输入电压偏低或不稳定，不能推进。请检查上游电源和 24VAC/COM 端子，处理后重新测量。",
                    definition.annotations);
        }
        if (!demoPhotoStep && (STEP_DDC_RS485.equals(step) || STEP_GATEWAY_RS485.equals(step))
                && tags.contains("rs485-abnormal")) {
            return new Result(true, false,
                    "485 链路存在异常。请检查 A/B 极性、端子紧固和屏蔽接地，处理后重新拍摄本步区域。",
                    definition.annotations);
        }
        if (!demoPhotoStep && STEP_GATEWAY_NETWORK.equals(step)
                && tags.contains("network-abnormal")) {
            return new Result(true, false,
                    "网关网络链路异常。请检查网线、交换机端口和地址配置，恢复后重新拍摄网络接口。",
                    definition.annotations);
        }
        boolean completeEvidence = hasCompleteEvidence(step, narration, tags, definition);
        if (!demoPhotoStep && (tags.contains("insufficient") || !completeEvidence)) {
            return new Result(true, false,
                    "当前证据还不完整，请补拍。" + definition.retakePrompt,
                    definition.annotations);
        }
        String reply = definition.reply;
        if (demoPhotoStep && (tags.contains("insufficient") || tags.contains("unrelated")
                || !completeEvidence || !hasReliableMarker)) {
            reply = fallbackPhotoReply(step);
        }
        if (STEP_SENSOR_WIRING.equals(step)
                && containsAnyTag(tags, tags("wiring-suspect", "exposed-conductor", "loose-wire"))) {
            reply = "接线异常和处理结果已记录。下一步：请刷新平台，确认温湿度数据是否恢复。";
        }
        session.maintenanceTask().putFact("工单排查阶段", stageLabel(definition.nextStep));
        if (STEP_COMPLETED.equals(definition.nextStep)) {
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
        return tags.containsAll(definition.requiredTags);
    }

    private static boolean hasExplicitSystemContextNarration(String narration) {
        String text = normalizeText(narration);
        return containsAny(text, "架构图", "系统图", "拓扑图", "连接关系", "系统关系",
                "采集链路", "现场系统情况");
    }

    private static boolean isPhotoEvidenceStep(String step) {
        return !STEP_DDC_POWER_MEASUREMENT.equals(step)
                && !STEP_SENSOR_WIRING.equals(step)
                && !STEP_METER_CHECK.equals(step)
                && !STEP_PLATFORM_RECOVERY.equals(step);
    }

    private static String fallbackPhotoReply(String step) {
        if (STEP_SYSTEM_CONTEXT.equals(step)) {
            return "现场系统情况已记录。下一步：请拍摄霍尼韦尔工控 DDC 铭牌和电源输入位置。";
        }
        if (STEP_DDC_POWER_PHOTO.equals(step)) {
            return "DDC 供电检查照片已记录，检测点请参考下方示意。下一步：请用万用表测量，简短口述实测电压和是否稳定。";
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
            return "温湿度传感器照片已记录，检测点请参考下方示意。下一步：请检查接线接口，处理后简短口述异常、处理结果和电压状态。";
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return "接线检查结果还不完整。请口述是否存在虚接或松动、是否已经处理，以及当前电压是否正常。";
        }
        if (STEP_PLATFORM_RECOVERY.equals(step)) {
            return "平台恢复画面已记录，本次接线异常排查闭环完成，照片和处理记录已保存。";
        }
        return "平台画面已记录。下一步：请上传架构图，或简短说明平台、网关、DDC 与传感器的连接关系。";
    }

    private static boolean isStepAttempt(String step, String narration) {
        String text = normalizeText(narration);
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
            return containsAny(text, "ddc", "工控", "输入端")
                    && containsAny(text, "电压", "万用表", "实测", "测量", "伏");
        }
        if (STEP_DDC_RS485.equals(step)) {
            return containsAny(text, "ddc", "工控") && containsAny(text, "485", "通讯");
        }
        if (STEP_GATEWAY_RS485.equals(step)) {
            return containsAny(text, "网关", "com1", "com2", "网口", "网络")
                    && containsAny(text, "485", "通讯", "com", "网线", "rj45", "状态灯");
        }
        if (STEP_GATEWAY_NETWORK.equals(step)) {
            return containsAny(text, "网关", "网口", "网络")
                    && containsAny(text, "网络", "网线", "状态灯", "链路灯", "rj45");
        }
        if (STEP_SENSOR_DEVICE.equals(step)) {
            return containsAny(text, "传感器", "温湿度") && containsAny(text, "本体", "设备", "照片");
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return containsAny(text, "接线", "端子", "虚接", "松动", "接触不良")
                    && containsAny(text, "接好", "处理", "修复", "紧固", "电压正常", "读数正常");
        }
        if (STEP_METER_CHECK.equals(step)) {
            return containsAny(text, "万用表", "测量", "读数", "表笔", "测量点", "实测");
        }
        if (STEP_PLATFORM_RECOVERY.equals(step)) {
            return containsAny(text, "恢复", "正常", "重新上报")
                    && containsAny(text, "平台", "温湿度", "数据", "页面");
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
                    "已标出 DDC 电源输入端和测量点。下一步：请用万用表测量，简短口述实测电压和是否稳定。",
                    "霍尼韦尔工控 DDC", "DDC 电源输入端", "电压测量点");
        }
        if (STEP_DDC_POWER_MEASUREMENT.equals(step)) {
            return new StepDefinition(STEP_DDC_RS485,
                    tags("honeywell-ddc", "rated-voltage", "meter-reading", "probe-point",
                            "voltage-in-range"),
                    "请口述 DDC 额定电压、实测电压、单位、24VAC/COM 测量位置以及读数是否稳定。",
                    "DDC 供电实测正常。下一步：请拍摄 DDC 的 485+、485- 接线端子和 485 状态灯。",
                    "DDC 供电测量结果");
        }
        if (STEP_DDC_RS485.equals(step)) {
            return new StepDefinition(STEP_GATEWAY_RS485,
                    tags("rs485-terminal", "rs485-led"),
                    "请靠近拍摄 DDC 的 485+、485- 端子、接线和 485 状态灯。",
                    "DDC 侧 485 接线与状态已记录。下一步：请在一张照片中拍摄网关上方 COM1/COM2 通讯灯和下方 RJ45 网口通讯灯。",
                    "DDC 485 端子", "DDC 485 状态灯");
        }
        if (STEP_GATEWAY_RS485.equals(step)) {
            return new StepDefinition(STEP_SENSOR_DEVICE,
                    tags("honeywell-gateway", "gateway-rs485-led", "network-port", "link-led"),
                    "请在一张照片中完整拍摄网关上方 COM1/COM2 485 通讯灯，以及下方 RJ45 网络口和通讯灯。不要拍 A/B 端子排或 PWR/RUN。",
                    "网关 485 与网络侧状态已记录。下一步：请拍摄霍尼韦尔温湿度传感器本体和线缆入口。",
                    "网关 485 通讯灯", "RJ45 网口通讯灯");
        }
        if (STEP_GATEWAY_NETWORK.equals(step)) {
            return new StepDefinition(STEP_SENSOR_DEVICE,
                    tags("honeywell-gateway", "network-port", "network-cable", "link-led"),
                    "请靠近拍摄网关下方 RJ45 网络接口、网线插头和网络状态灯。",
                    "网关网络侧未见明显异常。下一步：请拍摄霍尼韦尔温湿度传感器本体和线缆入口。",
                    "RJ45 网络接口", "网线连接", "网络状态灯");
        }
        if (STEP_SENSOR_DEVICE.equals(step)) {
            return new StepDefinition(STEP_SENSOR_WIRING,
                    tags("temp-humidity-sensor", "sensor-cable-entry"),
                    "请拍摄完整的温湿度传感器本体、品牌标识和线缆入口。",
                    "已确认温湿度传感器位置。下一步：请检查接线接口，处理后简短口述异常、处理结果和电压状态。",
                    "温湿度传感器本体", "传感器线缆入口");
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return new StepDefinition(STEP_PLATFORM_RECOVERY,
                    tags("terminal", "wiring-anomaly", "voltage-in-range"),
                    "请口述是否存在虚接、松动或接触不良，是否已经处理，以及当前电压是否正常。",
                    "接线异常和处理结果已记录。下一步：请刷新平台，确认温湿度数据是否恢复。",
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
                    "平台温湿度数据已恢复，本次接线异常排查闭环完成，过程和处理记录已保存。",
                    "温湿度恢复状态");
        }
        return new StepDefinition(STEP_SYSTEM_CONTEXT,
                tags("platform", "temperature-missing", "other-data-normal"),
                "请补拍平台页面，画面需清晰包含右侧报警列表中的温湿度传感器报警记录。",
                "平台其他数据正常，温湿度属于局部异常。下一步：请上传架构图，或简短说明平台、网关、DDC 与传感器的连接关系。",
                "右侧温湿度报警列表");
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
