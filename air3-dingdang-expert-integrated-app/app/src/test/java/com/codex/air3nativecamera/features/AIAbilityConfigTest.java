package com.codex.air3nativecamera.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public final class AIAbilityConfigTest {
    @Test
    public void defaultConfigsDescribeTheCompleteAbilityCenter() {
        List<AIAbilityConfig> configs = AIAbilityConfig.defaultConfigs();
        Set<String> ids = new HashSet<>();
        for (AIAbilityConfig config : configs) {
            ids.add(config.id());
        }

        assertEquals(9, configs.size());
        assertEquals(configs.size(), ids.size());
        assertTrue(ids.contains("diagnosis"));
        assertTrue(ids.contains("inspection"));
        assertTrue(ids.contains("perception"));
        assertTrue(ids.contains("device_brain"));
        assertTrue(ids.contains("knowledge"));
        assertTrue(ids.contains("video_evidence"));
        assertTrue(ids.contains("agent_center"));
        assertTrue(ids.contains("tasks"));
        assertTrue(ids.contains("expert_collab"));
    }

    @Test
    public void capabilityCenterUsesTheApprovedThreeByThreeOrderAndNames() {
        String[] expected = {
                "AI 故障诊断", "专家协同", "现场拍照",
                "短视频取证", "巡检任务", "维修任务",
                "华方知识库", "设备记忆", "AI运维技能"
        };
        List<AIAbilityConfig> configs = AIAbilityConfig.defaultConfigs();

        assertEquals(expected.length, configs.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], configs.get(index).title());
        }
    }

    @Test
    public void remoteAndMediaFlowsAreOnlineWhileLocalWorkflowsAreMarkedAvailable() {
        for (AIAbilityConfig config : AIAbilityConfig.defaultConfigs()) {
            if ("diagnosis".equals(config.id()) || "perception".equals(config.id())
                    || "video_evidence".equals(config.id())
                    || "expert_collab".equals(config.id())) {
                assertEquals(AIAbilityConfig.Status.ONLINE, config.status());
            } else {
                assertEquals(AIAbilityConfig.Status.LOCAL, config.status());
            }
            assertFalse(config.pageDescription().contains("不承诺"));
            assertFalse(config.pageDescription().contains("开发中"));
        }
    }

    @Test
    public void localOperationDetailsUseTheirExistingRoutes() {
        for (AIAbilityConfig config : AIAbilityConfig.defaultConfigs()) {
            if ("agent_center".equals(config.id())) {
                assertEquals(AIAbilityConfig.Route.AGENT_CENTER, config.route());
            } else if (config.status() == AIAbilityConfig.Status.LOCAL) {
                assertEquals(AIAbilityConfig.Route.PLACEHOLDER, config.route());
            }
        }
        assertFalse(AIAbilityConfig.defaultConfigs().isEmpty());
    }
}
