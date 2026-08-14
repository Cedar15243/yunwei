package com.codex.air3nativecamera.governance;

import static org.junit.Assert.assertEquals;

import com.codex.air3nativecamera.task.MaintenanceTask;

import org.junit.Test;

public final class ProjectGovernancePolicyTest {
    @Test
    public void classifiesExplicitTaskEndAndClosePhrases() {
        assertEquals(ProjectGovernancePolicy.Intent.END_TASK_COMPLETED,
                ProjectGovernancePolicy.classify("结束当前任务", false).intent());
        assertEquals(ProjectGovernancePolicy.Intent.END_TASK_CLOSED,
                ProjectGovernancePolicy.classify("关闭当前任务并返回首页", false).intent());
    }

    @Test
    public void turnsProjectSpecificFutureRulesIntoInstructionDrafts() {
        ProjectGovernancePolicy.Decision decision = ProjectGovernancePolicy.classify(
                "以后在这个项目遇到控制器报警先记录故障码", false);

        assertEquals(ProjectGovernancePolicy.Intent.PROJECT_INSTRUCTION, decision.intent());
        assertEquals("以后在这个项目遇到控制器报警先记录故障码", decision.instruction());
    }

    @Test
    public void ordinaryQuestionsNeverBecomeProjectInstructions() {
        assertEquals(ProjectGovernancePolicy.Intent.NONE,
                ProjectGovernancePolicy.classify("以后这个功能会怎么升级", false).intent());
        assertEquals(ProjectGovernancePolicy.Intent.NONE,
                ProjectGovernancePolicy.classify("控制器报警是什么原因", false).intent());
    }

    @Test
    public void pendingConfirmationAcceptsOnlyExplicitConfirmationOrCancellation() {
        assertEquals(ProjectGovernancePolicy.Intent.CONFIRM_PENDING,
                ProjectGovernancePolicy.classify("确认执行", true).intent());
        assertEquals(ProjectGovernancePolicy.Intent.CANCEL_PENDING,
                ProjectGovernancePolicy.classify("取消", true).intent());
        assertEquals(ProjectGovernancePolicy.Intent.CANCEL_PENDING_HOME,
                ProjectGovernancePolicy.classify("返回首页", true).intent());
        assertEquals(ProjectGovernancePolicy.Intent.NONE,
                ProjectGovernancePolicy.classify("继续分析这个问题", true).intent());
    }

    @Test
    public void taskEndSummaryIsReviewableAndBounded() {
        MaintenanceTask task = MaintenanceTask.start("控制器报警并且设备无法启动");
        task.putFact("设备型号", "HF-100");
        task.setDiagnosis("疑似控制器供电异常", "需要复核控制器供电与报警代码。", 82);
        task.replaceRepairSteps(new String[]{"记录报警代码", "检查控制器供电"});

        String summary = ProjectGovernancePolicy.taskEndSummary(task);

        assertEquals(true, summary.contains("控制器报警并且设备无法启动"));
        assertEquals(true, summary.contains("疑似控制器供电异常"));
        assertEquals(true, summary.contains("1/2"));
        assertEquals(true, summary.length() <= 8000);
    }
}
