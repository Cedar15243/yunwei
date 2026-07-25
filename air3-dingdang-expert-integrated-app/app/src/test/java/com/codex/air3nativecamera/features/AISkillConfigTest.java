package com.codex.air3nativecamera.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public final class AISkillConfigTest {
    @Test
    public void defaultSkillCatalogReservesTheRequestedDomains() {
        List<AISkillConfig> configs = AISkillConfig.defaultConfigs();
        Set<String> ids = new HashSet<>();
        for (AISkillConfig config : configs) {
            ids.add(config.id());
            assertEquals(AISkillConfig.Status.PLANNED, config.status());
        }

        assertEquals(5, configs.size());
        assertEquals(configs.size(), ids.size());
        assertTrue(ids.contains("server_ops"));
        assertTrue(ids.contains("network_fault"));
        assertTrue(ids.contains("pump_ops"));
        assertTrue(ids.contains("fire_inspection"));
        assertTrue(ids.contains("air_conditioning"));
    }
}
