package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KnowledgeCatalogTest {
    @Test
    public void catalogProvidesReadOnlySafetyAndMaintenanceReferences() {
        KnowledgeCatalog catalog = KnowledgeCatalog.defaultCatalog();

        assertTrue(catalog.entries().size() >= 3);
        KnowledgeCatalog.Entry entry = catalog.findById("power_module_safety");
        assertEquals("电源模块安全检查", entry.title());
        assertTrue(entry.readOnly());
    }
}
