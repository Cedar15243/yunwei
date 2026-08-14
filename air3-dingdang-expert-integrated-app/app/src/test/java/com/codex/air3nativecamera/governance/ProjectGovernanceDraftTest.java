package com.codex.air3nativecamera.governance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ProjectGovernanceDraftTest {
    @Test
    public void taskEndDraftPreservesServerMemoryAndAddsTheConfirmedTaskClosure() throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession session = manager.startNew("project-a", "控制器报警");
        session.maintenanceTask().setDiagnosis("控制器供电异常", "需要复核。", 80);
        session.maintenanceTask().replaceRepairSteps(new String[]{"记录报警代码", "检查控制器供电"});
        session.maintenanceTask().advanceRepairStep();
        session.maintenanceTask().addEvidence("控制器近景照片", "photo://controller");
        JSONObject project = new JSONObject().put("projectMemory", new JSONObject()
                .put("revision", 7)
                .put("confirmedFacts", new JSONArray().put("设备型号为 HF-100"))
                .put("excludedFacts", new JSONArray().put("不是主电源故障"))
                .put("risks", new JSONArray().put("复位前确认现场安全")))
                .put("tasks", new JSONArray().put(new JSONObject()
                        .put("localTaskId", session.id())
                        .put("skillVersionId", "skill-hvac@1.0.0")
                        .put("knowledgeVersionIds", new JSONArray().put("knowledge-hvac@3"))
                        .put("contentManifestVersion", 7)))
                .put("projectInstructions", new JSONArray().put(new JSONObject()
                        .put("instructionId", "instruction-a")
                        .put("version", 2)
                        .put("status", "active")));

        ProjectGovernanceDraft draft = ProjectGovernanceDraft.taskEnd(
                session, project, "completed");

        assertEquals(ProjectGovernanceDraft.Kind.TASK_END, draft.kind());
        assertEquals("project-a", draft.localProjectId());
        assertEquals(session.id(), draft.localTaskId());
        assertEquals(7, draft.expectedMemoryRevision());
        assertEquals("设备型号为 HF-100", draft.confirmedFacts().get(0));
        assertTrue(draft.confirmedFacts().get(1).contains("控制器报警"));
        assertEquals("不是主电源故障", draft.excludedFacts().get(0));
        assertEquals("复位前确认现场安全", draft.risks().get(0));
        assertTrue(draft.idempotencyKey().startsWith("end-task-"));

        OperationDetail detail = draft.confirmationDetail("一号机房");
        assertEquals("任务结束摘要", detail.title());
        assertTrue(detail.items().toString().contains("已完成步骤：1/2"));
        assertTrue(detail.items().toString().contains("未完成步骤：1"));
        assertTrue(detail.items().toString().contains("控制器近景照片"));
        assertTrue(detail.items().toString().contains("skill-hvac@1.0.0"));
        assertTrue(detail.items().toString().contains("knowledge-hvac@3"));
        assertTrue(detail.items().toString().contains("instruction-a@v2"));
        assertTrue(detail.itemActions().contains("managed_governance_switch_end:closed"));
        assertTrue(detail.itemActions().contains("managed_governance_review_memory"));
        assertTrue(detail.itemActions().contains("managed_governance_call_expert"));
        assertEquals("继续当前任务", detail.secondaryLabel());
    }

    @Test
    public void projectInstructionDraftIsProjectScopedAndHumanReviewable() throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession session = manager.startNew("project-a", "控制器报警");

        ProjectGovernanceDraft draft = ProjectGovernanceDraft.projectInstruction(
                session, "以后在这个项目遇到控制器报警先记录故障码");
        OperationDetail detail = draft.confirmationDetail("一号机房");

        assertEquals(ProjectGovernanceDraft.Kind.PROJECT_INSTRUCTION, draft.kind());
        assertEquals(0, draft.expectedInstructionVersion());
        assertEquals(1, draft.condition().getJSONArray("containsAny").length());
        assertEquals("控制器报警", draft.condition().getJSONArray("containsAny").getString(0));
        assertTrue(draft.instructionId().startsWith("instruction-"));
        assertTrue(draft.sourceTraceId().startsWith("trace-"));
        assertEquals("managed_governance_confirm", detail.primaryAction());
        assertEquals("managed_governance_cancel", detail.secondaryAction());
        assertTrue(detail.items().get(1).contains("先记录故障码"));
        assertTrue(detail.items().toString().contains("触发条件：后续问题包含“控制器报警”"));
    }

    @Test
    public void explicitProjectWideInstructionKeepsAnEmptyCondition() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession session = manager.startNew("project-a", "控制器报警");

        ProjectGovernanceDraft draft = ProjectGovernanceDraft.projectInstruction(
                session, "以后先记录每次现场操作");

        assertEquals(0, draft.condition().length());
        assertTrue(draft.confirmationDetail("一号机房").items().toString()
                .contains("触发条件：本项目后续所有 AI 对话"));
    }

    @Test
    public void genericProblemReferenceUsesTheCurrentTaskProblem() throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession session = manager.startNew("project-a", "冷机控制器间歇报警");

        ProjectGovernanceDraft draft = ProjectGovernanceDraft.projectInstruction(
                session, "以后在这个项目遇到这个问题时，先拍摄报警代码");

        assertEquals("冷机控制器间歇报警",
                draft.condition().getJSONArray("containsAny").getString(0));
    }

    @Test
    public void existingProjectInstructionCreatesHumanReviewedLifecycleVersions()
            throws Exception {
        java.lang.reflect.Method revisionFactory;
        try {
            revisionFactory = ProjectGovernanceDraft.class.getMethod(
                    "projectInstructionRevision",
                    String.class,
                    String.class,
                    int.class,
                    String.class,
                    JSONObject.class,
                    String.class,
                    java.util.List.class,
                    String.class,
                    String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("project instruction revision factory is missing", missing);
        }
        JSONObject condition = new JSONObject()
                .put("containsAny", new JSONArray().put("控制器报警"));
        java.util.List<String> exceptions = java.util.Arrays.asList("完全断电时先检查供电");

        ProjectGovernanceDraft edited = (ProjectGovernanceDraft) revisionFactory.invoke(
                null,
                "project-a",
                "instruction-a",
                3,
                "active",
                condition,
                "以后在这个项目遇到控制器报警先记录故障码",
                exceptions,
                "active",
                "以后在这个项目遇到控制器供电异常先读取报警代码");
        ProjectGovernanceDraft disabled = (ProjectGovernanceDraft) revisionFactory.invoke(
                null,
                "project-a",
                "instruction-a",
                3,
                "active",
                condition,
                "以后在这个项目遇到控制器报警先记录故障码",
                exceptions,
                "disabled",
                "");
        ProjectGovernanceDraft enabled = (ProjectGovernanceDraft) revisionFactory.invoke(
                null,
                "project-a",
                "instruction-a",
                4,
                "disabled",
                condition,
                "以后在这个项目遇到控制器报警先记录故障码",
                exceptions,
                "active",
                "");
        ProjectGovernanceDraft deleted = (ProjectGovernanceDraft) revisionFactory.invoke(
                null,
                "project-a",
                "instruction-a",
                4,
                "disabled",
                condition,
                "以后在这个项目遇到控制器报警先记录故障码",
                exceptions,
                "deleted",
                "");

        assertEquals("instruction-a", edited.instructionId());
        assertEquals(3, edited.expectedInstructionVersion());
        assertEquals("active", stringGetter(edited, "instructionStatus"));
        assertEquals("控制器供电异常",
                edited.condition().getJSONArray("containsAny").getString(0));
        assertEquals(exceptions, listGetter(edited, "instructionExceptions"));
        assertTrue(edited.confirmationDetail("一号机房").title().contains("新版本"));
        assertEquals("disabled", stringGetter(disabled, "instructionStatus"));
        assertTrue(disabled.confirmationDetail("一号机房").title().contains("停用"));
        assertEquals("active", stringGetter(enabled, "instructionStatus"));
        assertTrue(enabled.confirmationDetail("一号机房").title().contains("启用"));
        assertEquals("deleted", stringGetter(deleted, "instructionStatus"));
        assertTrue(deleted.confirmationDetail("一号机房").title().contains("删除"));
        assertTrue(deleted.confirmationDetail("一号机房").description().contains("历史版本"));
    }

    private static String stringGetter(ProjectGovernanceDraft draft, String name)
            throws Exception {
        try {
            return (String) ProjectGovernanceDraft.class.getMethod(name).invoke(draft);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(name + " getter is missing", missing);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> listGetter(
            ProjectGovernanceDraft draft, String name
    ) throws Exception {
        try {
            return (java.util.List<String>) ProjectGovernanceDraft.class
                    .getMethod(name).invoke(draft);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(name + " getter is missing", missing);
        }
    }
}
