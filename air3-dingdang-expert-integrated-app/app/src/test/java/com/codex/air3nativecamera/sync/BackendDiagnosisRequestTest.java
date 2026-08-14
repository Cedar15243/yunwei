package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

public final class BackendDiagnosisRequestTest {
    @Test
    public void diagnosisPayloadCarriesRealProjectAndTaskIdentity() throws Exception {
        JSONObject payload = BackendDiagnosisRequest.create(
                "image-a",
                "检查控制器",
                new AiExecutionContext("project-a", "task-a"));

        assertEquals("image-a", payload.getString("image_id"));
        assertEquals("检查控制器", payload.getString("final_text"));
        assertEquals("project-a", payload.getString("localProjectId"));
        assertEquals("task-a", payload.getString("localTaskId"));
        assertEquals("dingdang-android",
                payload.getJSONObject("client_context").getString("source"));
        assertFalse(payload.has("model"));
        assertFalse(payload.has("provider"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void executionContextRejectsMissingTaskIdentity() {
        new AiExecutionContext("project-a", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void executionContextRejectsUntrustedIdentifierCharacters() {
        new AiExecutionContext("project-a", "task/a");
    }

    @Test(expected = IllegalArgumentException.class)
    public void diagnosisPayloadRejectsMissingUserText() {
        BackendDiagnosisRequest.create(
                "",
                " ",
                new AiExecutionContext("project-a", "task-a"));
    }
}
