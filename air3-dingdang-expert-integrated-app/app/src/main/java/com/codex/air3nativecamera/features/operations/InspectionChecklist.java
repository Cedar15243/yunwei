package com.codex.air3nativecamera.features.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Local checklist progress for the current inspection task. */
public final class InspectionChecklist {
    public static final class Item {
        private final String id;
        private final String title;
        private final String detail;

        private Item(String id, String title, String detail) {
            this.id = id;
            this.title = title;
            this.detail = detail;
        }

        public String id() { return id; }

        public String title() { return title; }

        public String detail() { return detail; }
    }

    private final List<Item> items;
    private final Set<String> completedIds = new LinkedHashSet<>();

    private InspectionChecklist(List<Item> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public static InspectionChecklist defaultChecklist() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("server_status", "检查设备运行状态", "确认设备是否通电并记录异常现象。"));
        items.add(new Item("indicator_lights", "检查设备指示灯", "观察电源、告警与状态指示灯。"));
        items.add(new Item("cable_connection", "检查连接线路", "确认电源与关键连接线没有松动。"));
        return new InspectionChecklist(items);
    }

    public List<Item> items() {
        return items;
    }

    public boolean complete(String id) {
        if (!contains(id) || completedIds.contains(id)) {
            return false;
        }
        completedIds.add(id);
        return true;
    }

    public boolean isCompleted(String id) {
        return completedIds.contains(id);
    }

    public int totalCount() {
        return items.size();
    }

    public int completedCount() {
        return completedIds.size();
    }

    public String progressLabel() {
        return completedCount() + "/" + totalCount();
    }

    private boolean contains(String id) {
        if (id == null) {
            return false;
        }
        for (Item item : items) {
            if (item.id.equals(id)) {
                return true;
            }
        }
        return false;
    }
}
