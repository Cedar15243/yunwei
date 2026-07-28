package com.codex.air3nativecamera.features.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only local references with explicit local and enterprise connection states. */
public final class KnowledgeCatalog {
    public enum ConnectionState { LOCAL_READY, REMOTE_DISCONNECTED }

    public static final class Entry {
        private final String id;
        private final String system;
        private final String title;
        private final String summary;
        private final String version;
        private final String updatedAt;
        private final String detail;

        private Entry(String id, String system, String title, String summary, String version,
                String updatedAt, String detail) {
            this.id = id;
            this.system = system;
            this.title = title;
            this.summary = summary;
            this.version = version;
            this.updatedAt = updatedAt;
            this.detail = detail;
        }

        public String id() { return id; }
        public String system() { return system; }

        public String title() { return title; }

        public String summary() { return summary; }

        public String version() { return version; }

        public String updatedAt() { return updatedAt; }

        public String detail() { return detail; }

        public boolean readOnly() { return true; }
    }

    private final List<Entry> entries;

    private KnowledgeCatalog(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public static KnowledgeCatalog defaultCatalog() {
        List<Entry> entries = new ArrayList<>();
        entries.add(entry("power_module_safety", "电气", "电源模块安全检查", "断电确认、放电等待与防静电操作要点。", "3.2", "2026-06-18", "停电、验电、挂牌后再检查输入端与模块连接。"));
        entries.add(entry("hvac_maintenance", "暖通", "空调机组巡检与维护", "控制面板、过滤器、冷凝水和送回风检查。", "2.6", "2026-05-30", "先读取运行参数，再检查过滤器、排水与异常结露。"));
        entries.add(entry("pump_pipeline", "给排水", "泵阀与管网排查 SOP", "压力、渗漏、振动和阀门位置检查。", "2.1", "2026-06-08", "记录压力表读数，并与上次巡检值比较。"));
        entries.add(entry("fire_system", "消防", "消防设施巡检规范", "报警主机、泵柜、灭火器和应急照明。", "4.0", "2026-07-02", "异常项目必须记录点位、照片和现场确认结果。"));
        entries.add(entry("network_switch", "网络", "交换机灯态与链路排障", "SYS/ALM、端口和上联链路的检查顺序。", "3.5", "2026-06-25", "先拍摄整机，再补拍告警灯和上联端口近景。"));
        entries.add(entry("honeywell_environment", "霍尼韦尔环境", "温湿度采集链路排查", "平台、网关、网络与前端传感器闭环。", "1.8", "2026-07-20", "按平台异常、网关状态、通信链路和传感器接线逐步验证。"));
        entries.add(entry("server_startup_sop", "电气", "服务器启动排查 SOP", "按供电、指示灯、连接与部件验证的顺序检查。", "3.0", "2026-06-12", "先确认供电与指示灯，再检查连接和可替换部件。"));
        entries.add(entry("expert_escalation", "安全", "专家协同升级条件", "高风险、证据冲突或需要远程标注时升级。", "1.4", "2026-07-01", "进入协同前保存当前任务与维修步骤快照。"));
        return new KnowledgeCatalog(entries);
    }

    public ConnectionState localState() { return ConnectionState.LOCAL_READY; }

    public ConnectionState enterpriseState() { return ConnectionState.REMOTE_DISCONNECTED; }

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

    private static Entry entry(String id, String system, String title, String summary,
            String version, String updatedAt, String detail) {
        return new Entry(id, system, title, summary, version, updatedAt, detail);
    }
}
