package com.codex.air3nativecamera.features.inspection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InspectionAiBridge {
    private static final Pattern CURRENT_VALUE = Pattern.compile(
            "(?m)\\s*\\[\\[inspection-current:([^\\]]+)]]\\s*$",
            Pattern.CASE_INSENSITIVE);

    public static final class ParsedResponse {
        private final String observation;
        private final String currentValue;

        ParsedResponse(String observation, String currentValue) {
            this.observation = observation;
            this.currentValue = currentValue;
        }

        public String observation() { return observation; }
        public String currentValue() { return currentValue; }
    }

    private InspectionAiBridge() {
    }

    public static String prompt(InspectionTaskDefinition.Point point) {
        if (point == null) return "";
        return "请检查本轮实际照片中的一个巡检点位，不得根据任务名称臆测。\n"
                + "点位：" + point.title() + "\n"
                + "检查重点：" + point.detail() + "\n"
                + "请用不超过80字说明照片中可确认的状态；看不清时明确要求补拍。"
                + "若能读取状态或数值，最后单独输出 [[inspection-current:本次状态或数值]]。";
    }

    public static ParsedResponse parse(String response) {
        String source = response == null ? "" : response.trim();
        Matcher matcher = CURRENT_VALUE.matcher(source);
        String current = "";
        if (matcher.find()) {
            current = matcher.group(1).trim();
            source = source.substring(0, matcher.start()).trim();
        }
        return new ParsedResponse(source, current);
    }
}
