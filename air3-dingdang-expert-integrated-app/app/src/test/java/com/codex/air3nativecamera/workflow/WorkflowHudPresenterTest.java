package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.sync.TaskSyncEvent;
import com.codex.air3nativecamera.sync.WorkflowAssignmentRepository;
import com.codex.air3nativecamera.sync.WorkflowDeviceHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowHudPresenterTest {
    private static final String EXECUTION_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    public void rendersRealAssignmentsAsActionableWorkOrderRows() throws Exception {
        Fixture fixture = fixture();

        OperationDetail detail = new WorkflowHudPresenter().taskList(
                fixture.coordinator.tasks());

        assertEquals("维修工单", detail.tag());
        assertEquals("我的维修工单", detail.title());
        assertEquals(7, detail.items().size());
        assertTrue(detail.items().get(0).contains("MVS-RESUME"));
        assertTrue(detail.items().get(0).contains("冷水机组控制器故障"));
        assertTrue(detail.items().get(0).contains("Huafang HF-CH-01"));
        assertEquals("workflow_open:resume", detail.itemActions().get(0));
        assertEquals("", detail.primaryAction());
    }

    @Test
    public void mapsEveryAssignmentEntryStateWithoutInventingLocalSuccess() throws Exception {
        Fixture fixture = fixture();
        WorkflowHudPresenter presenter = new WorkflowHudPresenter();

        assertPrimary(presenter.taskDetail(fixture.coordinator.detail("required")),
                "workflow_start:required", "开始工作流");
        assertPrimary(presenter.taskDetail(fixture.coordinator.detail("resume")),
                "workflow_resume:resume", "继续工作流");

        OperationDetail optional = presenter.taskDetail(
                fixture.coordinator.detail("optional"));
        assertEquals("workflow_choose:optional", optional.primaryAction());
        assertEquals("按工作流执行", optional.primaryLabel());
        assertEquals("workflow_standard:optional", optional.secondaryAction());
        assertEquals("普通任务", optional.secondaryLabel());

        assertPrimary(presenter.taskDetail(fixture.coordinator.detail("standard")),
                "workflow_standard:standard", "进入普通任务");

        OperationDetail waiting = presenter.taskDetail(
                fixture.coordinator.detail("waiting"));
        assertEquals("等待工作流下发", waiting.primaryLabel());
        assertEquals("", waiting.primaryAction());
        assertEquals("workflow_list", waiting.secondaryAction());

        OperationDetail completed = presenter.taskDetail(
                fixture.coordinator.detail("completed"));
        assertEquals("工作流已完成", completed.primaryLabel());
        assertEquals("", completed.primaryAction());

        OperationDetail unavailable = presenter.taskDetail(
                fixture.coordinator.detail("failed"));
        assertEquals("工作流不可用", unavailable.primaryLabel());
        assertEquals("", unavailable.primaryAction());
    }

    @Test
    public void rendersStartedAndResumedStepsUsingTheExistingOperationHudContract() throws Exception {
        Fixture fixture = fixture();
        WorkflowHudPresenter presenter = new WorkflowHudPresenter();

        WorkflowExecutionCoordinator.OpenResult started =
                fixture.coordinator.startOrResume("required");
        OperationDetail startedDetail = presenter.openResult("required", started);
        assertEquals("步骤 1", startedDetail.tag());
        assertEquals("拍摄设备铭牌", startedDetail.title());
        assertEquals("保持铭牌文字完整清晰", startedDetail.description());
        assertEquals("workflow_capture_photo", startedDetail.primaryAction());
        assertEquals("workflow_back", startedDetail.secondaryAction());

        WorkflowExecutionCoordinator.OpenResult resumed =
                fixture.coordinator.startOrResume("resume");
        OperationDetail resumedDetail = presenter.openResult("resume", resumed);
        assertEquals("拍摄设备铭牌", resumedDetail.title());
        assertEquals("workflow_capture_photo", resumedDetail.primaryAction());
    }

    @Test
    public void rendersWaitingAndCompletedOpenResultsAsExplicitReadOnlyStates() throws Exception {
        Fixture fixture = fixture();
        WorkflowHudPresenter presenter = new WorkflowHudPresenter();

        OperationDetail waiting = presenter.openResult(
                "waiting", fixture.coordinator.startOrResume("waiting"));
        assertEquals("工作流尚未就绪", waiting.title());
        assertEquals("workflow_list", waiting.secondaryAction());

        OperationDetail completed = presenter.openResult(
                "completed", fixture.coordinator.startOrResume("completed"));
        assertEquals("任务已完成", completed.title());
        assertEquals("workflow_list", completed.secondaryAction());
    }

    @Test
    public void rendersBlockedAdvancedAndCompletedActionResultsAuthoritatively() throws Exception {
        Fixture fixture = fixture();
        WorkflowHudPresenter presenter = new WorkflowHudPresenter();
        fixture.coordinator.startOrResume("required");

        WorkflowExecutionCoordinator.ActionResult blocked = fixture.coordinator.advance(
                "required", new WorkflowStepContext(), new JSONObject(), new JSONObject());
        OperationDetail blockedDetail = presenter.actionResult("required", blocked);
        assertEquals("拍摄设备铭牌", blockedDetail.title());
        assertTrue(blockedDetail.description().contains("尚未满足"));

        fixture.coordinator.recordEvidence("required", new WorkflowEvidenceReference(
                "local-photo-1", "photo", "nameplate",
                WorkflowStepContext.EvidenceType.PHOTO,
                "task-evidence/photo-1.jpg", "", 0));
        WorkflowExecutionCoordinator.ActionResult advanced = fixture.coordinator.advance(
                "required", new WorkflowStepContext(), new JSONObject(),
                new JSONObject().put("capturedCount", 1));
        OperationDetail advancedDetail = presenter.actionResult("required", advanced);
        assertEquals("任务完成", advancedDetail.title());

        WorkflowExecutionCoordinator.ActionResult completed = fixture.coordinator.advance(
                "required", new WorkflowStepContext(), new JSONObject(), new JSONObject());
        OperationDetail completedDetail = presenter.actionResult("required", completed);
        assertEquals("任务已完成", completedDetail.title());
        assertEquals("workflow_list", completedDetail.secondaryAction());
    }

    private static void assertPrimary(
            OperationDetail detail,
            String action,
            String label
    ) {
        assertEquals(action, detail.primaryAction());
        assertEquals(label, detail.primaryLabel());
        assertEquals("workflow_list", detail.secondaryAction());
    }

    private static Fixture fixture() throws Exception {
        MemoryAssignmentStorage assignmentStorage = new MemoryAssignmentStorage();
        WorkflowAssignmentRepository assignments = new WorkflowAssignmentRepository(
                assignmentStorage);
        List<WorkflowDeviceHttpClient.Assignment> values = Arrays.asList(
                assignment("required", "required", "ready", 1L),
                assignment("optional", "optional", "ready", 2L),
                assignment("standard", "none", "ready", 3L),
                assignment("waiting", "required", "queued", 4L),
                assignment("completed", "required", "completed", 5L),
                assignment("failed", "required", "failed", 6L),
                assignment("resume", "required", "active", 7L));
        assignments.apply(assignmentPage(values, 7L));

        MemorySnapshots snapshots = new MemorySnapshots();
        cache(assignments, snapshots, "required", "");
        cache(assignments, snapshots, "optional", "");
        cache(assignments, snapshots, "completed", "completed");
        cache(assignments, snapshots, "resume", "active");
        return new Fixture(assignments, snapshots);
    }

    private static void cache(
            WorkflowAssignmentRepository assignments,
            MemorySnapshots snapshots,
            String id,
            String runtimeStatus
    ) throws Exception {
        assignments.markPackageCached(id, "version-" + id);
        JSONObject envelope = envelope(id);
        WorkflowPackage workflowPackage = WorkflowPackage.parseVerified(
                envelope, envelope.getJSONObject("executionPackage"));
        WorkflowRuntimeState state = new WorkflowStateMachine(workflowPackage).start();
        if ("active".equals(runtimeStatus)) {
            state = state.withExecutionId(EXECUTION_ID);
        } else if ("completed".equals(runtimeStatus)) {
            state = WorkflowRuntimeState.fromJson(state.toJson()
                    .put("execution_id", EXECUTION_ID)
                    .put("current_node_id", "complete")
                    .put("status", "COMPLETED")
                    .put("sequence", 2));
        }
        snapshots.values.put(id, new WorkflowSnapshot(envelope, workflowPackage, state));
    }

    private static WorkflowDeviceHttpClient.Assignment assignment(
            String id,
            String mode,
            String status,
            long sequence
    ) throws Exception {
        String workflowVersionId = "none".equals(mode) ? "" : "version-" + id;
        Constructor<WorkflowDeviceHttpClient.Assignment> constructor =
                WorkflowDeviceHttpClient.Assignment.class.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class,
                        String.class, String.class, long.class, String.class,
                        WorkflowDeviceHttpClient.WorkOrderSummary.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                id, "order-" + id, "project-a", workflowVersionId, mode, status,
                sequence, "2026-08-01T00:00:00Z", workOrderSummary(id));
    }

    private static WorkflowDeviceHttpClient.AssignmentPage assignmentPage(
            List<WorkflowDeviceHttpClient.Assignment> values,
            long sequence
    ) throws Exception {
        Constructor<WorkflowDeviceHttpClient.AssignmentPage> constructor =
                WorkflowDeviceHttpClient.AssignmentPage.class.getDeclaredConstructor(
                        List.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(values, sequence);
    }

    private static WorkflowDeviceHttpClient.WorkOrderSummary workOrderSummary(
            String id
    ) throws Exception {
        Constructor<?> constructor = Arrays.stream(
                        WorkflowDeviceHttpClient.WorkOrderSummary.class.getDeclaredConstructors())
                .filter(item -> item.getParameterTypes().length == 14)
                .findFirst()
                .orElseThrow(NoSuchMethodException::new);
        constructor.setAccessible(true);
        return (WorkflowDeviceHttpClient.WorkOrderSummary) constructor.newInstance(
                "MVS-" + id.toUpperCase(), "冷水机组控制器故障", "控制器报警",
                "customer-a", "repair", "asset-a", "hvac", "Huafang",
                "HF-CH-01", "high", "medium", "received",
                "2026-08-02T01:00:00Z", "2026-08-01T00:30:00Z");
    }

    private static JSONObject envelope(String assignmentId) throws Exception {
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
                .put("assignmentId", assignmentId)
                .put("workflowVersionId", "version-" + assignmentId)
                .put("signatureKeyId", "key-a")
                .put("minAppVersionCode", 9000)
                .put("executionPackage", executionPackage);
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
        private final WorkflowExecutionCoordinator coordinator;

        private Fixture(
                WorkflowAssignmentRepository assignments,
                MemorySnapshots snapshots
        ) {
            List<TaskSyncEvent> sent = new ArrayList<>();
            this.coordinator = new WorkflowExecutionCoordinator(
                    assignments, snapshots, sent::add, Runnable::run);
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
        private byte[] bytes;

        @Override
        public byte[] read() {
            return bytes == null ? null : bytes.clone();
        }

        @Override
        public void writeAtomically(byte[] next) throws IOException {
            bytes = next.clone();
        }
    }
}
