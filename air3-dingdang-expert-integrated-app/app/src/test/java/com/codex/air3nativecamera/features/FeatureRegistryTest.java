package com.codex.air3nativecamera.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class FeatureRegistryTest {
    @Test
    public void registersExpertAndEveryReservedOperationsFeature() {
        FeatureRegistry registry = FeatureRegistry.createDefault();

        assertEquals(Arrays.asList(
                "expert_collab",
                "equipment_inspection",
                "field_records",
                "asset_records",
                "work_orders",
                "knowledge_base",
                "operations_reports",
                "safe_operations",
                "training_drills"), registry.ids());
        assertTrue(registry.require("expert_collab").isAvailable());
        assertFalse(registry.require("equipment_inspection").isAvailable());
        assertEquals("现场记录", registry.require("field_records").title());
        assertEquals("培训演练", registry.require("training_drills").title());
    }

    @Test
    public void reservedFeatureOnlyReportsItsUnavailableContract() {
        FeatureRegistry registry = FeatureRegistry.createDefault();
        RecordingHost host = new RecordingHost();

        registry.require("work_orders").enter(host);

        assertEquals("work_orders", host.id);
        assertEquals("运维工单", host.title);
        assertFalse(host.available);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownFeatureIds() {
        FeatureRegistry.createDefault().require("missing");
    }

    private static final class RecordingHost implements FeatureEntry.FeatureHost {
        private String id;
        private String title;
        private boolean available;

        @Override
        public void openFeature(String id, String title, boolean available) {
            this.id = id;
            this.title = title;
            this.available = available;
        }
    }
}
