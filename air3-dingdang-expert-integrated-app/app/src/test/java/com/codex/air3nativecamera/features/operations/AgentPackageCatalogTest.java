package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AgentPackageCatalogTest {
    @Test
    public void localAgentSwitchCanBeEnabledDisabledAndPersisted() {
        AgentPackageCatalog catalog = AgentPackageCatalog.defaultCatalog();
        assertTrue(catalog.packages().size() >= 6);
        assertEquals(2, catalog.pageCount(4));
        assertFalse(catalog.find("water_ops").authorized());
        assertFalse(catalog.find("environment_ops").authorized());

        assertTrue(catalog.setAuthorized("environment_ops", true));
        assertTrue(catalog.setAuthorized("environment_ops", true));
        assertTrue(catalog.find("environment_ops").authorized());
        assertTrue(catalog.setAuthorized("environment_ops", false));
        assertFalse(catalog.find("environment_ops").authorized());

        assertTrue(catalog.toggle("water_ops"));
        assertTrue(catalog.find("water_ops").authorized());
        assertEquals("本机技能已启用", catalog.find("water_ops").authorizationLabel());

        AgentPackageCatalog restored = AgentPackageCatalog.fromJson(catalog.toJson());
        assertTrue(restored.find("water_ops").authorized());
        assertFalse(restored.find("fire_ops").authorized());

        assertTrue(restored.toggle("water_ops"));
        assertFalse(restored.find("water_ops").authorized());
        AgentPackageCatalog disabled = AgentPackageCatalog.fromJson(restored.toJson());
        assertFalse(disabled.find("water_ops").authorized());
    }
}
