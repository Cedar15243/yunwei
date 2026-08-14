package com.codex.air3nativecamera.sync;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TaskCompletionSyncPayloadTest {
    @Test
    public void recordsTheServerAcceptedHumanCompletionSnapshot() throws Exception {
        JSONObject payload = TaskCompletionSyncPayload.create(
                "completed",
                "已更换损坏的控制器并完成复测。",
                7L,
                Arrays.asList("24V 供电正常", "更换后通信恢复"),
                Collections.singletonList("不是总线极性问题"),
                Collections.singletonList("后续观察控制器温升"));

        assertTrue(payload.getBoolean("humanConfirmed"));
        assertEquals("completed", payload.getString("taskStatus"));
        assertEquals("COMPLETED", payload.getString("phase"));
        assertEquals(7L, payload.getLong("projectMemoryRevision"));
        assertEquals(2, payload.getJSONArray("confirmedFacts").length());
        assertEquals(1, payload.getJSONArray("excludedFacts").length());
        assertEquals(1, payload.getJSONArray("risks").length());
    }

    @Test
    public void rejectsUnconfirmedOrUnboundedCompletionData() {
        assertInvalid("active", "有效摘要", 0L,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertInvalid("completed", "", 0L,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertInvalid("completed", "有效摘要", -1L,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertInvalid("completed", "有效摘要", 0L,
                Collections.singletonList(repeat("x", 1001)), Collections.emptyList(), Collections.emptyList());
    }

    private static void assertInvalid(String status, String summary, long revision,
            java.util.List<String> confirmedFacts, java.util.List<String> excludedFacts,
            java.util.List<String> risks) {
        try {
            TaskCompletionSyncPayload.create(status, summary, revision,
                    confirmedFacts, excludedFacts, risks);
            fail("invalid task completion payload must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().startsWith("task_completion_"));
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(count * value.length());
        for (int index = 0; index < count; index++) builder.append(value);
        return builder.toString();
    }
}
