package com.codex.air3nativecamera.features.operations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InspectionChecklistTest {
    @Test
    public void completionProgressChangesOnlyOnceForTheSameInspectionItem() {
        InspectionChecklist checklist = InspectionChecklist.defaultChecklist();

        assertEquals(3, checklist.totalCount());
        assertEquals(0, checklist.completedCount());
        assertTrue(checklist.complete("server_status"));
        assertFalse(checklist.complete("server_status"));
        assertEquals(1, checklist.completedCount());
        assertEquals("1/3", checklist.progressLabel());
    }

    @Test
    public void unknownInspectionItemDoesNotChangeProgress() {
        InspectionChecklist checklist = InspectionChecklist.defaultChecklist();

        assertFalse(checklist.complete("unknown"));
        assertEquals(0, checklist.completedCount());
    }
}
