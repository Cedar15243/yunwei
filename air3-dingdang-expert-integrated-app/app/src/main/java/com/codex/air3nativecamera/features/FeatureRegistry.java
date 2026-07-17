package com.codex.air3nativecamera.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FeatureRegistry {
    private final Map<String, FeatureEntry> entries;

    private FeatureRegistry(List<FeatureEntry> entries) {
        LinkedHashMap<String, FeatureEntry> indexed = new LinkedHashMap<>();
        for (FeatureEntry entry : entries) {
            if (indexed.put(entry.id(), entry) != null) {
                throw new IllegalArgumentException("duplicate feature id: " + entry.id());
            }
        }
        this.entries = Collections.unmodifiableMap(indexed);
    }

    public static FeatureRegistry createDefault() {
        ArrayList<FeatureEntry> entries = new ArrayList<>();
        entries.add(new DefaultFeatureEntry("expert_collab", "专家协同", true));
        entries.add(new DefaultFeatureEntry("equipment_inspection", "设备巡检", false));
        entries.add(new DefaultFeatureEntry("field_records", "现场记录", false));
        entries.add(new DefaultFeatureEntry("asset_records", "设备档案", false));
        entries.add(new DefaultFeatureEntry("work_orders", "运维工单", false));
        entries.add(new DefaultFeatureEntry("knowledge_base", "运维知识库", false));
        entries.add(new DefaultFeatureEntry("operations_reports", "运维报告", false));
        entries.add(new DefaultFeatureEntry("safe_operations", "安全作业", false));
        entries.add(new DefaultFeatureEntry("training_drills", "培训演练", false));
        return new FeatureRegistry(entries);
    }

    public List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(entries.keySet()));
    }

    public FeatureEntry require(String id) {
        FeatureEntry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("unknown feature id: " + id);
        }
        return entry;
    }

    private static final class DefaultFeatureEntry implements FeatureEntry {
        private final String id;
        private final String title;
        private final boolean available;

        private DefaultFeatureEntry(String id, String title, boolean available) {
            this.id = id;
            this.title = title;
            this.available = available;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void enter(FeatureHost host) {
            if (host == null) {
                throw new IllegalArgumentException("feature host is required");
            }
            host.openFeature(id, title, available);
        }

        @Override
        public void release() {
            // Reserved entries own no resources until their implementation is enabled.
        }
    }
}
