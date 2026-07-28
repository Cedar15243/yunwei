package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class KnowledgeCatalogTest {
    @Test
    public void catalogProvidesReadOnlySafetyAndMaintenanceReferences() {
        KnowledgeCatalog catalog = KnowledgeCatalog.defaultCatalog();

        assertTrue(catalog.entries().size() >= 3);
        KnowledgeCatalog.Entry entry = catalog.findById("power_module_safety");
        assertEquals("电源模块安全检查", entry.title());
        assertTrue(entry.readOnly());
        assertEquals(KnowledgeCatalog.ConnectionState.LOCAL_READY, catalog.localState());
        assertEquals(KnowledgeCatalog.ConnectionState.REMOTE_DISCONNECTED, catalog.enterpriseState());

        Set<String> systems = new HashSet<>();
        for (KnowledgeCatalog.Entry item : catalog.entries()) systems.add(item.system());
        assertTrue(systems.contains("电气"));
        assertTrue(systems.contains("暖通"));
        assertTrue(systems.contains("给排水"));
        assertTrue(systems.contains("消防"));
        assertTrue(systems.contains("网络"));
        assertTrue(systems.contains("霍尼韦尔环境"));
    }
}
