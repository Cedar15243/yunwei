package com.codex.air3nativecamera.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public final class AIAgentConfigTest {
    @Test
    public void defaultAgentsAreCatalogOnlyAndReferenceReservedSkills() {
        List<AIAgentConfig> agents = AIAgentConfig.defaultConfigs();
        Set<String> ids = new HashSet<>();
        for (AIAgentConfig agent : agents) {
            ids.add(agent.id());
            assertEquals(AIAgentConfig.Status.PLANNED, agent.status());
            assertTrue(agent.skillIds().size() > 0);
        }

        assertEquals(2, agents.size());
        assertEquals(agents.size(), ids.size());
        assertTrue(ids.contains("ops_copilot"));
        assertTrue(ids.contains("facility_copilot"));
    }
}
