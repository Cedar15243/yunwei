package com.codex.air3nativecamera.features.inspection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class InspectionCatalogTest {
    @Test
    public void labTaskCoversPhotoFirstMeterSwitchAndEnvironmentChecks() {
        InspectionTaskDefinition task = InspectionCatalog.defaultCatalog().find("lab-training-room");

        assertEquals("实训室设备巡检", task.title());
        assertTrue(task.points().size() >= 4);
        assertTrue(task.deviceTypes().contains("交换机"));
        assertTrue(task.deviceTypes().contains("电表"));
        assertTrue(task.deviceTypes().contains("温湿度传感器"));
        assertTrue(task.points().get(0).photoRequired());
        assertTrue(task.points().get(1).previousValue().contains("kWh"));
    }

    @Test
    public void catalogContainsFourExecutableGlassesInspectionWorkflows() {
        InspectionCatalog catalog = InspectionCatalog.defaultCatalog();
        assertEquals(4, catalog.tasks().size());
        assertEquals(3, catalog.industryTasks().size());

        String[] ids = {"lab-training-room", "water-power-heating", "air-conditioning", "fire-safety"};
        Set<String> devices = new HashSet<>();
        for (String id : ids) {
            InspectionTaskDefinition task = catalog.find(id);
            assertTrue("Missing workflow: " + id, task != null);
            assertTrue(task.points().size() >= 4);
            devices.addAll(task.deviceTypes());
            assertFalse(task.title().contains("假数据"));
            assertFalse(task.title().contains("模板"));
            for (InspectionTaskDefinition.Point point : task.points()) {
                assertTrue(point.photoRequired());
                assertFalse(point.detail().trim().isEmpty());
            }
        }

        String[] expected = {"交换机", "电表", "温湿度传感器", "服务器", "配电柜",
                "水泵", "压力表", "空调控制器", "过滤器", "消防报警主机", "消防泵柜",
                "灭火器", "应急照明"};
        for (String device : expected) {
            assertTrue("Missing device coverage: " + device, devices.contains(device));
        }
    }
}
