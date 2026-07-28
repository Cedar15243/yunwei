package com.codex.air3nativecamera.features.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only local asset examples used by the isolated investor build. */
public final class DeviceMemoryCatalog {
    public static final class DeviceRecord {
        private final String id;
        private final String system;
        private final String brand;
        private final String model;
        private final int quantity;
        private final String status;
        private final String lastInspection;
        private final int faultCount;
        private final int repairCount;
        private final String keyParameter;

        private DeviceRecord(String id, String system, String brand, String model, int quantity,
                String status, String lastInspection, int faultCount, int repairCount,
                String keyParameter) {
            this.id = id;
            this.system = system;
            this.brand = brand;
            this.model = model;
            this.quantity = quantity;
            this.status = status;
            this.lastInspection = lastInspection;
            this.faultCount = faultCount;
            this.repairCount = repairCount;
            this.keyParameter = keyParameter;
        }

        public String id() { return id; }
        public String system() { return system; }
        public String brand() { return brand; }
        public String model() { return model; }
        public int quantity() { return quantity; }
        public String status() { return status; }
        public String lastInspection() { return lastInspection; }
        public int faultCount() { return faultCount; }
        public int repairCount() { return repairCount; }
        public String keyParameter() { return keyParameter; }
    }

    private final List<DeviceRecord> records;

    private DeviceMemoryCatalog(List<DeviceRecord> records) {
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    public static DeviceMemoryCatalog defaultCatalog() {
        List<DeviceRecord> records = new ArrayList<>();
        records.add(record("sw-01", "网络", "H3C", "S5130S", 6, "正常", "2026-07-23", 1, 1, "上联 1Gbps"));
        records.add(record("gw-01", "环境", "Honeywell", "环境采集网关", 2, "关注", "2026-07-24", 2, 1, "12 个采集点"));
        records.add(record("meter-01", "水电暖", "正泰", "DTSU666", 8, "正常", "2026-07-22", 0, 0, "累计 3628.7 kWh"));
        records.add(record("pump-01", "水电暖", "南方泵业", "CDL20", 3, "正常", "2026-07-22", 1, 2, "管压 0.42 MPa"));
        records.add(record("ac-01", "空调", "格力", "GMV-615", 4, "正常", "2026-07-21", 1, 1, "送风 17 C"));
        records.add(record("ahu-01", "空调", "开利", "AHU-08", 2, "维护中", "2026-07-21", 2, 3, "过滤器压差 86 Pa"));
        records.add(record("fire-01", "消防", "海湾", "GST5000", 1, "正常", "2026-07-24", 1, 1, "回路 8 / 点位 486"));
        records.add(record("pump-fire", "消防", "凯泉", "XBD", 2, "正常", "2026-07-24", 0, 1, "自动 / 0.68 MPa"));
        return new DeviceMemoryCatalog(records);
    }

    public List<DeviceRecord> records() { return records; }

    public int totalQuantity() {
        int total = 0;
        for (DeviceRecord record : records) total += record.quantity;
        return total;
    }

    public int pageCount(int pageSize) {
        int safeSize = Math.max(1, pageSize);
        return Math.max(1, (records.size() + safeSize - 1) / safeSize);
    }

    public List<DeviceRecord> page(int pageIndex, int pageSize) {
        int safeSize = Math.max(1, pageSize);
        int safePage = Math.max(0, Math.min(pageIndex, pageCount(safeSize) - 1));
        int start = Math.min(records.size(), safePage * safeSize);
        return Collections.unmodifiableList(new ArrayList<>(
                records.subList(start, Math.min(records.size(), start + safeSize))));
    }

    private static DeviceRecord record(String id, String system, String brand, String model,
            int quantity, String status, String lastInspection, int faultCount, int repairCount,
            String keyParameter) {
        return new DeviceRecord(id, system, brand, model, quantity, status, lastInspection,
                faultCount, repairCount, keyParameter);
    }
}
