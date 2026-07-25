package com.codex.air3nativecamera.features.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Catalog-only local references. It does not imply cloud retrieval or device control. */
public final class KnowledgeCatalog {
    public static final class Entry {
        private final String id;
        private final String title;
        private final String summary;

        private Entry(String id, String title, String summary) {
            this.id = id;
            this.title = title;
            this.summary = summary;
        }

        public String id() { return id; }

        public String title() { return title; }

        public String summary() { return summary; }

        public boolean readOnly() { return true; }
    }

    private final List<Entry> entries;

    private KnowledgeCatalog(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public static KnowledgeCatalog defaultCatalog() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("power_module_safety", "电源模块安全检查", "断电确认、放电等待与防静电操作要点。"));
        entries.add(new Entry("server_startup_sop", "服务器启动排查 SOP", "按供电、指示灯、连接与部件验证的顺序检查。"));
        entries.add(new Entry("expert_escalation", "专家协同升级条件", "发现高风险、无法确认或需要远程标注时呼叫专家。"));
        return new KnowledgeCatalog(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry findById(String id) {
        for (Entry entry : entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }
}
