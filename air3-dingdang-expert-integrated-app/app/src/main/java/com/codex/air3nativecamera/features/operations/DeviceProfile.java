package com.codex.air3nativecamera.features.operations;

import com.codex.air3nativecamera.task.MaintenanceTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only local device context assembled from the active maintenance task. */
public final class DeviceProfile {
    private final String currentProblem;
    private final Map<String, String> facts;
    private final List<String> evidenceLabels;

    private DeviceProfile(String currentProblem, Map<String, String> facts, List<String> evidenceLabels) {
        this.currentProblem = currentProblem;
        this.facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        this.evidenceLabels = Collections.unmodifiableList(new ArrayList<>(evidenceLabels));
    }

    public static DeviceProfile from(MaintenanceTask task) {
        if (task == null) {
            return new DeviceProfile("暂无进行中的设备任务", Collections.<String, String>emptyMap(),
                    Collections.<String>emptyList());
        }
        return new DeviceProfile(task.initialProblem(), task.facts(), task.evidenceLabels());
    }

    public String currentProblem() {
        return currentProblem;
    }

    public String factValue(String key) {
        return facts.get(key);
    }

    public Map<String, String> facts() {
        return facts;
    }

    public int evidenceCount() {
        return evidenceLabels.size();
    }

    public List<String> evidenceLabels() {
        return evidenceLabels;
    }
}
