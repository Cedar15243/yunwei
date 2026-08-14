package com.codex.air3nativecamera.governance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProjectInstructionLifecyclePolicyTest {
    @Test
    public void editVoiceOnlyAcceptsACompleteProjectRule() {
        ProjectInstructionLifecyclePolicy.EditDecision accepted =
                ProjectInstructionLifecyclePolicy.classifyEditVoice(
                        "以后在这个项目遇到控制器供电异常先读取报警代码");
        ProjectInstructionLifecyclePolicy.EditDecision rejected =
                ProjectInstructionLifecyclePolicy.classifyEditVoice("检查一下控制器");

        assertEquals(ProjectInstructionLifecyclePolicy.EditIntent.REVISE, accepted.intent());
        assertTrue(accepted.instruction().contains("先读取报警代码"));
        assertEquals(ProjectInstructionLifecyclePolicy.EditIntent.INVALID, rejected.intent());
        assertEquals("", rejected.instruction());
    }

    @Test
    public void editVoiceCanCancelWithoutEnteringTheAiConversation() {
        assertEquals(ProjectInstructionLifecyclePolicy.EditIntent.CANCEL,
                ProjectInstructionLifecyclePolicy.classifyEditVoice("取消").intent());
        assertEquals(ProjectInstructionLifecyclePolicy.EditIntent.CANCEL,
                ProjectInstructionLifecyclePolicy.classifyEditVoice("返回").intent());
        assertEquals(ProjectInstructionLifecyclePolicy.EditIntent.CANCEL_HOME,
                ProjectInstructionLifecyclePolicy.classifyEditVoice("返回首页").intent());
    }

    @Test
    public void lifecycleActionIdentityPreservesTheSelectedProjectAndInstruction() {
        ProjectInstructionLifecyclePolicy.Identity identity =
                ProjectInstructionLifecyclePolicy.parseIdentity("project-a:instruction-b");

        assertEquals("project-a", identity.projectId());
        assertEquals("instruction-b", identity.instructionId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void lifecycleActionIdentityRejectsIncompleteInput() {
        ProjectInstructionLifecyclePolicy.parseIdentity("project-a:");
    }

    @Test
    public void onlyInstructionVersionConflictRequiresAuthorityRefresh() {
        assertTrue(ProjectInstructionLifecyclePolicy.isVersionConflict(
                "project_instruction_version_conflict"));
        assertFalse(ProjectInstructionLifecyclePolicy.isVersionConflict(
                "project_memory_revision_conflict"));
        assertFalse(ProjectInstructionLifecyclePolicy.isVersionConflict("request_failed"));
    }
}
