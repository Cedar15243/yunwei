package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.task.MaintenanceTask;

import org.junit.Test;

public final class DeviceProfileTest {
    @Test
    public void profileUsesOnlyFactsAndEvidenceFromTheCurrentLocalTask() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.putFact("设备", "1号服务器");
        task.addEvidence("电源模块近景", "local-photo");

        DeviceProfile profile = DeviceProfile.from(task);

        assertEquals("服务器无法启动", profile.currentProblem());
        assertEquals("1号服务器", profile.factValue("设备"));
        assertEquals(1, profile.evidenceCount());
        assertTrue(profile.evidenceLabels().contains("电源模块近景"));
    }
}
