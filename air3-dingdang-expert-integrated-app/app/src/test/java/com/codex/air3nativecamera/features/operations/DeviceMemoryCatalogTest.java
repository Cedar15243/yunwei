package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class DeviceMemoryCatalogTest {
    @Test
    public void defaultCatalogCoversFieldSystemsAndTotalsDeviceQuantity() {
        DeviceMemoryCatalog catalog = DeviceMemoryCatalog.defaultCatalog();
        Set<String> systems = new HashSet<>();
        int expectedQuantity = 0;

        for (DeviceMemoryCatalog.DeviceRecord record : catalog.records()) {
            systems.add(record.system());
            expectedQuantity += record.quantity();
        }

        assertTrue(catalog.records().size() >= 8);
        assertTrue(systems.contains("水电暖"));
        assertTrue(systems.contains("空调"));
        assertTrue(systems.contains("消防"));
        assertTrue(systems.contains("网络"));
        assertTrue(systems.contains("环境"));
        assertEquals(expectedQuantity, catalog.totalQuantity());
        assertEquals(2, catalog.pageCount(4));
        assertEquals(4, catalog.page(0, 4).size());
        assertEquals(catalog.records().size() - 4, catalog.page(1, 4).size());
    }
}
