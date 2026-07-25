package com.codex.air3nativecamera.features.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Presentation-neutral content for one local capability detail view. */
public final class OperationDetail {
    private final String tag;
    private final String title;
    private final String description;
    private final List<String> items;
    private final String primaryAction;
    private final String primaryLabel;
    private final String secondaryAction;
    private final String secondaryLabel;

    public OperationDetail(
            String tag,
            String title,
            String description,
            List<String> items,
            String primaryAction,
            String primaryLabel,
            String secondaryAction,
            String secondaryLabel) {
        this.tag = text(tag);
        this.title = text(title);
        this.description = text(description);
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.primaryAction = text(primaryAction);
        this.primaryLabel = text(primaryLabel);
        this.secondaryAction = text(secondaryAction);
        this.secondaryLabel = text(secondaryLabel);
    }

    public String tag() { return tag; }

    public String title() { return title; }

    public String description() { return description; }

    public List<String> items() { return items; }

    public String primaryAction() { return primaryAction; }

    public String primaryLabel() { return primaryLabel; }

    public String secondaryAction() { return secondaryAction; }

    public String secondaryLabel() { return secondaryLabel; }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
