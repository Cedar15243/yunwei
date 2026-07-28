package com.codex.air3nativecamera.features.operations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Local interactive Agent/Skill package catalog; authorization never leaves this app. */
public final class AgentPackageCatalog {
    public static final class AgentPackage {
        private final String id;
        private final String system;
        private final String title;
        private final String version;
        private final List<String> skills;
        private final String permissionScope;
        private final String description;
        private boolean authorized;

        private AgentPackage(String id, String system, String title, String version,
                List<String> skills, String permissionScope, String description) {
            this.id = id;
            this.system = system;
            this.title = title;
            this.version = version;
            this.skills = Collections.unmodifiableList(new ArrayList<>(skills));
            this.permissionScope = permissionScope;
            this.description = description;
        }

        public String id() { return id; }
        public String system() { return system; }
        public String title() { return title; }
        public String version() { return version; }
        public List<String> skills() { return skills; }
        public String permissionScope() { return permissionScope; }
        public String description() { return description; }
        public boolean authorized() { return authorized; }
        public String authorizationLabel() { return authorized ? "本机技能已启用" : "本机技能已停用"; }
    }

    private final List<AgentPackage> packages;

    private AgentPackageCatalog(List<AgentPackage> packages) {
        this.packages = packages;
    }

    public static AgentPackageCatalog defaultCatalog() {
        List<AgentPackage> packages = new ArrayList<>();
        packages.add(pack("network_ops", "网络", "网络运维技能", "1.4", "交换机灯态识别", "链路排障", "只读现场证据与任务记忆", "辅助交换机、链路与端口故障排查"));
        packages.add(pack("water_ops", "水电暖", "水电暖巡检技能", "1.2", "表计识别", "泵阀检查", "本机巡检记录与照片", "执行水电暖逐点巡检与前后值比较"));
        packages.add(pack("hvac_ops", "空调", "暖通空调技能", "1.3", "参数识别", "过滤器检查", "本机巡检记录与照片", "辅助空调运行参数和维护状态判断"));
        packages.add(pack("fire_ops", "消防", "消防巡检技能", "1.1", "主机灯态", "器材检查", "本机巡检记录与照片", "执行消防设施逐点巡检和异常记录"));
        packages.add(pack("environment_ops", "环境", "环境诊断技能", "1.5", "温湿度读取", "网关链路", "当前任务证据与设备记忆", "辅助环境采集平台、网关与传感器链路排查"));
        packages.add(pack("safety_ops", "安全", "安全作业技能", "1.0", "风险提示", "操作复核", "当前任务风险与维修步骤", "在高风险操作前提供安全复核"));
        return new AgentPackageCatalog(packages);
    }

    public List<AgentPackage> packages() { return Collections.unmodifiableList(packages); }

    public AgentPackage find(String id) {
        for (AgentPackage item : packages) if (item.id.equals(id)) return item;
        return null;
    }

    public boolean authorize(String id) {
        return setAuthorized(id, true);
    }

    public boolean setAuthorized(String id, boolean authorized) {
        AgentPackage item = find(id);
        if (item == null) return false;
        item.authorized = authorized;
        return true;
    }

    public boolean toggle(String id) {
        AgentPackage item = find(id);
        if (item == null) return false;
        item.authorized = !item.authorized;
        return true;
    }

    public int pageCount(int pageSize) {
        int safeSize = Math.max(1, pageSize);
        return Math.max(1, (packages.size() + safeSize - 1) / safeSize);
    }

    public List<AgentPackage> page(int pageIndex, int pageSize) {
        int safeSize = Math.max(1, pageSize);
        int safePage = Math.max(0, Math.min(pageIndex, pageCount(safeSize) - 1));
        int start = Math.min(packages.size(), safePage * safeSize);
        return Collections.unmodifiableList(new ArrayList<>(
                packages.subList(start, Math.min(packages.size(), start + safeSize))));
    }

    public JSONObject toJson() {
        JSONArray authorized = new JSONArray();
        for (AgentPackage item : packages) if (item.authorized) authorized.put(item.id);
        try {
            return new JSONObject().put("authorized", authorized);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize Agent authorizations", exception);
        }
    }

    public static AgentPackageCatalog fromJson(JSONObject json) {
        AgentPackageCatalog catalog = defaultCatalog();
        JSONArray authorized = json == null ? null : json.optJSONArray("authorized");
        for (int i = 0; authorized != null && i < authorized.length(); i++) {
            catalog.authorize(authorized.optString(i, ""));
        }
        return catalog;
    }

    private static AgentPackage pack(String id, String system, String title, String version,
            String skillOne, String skillTwo, String permission, String description) {
        return new AgentPackage(id, system, title, version, Arrays.asList(skillOne, skillTwo),
                permission, description);
    }
}
