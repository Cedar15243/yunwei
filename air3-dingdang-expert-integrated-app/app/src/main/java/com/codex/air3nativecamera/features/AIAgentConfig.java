package com.codex.air3nativecamera.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Catalog-only model for future AI agents. Agents do not invoke any model or device action yet. */
public final class AIAgentConfig {
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
    private final List<String> skillIds;
    private final Status status;

    public AIAgentConfig(String id, String title, String summary, List<String> skillIds, Status status) {
        this.id = requireText(id, "id");
        this.title = requireText(title, "title");
        this.summary = requireText(summary, "summary");
        this.skillIds = Collections.unmodifiableList(new ArrayList<>(skillIds == null
                ? Collections.<String>emptyList() : skillIds));
        this.status = status == null ? Status.PLANNED : status;
    }

    public String id() { return id; }

    public String title() { return title; }

    public String summary() { return summary; }

    public List<String> skillIds() { return skillIds; }

    public Status status() { return status; }

    public static List<AIAgentConfig> defaultConfigs() {
        ArrayList<AIAgentConfig> configs = new ArrayList<>();
        configs.add(new AIAgentConfig(
                "ops_copilot", "AI 运维副驾驶", "串联诊断、现场证据与维修指导", listOf("server_ops", "network_fault"), Status.PLANNED));
        configs.add(new AIAgentConfig(
                "facility_copilot", "设施运维副驾驶", "面向水泵、消防与空调系统的现场协作", listOf("pump_ops", "fire_inspection", "air_conditioning"), Status.PLANNED));
        ensureUniqueIds(configs);
        return Collections.unmodifiableList(configs);
    }

    private static List<String> listOf(String... ids) {
        ArrayList<String> values = new ArrayList<>();
        Collections.addAll(values, ids);
        return values;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void ensureUniqueIds(List<AIAgentConfig> configs) {
        Set<String> ids = new LinkedHashSet<>();
        for (AIAgentConfig config : configs) {
            if (!ids.add(config.id())) {
                throw new IllegalArgumentException("duplicate AI agent id: " + config.id());
            }
        }
    }
}
