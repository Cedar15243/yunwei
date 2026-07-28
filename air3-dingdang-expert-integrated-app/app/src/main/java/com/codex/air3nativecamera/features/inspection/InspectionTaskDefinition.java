package com.codex.air3nativecamera.features.inspection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class InspectionTaskDefinition {
    public enum Scope { MY_TASK, INDUSTRY }

    public static final class Point {
        private final String id;
        private final String title;
        private final String detail;
        private final String previousValue;
        private final boolean photoRequired;

        public Point(String id, String title, String detail, boolean photoRequired) {
            this(id, title, detail, "上次巡检正常", photoRequired);
        }

        public Point(String id, String title, String detail, String previousValue, boolean photoRequired) {
            this.id = text(id);
            this.title = text(title);
            this.detail = text(detail);
            this.previousValue = text(previousValue);
            this.photoRequired = photoRequired;
        }

        public String id() { return id; }
        public String title() { return title; }
        public String detail() { return detail; }
        public String previousValue() { return previousValue; }
        public boolean photoRequired() { return photoRequired; }
    }

    private final String id;
    private final String title;
    private final String summary;
    private final Scope scope;
    private final Set<String> deviceTypes;
    private final List<Point> points;

    public InspectionTaskDefinition(String id, String title, String summary, Scope scope,
            Set<String> deviceTypes, List<Point> points) {
        this.id = text(id);
        this.title = text(title);
        this.summary = text(summary);
        this.scope = scope == null ? Scope.INDUSTRY : scope;
        this.deviceTypes = Collections.unmodifiableSet(new LinkedHashSet<>(deviceTypes));
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
    }

    public String id() { return id; }
    public String title() { return title; }
    public String summary() { return summary; }
    public Scope scope() { return scope; }
    public Set<String> deviceTypes() { return deviceTypes; }
    public List<Point> points() { return points; }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
