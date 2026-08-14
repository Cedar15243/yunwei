package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.sync.TaskSyncEvent;
import com.codex.air3nativecamera.sync.AiExecutionContext;
import com.codex.air3nativecamera.sync.WorkflowAssignmentRepository;
import com.codex.air3nativecamera.sync.WorkflowDeviceHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowExecutionCoordinatorTest {
    private static final String EXECUTION_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    public void formFieldsPersistAsDraftsAndAdvanceOnlyAfterExplicitNext() throws Exception {
        Fixture fixture = fixture(formEnvelope());
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();
        coordinator.startOrResume("assignment-a");
        fixture.jobs.remove(0).run();

        WorkflowExecutionCoordinator.ActionResult first = invokeRecordFormField(
                coordinator, "assignment-a", "form", "siteCode", "  A-01  ");

        assertEquals(WorkflowExecutionCoordinator.ActionCode.RECORDED, first.code());
        assertEquals("form", first.state().currentNodeId());
        assertEquals("A-01", first.state().variables().getString("siteCode"));
        assertTrue(first.step().items().get(0).contains("A-01"));

        WorkflowExecutionCoordinator restored = fixture.coordinator();
        WorkflowExecutionCoordinator.ActionResult second = invokeRecordFormField(
                restored, "assignment-a", "form", "temperature", "18.5");
        assertEquals(WorkflowExecutionCoordinator.ActionCode.RECORDED, second.code());
        assertEquals("18.5", second.state().variables().getString("temperature"));

        WorkflowExecutionCoordinator.ActionResult advanced = invokeAdvanceForm(
                restored, "assignment-a");
        assertEquals(WorkflowExecutionCoordinator.ActionCode.ADVANCED, advanced.code());
        assertEquals("complete", advanced.state().currentNodeId());
    }

    @Test
    public void formDraftRejectsUnknownFieldsAndInvalidNumberValues() throws Exception {
        Fixture fixture = fixture(formEnvelope());
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();
        coordinator.startOrResume("assignment-a");

        assertEquals(WorkflowExecutionCoordinator.ActionCode.INVALID_STATE,
                invokeRecordFormField(
                        coordinator, "assignment-a", "form", "unknown", "value").code());
        assertEquals(WorkflowExecutionCoordinator.ActionCode.INVALID_STATE,
                invokeRecordFormField(
                        coordinator, "assignment-a", "form", "temperature", "hot").code());
    }

    @Test
    public void exposesRealWorkOrderDataAndTheCorrectEntryAction() throws Exception {
        Fixture fixture = fixture();
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();

        List<WorkflowExecutionCoordinator.TaskListItem> tasks = coordinator.tasks();

        assertEquals(1, tasks.size());
        assertEquals("MVS-20260801-001", tasks.get(0).displayCode());
        assertEquals("冷水机组控制器故障", tasks.get(0).title());
        assertEquals("Huafang HF-CH-01", tasks.get(0).assetLabel());
        assertEquals(WorkflowExecutionCoordinator.EntryAction.START_WORKFLOW,
                tasks.get(0).entryAction());

        WorkflowExecutionCoordinator.TaskDetail detail =
                coordinator.detail("assignment-a");
        assertEquals("控制器报警", detail.description());
        assertEquals("设备收货检查", detail.workflowTitle());
        assertEquals("required", detail.mode());
        assertEquals(WorkflowExecutionCoordinator.EntryAction.START_WORKFLOW,
                detail.entryAction());
    }

    @Test
    public void workflowExecutionContextUsesTheAssignedProjectAndStableTaskIdentity() throws Exception {
        WorkflowExecutionCoordinator coordinator = fixture().coordinator();

        AiExecutionContext context = coordinator.executionContextFor("assignment-a");

        assertEquals("project-a", context.localProjectId());
        assertEquals("workflow-task:assignment-a", context.localTaskId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void workflowExecutionContextRejectsUnknownAssignment() throws Exception {
        fixture().coordinator().executionContextFor("missing-assignment");
    }

    @Test
    public void atomicallyStartsThenRestoresTheSameExecutionAndStep() throws Exception {
        Fixture fixture = fixture();
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();

        WorkflowExecutionCoordinator.OpenResult opened =
                coordinator.startOrResume("assignment-a");

        assertEquals(WorkflowExecutionCoordinator.OpenCode.STARTED, opened.code());
        assertEquals(EXECUTION_ID, opened.state().executionId());
        assertEquals("photo", opened.state().currentNodeId());
        assertEquals("拍摄设备铭牌", opened.step().title());
        assertEquals(WorkflowCapabilityRegistry.PageTemplate.EVIDENCE_CAPTURE,
                opened.step().pageTemplate());
        WorkflowRuntimeState durable = fixture.snapshots.load("assignment-a").runtimeState();
        assertEquals(EXECUTION_ID, durable.executionId());
        assertEquals(4, durable.pendingEvents().size());
        assertEquals(Arrays.asList(
                        "workflow_assignment_status",
                        "workflow_assignment_status",
                        "workflow_assignment_status",
                        "workflow_execution_start"),
                eventTypes(durable.pendingEvents()));
        assertEquals(1, fixture.jobs.size());
        assertTrue(fixture.sent.isEmpty());

        fixture.jobs.remove(0).run();
        assertEquals(4, fixture.sent.size());
        assertFalse(fixture.snapshots.load("assignment-a").runtimeState()
                .hasPendingEvent("assignment-a:execution:start"));

        WorkflowExecutionCoordinator.OpenResult restored =
                fixture.coordinator().startOrResume("assignment-a");
        assertEquals(WorkflowExecutionCoordinator.OpenCode.RESUMED, restored.code());
        assertEquals(EXECUTION_ID, restored.state().executionId());
        assertEquals("photo", restored.state().currentNodeId());
        assertTrue(fixture.jobs.isEmpty());
    }

    @Test
    public void completedExecutionReschedulesItsDurableOutboxAfterRestart() throws Exception {
        Fixture fixture = fixture();
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();
        coordinator.startOrResume("assignment-a");
        fixture.jobs.remove(0).run();
        coordinator.recordEvidence("assignment-a", new WorkflowEvidenceReference(
                "local-photo-1", "photo", "nameplate",
                WorkflowStepContext.EvidenceType.PHOTO,
                "task-evidence/photo-1.jpg",
                "77777777-7777-4777-8777-777777777777", 0));
        coordinator.advance(
                "assignment-a", new WorkflowStepContext(),
                new JSONObject(), new JSONObject().put("capturedCount", 1));
        fixture.jobs.remove(0).run();

        WorkflowExecutionCoordinator.ActionResult completed = coordinator.advance(
                "assignment-a", new WorkflowStepContext(),
                new JSONObject(), new JSONObject());
        assertEquals(WorkflowRuntimeState.Status.COMPLETED, completed.state().status());
        assertEquals(1, fixture.jobs.size());

        fixture.jobs.clear();
        WorkflowExecutionCoordinator.OpenResult restored =
                fixture.coordinator().startOrResume("assignment-a");

        assertEquals(WorkflowExecutionCoordinator.OpenCode.COMPLETED, restored.code());
        assertEquals(1, fixture.jobs.size());
    }

    @Test
    public void defersOfflineEvidenceAndEveryLaterStepUntilTheRemoteAssetExists() throws Exception {
        Fixture fixture = fixture();
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();
        coordinator.startOrResume("assignment-a");
        fixture.jobs.remove(0).run();

        WorkflowEvidenceReference localPhoto = new WorkflowEvidenceReference(
                "local-photo-1", "photo", "nameplate",
                WorkflowStepContext.EvidenceType.PHOTO,
                "task-evidence/photo-1.jpg", "", 0);
        WorkflowExecutionCoordinator.ActionResult recorded = coordinator.recordEvidence(
                "assignment-a", localPhoto);
        assertEquals(WorkflowExecutionCoordinator.ActionCode.RECORDED, recorded.code());

        WorkflowExecutionCoordinator.ActionResult atComplete = coordinator.advance(
                "assignment-a",
                new WorkflowStepContext()
                        .addEvidence("nameplate", WorkflowStepContext.EvidenceType.PHOTO, 0),
                new JSONObject(),
                new JSONObject().put("capturedCount", 1));
        assertEquals(WorkflowExecutionCoordinator.ActionCode.ADVANCED, atComplete.code());
        assertEquals("complete", atComplete.state().currentNodeId());
        assertEquals(1, atComplete.state().deferredSteps().size());
        assertEquals(0, stepEvents(atComplete.state().pendingEvents()).size());

        WorkflowExecutionCoordinator.ActionResult completed = coordinator.advance(
                "assignment-a", new WorkflowStepContext(),
                new JSONObject(), new JSONObject());
        assertEquals(WorkflowRuntimeState.Status.COMPLETED, completed.state().status());
        assertEquals(2, completed.state().deferredSteps().size());
        assertTrue(completed.state().deferredSteps().get(1).localEvidenceIds().isEmpty());
        assertEquals(0, stepEvents(completed.state().pendingEvents()).size());

        WorkflowExecutionCoordinator.ActionResult replayed = fixture.coordinator()
                .onEvidenceUploaded(
                        "assignment-a", "local-photo-1",
                        "77777777-7777-4777-8777-777777777777");
        assertEquals(WorkflowExecutionCoordinator.ActionCode.REPLAY_QUEUED, replayed.code());
        assertTrue(replayed.state().deferredSteps().isEmpty());
        List<TaskSyncEvent> replayEvents = stepEvents(replayed.state().pendingEvents());
        assertEquals(2, replayEvents.size());
        assertEquals("photo", new JSONObject(replayEvents.get(0).payload()).getString("nodeId"));
        assertEquals("77777777-7777-4777-8777-777777777777",
                new JSONObject(replayEvents.get(0).payload())
                        .getJSONArray("evidenceAssetIds").getString(0));
        assertEquals("complete",
                new JSONObject(replayEvents.get(1).payload()).getString("nodeId"));
        assertEquals(1, fixture.jobs.size());

        fixture.jobs.remove(0).run();
        assertEquals(Arrays.asList("photo", "complete"), sentStepNodes(fixture.sent));
        assertTrue(fixture.snapshots.load("assignment-a").runtimeState()
                .pendingEvents().isEmpty());
    }

    @Test
    public void refusesAnUnpersistedEvidenceClaimFromTheUiContext() throws Exception {
        Fixture fixture = fixture();
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();
        coordinator.startOrResume("assignment-a");
        fixture.jobs.remove(0).run();

        WorkflowExecutionCoordinator.ActionResult result = coordinator.advance(
                "assignment-a",
                new WorkflowStepContext()
                        .addEvidence("nameplate", WorkflowStepContext.EvidenceType.PHOTO, 0),
                new JSONObject(), new JSONObject());

        assertEquals(WorkflowExecutionCoordinator.ActionCode.BLOCKED, result.code());
        assertEquals("evidence_count_required", result.reason());
        assertEquals("photo", result.state().currentNodeId());
        assertTrue(result.state().deferredSteps().isEmpty());
        assertTrue(stepEvents(result.state().pendingEvents()).isEmpty());
    }

    @Test
    public void pendingDeliveryNeverOverwritesNewerEvidenceAndStepState() throws Exception {
        Fixture fixture = fixture();
        WorkflowExecutionCoordinator coordinator = fixture.coordinator();
        coordinator.startOrResume("assignment-a");
        assertEquals(1, fixture.jobs.size());

        coordinator.recordEvidence("assignment-a", new WorkflowEvidenceReference(
                "local-photo-1", "photo", "nameplate",
                WorkflowStepContext.EvidenceType.PHOTO,
                "task-evidence/photo-1.jpg", "", 0));
        coordinator.advance(
                "assignment-a", new WorkflowStepContext(),
                new JSONObject(), new JSONObject().put("capturedCount", 1));
        coordinator.onEvidenceUploaded(
                "assignment-a", "local-photo-1",
                "77777777-7777-4777-8777-777777777777");

        assertEquals(1, fixture.jobs.size());
        fixture.jobs.remove(0).run();

        WorkflowRuntimeState durable = fixture.snapshots.load("assignment-a").runtimeState();
        assertEquals("complete", durable.currentNodeId());
        assertTrue(durable.hasRemoteEvidenceAsset("local-photo-1"));
        assertTrue(durable.deferredSteps().isEmpty());
        assertTrue(durable.pendingEvents().isEmpty());
        assertEquals(Arrays.asList(
                        "workflow_assignment_status",
                        "workflow_assignment_status",
                        "workflow_assignment_status",
                        "workflow_execution_start",
                        "workflow_step_event"),
                eventTypes(fixture.sent));
    }

    private static List<String> eventTypes(List<TaskSyncEvent> events) {
        List<String> result = new ArrayList<>();
        for (TaskSyncEvent event : events) result.add(event.eventType());
        return result;
    }

    private static List<TaskSyncEvent> stepEvents(List<TaskSyncEvent> events) {
        List<TaskSyncEvent> result = new ArrayList<>();
        for (TaskSyncEvent event : events) {
            if ("workflow_step_event".equals(event.eventType())) result.add(event);
        }
        return result;
    }

    private static List<String> sentStepNodes(List<TaskSyncEvent> events) throws Exception {
        List<String> result = new ArrayList<>();
        for (TaskSyncEvent event : events) {
            if ("workflow_step_event".equals(event.eventType())) {
                result.add(new JSONObject(event.payload()).getString("nodeId"));
            }
        }
        return result;
    }

    private Fixture fixture() throws Exception {
        return fixture(envelope());
    }

    private Fixture fixture(JSONObject envelope) throws Exception {
        MemoryAssignmentStorage assignmentStorage = new MemoryAssignmentStorage();
        WorkflowAssignmentRepository assignments = new WorkflowAssignmentRepository(assignmentStorage);
        assignments.apply(assignmentPage(assignment()));
        assignments.markPackageCached("assignment-a", "version-a");

        WorkflowPackage workflowPackage = WorkflowPackage.parseVerified(
                envelope, envelope.getJSONObject("executionPackage"));
        WorkflowRuntimeState state = new WorkflowStateMachine(workflowPackage).start();
        MemorySnapshots snapshots = new MemorySnapshots();
        snapshots.values.put("assignment-a",
                new WorkflowSnapshot(envelope, workflowPackage, state));
        return new Fixture(assignments, snapshots);
    }

    private WorkflowDeviceHttpClient.Assignment assignment() throws Exception {
        WorkflowDeviceHttpClient.WorkOrderSummary summary = workOrderSummary();
        Constructor<WorkflowDeviceHttpClient.Assignment> constructor =
                WorkflowDeviceHttpClient.Assignment.class.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class,
                        String.class, String.class, long.class, String.class,
                        WorkflowDeviceHttpClient.WorkOrderSummary.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                "assignment-a", "order-a", "project-a", "version-a", "required",
                "ready", 7L, "2026-08-01T00:00:00Z", summary);
    }

    private WorkflowDeviceHttpClient.AssignmentPage assignmentPage(
            WorkflowDeviceHttpClient.Assignment assignment
    ) throws Exception {
        Constructor<WorkflowDeviceHttpClient.AssignmentPage> constructor =
                WorkflowDeviceHttpClient.AssignmentPage.class.getDeclaredConstructor(
                        List.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(Arrays.asList(assignment), 7L);
    }

    private WorkflowDeviceHttpClient.WorkOrderSummary workOrderSummary() throws Exception {
        Constructor<?> constructor = Arrays.stream(
                        WorkflowDeviceHttpClient.WorkOrderSummary.class.getDeclaredConstructors())
                .filter(item -> item.getParameterTypes().length == 14)
                .findFirst()
                .orElseThrow(NoSuchMethodException::new);
        constructor.setAccessible(true);
        return (WorkflowDeviceHttpClient.WorkOrderSummary) constructor.newInstance(
                "MVS-20260801-001", "冷水机组控制器故障", "控制器报警",
                "customer-a", "repair", "asset-a", "hvac", "Huafang",
                "HF-CH-01", "high", "medium", "received",
                "2026-08-02T01:00:00Z", "2026-08-01T00:30:00Z");
    }

    private JSONObject envelope() throws Exception {
        JSONObject executionPackage = new JSONObject()
                .put("workflowId", "workflow-a")
                .put("schemaVersion", 1)
                .put("title", "设备收货检查")
                .put("contentSha256", repeat('a', 64))
                .put("requiredCapabilities", new JSONArray()
                        .put("workflow.runtime.v1").put("camera.photo"))
                .put("nodes", new JSONArray()
                        .put(node("start", "start", new JSONObject()))
                        .put(node("photo", "photo_capture", new JSONObject()
                                .put("title", "拍摄设备铭牌")
                                .put("description", "保持铭牌文字完整清晰")
                                .put("evidenceKey", "nameplate")
                                .put("minCount", 1)))
                        .put(node("complete", "complete", new JSONObject())))
                .put("transitions", new JSONArray()
                        .put(transition("start-photo", "start", "photo"))
                        .put(transition("photo-complete", "photo", "complete")));
        return new JSONObject()
                .put("assignmentId", "assignment-a")
                .put("workflowVersionId", "version-a")
                .put("signatureKeyId", "key-a")
                .put("minAppVersionCode", 9000)
                .put("executionPackage", executionPackage);
    }

    private JSONObject formEnvelope() throws Exception {
        JSONObject executionPackage = new JSONObject()
                .put("workflowId", "workflow-form")
                .put("schemaVersion", 1)
                .put("title", "现场配置记录")
                .put("contentSha256", repeat('b', 64))
                .put("requiredCapabilities", new JSONArray().put("workflow.runtime.v1"))
                .put("nodes", new JSONArray()
                        .put(node("start", "start", new JSONObject()))
                        .put(node("form", "form", new JSONObject()
                                .put("title", "填写现场信息")
                                .put("fields", new JSONArray()
                                        .put(new JSONObject()
                                                .put("key", "siteCode")
                                                .put("label", "站点编号")
                                                .put("type", "text")
                                                .put("required", true))
                                        .put(new JSONObject()
                                                .put("key", "temperature")
                                                .put("label", "现场温度")
                                                .put("type", "number")
                                                .put("min", -50)
                                                .put("max", 100)
                                                .put("required", true)))))
                        .put(node("complete", "complete", new JSONObject())))
                .put("transitions", new JSONArray()
                        .put(transition("start-form", "start", "form"))
                        .put(transition("form-complete", "form", "complete")));
        return new JSONObject()
                .put("assignmentId", "assignment-a")
                .put("workflowVersionId", "version-a")
                .put("signatureKeyId", "key-a")
                .put("minAppVersionCode", 9000)
                .put("executionPackage", executionPackage);
    }

    private static WorkflowExecutionCoordinator.ActionResult invokeRecordFormField(
            WorkflowExecutionCoordinator coordinator,
            String assignmentId,
            String nodeId,
            String fieldKey,
            String value
    ) throws Exception {
        Method method;
        try {
            method = WorkflowExecutionCoordinator.class.getDeclaredMethod(
                    "recordFormField", String.class, String.class, String.class, String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("workflow form draft API is missing", missing);
        }
        method.setAccessible(true);
        return (WorkflowExecutionCoordinator.ActionResult) method.invoke(
                coordinator, assignmentId, nodeId, fieldKey, value);
    }

    private static WorkflowExecutionCoordinator.ActionResult invokeAdvanceForm(
            WorkflowExecutionCoordinator coordinator,
            String assignmentId
    ) throws Exception {
        Method method;
        try {
            method = WorkflowExecutionCoordinator.class.getDeclaredMethod(
                    "advanceForm", String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("workflow form advance API is missing", missing);
        }
        method.setAccessible(true);
        return (WorkflowExecutionCoordinator.ActionResult) method.invoke(coordinator, assignmentId);
    }

    private static JSONObject node(String id, String type, JSONObject config) throws Exception {
        return new JSONObject().put("nodeId", id).put("type", type).put("config", config);
    }

    private static JSONObject transition(String id, String from, String to) throws Exception {
        return new JSONObject().put("transitionId", id)
                .put("fromNodeId", from).put("toNodeId", to);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static final class Fixture {
        private final WorkflowAssignmentRepository assignments;
        private final MemorySnapshots snapshots;
        private final List<TaskSyncEvent> sent = new ArrayList<>();
        private final List<Runnable> jobs = new ArrayList<>();

        private Fixture(
                WorkflowAssignmentRepository assignments,
                MemorySnapshots snapshots
        ) {
            this.assignments = assignments;
            this.snapshots = snapshots;
        }

        private WorkflowExecutionCoordinator coordinator() {
            return new WorkflowExecutionCoordinator(
                    assignments,
                    snapshots,
                    sent::add,
                    jobs::add,
                    () -> EXECUTION_ID,
                    () -> 1000L);
        }
    }

    private static final class MemorySnapshots
            implements WorkflowExecutionCoordinator.SnapshotAccess {
        private final Map<String, WorkflowSnapshot> values = new HashMap<>();

        @Override
        public WorkflowSnapshot load(String assignmentId) {
            return values.get(assignmentId);
        }

        @Override
        public boolean save(
                String assignmentId,
                JSONObject envelope,
                WorkflowRuntimeState state
        ) {
            WorkflowSnapshot current = values.get(assignmentId);
            if (current == null) return false;
            values.put(assignmentId,
                    new WorkflowSnapshot(envelope, current.workflowPackage(), state));
            return true;
        }
    }

    private static final class MemoryAssignmentStorage
            implements WorkflowAssignmentRepository.Storage {
        private byte[] value;

        @Override
        public byte[] read() {
            return value == null ? null : value.clone();
        }

        @Override
        public void writeAtomically(byte[] next) throws IOException {
            value = next.clone();
        }
    }
}
