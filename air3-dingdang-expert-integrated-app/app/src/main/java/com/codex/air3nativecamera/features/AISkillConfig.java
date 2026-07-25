package com.codex.air3nativecamera.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Catalog-only model for future domain skills. It owns no runtime behavior yet. */
public final class AISkillConfig {
    public enum Status {
        PLANNED("待接入");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String id;
    private final String title;
    private final String summary;
    private final Status status;

    public AISkillConfig(String id, String title, String summary, Status status) {
        this.id = requireText(id, "id");
        this.title = requireText(title, "title");
        this.summary = requireText(summary, "summary");
        this.status = status == null ? Status.PLANNED : status;
    }

    public String id() { return id; }

    public String title() { return title; }

    public String summary() { return summary; }

    public Status status() { return status; }

    public static List<AISkillConfig> defaultConfigs() {
        ArrayList<AISkillConfig> configs = new ArrayList<>();
        configs.add(new AISkillConfig("server_ops", "服务器运维 Skill", "服务器告警、诊断与处置流程", Status.PLANNED));
        configs.add(new AISkillConfig("network_fault", "网络故障 Skill", "网络连通性、链路与配置排查", Status.PLANNED));
        configs.add(new AISkillConfig("pump_ops", "水泵运维 Skill", "水泵运行状态与维护流程", Status.PLANNED));
        configs.add(new AISkillConfig("fire_inspection", "消防巡检 Skill", "消防设备巡检与异常记录", Status.PLANNED));
        configs.add(new AISkillConfig("air_conditioning", "空调系统 Skill", "空调系统状态与故障辅助", Status.PLANNED));
        ensureUniqueIds(configs);
        return Collections.unmodifiableList(configs);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void ensureUniqueIds(List<AISkillConfig> configs) {
        Set<String> ids = new LinkedHashSet<>();
        for (AISkillConfig config : configs) {
            if (!ids.add(config.id())) {
                throw new IllegalArgumentException("duplicate AI skill id: " + config.id());
            }
        }
    }
}
