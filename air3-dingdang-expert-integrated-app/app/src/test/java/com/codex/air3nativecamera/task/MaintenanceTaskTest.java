package com.codex.air3nativecamera.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONObject;

public final class MaintenanceTaskTest {

    @Test
    public void countsPersistedAiTurnsIndependentlyFromTheTransientChatSurface() {
        MaintenanceTask task = MaintenanceTask.start("交换机告警灯闪烁");

        task.addTurn("现场人员", "已经补拍上联端口");
        assertEquals(0, task.aiTurnCount());

        task.addTurn("AI", "请确认 ALM 指示灯状态");
        task.addTurn("现场人员", "ALM 未亮");
        task.addTurn("AI", "链路状态正常，继续检查端口丢包");

        assertEquals(2, task.aiTurnCount());
    }
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
    public void followUpDiagnosisDoesNotResetActiveRepairGuidance() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.replaceRepairSteps(new String[]{"检查电源接口", "观察状态灯", "拍摄模块近景"});
        task.advanceRepairStep();

        task.setDiagnosis("接口已确认", "请继续观察电源状态灯。", 88);

        assertEquals(MaintenanceTask.Phase.GUIDANCE, task.phase());
        assertEquals(2, task.currentRepairStepNumber());
        assertEquals("观察状态灯", task.currentRepairStep());
    }

    @Test
    public void followUpDiagnosisDoesNotReopenACompletedRepairTask() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.replaceRepairSteps(new String[]{"检查电源接口"});
        task.complete();

        task.setDiagnosis("复核完成", "设备已恢复正常。", 96);

        assertEquals(MaintenanceTask.Phase.COMPLETED, task.phase());
    }

    @Test
    public void refreshedRepairStepsKeepTheCurrentGuidancePosition() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.replaceRepairSteps(new String[]{"检查电源接口", "观察状态灯", "拍摄模块近景"});
        task.advanceRepairStep();

        task.replaceRepairSteps(new String[]{"确认电源输入", "观察新的状态灯", "拍摄接口近景"});

        assertEquals(MaintenanceTask.Phase.GUIDANCE, task.phase());
        assertEquals(2, task.currentRepairStepNumber());
        assertEquals("观察新的状态灯", task.currentRepairStep());
    }

    @Test
    public void refreshedRepairStepsDoNotReopenACompletedTask() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.replaceRepairSteps(new String[]{"检查电源接口"});
        task.complete();

        task.replaceRepairSteps(new String[]{"复核设备状态", "记录维修结果"});

        assertEquals(MaintenanceTask.Phase.COMPLETED, task.phase());
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

    @Test
    public void shortNumberedLinesConsumeEnoughDisplayBudgetToAvoidClippingThePager() {
        MaintenanceTask task = MaintenanceTask.start("传感器没有输出");
        String reply = "当前判断：信号链路异常。\n"
                + "1. 检查信号端电压。\n"
                + "2. 检查端子是否松动。\n"
                + "3. 检查屏蔽层接地。\n"
                + "4. 检查信号线通断。\n"
                + "5. 更换传感器复测。";
        task.addTurn("AI", reply);

        int pageSize = 112;
        assertTrue(task.conversationPageCount(pageSize) > 1);
        for (int page = 0; page < task.conversationPageCount(pageSize); page++) {
            assertTrue(task.conversationPage(page, pageSize).split("\\n", -1).length <= 4);
        }
    }

    @Test
    public void consecutiveBlankLinesNeverCreateAnEmptyHudPage() {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        StringBuilder reply = new StringBuilder();
        for (int index = 0; index < 88; index++) reply.append('甲');
        reply.append("\n\n");
        for (int index = 0; index < 80; index++) reply.append('乙');
        task.addTurn("AI", reply.toString());

        int pageSize = 112;
        assertEquals(2, task.conversationPageCount(pageSize));
        for (int page = 0; page < task.conversationPageCount(pageSize); page++) {
            assertFalse(task.conversationPage(page, pageSize).trim().isEmpty());
        }
    }

    @Test
    public void hudPagesNormalizeMarkdownButPersistTheOriginalAiReply() throws Exception {
        MaintenanceTask task = MaintenanceTask.start("传感器没有输出");
        String reply = "## 处理建议\n- **检查** `TEMP_SENSOR_1`\n- 确认 4*20mA 信号";
        task.addTurn("AI", reply);
        task.setDiagnosis("处理建议", reply, 82);

        assertEquals("处理建议\n• 检查 TEMP_SENSOR_1\n• 确认 4*20mA 信号",
                task.conversationPage(0, 300));
        assertEquals(task.conversationPage(0, 300), task.responsePage(0, 300));
        assertTrue(task.toJson().toString().contains("## 处理建议"));
        assertTrue(task.buildPromptMemory(6).contains("**检查**"));
    }

    @Test
    public void repairStepHudTextIsNormalizedWithoutChangingTheStoredStep() {
        MaintenanceTask task = MaintenanceTask.start("传感器没有输出");
        task.replaceRepairSteps(new String[]{"**检查** `TEMP_SENSOR_1`"});

        assertEquals("检查 TEMP_SENSOR_1", task.currentRepairStepForHud());
        assertEquals("**检查** `TEMP_SENSOR_1`", task.currentRepairStep());
        assertTrue(task.toJson().toString().contains("**检查**"));
    }

    @Test
    public void taskRoundTripRetainsEvidenceConversationDiagnosisAndRepairProgress() throws Exception {
        MaintenanceTask task = MaintenanceTask.start("温湿度传感器数据异常");
        task.putFact("设备型号", "Honeywell T7350");
        task.addEvidence("网关全景", "photo://wide-1");
        task.addTurn("现场人员", "平台温度一直显示 0 度");
        task.addTurn("AI", "请先确认网关供电指示灯");
        task.setDiagnosis("采集链路异常", "先排查网关供电，再检查传感器接线。", 86);
        task.replaceRepairSteps(new String[]{"检查网关供电", "检查网络", "检查传感器接线"});
        task.advanceRepairStep();

        MaintenanceTask restored = MaintenanceTask.fromJson(new JSONObject(task.toJson().toString()));

        assertEquals("温湿度传感器数据异常", restored.initialProblem());
        assertEquals("Honeywell T7350", restored.facts().get("设备型号"));
        assertEquals("photo://wide-1", restored.evidenceReferences().get(0));
        assertTrue(restored.buildPromptMemory(6).contains("平台温度一直显示 0 度"));
        assertEquals("采集链路异常", restored.diagnosisTitle());
        assertEquals(2, restored.currentRepairStepNumber());
        assertEquals(MaintenanceTask.Phase.GUIDANCE, restored.phase());
    }

    @Test
    public void restoresLegacyTasksWithRepairStepsAsGuidanceInsteadOfDiagnosis() throws Exception {
        MaintenanceTask task = MaintenanceTask.start("服务器无法启动");
        task.replaceRepairSteps(new String[]{"检查电源接口", "观察状态灯"});
        JSONObject legacy = new JSONObject(task.toJson().toString());
        legacy.put("phase", MaintenanceTask.Phase.DIAGNOSIS.name());

        MaintenanceTask restored = MaintenanceTask.fromJson(legacy);

        assertEquals(MaintenanceTask.Phase.GUIDANCE, restored.phase());
        assertEquals(1, restored.currentRepairStepNumber());
    }
}
