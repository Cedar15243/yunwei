package com.codex.air3nativecamera.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MaintenanceTaskTest {
    @Test
    public void taskMemoryRetainsFactsEvidenceAndOnlyTheRelevantRecentTurns() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.putFact("设备", "1 号服务器");
        task.addEvidence("全景照片", "field-wide.jpg");
        task.addEvidence("电源模块近景", "power-close.jpg");
        task.addTurn("现场人员", "电源灯不亮");
        task.addTurn("AI", "请检查电源输入");
        task.addTurn("现场人员", "外接电源正常");

        String memory = task.buildPromptMemory(2);

        assertTrue(memory.contains("设备：1 号服务器"));
        assertTrue(memory.contains("全景照片、电源模块近景"));
        assertTrue(memory.contains("外接电源正常"));
        assertTrue(memory.contains("请检查电源输入"));
    }

    @Test
    public void diagnosisResponsePaginatesWithoutDroppingTheOriginalContent() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.setDiagnosis("电源模块异常", "第一段。第二段。第三段。第四段。第五段。", 92);

        assertEquals(3, task.responsePageCount(8));
        assertEquals("第一段。第二段。", task.responsePage(0, 8));
        assertEquals("第五段。", task.responsePage(2, 8));
    }

    @Test
    public void repairProgressUsesAiProvidedStepsAndSnapshotRestoresIt() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.replaceRepairSteps(new String[]{"检查电源接口", "观察状态灯", "拍摄模块近景", "确认更换结果"});
        task.advanceRepairStep();
        MaintenanceTask.Snapshot snapshot = task.snapshot();
        task.advanceRepairStep();

        task.restore(snapshot);

        assertEquals(2, task.currentRepairStepNumber());
        assertEquals(4, task.repairStepCount());
        assertEquals("观察状态灯", task.currentRepairStep());
    }

    @Test
    public void conversationPagesShowOnlyTheLatestAiRepairReply() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.addEvidence("现场照片 1", "field-wide.jpg");
        task.addTurn("现场人员", "服务器无法启动，电源灯不亮");
        task.addTurn("AI", "请确认电源输入并补拍电源模块近景。");

        String page = task.conversationPage(0, 180);

        assertEquals(0, task.conversationPageIndex(180));
        assertTrue(page.contains("请确认电源输入"));
        assertFalse(page.contains("现场照片 1"));
        assertFalse(page.contains("现场人员：服务器无法启动"));
    }

    @Test
    public void longConversationReplyAdvancesExactlyOnePageWithoutDroppingText() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        String reply = "问题：服务器电源指示灯不亮。"
                + "第一步：确认机柜电源输入是否正常。"
                + "第二步：检查电源线和接口是否松动。"
                + "第三步：拍摄电源模块状态灯近景。"
                + "第四步：确认备用电源模块型号。"
                + "第五步：完成更换后重新上电验证。";
        task.addTurn("AI", reply);

        int pageSize = 36;
        int pageCount = task.conversationPageCount(pageSize);
        StringBuilder rebuilt = new StringBuilder();
        for (int index = 0; index < pageCount; index++) {
            String page = task.conversationPage(index, pageSize);
            assertTrue(page.length() <= pageSize);
            rebuilt.append(page);
        }

        assertTrue(pageCount > 2);
        assertEquals(reply, rebuilt.toString());
        assertEquals(0, task.conversationPageIndex(pageSize));
        assertTrue(task.nextConversationPage(pageSize));
        assertEquals(1, task.conversationPageIndex(pageSize));
        assertTrue(task.nextConversationPage(pageSize));
        assertEquals(2, task.conversationPageIndex(pageSize));
        while (task.nextConversationPage(pageSize)) {
            // Move to the final page one page at a time.
        }
        assertEquals(pageCount - 1, task.conversationPageIndex(pageSize));
        assertFalse(task.nextConversationPage(pageSize));
        assertEquals(pageCount - 1, task.conversationPageIndex(pageSize));
    }
}
