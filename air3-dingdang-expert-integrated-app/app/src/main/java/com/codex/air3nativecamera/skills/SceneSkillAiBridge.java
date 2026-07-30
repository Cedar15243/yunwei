package com.codex.air3nativecamera.skills;

import com.codex.air3nativecamera.task.TaskSession;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SceneSkillAiBridge {
    private static final int MAX_HUD_MARKERS = 4;
    private static final Pattern EVIDENCE_LINE = Pattern.compile(
            "(?m)\\s*\\[\\[scene-evidence:([^\\]]+)]]\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CANDIDATE_LINE = Pattern.compile(
            "(?m)^\\s*\\[\\[scene-candidate:([^\\]]+)]]\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKER_LINE = Pattern.compile(
            "(?m)^\\s*\\[\\[scene-marker:([^\\]]+)]]\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLTAGE_VALUE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:伏|v(?:ac|dc)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SPOKEN_NUMBER = Pattern.compile(
            "(?:实测(?:电压)?|测得|测出来|电压(?:是|为)?|读数(?:是|为)?|表显(?:是|为)?|"
                    + "只有|现在(?:是|为)?|测了)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern CHINESE_VOLTAGE_VALUE = Pattern.compile(
            "([零〇一二两三四五六七八九十百点]+)\\s*(?:伏|v(?:ac|dc)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_CHINESE_SPOKEN_NUMBER = Pattern.compile(
            "(?:实测(?:电压)?|测得|测出来|电压(?:是|为)?|读数(?:是|为)?|表显(?:是|为)?|"
                    + "只有|现在(?:是|为)?|测了)\\s*"
                    + "([零〇一二两三四五六七八九十百]+(?:点[零〇一二两三四五六七八九]+)?)");

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
        private final java.util.List<DetectionMarker> evidenceMarkers;

        ParsedResponse(String visibleText, Set<String> evidenceTags, String candidateSkillId,
                java.util.List<DetectionMarker> markers,
                java.util.List<DetectionMarker> evidenceMarkers) {
            this.visibleText = visibleText;
            this.evidenceTags = Collections.unmodifiableSet(new LinkedHashSet<>(evidenceTags));
            this.candidateSkillId = candidateSkillId == null ? "" : candidateSkillId.trim();
            this.markers = Collections.unmodifiableList(new java.util.ArrayList<>(markers));
            this.evidenceMarkers = Collections.unmodifiableList(
                    new java.util.ArrayList<>(evidenceMarkers));
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

        public java.util.List<DetectionMarker> evidenceMarkers() {
            return evidenceMarkers;
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
        String step = HoneywellTempHumiditySkill.normalizeStepId(session.sceneStepId());
        StepProtocol protocol = protocol(step);
        boolean contextStep = HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT
                .equals(step);
        boolean ddcPowerPhotoStep = HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO
                .equals(step);
        boolean ddcPowerMeasurementStep = HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT
                .equals(step);
        boolean gatewayLightStep = HoneywellTempHumiditySkill.STEP_DDC_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK.equals(step);
        boolean meterStep = HoneywellTempHumiditySkill.STEP_METER_CHECK
                .equals(step);
        boolean sensorWiringStep = HoneywellTempHumiditySkill.STEP_SENSOR_WIRING
                .equals(step);
        boolean platformRecoveryStep = HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY
                .equals(step);
        boolean spokenMeasurementTurn = !currentTurnHasPhoto
                && (ddcPowerMeasurementStep || gatewayLightStep || sensorWiringStep || meterStep
                || platformRecoveryStep);
        String evidenceInstruction;
        if (contextStep) {
            evidenceInstruction = "可依据本轮上传的架构图、现场描述或设备照片建立临时采集链路；"
                    + "用户明确说明本轮是架构图、系统图、拓扑图或连接关系，且内容确实可用于建立链路时输出 system-context。"
                    + "识别到具体设备时再附加对应标签，不得为了满足协议臆造品牌；信息不足时输出 insufficient：";
        } else if (spokenMeasurementTurn) {
            evidenceInstruction = "本轮没有新照片。请结合任务中已确认的照片证据，只解析工程师本轮口述结果；"
                    + "不得虚构新的视觉证据。本轮可以只口述结果，已确认照片和口述信息共同满足这些要素才算通过：";
        } else if (sensorWiringStep) {
            evidenceInstruction = "上一阶段已确认这是温湿度传感器；本轮只判断拆盖后的实际照片，"
                    + "不得因当前图看不到外壳品牌而要求重新确认设备身份。照片清晰包含这些要素才算通过：";
        } else {
            evidenceInstruction = "请只判断本轮实际照片，不得根据文字描述臆测照片中不存在的内容。"
                    + "眼镜照片分辨率较低时，只要仍能辨认当前检查对象和关键区域，就按可见内容输出标签，"
                    + "不要仅因画质低拒绝。照片应包含这些要素：";
        }
        return "\n\n本任务启用了现场证据核验。当前步骤：" + protocol.title + "。"
                + evidenceInstruction + protocol.tags + "。"
                + "当前问题与本步骤无关时输出 [[scene-evidence:unrelated]]。"
                + "证据不足时明确指出需要补拍的位置，不推进步骤。"
                + (ddcPowerPhotoStep
                ? "本轮只确认 DDC 设备身份、24VAC/COM 电源输入端和建议测量点。"
                        + "只要照片清晰看到 Honeywell 品牌或工控 DDC 本体标识，并能定位 24VAC 与 COM 电源端子，"
                        + "就必须输出 honeywell-ddc、power-input；不要求另有独立铭牌或型号贴纸。"
                        + "检测框紧贴 24VAC/COM 电源端子，即上方黑色三位端子区中的 10号 24VAC 与 11号 COM 测量端；"
                        + "12号 E-GND 可以出现在同一区域，但不是本步测量点。"
                        + "不要框选黄色设备名称标签，也不要框选下方 1号、2号 S-BUS 端子。"
                        + "不得仅凭 STA/485 灯判断供电正常。"
                : "")
                + (ddcPowerMeasurementStep
                ? "工程师可以只口述 DDC 测量结果，无需上传万用表照片。请解析额定值、实测值、单位、24VAC/COM 测量位置和稳定性。"
                        + "若读数低于额定范围输出 voltage-low，若波动明显输出 voltage-unstable，均不得推进 485 检查。"
                : "")
                + (gatewayLightStep
                ? "本轮不要求照片。分别解析网关上方485通讯灯和下方网络通讯灯是不亮、常亮还是闪烁。"
                        + "485灯闪烁输出 gateway-rs485-blinking，不亮输出 gateway-rs485-off，常亮输出 gateway-rs485-solid；"
                        + "网络灯闪烁输出 network-link-blinking，不亮输出 network-link-off，常亮输出 network-link-solid。"
                        + "只有两个灯都明确为闪烁才能通过；描述不清时输出 insufficient。"
                : "")
                + (meterStep
                ? "本轮可以没有万用表照片。请结合任务中已确认的接线照片，解析工程师口述的额定值、实测值、单位、测量位置和稳定性；"
                        + "测量电压使用万用表直流电压档，不要把电流档直接跨接在电源两端。"
                : "")
                + (sensorWiringStep
                ? "本轮允许没有照片。请解析工程师口述的接线检查或处理结果，以及传感器电源点与公共端之间的电压状态；"
                        + "确认虚接、松动或接触不良时输出 terminal、wiring-anomaly，确认已经紧固或重新接好时输出 wiring-resolved。"
                        + "接线正常时输出 terminal、wiring-resolved；用户明确说电压正常、供电正常、12V正常，"
                        + "或实测值在10.8-13.2V且稳定时输出 meter-reading、probe-point、voltage-in-range；"
                        + "偏离12V或读数波动时输出 voltage-low、voltage-high 或 voltage-unstable。"
                        + "“接线接触不良”“原来虚接现在已恢复”“接线正常电压正常”“12V正常”均是可推进的明确现场结果，"
                        + "不得因为没有万用表照片或精确小数输出 insufficient。"
                : "")
                + (platformRecoveryStep
                ? "本轮允许没有照片。工程师确认平台温湿度数据恢复时输出 platform、temperature-normal；"
                        + "未明确恢复时输出 insufficient。"
                : "")
                + "回复最后单独输出 [[scene-evidence:已确认的英文标签]]；"
                + "若证据不足输出 [[scene-evidence:insufficient]]。该行不要解释。"
                + (currentTurnHasPhoto
                ? "本轮有照片，请尽量为当前检查对象输出可靠检测框：" + protocol.markerTargets
                        + "；能确认对象但无法精确框选时，保留证据标签且不要伪造坐标。"
                : "")
                + markerInstruction();
    }

    public static String candidateInstruction() {
        return "\n\n请核对本轮实际照片是否同时满足：监控平台页面、右侧报警列表中存在温湿度传感器报警记录、"
                + "其他监测数据正常。不得只根据用户文字判断。"
                + "只要图中可见监控大屏、工业数字化大屏或设备监控界面，必须包含 platform 标签。"
                + "全部满足时在回复最后输出 [[scene-candidate:"
                + HoneywellTempHumiditySkill.SKILL_ID + "]]，否则输出 [[scene-candidate:none]]。"
                + "另起一行输出已确认的 [[scene-evidence:platform,temperature-missing,other-data-normal]]；"
                + "证据不足时输出 [[scene-evidence:insufficient]]。这些标记不要解释。"
                + "只输出一个检测框，紧贴右侧报警列表中的温湿度传感器报警记录；"
                + "不要框选下方机房环境检测区，也不要框选整个屏幕。"
                + "不能可靠框选时输出 insufficient，不得绑定任务。"
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
        while (matcher.find()) {
            for (String value : matcher.group(1).split(",")) {
                String tag = normalizeEvidenceTag(value);
                if (tag.length() > 0) {
                    tags.add(tag);
                }
            }
        }
        source = EVIDENCE_LINE.matcher(source).replaceAll("").trim();
        Matcher markerMatcher = MARKER_LINE.matcher(source);
        java.util.List<DetectionMarker> markers = new java.util.ArrayList<>();
        while (markerMatcher.find()) {
            DetectionMarker marker = parseMarker(markerMatcher.group(1));
            if (marker != null) {
                markers.add(marker);
            }
        }
        source = markerMatcher.replaceAll("").trim();
        boolean rejectedEvidence = tags.contains("insufficient") || tags.contains("unrelated");
        if (!rejectedEvidence) {
            for (DetectionMarker marker : markers) {
                String markerEvidence = highConfidenceMarkerEvidenceTag(marker);
                if (markerEvidence.length() > 0) {
                    tags.add(markerEvidence);
                }
            }
        }
        java.util.List<DetectionMarker> hudMarkers = rejectedEvidence
                ? Collections.emptyList() : selectHudMarkers(markers);
        java.util.List<DetectionMarker> evidenceMarkers = rejectedEvidence
                ? Collections.emptyList() : markers;
        return new ParsedResponse(source, tags, candidateSkillId, hudMarkers, evidenceMarkers);
    }

    public static Set<String> reconcileEvidence(TaskSession session, String narration,
            ParsedResponse parsed, boolean currentTurnHasPhoto) {
        Set<String> result = new LinkedHashSet<>();
        if (parsed != null) {
            result.addAll(parsed.evidenceTags());
        }
        if (session == null
                || !HoneywellTempHumiditySkill.SKILL_ID.equals(session.sceneSkillId())) {
            return Collections.unmodifiableSet(result);
        }
        String step = HoneywellTempHumiditySkill.normalizeStepId(session.sceneStepId());
        String spokenText = narration == null ? "" : narration.trim().toLowerCase(Locale.ROOT);
        boolean gatewayLightStep = HoneywellTempHumiditySkill.STEP_DDC_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK.equals(step);
        boolean photoGatewayLightTurn = currentTurnHasPhoto && gatewayLightStep;
        if (photoGatewayLightTurn) {
            removeEvidenceState(result, "gateway-rs485-");
            removeEvidenceState(result, "network-link-");
            result.remove("rs485-abnormal");
            result.remove("network-abnormal");
            result.add("insufficient");
        }
        boolean explicitSpokenSystemContext = HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT
                .equals(step) && describesSystemRelationship(
                        spokenText);
        boolean explicitSpokenStepResult = !photoGatewayLightTurn
                && hasDeterministicSpokenEvidence(step, spokenText);
        if (explicitSpokenSystemContext || explicitSpokenStepResult) {
            result.remove("unrelated");
            result.remove("insufficient");
        } else if (!photoGatewayLightTurn
                && (result.contains("unrelated") || result.contains("insufficient"))) {
            return Collections.unmodifiableSet(result);
        }
        java.util.List<DetectionMarker> markers = parsed == null
                ? Collections.<DetectionMarker>emptyList() : parsed.evidenceMarkers();
        if (currentTurnHasPhoto) {
            reconcilePhotoEvidence(step, markers, result);
        }
        if (photoGatewayLightTurn) {
            return Collections.unmodifiableSet(result);
        }
        reconcileSpokenEvidence(step, narration, result);
        return Collections.unmodifiableSet(result);
    }

    private static void reconcilePhotoEvidence(String step, java.util.List<DetectionMarker> markers,
            Set<String> evidence) {
        if (HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT.equals(step)) {
            reconcileSystemContext(markers, evidence);
        }
        for (DetectionMarker marker : markers) {
            if (marker == null || marker.confidence() < 75) {
                continue;
            }
            String key = markerKey(marker.label());
            if (isDdcLabel(key)) {
                evidence.add("honeywell-ddc");
            }
            if (isGatewayLabel(key)) {
                evidence.add("honeywell-gateway");
            }
            if (isSensorLabel(key)) {
                evidence.add("temp-humidity-sensor");
            }
            if (HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY.equals(step)) {
                if (isPlatformLabel(key)) evidence.add("platform");
                if ((key.contains("温湿度") && containsAny(key, "异常", "缺失", "无数据", "报警", "告警"))
                        || containsAny(key, "报警显示区", "报警列表", "告警列表", "待处理告警")) {
                    evidence.add("temperature-missing");
                }
                if (containsAny(key, "其他正常", "其余正常", "正常数据")) {
                    evidence.add("other-data-normal");
                }
            } else if (HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO.equals(step)) {
                if ((containsAny(key, "24vac", "24v", "10号", "电源", "供电"))
                        && containsAny(key, "11号", "com", "输入", "端子", "测量点")) {
                    evidence.add("power-input");
                }
            } else if (HoneywellTempHumiditySkill.STEP_DDC_RS485.equals(step)) {
                if (key.contains("485") && containsAny(key, "端子", "接线", "485+", "485-")) {
                    evidence.add("rs485-terminal");
                }
                if (key.contains("485") && containsAny(key, "灯", "指示", "状态")) {
                    evidence.add("rs485-led");
                }
            } else if (HoneywellTempHumiditySkill.STEP_GATEWAY_RS485.equals(step)) {
                if (containsAny(key, "com1", "com2", "485")
                        && containsAny(key, "灯", "指示", "状态")) {
                    evidence.add("gateway-rs485-led");
                }
                if (containsAny(key, "rj45", "网口", "网络口")) evidence.add("network-port");
                if (containsAny(key, "网线", "networkcable", "ethernetcable")) {
                    evidence.add("network-cable");
                }
                if (containsAny(key, "link灯", "网络灯", "网络状态灯", "链路灯", "网口通讯灯", "网络口通讯灯")) {
                    evidence.add("link-led");
                }
            } else if (HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK.equals(step)) {
                if (containsAny(key, "rj45", "网口", "网络口")) evidence.add("network-port");
                if (containsAny(key, "网线", "networkcable", "ethernetcable")) {
                    evidence.add("network-cable");
                }
                if (containsAny(key, "link灯", "网络灯", "网络状态灯", "链路灯")) {
                    evidence.add("link-led");
                }
            } else if (HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE.equals(step)) {
                if (containsAny(key, "线缆入口", "电缆入口", "进线口", "cableentry")) {
                    evidence.add("sensor-cable-entry");
                }
            } else if (HoneywellTempHumiditySkill.STEP_SENSOR_WIRING.equals(step)) {
                if (key.contains("端子")) evidence.add("terminal");
                if (containsAny(key, "信号线", "接入端子", "导线", "线序")) {
                    evidence.add("signal-wire");
                }
                if (containsAny(key, "裸露导体", "露铜")) evidence.add("exposed-conductor");
                if (containsAny(key, "松动", "脱落")) evidence.add("loose-wire");
                if (containsAny(key, "接线异常", "接触不良")) evidence.add("wiring-suspect");
            } else if (HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY.equals(step)) {
                if (isPlatformLabel(key)) evidence.add("platform");
                if (key.contains("温湿度") && containsAny(key, "恢复", "正常")) {
                    evidence.add("temperature-normal");
                }
                if (key.contains("刷新") && containsAny(key, "时间", "时刻")) {
                    evidence.add("refresh-time");
                }
            }
        }
    }

    private static void reconcileSystemContext(java.util.List<DetectionMarker> markers,
            Set<String> evidence) {
        boolean ddc = false;
        boolean gateway = false;
        boolean platform = false;
        boolean networkNode = false;
        boolean sensor = false;
        for (DetectionMarker marker : markers) {
            if (marker == null || marker.confidence() < 65) {
                continue;
            }
            String key = markerKey(marker.label());
            ddc |= isDdcLabel(key);
            gateway |= isGatewayLabel(key);
            platform |= isPlatformLabel(key) || key.contains("服务器");
            networkNode |= containsAny(key, "交换机", "switch", "路由器", "网络节点");
            sensor |= isSensorLabel(key);
        }
        if (ddc) evidence.add("honeywell-ddc");
        if (gateway) evidence.add("honeywell-gateway");
        if (platform) evidence.add("platform");
        if (sensor) evidence.add("temp-humidity-sensor");
        int nodes = (ddc ? 1 : 0) + (gateway ? 1 : 0) + (platform ? 1 : 0)
                + (networkNode ? 1 : 0) + (sensor ? 1 : 0);
        if ((ddc && gateway) || nodes >= 3) {
            evidence.add("system-context");
        }
    }

    private static void reconcileSpokenEvidence(String step, String narration,
            Set<String> evidence) {
        String text = narration == null ? "" : narration.trim().toLowerCase(Locale.ROOT);
        if (HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT.equals(step)
                && describesSystemRelationship(text)) {
            evidence.add("system-context");
        }
        if (HoneywellTempHumiditySkill.STEP_SENSOR_WIRING.equals(step)) {
            if (!HoneywellTempHumiditySkill.hasSensorWiringOutcome(text)) {
                return;
            }
            if (containsAny(text, "虚接", "松动", "松了", "有点松", "线松", "接触不良", "脱落", "接反")) {
                evidence.add("terminal");
                evidence.add("wiring-anomaly");
            }
            if (HoneywellTempHumiditySkill.hasResolvedOrNormalWiringStatement(text)) {
                evidence.add("terminal");
                evidence.add("wiring-resolved");
            }
            if (HoneywellTempHumiditySkill.hasNormalSensorVoltageStatement(text)) {
                evidence.add("terminal");
                evidence.add("meter-reading");
                evidence.add("probe-point");
                evidence.add("voltage-in-range");
            }
        }
        if (HoneywellTempHumiditySkill.STEP_DDC_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK.equals(step)) {
            reconcileGatewayLightStates(text, evidence);
        }
        if (HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY.equals(step)
                && containsAny(text, "恢复", "正常", "重新上报", "数据有了", "报警没了", "不报警了", "好了")
                && !containsAny(text, "没恢复", "未恢复", "还是异常", "还有报警")) {
            evidence.add("platform");
            evidence.add("temperature-normal");
        }
        Double voltageValue = spokenVoltage(text);
        if (voltageValue == null) {
            return;
        }
        double voltage = voltageValue.doubleValue();
        removeEvidenceState(evidence, "voltage-");
        if (HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT.equals(step)) {
            evidence.add("honeywell-ddc");
            evidence.add("rated-voltage");
            evidence.add("meter-reading");
            evidence.add("probe-point");
            boolean unstable = containsAny(text, "不稳定", "波动", "跳动");
            if (unstable) evidence.add("voltage-unstable");
            if (voltage < 21.6d) {
                evidence.add("voltage-low");
            } else if (voltage > 26.4d) {
                evidence.add("voltage-high");
            } else if (!unstable) {
                evidence.add("voltage-in-range");
            }
        } else if (HoneywellTempHumiditySkill.STEP_SENSOR_WIRING.equals(step)) {
            evidence.add("terminal");
            evidence.add("meter-reading");
            evidence.add("probe-point");
            boolean unstable = containsAny(text, "不稳定", "波动", "跳动", "乱跳");
            if (unstable) evidence.add("voltage-unstable");
            if (voltage < 10.8d) {
                evidence.add("voltage-low");
            } else if (voltage > 13.2d) {
                evidence.add("voltage-high");
            } else if (!unstable) {
                evidence.add("voltage-in-range");
            }
        } else if (HoneywellTempHumiditySkill.STEP_METER_CHECK.equals(step)) {
            evidence.add("terminal");
            evidence.add("meter-reading");
            evidence.add("probe-point");
            if (containsAny(text, "异常", "不正常", "没电", "没有电", "断路",
                    "接触不良", "松动", "脱落", "接反")) {
                evidence.add("wiring-anomaly");
            }
        }
    }

    private static void reconcileGatewayLightStates(String text, Set<String> evidence) {
        String compact = text == null ? "" : text.replace(" ", "");
        Set<String> spokenStates = new LinkedHashSet<>();
        boolean bothSubjects = containsAny(compact, "485", "上面", "上方")
                && containsAny(compact, "网络", "网口", "下面", "下方", "rj45");
        if ((containsAny(compact, "两个都闪", "都在闪", "都是闪", "全部闪"))
                || (bothSubjects && compact.contains("都") && containsAny(compact, "闪", "闪烁"))) {
            spokenStates.add("gateway-rs485-blinking");
            spokenStates.add("network-link-blinking");
        } else {
            String rs485State = indicatorState(compact,
                    new String[] {"485", "com1", "com2", "上面", "上方"});
            String networkState = indicatorState(compact,
                    new String[] {"网络", "网口", "rj45", "下面", "下方"});
            if (rs485State.length() > 0) spokenStates.add("gateway-rs485-" + rs485State);
            if (networkState.length() > 0) spokenStates.add("network-link-" + networkState);
        }
        if (spokenStates.isEmpty()) return;
        removeEvidenceState(evidence, "gateway-rs485-");
        removeEvidenceState(evidence, "network-link-");
        evidence.remove("rs485-abnormal");
        evidence.remove("network-abnormal");
        evidence.addAll(spokenStates);
    }

    private static String indicatorState(String text, String[] subjects) {
        String[] clauses = text.split("[，,。；;]");
        for (String clause : clauses) {
            if (!containsAny(clause, subjects)) {
                continue;
            }
            if (containsAny(clause, "不亮", "没亮", "没有亮", "熄灭")) return "off";
            if (containsAny(clause, "常亮", "一直亮", "长亮")) return "solid";
            if (containsAny(clause, "闪烁", "在闪", "闪", "一闪一闪")) return "blinking";
        }
        return "";
    }

    static Double spokenVoltage(String text) {
        String source = text == null ? "" : text;
        Double lastVoltage = null;
        int lastVoltageIndex = -1;
        Matcher voltageMatcher = VOLTAGE_VALUE.matcher(source);
        while (voltageMatcher.find()) {
            try {
                if (voltageMatcher.start() >= lastVoltageIndex) {
                    lastVoltage = Double.valueOf(voltageMatcher.group(1));
                    lastVoltageIndex = voltageMatcher.start();
                }
            } catch (NumberFormatException ignored) {
                // Ignore this candidate and continue looking for a later valid reading.
            }
        }
        Matcher chineseVoltageMatcher = CHINESE_VOLTAGE_VALUE.matcher(source);
        while (chineseVoltageMatcher.find()) {
            Double parsed = parseChineseNumber(chineseVoltageMatcher.group(1));
            if (parsed != null && chineseVoltageMatcher.start() >= lastVoltageIndex) {
                lastVoltage = parsed;
                lastVoltageIndex = chineseVoltageMatcher.start();
            }
        }
        if (lastVoltage != null) {
            return lastVoltage;
        }
        String compact = source.replace(" ", "");
        Matcher number = EXPLICIT_SPOKEN_NUMBER.matcher(compact);
        Double lastNumber = null;
        int lastNumberIndex = -1;
        while (number.find()) {
            try {
                lastNumber = Double.valueOf(number.group(1));
                lastNumberIndex = number.start(1);
            } catch (NumberFormatException ignored) {
                // Ignore this candidate and continue looking for a later valid reading.
            }
        }
        Matcher chineseNumber = EXPLICIT_CHINESE_SPOKEN_NUMBER.matcher(compact);
        while (chineseNumber.find()) {
            Double parsed = parseChineseNumber(chineseNumber.group(1));
            if (parsed != null && chineseNumber.start(1) >= lastNumberIndex) {
                lastNumber = parsed;
                lastNumberIndex = chineseNumber.start(1);
            }
        }
        return lastNumber;
    }

    private static Double parseChineseNumber(String value) {
        if (value == null || value.length() == 0) {
            return null;
        }
        String[] parts = value.replace('〇', '零').split("点", -1);
        if (parts.length > 2 || parts[0].length() == 0) {
            return null;
        }
        Integer integer = parseChineseInteger(parts[0]);
        if (integer == null) {
            return null;
        }
        double result = integer.doubleValue();
        if (parts.length == 2) {
            if (parts[1].length() == 0) {
                return null;
            }
            double place = 0.1d;
            for (int i = 0; i < parts[1].length(); i++) {
                int digit = chineseDigit(parts[1].charAt(i));
                if (digit < 0) {
                    return null;
                }
                result += digit * place;
                place /= 10d;
            }
        }
        return result;
    }

    private static Integer parseChineseInteger(String value) {
        int total = 0;
        int current = -1;
        boolean usedUnit = false;
        int digitCount = 0;
        for (int i = 0; i < value.length(); i++) {
            char token = value.charAt(i);
            int digit = chineseDigit(token);
            if (digit >= 0) {
                current = digit;
                digitCount++;
                continue;
            }
            int unit = token == '十' ? 10 : (token == '百' ? 100 : 0);
            if (unit == 0) {
                return null;
            }
            total += (current < 0 ? 1 : current) * unit;
            current = -1;
            usedUnit = true;
        }
        if (!usedUnit && digitCount > 1) {
            return null;
        }
        return total + Math.max(current, 0);
    }

    private static int chineseDigit(char value) {
        switch (value) {
            case '零': return 0;
            case '一': return 1;
            case '二':
            case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    private static boolean hasDeterministicSpokenEvidence(String step, String text) {
        if (HoneywellTempHumiditySkill.STEP_SENSOR_WIRING.equals(step)) {
            return HoneywellTempHumiditySkill.hasSensorWiringOutcome(text);
        }
        if (HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT.equals(step)
                || HoneywellTempHumiditySkill.STEP_METER_CHECK.equals(step)) {
            return spokenVoltage(text) != null;
        }
        if (HoneywellTempHumiditySkill.STEP_DDC_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_RS485.equals(step)
                || HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK.equals(step)) {
            Set<String> states = new LinkedHashSet<>();
            reconcileGatewayLightStates(text, states);
            return !states.isEmpty();
        }
        return HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY.equals(step)
                && containsAny(text, "恢复", "正常", "重新上报", "数据有了", "报警没了", "不报警了", "好了")
                && !containsAny(text, "没恢复", "未恢复", "还是异常", "还有报警");
    }

    private static void removeEvidenceState(Set<String> evidence, String prefix) {
        java.util.Iterator<String> iterator = evidence.iterator();
        while (iterator.hasNext()) {
            String value = iterator.next();
            if (value.startsWith(prefix)) {
                iterator.remove();
            }
        }
    }

    private static boolean describesSystemRelationship(String text) {
        int entities = 0;
        if (containsAny(text, "平台", "服务器")) entities++;
        if (containsAny(text, "网关", "gateway")) entities++;
        if (containsAny(text, "ddc", "工控")) entities++;
        if (containsAny(text, "传感器", "温湿度")) entities++;
        return entities >= 2 && containsAny(text, "连接", "接到", "接网关", "通过", "链路", "再接");
    }

    private static String markerKey(String label) {
        return (label == null ? "" : label.trim().toLowerCase(Locale.ROOT))
                .replace(" ", "").replace("_", "").replace("-", "");
    }

    private static boolean isDdcLabel(String key) {
        return key.contains("ddc") || key.contains("工控控制器");
    }

    private static boolean isGatewayLabel(String key) {
        return key.contains("网关") || key.contains("gateway");
    }

    private static boolean isPlatformLabel(String key) {
        return key.contains("平台") || key.contains("监控界面") || key.contains("platform");
    }

    private static boolean isSensorLabel(String key) {
        return key.contains("温湿度传感器") || key.contains("temperaturehumiditysensor");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
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

    public static java.util.List<DetectionMarker> markersForStep(TaskSession session,
            ParsedResponse parsed) {
        if (parsed == null) {
            return Collections.emptyList();
        }
        if (session == null
                || !HoneywellTempHumiditySkill.SKILL_ID.equals(session.sceneSkillId())) {
            return parsed.markers();
        }
        String step = HoneywellTempHumiditySkill.normalizeStepId(session.sceneStepId());
        java.util.List<DetectionMarker> source = parsed.evidenceMarkers();
        if (HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY.equals(step)) {
            return mergedMarkerList("右侧温湿度报警列表", source, MarkerGroup.PLATFORM_ALARM);
        }
        if (HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO.equals(step)) {
            return mergedMarkerList("10号24VAC与11号COM测量端", source,
                    MarkerGroup.DDC_POWER);
        }
        if (HoneywellTempHumiditySkill.STEP_GATEWAY_RS485.equals(step)) {
            java.util.List<DetectionMarker> result = new java.util.ArrayList<>(2);
            DetectionMarker rs485 = mergeMarkerGroup("COM1/COM2 485通讯灯", source,
                    MarkerGroup.GATEWAY_RS485_LIGHTS);
            DetectionMarker network = mergeMarkerGroup("RJ45网口通讯灯", source,
                    MarkerGroup.GATEWAY_NETWORK_LIGHTS);
            if (rs485 != null) result.add(rs485);
            if (network != null) result.add(network);
            return Collections.unmodifiableList(result);
        }
        return parsed.markers();
    }

    private static java.util.List<DetectionMarker> mergedMarkerList(String label,
            java.util.List<DetectionMarker> source, MarkerGroup group) {
        DetectionMarker marker = mergeMarkerGroup(label, source, group);
        return marker == null ? Collections.emptyList() : Collections.singletonList(marker);
    }

    private static DetectionMarker mergeMarkerGroup(String label,
            java.util.List<DetectionMarker> source, MarkerGroup group) {
        double left = 1d;
        double top = 1d;
        double right = 0d;
        double bottom = 0d;
        int confidence = 0;
        String status = "unknown";
        boolean found = false;
        for (DetectionMarker marker : source) {
            if (marker == null || marker.confidence() < 60
                    || !belongsToMarkerGroup(markerKey(marker.label()), group)) {
                continue;
            }
            found = true;
            left = Math.min(left, marker.x());
            top = Math.min(top, marker.y());
            right = Math.max(right, marker.x() + marker.width());
            bottom = Math.max(bottom, marker.y() + marker.height());
            confidence = Math.max(confidence, marker.confidence());
            if (markerStatusPriority(marker.status()) > markerStatusPriority(status)) {
                status = marker.status();
            }
        }
        if (!found) {
            return null;
        }
        return new DetectionMarker(label, status, left, top, right - left, bottom - top,
                confidence);
    }

    private static boolean belongsToMarkerGroup(String key, MarkerGroup group) {
        if (group == MarkerGroup.PLATFORM_ALARM) {
            return containsAny(key, "报警", "告警", "alarm")
                    && !containsAny(key, "机房环境检测", "环境检测区", "整个屏幕", "整屏");
        }
        if (group == MarkerGroup.DDC_POWER) {
            boolean voltage = containsAny(key, "24vac", "24v", "10号");
            boolean common = containsAny(key, "11号", "com");
            return voltage && common
                    && !containsAny(key, "黄色", "名称标签", "sbus", "1号2号");
        }
        if (group == MarkerGroup.GATEWAY_RS485_LIGHTS) {
            return containsAny(key, "com1", "com2", "485")
                    && containsAny(key, "通讯灯", "信号灯", "指示灯", "状态灯")
                    && !containsAny(key, "pwr", "run", "端子", "接线", "a/b", "ab端子");
        }
        return (containsAny(key, "rj45", "网口", "网络口")
                || (containsAny(key, "网络", "link", "链路")
                && containsAny(key, "通讯灯", "状态灯", "指示灯", "link灯")))
                && !containsAny(key, "网线", "插头", "端子", "pwr", "run");
    }

    public static boolean hasReliableEvidenceMarker(ParsedResponse parsed) {
        if (parsed == null) {
            return false;
        }
        for (DetectionMarker marker : parsed.evidenceMarkers()) {
            if (marker != null && marker.confidence() >= 60) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasReliableEvidenceMarker(java.util.List<DetectionMarker> markers) {
        if (markers == null) {
            return false;
        }
        for (DetectionMarker marker : markers) {
            if (marker != null && marker.confidence() >= 60) {
                return true;
            }
        }
        return false;
    }

    private static StepProtocol protocol(String step) {
        if (HoneywellTempHumiditySkill.STEP_SYSTEM_CONTEXT.equals(step)) {
            return new StepProtocol("补充现场系统情况",
                    "system-context；可选标签 platform, honeywell-gateway, honeywell-ddc, temp-humidity-sensor",
                    "架构图中的连接链路或可辨识设备节点");
        }
        if (HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO.equals(step)) {
            return new StepProtocol("确认霍尼韦尔工控 DDC 供电测量点",
                    "必需 honeywell-ddc, power-input；看清 24VAC 等额定标识时附加 rated-voltage",
                    "上方黑色三位端子区中的 10号 24VAC 与 11号 COM 测量端；"
                            + "不要框选黄色设备名称标签或下方 1号、2号 S-BUS 端子");
        }
        if (HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT.equals(step)) {
            return new StepProtocol("测量霍尼韦尔工控 DDC 供电",
                    "honeywell-ddc, rated-voltage, meter-reading, probe-point, voltage-in-range",
                    "已确认的 DDC 电源测量点");
        }
        if (HoneywellTempHumiditySkill.STEP_DDC_RS485.equals(step)) {
            return gatewayLightProtocol();
        }
        if (HoneywellTempHumiditySkill.STEP_GATEWAY_RS485.equals(step)) {
            return gatewayLightProtocol();
        }
        if (HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK.equals(step)) {
            return gatewayLightProtocol();
        }
        if (HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE.equals(step)) {
            return new StepProtocol("确认温湿度传感器本体",
                    "temp-humidity-sensor, sensor-cable-entry",
                    "温湿度传感器本体、接线端子、12V电源点与公共端检测区域");
        }
        if (HoneywellTempHumiditySkill.STEP_SENSOR_WIRING.equals(step)) {
            return new StepProtocol("确认温湿度传感器接线处理",
                    "terminal；接线异常使用 wiring-anomaly，已处理或接线正常使用 wiring-resolved，电压正常使用 voltage-in-range",
                    "已确认的传感器接线接口");
        }
        if (HoneywellTempHumiditySkill.STEP_METER_CHECK.equals(step)) {
            return new StepProtocol("检查接线并读取万用表",
                    "terminal, meter-reading, probe-point, wiring-anomaly",
                    "已确认的传感器端子测量点");
        }
        if (HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY.equals(step)) {
            return new StepProtocol("确认平台数据恢复",
                    "platform, temperature-normal",
                    "温湿度恢复状态");
        }
        return new StepProtocol("确认平台温湿度局部异常",
                "platform, temperature-missing, other-data-normal",
                "右侧报警列表中的温湿度传感器报警记录；只输出一个检测框，"
                        + "不要框选下方机房环境检测区或整个屏幕");
    }

    private static StepProtocol gatewayLightProtocol() {
        return new StepProtocol("口述网关485与网络通讯灯状态",
                "gateway-rs485-blinking, network-link-blinking；"
                        + "异常时使用 gateway-rs485-off/solid、network-link-off/solid",
                "本轮不需要照片和检测框");
    }

    private static String markerInstruction() {
        return "\n如本轮图片中可可靠定位检测区域，逐行输出 [[scene-marker:中文标签|状态|x|y|宽|高|置信度]]。"
                + "状态只能为 normal、suspect、abnormal 或 unknown；坐标和尺寸均为 0 到 1 的图片比例，"
                + "必须使用小数比例，例如 x=0.10、y=0.10、宽=0.20、高=0.20；严禁输出像素坐标，"
                + "任一坐标或尺寸大于 1 均视为无效。置信度为 0 到 1。最多输出 4 个标记，"
                + "优先标出 abnormal、suspect 和高置信度区域。检测框必须紧贴检查对象，"
                + "设备身份只作为证据标签，不要框选整台设备。无法可靠定位时不要输出标记。";
    }

    private static String normalizeEvidenceTag(String value) {
        String tag = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int separator = tag.indexOf('=');
        if (separator > 0) {
            tag = tag.substring(0, separator);
        }
        if ("electrical-control-system-diagram".equals(tag)
                || "system-diagram".equals(tag) || "topology-diagram".equals(tag)
                || "architecture-diagram".equals(tag)) {
            return "system-context";
        }
        return tag;
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
            return new DetectionMarker(normalizeMarkerLabel(label), status, x, y, width, height,
                    (int) Math.round(confidence * 100d));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static java.util.List<DetectionMarker> selectHudMarkers(
            java.util.List<DetectionMarker> markers) {
        java.util.List<DetectionMarker> reliable = new java.util.ArrayList<>();
        for (DetectionMarker marker : markers) {
            if (marker != null && marker.confidence() >= 60) {
                reliable.add(marker);
            }
        }
        if (reliable.size() <= MAX_HUD_MARKERS) {
            return reliable;
        }
        java.util.List<DetectionMarker> ranked = new java.util.ArrayList<>(reliable);
        Collections.sort(ranked, (left, right) -> {
            int statusComparison = Integer.compare(
                    markerStatusPriority(right.status()), markerStatusPriority(left.status()));
            if (statusComparison != 0) {
                return statusComparison;
            }
            return Integer.compare(right.confidence(), left.confidence());
        });
        java.util.List<DetectionMarker> selected = ranked.subList(0, MAX_HUD_MARKERS);
        java.util.List<DetectionMarker> result = new java.util.ArrayList<>(MAX_HUD_MARKERS);
        for (DetectionMarker marker : reliable) {
            if (selected.contains(marker)) {
                result.add(marker);
            }
        }
        return result;
    }

    private static int markerStatusPriority(String status) {
        if ("abnormal".equals(status)) {
            return 4;
        }
        if ("suspect".equals(status)) {
            return 3;
        }
        if ("normal".equals(status)) {
            return 2;
        }
        return 1;
    }

    private static String highConfidenceMarkerEvidenceTag(DetectionMarker marker) {
        if (marker == null || marker.confidence() < 85) {
            return "";
        }
        String label = marker.label().trim().toLowerCase(Locale.ROOT);
        if ("power-input".equals(label)
                || (label.contains("24vac/com") && label.contains("电源"))) {
            return "power-input";
        }
        switch (label) {
            case "ddc 485端子":
                return "rs485-terminal";
            case "ddc 485状态灯":
                return "rs485-led";
            case "网关485端子":
                return "gateway-rs485-terminal";
            case "网关485信号灯":
                return "gateway-rs485-led";
            case "网关网络口":
                return "network-port";
            case "网线":
                return "network-cable";
            case "网络状态灯":
                return "link-led";
            case "接线端子":
                return "terminal";
            case "信号线":
                return "signal-wire";
            case "裸露导体":
                return "exposed-conductor";
            case "疑似接线异常":
                return "wiring-suspect";
            case "温湿度异常区":
                return "temperature-missing";
            case "其他正常数据":
                return "other-data-normal";
            default:
                return "";
        }
    }

    private static String normalizeMarkerLabel(String label) {
        String key = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "rs485-terminal":
                return "DDC 485端子";
            case "rs485-led":
                return "DDC 485状态灯";
            case "gateway-rs485-terminal":
                return "网关485端子";
            case "gateway-rs485-led":
                return "网关485信号灯";
            case "network-port":
                return "网关网络口";
            case "network-cable":
                return "网线";
            case "link-led":
                return "网络状态灯";
            case "terminal":
                return "接线端子";
            case "signal-wire":
                return "信号线";
            case "exposed-conductor":
                return "裸露导体";
            case "wiring-suspect":
                return "疑似接线异常";
            case "temperature-missing":
                return "温湿度异常区";
            case "other-data-normal":
                return "其他正常数据";
            default:
                return label == null ? "" : label.trim();
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
        final String markerTargets;

        StepProtocol(String title, String tags, String markerTargets) {
            this.title = title;
            this.tags = tags;
            this.markerTargets = markerTargets;
        }
    }

    private enum MarkerGroup {
        PLATFORM_ALARM,
        DDC_POWER,
        GATEWAY_RS485_LIGHTS,
        GATEWAY_NETWORK_LIGHTS
    }
}
