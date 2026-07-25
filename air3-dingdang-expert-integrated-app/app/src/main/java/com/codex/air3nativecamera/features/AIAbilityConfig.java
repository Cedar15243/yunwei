package com.codex.air3nativecamera.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Defines the AI capability center without coupling future modules to the home screen layout. */
public final class AIAbilityConfig {
    public enum Status {
        ONLINE("已上线"),
        PREVIEW("能力预览");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Route {
        DIAGNOSIS,
        EXPERT_COLLAB,
        SKILL_CENTER,
        AGENT_CENTER,
        PLACEHOLDER
    }

    private final String id;
    private final String title;
    private final String icon;
    private final Status status;
    private final Route route;
    private final String summary;
    private final String pageDescription;
    private final String voiceCommand;

    public AIAbilityConfig(
            String id,
            String title,
            String icon,
            Status status,
            Route route,
            String summary,
            String pageDescription,
            String voiceCommand) {
        this.id = requireText(id, "id");
        this.title = requireText(title, "title");
        this.icon = requireText(icon, "icon");
        this.status = status == null ? Status.PREVIEW : status;
        this.route = route == null ? Route.PLACEHOLDER : route;
        this.summary = requireText(summary, "summary");
        this.pageDescription = requireText(pageDescription, "pageDescription");
        this.voiceCommand = requireText(voiceCommand, "voiceCommand");
    }

    public String id() { return id; }

    public String title() { return title; }

    public String icon() { return icon; }

    public Status status() { return status; }

    public Route route() { return route; }

    public String summary() { return summary; }

    public String pageDescription() { return pageDescription; }

    public String voiceCommand() { return voiceCommand; }

    public static List<AIAbilityConfig> defaultConfigs() {
        ArrayList<AIAbilityConfig> configs = new ArrayList<>();
        configs.add(new AIAbilityConfig(
                "diagnosis", "AI 故障诊断", "诊", Status.ONLINE, Route.DIAGNOSIS,
                "语音、图片与诊断指导", "复用现有语音输入、图片分析和 AI 诊断流程。", "可以说：“小叮当，开始诊断”"));
        configs.add(new AIAbilityConfig(
                "expert_collab", "专家协同", "专", Status.ONLINE, Route.EXPERT_COLLAB,
                "远程视频与现场指导", "复用现有专家实时视频协同与标注流程。", "可以说：“小叮当，呼叫专家”"));
        configs.add(new AIAbilityConfig(
                "perception", "现场拍照", "拍", Status.ONLINE, Route.PLACEHOLDER,
                "照片与语音联合取证", "拍摄全景、近景或验证照片，并与本轮语音描述一起关联到当前维修任务。", "可以说：“小叮当，现场拍照”"));
        configs.add(new AIAbilityConfig(
                "video_evidence", "短视频取证", "录", Status.ONLINE, Route.PLACEHOLDER,
                "最长 15 秒现场记录", "录制现场短视频并关联当前任务；当前只保存证据，不伪装成视频理解。", "可以说：“小叮当，短视频取证”"));
        configs.add(new AIAbilityConfig(
                "inspection", "巡检任务", "巡", Status.PREVIEW, Route.PLACEHOLDER,
                "检查清单与本地进度", "记录本机巡检清单和当前进度，不承诺自动识别或生成云端报告。", "可以说：“小叮当，巡检任务”"));
        configs.add(new AIAbilityConfig(
                "tasks", "维修任务", "任", Status.PREVIEW, Route.PLACEHOLDER,
                "当前维修步骤与记录", "展示本机当前维修任务、证据和步骤进度。", "可以说：“小叮当，维修任务”"));
        configs.add(new AIAbilityConfig(
                "knowledge", "华方知识库", "知", Status.PREVIEW, Route.PLACEHOLDER,
                "SOP、手册与技术资料", "华方设备手册、SOP 与技术资料的只读目录预览。", "可以说：“小叮当，华方知识库”"));
        configs.add(new AIAbilityConfig(
                "device_brain", "设备记忆", "忆", Status.PREVIEW, Route.PLACEHOLDER,
                "设备事实、故障与经验", "汇总当前任务确认的设备信息、故障现象和维修证据。", "可以说：“小叮当，设备记忆”"));
        configs.add(new AIAbilityConfig(
                "agent_center", "AI Agent 中心", "A", Status.PREVIEW, Route.AGENT_CENTER,
                "运维智能体与 Skill 预留", "展示未来智能体、职责范围和领域 Skill 的扩展结构；当前不执行设备操作。", "可以说：“小叮当，AI Agent 中心”"));
        ensureUniqueIds(configs);
        return Collections.unmodifiableList(configs);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void ensureUniqueIds(List<AIAbilityConfig> configs) {
        Set<String> ids = new LinkedHashSet<>();
        for (AIAbilityConfig config : configs) {
            if (!ids.add(config.id())) {
                throw new IllegalArgumentException("duplicate AI ability id: " + config.id());
            }
        }
    }
}
