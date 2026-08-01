package com.codex.air3nativecamera.sync;

import org.json.JSONObject;

import java.io.IOException;

public interface WorkflowAssignmentGateway {
    WorkflowDeviceHttpClient.AssignmentPage listAssignments(long afterSequence, int limit)
            throws IOException;

    JSONObject fetchPackage(String assignmentId) throws IOException;
}
