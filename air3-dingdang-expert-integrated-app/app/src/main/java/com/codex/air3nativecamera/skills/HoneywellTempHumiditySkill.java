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
    public static final String STEP_GATEWAY_POWER = "gateway-power";
    public static final String STEP_GATEWAY_NETWORK = "gateway-network";
    public static final String STEP_SENSOR_WIRING = "sensor-wiring";
    public static final String STEP_METER_CHECK = "meter-check";
    public static final String STEP_PLATFORM_RECOVERY = "platform-recovery";
    public static final String STEP_COMPLETED = "completed";

    /** Activates the private workflow only for a photographed, explicitly described lab incident. */
    public boolean tryActivate(TaskSession session, String narration, boolean hasPhoto) {
        if (session == null || hasPhoto == false || session.sceneSkillId().length() > 0) {
            return false;
        }
        String text = narration == null ? "" : narration.trim().toLowerCase(Locale.ROOT);
        boolean lab = containsAny(text, "实训室", "实验室");
        boolean environmentData = containsAny(text, "温湿度", "温度湿度");
        boolean missing = containsAny(text, "没有数据", "无数据", "数据缺失", "数据不显示");
        boolean otherDataNormal = containsAny(text, "其他数据正常", "其余数据正常", "其他正常", "其余正常");
        if (!lab || !environmentData || !missing || !otherDataNormal) {
            return false;
        }
        session.bindSceneSkill(SKILL_ID, STEP_PLATFORM_ANOMALY);
        return true;
    }

    public boolean shouldHandleTurn(TaskSession session, String narration, boolean hasPhoto) {
        if (session == null || !SKILL_ID.equals(session.sceneSkillId())) {
            return false;
        }
        String step = normalizeStep(session.sceneStepId());
        boolean hasNarration = narration != null && narration.trim().length() > 1;
        return hasPhoto || (STEP_SYSTEM_CONTEXT.equals(step) && hasNarration)
                || (STEP_GATEWAY_POWER.equals(step) && hasNarration)
                || (STEP_METER_CHECK.equals(step) && hasNarration);
    }

    public boolean isCandidateTurn(TaskSession session, String narration, boolean hasPhoto) {
        if (session == null || !hasPhoto || session.sceneSkillId().length() > 0) {
            return false;
        }
        // The model verifies the photographed scene. Do not require the operator to memorize a script.
        return true;
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
        if (session == null || !SKILL_ID.equals(session.sceneSkillId())) {
            return new Result(false, false, "", Collections.<String>emptyList());
        }
        String step = normalizeStep(session.sceneStepId());
        StepDefinition definition = definition(step);
        Set<String> tags = normalizeTags(detectedTags);
        if (tags.contains("unrelated")) {
            return new Result(false, false, "", Collections.<String>emptyList());
        }
        if (!isStepAttempt(step, narration) && !tags.contains("insufficient")
                && !containsAnyTag(tags, definition.requiredTags)) {
            return new Result(false, false, "", Collections.<String>emptyList());
        }
        if (STEP_GATEWAY_POWER.equals(step)
                && (tags.contains("voltage-low") || tags.contains("voltage-unstable"))) {
            return new Result(true, false,
                    "万用表读数显示网关输入电压低于或不稳定，不能因电源灯亮就判定供电正常。请按额定值检查上游电源、保险和端子，处理后重新测量。",
                    definition.annotations);
        }
        if (!tags.containsAll(definition.requiredTags)) {
            return new Result(true, false, definition.retakePrompt, definition.annotations);
        }
        session.maintenanceTask().putFact("工单排查阶段", stageLabel(definition.nextStep));
        if (STEP_COMPLETED.equals(definition.nextStep)) {
            session.clearSceneSkill();
        } else {
            session.bindSceneSkill(SKILL_ID, definition.nextStep);
        }
        return new Result(true, true, definition.reply, definition.annotations);
    }

    private static boolean isStepAttempt(String step, String narration) {
        String text = narration == null ? "" : narration.trim().toLowerCase(Locale.ROOT);
        if (containsAny(text, "仅发送图片", "只发送图片", "只发图片", "仅结合这张现场照片",
                "结合这张现场照片", "这张现场照片")) {
            return true;
        }
        if (STEP_GATEWAY_POWER.equals(step)) {
            return containsAny(text, "网关", "霍尼韦尔")
                    && containsAny(text, "供电", "电源", "运行灯", "电压", "万用表", "测量");
        }
        if (STEP_GATEWAY_NETWORK.equals(step)) {
            return containsAny(text, "网关", "网口", "网络")
                    && containsAny(text, "网络", "网线", "状态灯", "链路灯");
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return containsAny(text, "传感器", "温湿度")
                    && containsAny(text, "接线", "端子", "信号线");
        }
        if (STEP_METER_CHECK.equals(step)) {
            return containsAny(text, "万用表", "测量", "读数", "表笔", "测量点");
        }
        if (STEP_PLATFORM_RECOVERY.equals(step)) {
            return containsAny(text, "恢复", "正常", "重新上报")
                    && containsAny(text, "平台", "温湿度", "数据", "页面");
        }
        boolean platformAlarm = containsAny(text, "平台", "平台页面")
                && containsAny(text, "报警", "告警", "出现异常");
        return platformAlarm || (containsAny(text, "温湿度", "温度湿度")
                && containsAny(text, "平台", "数据", "页面"));
    }

    private static String normalizeStep(String step) {
        if (STEP_GATEWAY_POWER.equals(step) || STEP_GATEWAY_NETWORK.equals(step)
                || STEP_SENSOR_WIRING.equals(step) || STEP_METER_CHECK.equals(step)
                || STEP_PLATFORM_RECOVERY.equals(step) || STEP_COMPLETED.equals(step)
                || STEP_SYSTEM_CONTEXT.equals(step)) {
            return step;
        }
        return STEP_PLATFORM_ANOMALY;
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
                    result.add(value.trim().toLowerCase(Locale.ROOT));
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

    private static StepDefinition definition(String step) {
        if (STEP_SYSTEM_CONTEXT.equals(step)) {
            return new StepDefinition(STEP_GATEWAY_POWER,
                    tags("system-context", "honeywell-gateway", "temp-humidity-sensor"),
                    "请补充现场系统情况：可上传架构图、描述平台与设备的连接关系，或拍摄控制柜和设备铭牌。",
                    "已根据提供的现场信息建立临时采集链路：监控平台 → 网络 → 霍尼韦尔网关 → 温湿度传感器。下一步请确认网关铭牌或图纸上的额定电压，再测量电源输入。",
                    "监控平台", "霍尼韦尔网关", "温湿度传感器", "采集链路");
        }
        if (STEP_GATEWAY_POWER.equals(step)) {
            return new StepDefinition(STEP_GATEWAY_NETWORK,
                    tags("honeywell-gateway", "power-input", "rated-voltage", "meter",
                            "meter-reading", "probe-point", "voltage-in-range"),
                    "请不要只看电源灯。先拍摄铭牌或图纸额定值和网关输入端子，让 AI 确认测量位置；再用万用表直流电压档测量，并口述额定值、实测值、单位和测量点，无需拍摄万用表。",
                    "已按额定值和万用表读数确认网关输入电压在允许范围内。电源灯仅作辅助证据，下一步请拍摄网口、网线和网络状态灯。",
                    "额定电压信息", "电源输入端子", "建议测量点");
        }
        if (STEP_GATEWAY_NETWORK.equals(step)) {
            return new StepDefinition(STEP_SENSOR_WIRING,
                    tags("honeywell-gateway", "network-port", "link-led"),
                    "当前照片需同时看清网口、网线连接点和链路灯，请补拍网络接口区域。",
                    "网关网络侧未见明显异常。下一步请拍摄前端温湿度传感器本体和接线端子。",
                    "网口位置", "网线连接点", "网络状态灯");
        }
        if (STEP_SENSOR_WIRING.equals(step)) {
            return new StepDefinition(STEP_METER_CHECK,
                    tags("temp-humidity-sensor", "terminal", "signal-wire"),
                    "当前照片需同时看清温湿度传感器、接线端子和信号线，请补拍接线近景。",
                    "前面平台、网关供电和网络证据已基本排除，问题范围已收敛到温湿度传感器。请按现场安全规程断电、拆线并拍摄端子和线序；看清接线后，再用万用表直流电压档检查指定测量点。",
                    "温湿度传感器本体", "接线端子", "信号线");
        }
        if (STEP_METER_CHECK.equals(step)) {
            return new StepDefinition(STEP_PLATFORM_RECOVERY,
                    tags("terminal", "meter", "dc-voltage-mode", "meter-reading", "probe-point", "wiring-anomaly"),
                    "请直接口述测量结果：额定值、实测值、单位、测量位置和是否稳定。无需拍摄万用表；如需确认接线异常，请补充接线端子或线序位置。",
                    "最终根因：温湿度传感器接线异常。请复核松动、脱落、接反或接触不良，重新接线后回到平台确认数据恢复。",
                    "接线端子", "疑似异常接线", "万用表读数", "表笔测量点");
        }
        if (STEP_PLATFORM_RECOVERY.equals(step)) {
            return new StepDefinition(STEP_COMPLETED,
                    tags("platform", "temperature-normal", "refresh-time"),
                    "请拍摄温湿度数据恢复区域和页面刷新时间，完成闭环确认。",
                    "平台温湿度数据已恢复，本次接线异常排查闭环完成，照片与处理记录已保存。",
                    "温湿度恢复区域", "页面刷新时间");
        }
        return new StepDefinition(STEP_SYSTEM_CONTEXT,
                tags("platform", "temperature-missing", "other-data-normal"),
                "请补拍平台页面，画面需同时包含温湿度异常区域、其他正常数据和刷新时间。",
                "平台其他数据正常，温湿度数据属于局部异常，不像平台整体故障。请补充现场系统情况：可以上传架构图、描述设备连接关系，或拍摄控制柜和设备铭牌。",
                "温湿度异常区域", "其他正常数据", "页面刷新时间");
    }

    private static Set<String> tags(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static String stageLabel(String step) {
        if (STEP_SYSTEM_CONTEXT.equals(step)) return "补充现场系统情况";
        if (STEP_GATEWAY_POWER.equals(step)) return "检查网关供电";
        if (STEP_GATEWAY_NETWORK.equals(step)) return "检查网关网络";
        if (STEP_SENSOR_WIRING.equals(step)) return "检查传感器与接线";
        if (STEP_METER_CHECK.equals(step)) return "万用表与接线复核";
        if (STEP_PLATFORM_RECOVERY.equals(step)) return "确认平台恢复";
        if (STEP_COMPLETED.equals(step)) return "排查完成";
        return "确认平台异常";
    }

    private static final class StepDefinition {
        final String nextStep;
        final Set<String> requiredTags;
        final String retakePrompt;
        final String reply;
        final List<String> annotations;

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
