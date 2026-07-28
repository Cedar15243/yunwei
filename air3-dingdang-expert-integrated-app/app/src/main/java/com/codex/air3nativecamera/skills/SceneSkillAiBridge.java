package com.codex.air3nativecamera.skills;

import com.codex.air3nativecamera.task.TaskSession;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SceneSkillAiBridge {
    private static final Pattern EVIDENCE_LINE = Pattern.compile(
            "(?m)\\s*\\[\\[scene-evidence:([^\\]]+)]]\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CANDIDATE_LINE = Pattern.compile(
            "(?m)^\\s*\\[\\[scene-candidate:([^\\]]+)]]\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKER_LINE = Pattern.compile(
            "(?m)^\\s*\\[\\[scene-marker:([^\\]]+)]]\\s*$",
            Pattern.CASE_INSENSITIVE);

    public static final class DetectionMarker {
        private final String label;
        private final String status;
        private final double x;
        private final double y;
        private final double width;
        private final double height;
        private final int confidence;

        DetectionMarker(String label, String status, double x, double y, double width,
                double height, int confidence) {
            this.label = label;
            this.status = status;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }

        public String label() { return label; }
        public String status() { return status; }
        public double x() { return x; }
        public double y() { return y; }
        public double width() { return width; }
        public double height() { return height; }
        public int confidence() { return confidence; }
    }

    public static final class ParsedResponse {
        private final String visibleText;
        private final Set<String> evidenceTags;
        private final String candidateSkillId;
        private final java.util.List<DetectionMarker> markers;

        ParsedResponse(String visibleText, Set<String> evidenceTags, String candidateSkillId,
                java.util.List<DetectionMarker> markers) {
            this.visibleText = visibleText;
            this.evidenceTags = Collections.unmodifiableSet(new LinkedHashSet<>(evidenceTags));
            this.candidateSkillId = candidateSkillId == null ? "" : candidateSkillId.trim();
            this.markers = Collections.unmodifiableList(new java.util.ArrayList<>(markers));
        }

        public String visibleText() {
            return visibleText;
        }

        public Set<String> evidenceTags() {
            return evidenceTags;
        }

        public String candidateSkillId() {
            return candidateSkillId;
        }

        public java.util.List<DetectionMarker> markers() {
            return markers;
        }
    }

    private SceneSkillAiBridge() {
    }

    public static String instruction(TaskSession session) {
        return instruction(session, true);
    }

    public static String instruction(TaskSession session, boolean currentTurnHasPhoto) {
        if (session == null || !HoneywellTempHumiditySkill.SKILL_ID.equals(session.sceneSkillId())) {
            return "";
        }
        StepProtocol protocol = protocol(session.sceneStepId());
        boolean contextStep = HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT
                .equals(session.sceneStepId());
        boolean gatewayPowerStep = HoneywellTempHumiditySkill.STEP_GATEWAY_POWER
                .equals(session.sceneStepId());
        boolean meterStep = HoneywellTempHumiditySkill.STEP_METER_CHECK
                .equals(session.sceneStepId());
        boolean spokenMeasurementTurn = !currentTurnHasPhoto
                && (gatewayPowerStep || meterStep);
        String evidenceInstruction;
        if (contextStep) {
            evidenceInstruction = "可依据本轮上传的架构图、现场描述或设备照片建立临时采集链路；"
                    + "信息不足时只追问缺少的现场信息，不得假定设备品牌。"
                    + "只有确认链路包含霍尼韦尔网关和温湿度传感器时才算通过：";
        } else if (spokenMeasurementTurn) {
            evidenceInstruction = "本轮没有新照片。请结合任务中已确认的照片证据，只解析工程师本轮口述结果；"
                    + "不得虚构新的视觉证据。本轮可以只口述结果，已确认照片和口述信息共同满足这些要素才算通过：";
        } else {
            evidenceInstruction = "请只判断本轮实际照片，不得根据文字描述臆测照片中不存在的内容。"
                    + "照片同时清晰包含这些要素才算通过：";
        }
        return "\n\n本任务启用了现场证据核验。当前步骤：" + protocol.title + "。"
                + evidenceInstruction + protocol.tags + "。"
                + "当前问题与本步骤无关时输出 [[scene-evidence:unrelated]]。"
                + "证据不足时明确指出需要补拍的位置，不推进步骤。"
                + (gatewayPowerStep
                ? "网关供电先用照片确认铭牌额定值、输入端子和建议测量点；工程师完成测量后可以只口述结果，无需上传万用表照片。"
                        + "请解析额定值、实测值、单位、测量位置和稳定性。测量使用万用表直流电压档；电源灯只能作辅助证据。"
                        + "若读数低于额定范围输出 voltage-low，若波动明显输出 voltage-unstable，均不得推进网络步骤。"
                : "")
                + (meterStep
                ? "本轮可以没有万用表照片。请结合任务中已确认的接线照片，解析工程师口述的额定值、实测值、单位、测量位置和稳定性；"
                        + "测量电压使用万用表直流电压档，不要把电流档直接跨接在电源两端。"
                : "")
                + "回复最后单独输出 [[scene-evidence:已确认的英文标签]]；"
                + "若证据不足输出 [[scene-evidence:insufficient]]。该行不要解释。"
                + markerInstruction();
    }

    public static String candidateInstruction() {
        return "\n\n请核对本轮实际照片是否同时满足：监控平台页面、温湿度数据缺失或异常、"
                + "其他监测数据正常。不得只根据用户文字判断。"
                + "全部满足时在回复最后输出 [[scene-candidate:"
                + HoneywellTempHumiditySkill.SKILL_ID + "]]，否则输出 [[scene-candidate:none]]。"
                + "另起一行输出已确认的 [[scene-evidence:platform,temperature-missing,other-data-normal]]；"
                + "证据不足时输出 [[scene-evidence:insufficient]]。这些标记不要解释。"
                + markerInstruction();
    }

    public static String imageMarkerInstruction() {
        return "\n\n请仅根据本轮实际图片识别可见设备、状态和异常区域。"
                + "检测区域使用下面的 scene-marker 协议返回。机器标记行不会展示给现场人员，"
                + "不属于维修建议，也不受单步回复限制。图片中存在可辨识区域时必须至少输出一个标记；"
                + "无法可靠定位时不要输出标记。"
                + markerInstruction();
    }

    public static ParsedResponse parse(String response) {
        String source = response == null ? "" : response.trim();
        Matcher candidateMatcher = CANDIDATE_LINE.matcher(source);
        String candidateSkillId = "";
        if (candidateMatcher.find()) {
            candidateSkillId = candidateMatcher.group(1).trim().toLowerCase(Locale.ROOT);
            source = candidateMatcher.replaceAll("").trim();
        }
        Matcher matcher = EVIDENCE_LINE.matcher(source);
        Set<String> tags = new LinkedHashSet<>();
        if (matcher.find()) {
            for (String value : matcher.group(1).split(",")) {
                String tag = value.trim().toLowerCase(Locale.ROOT);
                if (tag.length() > 0) {
                    tags.add(tag);
                }
            }
            source = matcher.replaceAll("").trim();
        }
        Matcher markerMatcher = MARKER_LINE.matcher(source);
        java.util.List<DetectionMarker> markers = new java.util.ArrayList<>();
        while (markerMatcher.find()) {
            DetectionMarker marker = parseMarker(markerMatcher.group(1));
            if (marker != null) {
                markers.add(marker);
            }
        }
        source = markerMatcher.replaceAll("").trim();
        return new ParsedResponse(source, tags, candidateSkillId, markers);
    }

    public static String markersJson(java.util.List<DetectionMarker> markers) {
        if (markers == null || markers.isEmpty()) {
            return "[]";
        }
        StringBuilder result = new StringBuilder("[");
        for (DetectionMarker marker : markers) {
            if (marker == null) {
                continue;
            }
            if (result.length() > 1) {
                result.append(',');
            }
            result.append("{\"label\":\"").append(jsonText(marker.label()))
                    .append("\",\"status\":\"").append(marker.status())
                    .append("\",\"x\":").append(marker.x())
                    .append(",\"y\":").append(marker.y())
                    .append(",\"width\":").append(marker.width())
                    .append(",\"height\":").append(marker.height())
                    .append(",\"confidence\":").append(marker.confidence())
                    .append('}');
        }
        return result.append(']').toString();
    }

    private static StepProtocol protocol(String step) {
        if (HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT.equals(step)) {
            return new StepProtocol("补充现场系统情况",
                    "system-context, honeywell-gateway, temp-humidity-sensor");
        }
        if (HoneywellTempHumiditySkill.STEP_GATEWAY_POWER.equals(step)) {
            return new StepProtocol("检查霍尼韦尔网关供电",
                    "honeywell-gateway, power-input, rated-voltage, meter, meter-reading, probe-point, voltage-in-range");
        }
        if (HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK.equals(step)) {
            return new StepProtocol("检查霍尼韦尔网关网络",
                    "honeywell-gateway, network-port, link-led");
        }
        if (HoneywellTempHumiditySkill.STEP_SENSOR_WIRING.equals(step)) {
            return new StepProtocol("检查温湿度传感器本体与接线",
                    "temp-humidity-sensor, terminal, signal-wire");
        }
        if (HoneywellTempHumiditySkill.STEP_METER_CHECK.equals(step)) {
            return new StepProtocol("检查接线并读取万用表",
                    "terminal, meter, dc-voltage-mode, meter-reading, probe-point, wiring-anomaly");
        }
        if (HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY.equals(step)) {
            return new StepProtocol("确认平台数据恢复",
                    "platform, temperature-normal, refresh-time");
        }
        return new StepProtocol("确认平台温湿度局部异常",
                "platform, temperature-missing, other-data-normal");
    }

    private static String markerInstruction() {
        return "\n如本轮图片中可可靠定位检测区域，可逐行输出 [[scene-marker:标签|状态|x|y|宽|高|置信度]]。"
                + "状态只能为 normal、suspect、abnormal 或 unknown；坐标和尺寸均为 0 到 1 的图片比例，"
                + "置信度为 0 到 1。无法可靠定位时不要输出标记。";
    }

    private static DetectionMarker parseMarker(String raw) {
        if (raw == null) {
            return null;
        }
        String[] fields = raw.split("\\|", -1);
        if (fields.length != 7) {
            return null;
        }
        String label = fields[0].trim();
        String status = fields[1].trim().toLowerCase(Locale.ROOT);
        if (label.length() == 0 || !isMarkerStatus(status)) {
            return null;
        }
        try {
            double x = Double.parseDouble(fields[2].trim());
            double y = Double.parseDouble(fields[3].trim());
            double width = Double.parseDouble(fields[4].trim());
            double height = Double.parseDouble(fields[5].trim());
            double confidence = Double.parseDouble(fields[6].trim());
            if (x < 0d || y < 0d || width <= 0d || height <= 0d || confidence < 0d
                    || confidence > 1d || x + width > 1d || y + height > 1d) {
                return null;
            }
            return new DetectionMarker(label, status, x, y, width, height,
                    (int) Math.round(confidence * 100d));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isMarkerStatus(String status) {
        return "normal".equals(status) || "suspect".equals(status)
                || "abnormal".equals(status) || "unknown".equals(status);
    }

    private static String jsonText(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static final class StepProtocol {
        final String title;
        final String tags;

        StepProtocol(String title, String tags) {
            this.title = title;
            this.tags = tags;
        }
    }
}
