package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.sync.AiExecutionContext;
import com.codex.air3nativecamera.sync.DeviceAccessTokenProvider;
import com.codex.air3nativecamera.sync.DeviceSyncConfiguration;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.task.TaskSessionManager;

import java.lang.reflect.Method;

import org.junit.Test;

public final class MainActivityProjectBindingTest {
    @Test
    public void activeTaskProducesTheRealAiExecutionContext() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = manager.startNew("project-a", "检查控制器");

        AiExecutionContext context = MainActivity.aiExecutionContextFor(manager.active());

        assertEquals("project-a", context.localProjectId());
        assertEquals(task.id(), context.localTaskId());
    }

    @Test
    public void secureRuntimeUsesTheActiveTaskAsTheBackendChatSession() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession firstTask = manager.startNew("project-a", "检查控制器");

        assertEquals(firstTask.id(), MainActivity.backendSessionIdForExecution(
                true, "legacy-project-session", firstTask));

        manager.completeActive();
        TaskSession secondTask = manager.startNew("project-a", "检查服务器");

        assertEquals(secondTask.id(), MainActivity.backendSessionIdForExecution(
                true, "legacy-project-session", secondTask));
        assertFalse(firstTask.id().equals(secondTask.id()));
    }

    @Test
    public void legacyRuntimeKeepsTheExistingProjectChatSession() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession task = manager.startNew("project-a", "检查控制器");

        assertEquals("legacy-project-session", MainActivity.backendSessionIdForExecution(
                false, " legacy-project-session ", task));
    }

    @Test
    public void secureRuntimeNeverReusesAProjectSessionWithoutAnActiveTask() {
        assertEquals("", MainActivity.backendSessionIdForExecution(
                true, "completed-task-session", null));
    }

    @Test
    public void managedMediaUploadRequiresAnActiveTask() {
        TaskSessionManager manager = new TaskSessionManager();

        assertTrue(MainActivity.requiresActiveTaskBeforeManagedMediaUpload(
                true, manager.active()));
        TaskSession active = manager.startNew("project-a", "巡检任务：实训室设备巡检");
        assertFalse(MainActivity.requiresActiveTaskBeforeManagedMediaUpload(true, active));
        assertFalse(MainActivity.requiresActiveTaskBeforeManagedMediaUpload(false, null));
    }

    @Test(expected = IllegalStateException.class)
    public void returningHomePreventsThePausedTaskFromReachingAi() {
        TaskSessionManager manager = new TaskSessionManager();
        manager.startNew("project-a", "检查控制器");
        manager.pauseActive();

        MainActivity.aiExecutionContextFor(manager.findProject("project-a"));
    }

    @Test(expected = IllegalStateException.class)
    public void completedTaskCannotBeReusedByAi() {
        TaskSessionManager manager = new TaskSessionManager();
        manager.startNew("project-a", "检查控制器");
        manager.completeActive();

        MainActivity.aiExecutionContextFor(manager.active());
    }

    @Test(expected = IllegalStateException.class)
    public void missingTaskCannotCreateAnAiExecutionContext() {
        MainActivity.aiExecutionContextFor(null);
    }

    @Test
    public void governedExecutionContextClientExistsOnlyForSecureShortSessionRuntime() {
        DeviceSyncConfiguration configuration = DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events", "bootstrap-a");
        DeviceAccessTokenProvider tokenProvider = new DeviceAccessTokenProvider() {
            @Override public String accessToken() { return "access-a"; }
        };

        assertNotNull(MainActivity.createExecutionContextDeviceClient(
                true, configuration, tokenProvider));
        assertNull(MainActivity.createExecutionContextDeviceClient(
                false, configuration, tokenProvider));
        assertNull(MainActivity.createExecutionContextDeviceClient(
                true, null, tokenProvider));
        assertNull(MainActivity.createExecutionContextDeviceClient(
                true, configuration, null));
    }

    @Test
    public void managedBackendAuthorityRequiresBothTheHttpsAddressAndCredential() {
        assertTrue(MainActivity.hasCompleteManagedBackend(
                "https://ops.example.com/v9-ops", "bootstrap-a"));
        assertFalse(MainActivity.hasCompleteManagedBackend(
                "https://ops.example.com/v9-ops", ""));
        assertFalse(MainActivity.hasCompleteManagedBackend(
                "", "bootstrap-a"));
        assertFalse(MainActivity.hasCompleteManagedBackend(
                "http://ops.example.com/v9-ops", "bootstrap-a"));
    }

    @Test
    public void managedProvisioningAuthorityIncludesControlledDebugAuditProvisioning() {
        assertTrue(MainActivity.hasManagedProvisioningAuthority(
                true, false, false));
        assertTrue(MainActivity.hasManagedProvisioningAuthority(
                false, true, true));
        assertFalse(MainActivity.hasManagedProvisioningAuthority(
                false, true, false));
        assertFalse(MainActivity.hasManagedProvisioningAuthority(
                false, false, true));
    }

    @Test
    public void secureRuntimeSendsOnlyTheRawUserTurnToTheManagedGateway() {
        String enrichedPrompt = "任务记忆：已检查电源\n当前问题：控制器仍然报警";

        assertEquals("控制器仍然报警", MainActivity.aiTransportPrompt(
                true, "  控制器仍然报警  ", enrichedPrompt));
        assertEquals(enrichedPrompt, MainActivity.aiTransportPrompt(
                false, "控制器仍然报警", enrichedPrompt));
    }

    @Test
    public void secureRuntimeUsesManagedProjectMemoryAndSkillSurfacesOnly() {
        assertTrue(MainActivity.usesManagedExecutionContextUi(true, "project_memory"));
        assertTrue(MainActivity.usesManagedExecutionContextUi(true, "agent_center"));
        assertTrue(MainActivity.usesManagedExecutionContextUi(true, "skill_center"));
        assertTrue(MainActivity.usesManagedExecutionContextUi(true, "knowledge"));
        assertTrue(MainActivity.usesManagedExecutionContextUi(true, "device_brain"));
        assertFalse(MainActivity.usesManagedExecutionContextUi(false, "agent_center"));
        assertFalse(MainActivity.usesLegacyLocalSkillRuntime(true));
        assertTrue(MainActivity.usesLegacyLocalSkillRuntime(false));
    }

    @Test
    public void secureRuntimeMapsLegacyMenuEntriesToPublishedV9Abilities() throws Exception {
        assertEquals("inspection", invokeStringPolicy(
                "resolveManagedAbilityId", true, "equipment_inspection"));
        assertEquals("tasks", invokeStringPolicy(
                "resolveManagedAbilityId", true, "field_records"));
        assertEquals("capabilities", invokeStringPolicy(
                "resolveManagedAbilityId", true, "more_operations"));
        assertEquals("agent_center", invokeStringPolicy(
                "resolveManagedAbilityId", true, "skill_center"));

        assertEquals("equipment_inspection", invokeStringPolicy(
                "resolveManagedAbilityId", false, "equipment_inspection"));
    }

    @Test
    public void secureRuntimeRejectsLegacyLocalSkillStateAndUnpublishedFeatures() throws Exception {
        assertFalse(invokeBooleanPolicy(
                "shouldExecuteLegacySkillAction", true, "set_agent:network_ops:enabled"));
        assertTrue(invokeBooleanPolicy(
                "shouldExecuteLegacySkillAction", false, "set_agent:network_ops:enabled"));
        assertTrue(invokeBooleanPolicy(
                "shouldExecuteLegacySkillAction", true, "managed_skill_enable:network_ops"));

        assertEquals("\u5f53\u524d\u7ec4\u7ec7\u672a\u53d1\u5e03\u201c\u5b89\u5168\u4f5c\u4e1a\u201d\u80fd\u529b",
                invokeStringPolicy("unavailableFeatureStatus", true,
                        "\u5b89\u5168\u4f5c\u4e1a"));
        assertEquals("\u5b89\u5168\u4f5c\u4e1a\u7b79\u5907\u4e2d",
                invokeStringPolicy("unavailableFeatureStatus", false,
                        "\u5b89\u5168\u4f5c\u4e1a"));
    }

    @Test
    public void managedRestoreRequiresTheExactOpenTaskAndProject() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession open = manager.startNew("project-a", "控制器报警");
        manager.pauseActive();
        TaskSession completed = manager.startNew("project-a", "历史故障");
        manager.completeActive();
        manager.pauseActive();

        assertFalse(MainActivity.resumeManagedTask(
                manager, "project-b", open.id()));
        assertFalse(MainActivity.resumeManagedTask(
                manager, "project-a", completed.id()));
        assertTrue(MainActivity.resumeManagedTask(
                manager, "project-a", open.id()));
        assertEquals(open.id(), manager.active().id());
    }

    private static String invokeStringPolicy(
            String methodName,
            boolean secureRuntime,
            String value
    ) throws Exception {
        Method method;
        try {
            method = MainActivity.class.getDeclaredMethod(
                    methodName, boolean.class, String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing V9 route policy: " + methodName, missing);
        }
        method.setAccessible(true);
        return (String) method.invoke(null, secureRuntime, value);
    }

    private static boolean invokeBooleanPolicy(
            String methodName,
            boolean secureRuntime,
            String value
    ) throws Exception {
        Method method;
        try {
            method = MainActivity.class.getDeclaredMethod(
                    methodName, boolean.class, String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing V9 route policy: " + methodName, missing);
        }
        method.setAccessible(true);
        return (Boolean) method.invoke(null, secureRuntime, value);
    }

    @Test
    public void secureProjectNavigationPausesCurrentTaskWithoutRestoringHistory()
            throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession historical = manager.startNew("project-history", "历史任务");
        manager.pauseActive();
        TaskSession current = manager.startNew("project-current", "当前任务");

        boolean resumed = invokeLocalProjectNavigationRestore(
                true, manager, historical.projectId());

        assertFalse(resumed);
        assertNull(manager.active());
        assertEquals(TaskSession.Status.PAUSED, current.status());
        assertEquals(TaskSession.Status.PAUSED, historical.status());
    }

    @Test
    public void legacyProjectNavigationCanStillRestorePausedLocalHistory()
            throws Exception {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession historical = manager.startNew("project-history", "历史任务");
        manager.pauseActive();
        manager.startNew("project-current", "当前任务");

        boolean resumed = invokeLocalProjectNavigationRestore(
                false, manager, historical.projectId());

        assertTrue(resumed);
        assertEquals(historical.id(), manager.active().id());
    }

    @Test
    public void managedNewTaskStaysInTheSelectedProjectWithoutReusingHistory() {
        assertFalse(MainActivity.shouldForkProjectForNewTask(
                "project-a", "project-a", true, true));
        assertTrue(MainActivity.shouldForkProjectForNewTask(
                "", "project-a", true, false));
        assertTrue(MainActivity.shouldForkProjectForNewTask(
                "project-b", "project-a", false, true));
        assertFalse(MainActivity.shouldForkProjectForNewTask(
                "", "project-a", false, false));
    }

    @Test
    public void managedTaskCompletesLocallyOnlyAfterTheServerSuccessCommit() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession session = manager.startNew("project-a", "控制器报警");

        assertTrue(MainActivity.completeManagedTaskAfterServerSuccess(
                manager, "project-a", session.id()));
        assertNull(manager.active());
        assertEquals(TaskSession.Status.COMPLETED, manager.find(session.id()).status());
        assertEquals(com.codex.air3nativecamera.task.MaintenanceTask.Phase.COMPLETED,
                manager.find(session.id()).maintenanceTask().phase());
    }

    @Test
    public void staleServerSuccessCannotCompleteADifferentActiveTask() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession session = manager.startNew("project-a", "控制器报警");

        assertFalse(MainActivity.completeManagedTaskAfterServerSuccess(
                manager, "project-a", "task-other"));
        assertEquals(session.id(), manager.active().id());
        assertEquals(TaskSession.Status.ACTIVE, session.status());
    }

    @Test
    public void serverSuccessCompletesTheExactPausedTaskWithoutReplacingTheNewActiveTask() {
        TaskSessionManager manager = new TaskSessionManager();
        TaskSession submitted = manager.startNew("project-a", "控制器报警");
        manager.pauseActive();
        TaskSession current = manager.startNew("project-b", "服务器无法启动");

        assertTrue(MainActivity.completeManagedTaskAfterServerSuccess(
                manager, "project-a", submitted.id()));
        assertEquals(current.id(), manager.active().id());
        assertEquals(TaskSession.Status.ACTIVE, current.status());
        assertEquals(TaskSession.Status.COMPLETED, submitted.status());
        assertEquals(com.codex.air3nativecamera.task.MaintenanceTask.Phase.COMPLETED,
                submitted.maintenanceTask().phase());
    }

    private static boolean invokeLocalProjectNavigationRestore(
            boolean secureRuntime,
            TaskSessionManager manager,
            String projectId
    ) throws Exception {
        final Method method;
        try {
            method = MainActivity.class.getDeclaredMethod(
                    "resumeTaskFromLocalProjectNavigation",
                    boolean.class,
                    TaskSessionManager.class,
                    String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(
                    "local project navigation restore policy is missing", missing);
        }
        method.setAccessible(true);
        return (Boolean) method.invoke(null, secureRuntime, manager, projectId);
    }
}
