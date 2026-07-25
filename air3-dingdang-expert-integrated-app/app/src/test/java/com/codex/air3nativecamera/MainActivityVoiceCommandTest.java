package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.voice.LegacyVoiceCommandRouter;
import com.codex.air3nativecamera.voice.VoiceCommandRouter;
import com.codex.air3nativecamera.voice.WakeListeningSchedulePolicy;
import org.junit.Test;

public final class MainActivityVoiceCommandTest {
    private final LegacyVoiceCommandRouter router = new LegacyVoiceCommandRouter();

    @Test
    public void enablesForegroundVoiceWorkflowForV7PreviewPackagesOnly() {
        assertTrue(MainActivity.isVoicePreviewPackage(
                "com.codex.air3nativecamera.dingdangexpert.follow.preview.v71"));
        assertTrue(MainActivity.isVoicePreviewPackage(
                "com.codex.air3nativecamera.dingdangexpert.follow.preview.voice.hudweb"));
        assertFalse(MainActivity.isVoicePreviewPackage(
                "com.codex.air3nativecamera.dingdangexpert.follow.preview"));
        assertFalse(MainActivity.isVoicePreviewPackage(null));
    }

    @Test
    public void classifiesEveryLegacyChatControlCommand() {
        assertCommand("打开相机", LegacyVoiceCommandRouter.Command.OPEN_CAMERA);
        assertCommand("打开摄像头", LegacyVoiceCommandRouter.Command.OPEN_CAMERA);
        assertCommand("拍照片", LegacyVoiceCommandRouter.Command.TAKE_PHOTO);
        assertCommand("照一下", LegacyVoiceCommandRouter.Command.TAKE_PHOTO);
        assertCommand("重拍", LegacyVoiceCommandRouter.Command.RETAKE_PHOTO);
        assertCommand("开始分析", LegacyVoiceCommandRouter.Command.SEND);
        assertCommand("帮我分析", LegacyVoiceCommandRouter.Command.SEND);
        assertCommand("返回聊天", LegacyVoiceCommandRouter.Command.BACK_TO_CHAT);
        assertCommand("继续说", LegacyVoiceCommandRouter.Command.START_VOICE);
        assertCommand("新建项目", LegacyVoiceCommandRouter.Command.NEW_PROJECT);
        assertCommand("下一条记录", LegacyVoiceCommandRouter.Command.NEXT_PROJECT);
        assertCommand("上一条记录", LegacyVoiceCommandRouter.Command.PREVIOUS_PROJECT);
        assertCommand("最新记录", LegacyVoiceCommandRouter.Command.LATEST_PROJECT);
        assertCommand("查看记录", LegacyVoiceCommandRouter.Command.SHOW_RECORDS);
    }

    @Test
    public void keepsLongProblemDescriptionsOutOfLegacyControlRouting() {
        assertCommand("平台页面报错五零二，需要排查原因", LegacyVoiceCommandRouter.Command.NONE);
    }

    @Test
    public void keepsClarificationRepliesInTheConversationWorkspace() {
        assertFalse(MainActivity.isActionableDiagnosisResponse(
                "请明确具体问题或故障现象，并补充现场照片后再继续分析。"));
        assertFalse(MainActivity.isActionableDiagnosisResponse(
                "初步判断：当前证据不足，请补充设备铭牌和电源区域近景。"));
    }

    @Test
    public void promotesConfirmedDiagnosisToTheGuidanceWorkspace() {
        assertTrue(MainActivity.isActionableDiagnosisResponse(
                "初步判断：服务器电源模块异常\n异常概率：92%\n维修步骤：\n1. 检查电源连接"));
    }

    @Test
    public void doesNotRestoreAStoredConversationOverTheStandbyHud() {
        assertFalse(MainActivity.shouldShowHudTaskWorkspace(false, 12, 8));
        assertFalse(MainActivity.shouldShowHudTaskWorkspace(true, -1, 8));
        assertFalse(MainActivity.shouldShowHudTaskWorkspace(true, 7, 8));
        assertTrue(MainActivity.shouldShowHudTaskWorkspace(true, 8, 8));
        assertTrue(MainActivity.shouldShowHudTaskWorkspace(true, 12, 8));
    }

    @Test
    public void keepsEveryCompletedTaskReplyInsideTheExistingConversation() {
        assertFalse(MainActivity.shouldRemainInTaskConversation(0, true));
        assertTrue(MainActivity.shouldRemainInTaskConversation(1, true));
        assertTrue(MainActivity.shouldRemainInTaskConversation(1, false));
        assertTrue(MainActivity.shouldRemainInTaskConversation(2, false));
    }

    @Test
    public void keepsTaskVoiceAndAiActivityInsideTheEstablishedWorkspace() {
        assertFalse(MainActivity.shouldKeepEstablishedTaskSurface(false, 1, false));
        assertFalse(MainActivity.shouldKeepEstablishedTaskSurface(true, 0, false));
        assertTrue(MainActivity.shouldKeepEstablishedTaskSurface(true, 1, false));
        assertTrue(MainActivity.shouldKeepEstablishedTaskSurface(true, 1, true));
        assertTrue(MainActivity.shouldKeepEstablishedTaskSurface(true, 0, true));
    }

    @Test
    public void entersRepairGuidanceOnlyAfterAnAiReplyProvidesRepairSteps() {
        assertFalse(MainActivity.shouldStartGuidance(false, 1, 3));
        assertFalse(MainActivity.shouldStartGuidance(true, 0, 3));
        assertFalse(MainActivity.shouldStartGuidance(true, 1, 0));
        assertTrue(MainActivity.shouldStartGuidance(true, 1, 3));
    }

    @Test
    public void describesCapturedEvidenceAsADraftUntilTheOperatorSendsIt() {
        assertEquals("未添加现场照片", MainActivity.photoEvidenceStatus(false, false));
        assertEquals("照片待发送", MainActivity.photoEvidenceStatus(true, true));
        assertEquals("照片已加入本轮", MainActivity.photoEvidenceStatus(true, false));
    }

    @Test
    public void photoDraftPromptExplainsWakeDescriptionAndAutomaticImageOnlyFallback() {
        String prompt = MainActivity.photoDescriptionPrompt();

        assertTrue(prompt.contains("小叮当"));
        assertTrue(prompt.contains("补充描述"));
        assertTrue(prompt.contains("30 秒"));
        assertTrue(prompt.contains("仅发送图片"));
    }

    @Test
    public void photoDraftTimeoutNeverRacesActiveOrPartialSpeech() {
        assertTrue(MainActivity.shouldDeferPhotoDraftAutoSubmit(true, false, false, ""));
        assertTrue(MainActivity.shouldDeferPhotoDraftAutoSubmit(false, true, false, ""));
        assertTrue(MainActivity.shouldDeferPhotoDraftAutoSubmit(false, false, true, ""));
        assertTrue(MainActivity.shouldDeferPhotoDraftAutoSubmit(false, false, false, "电源灯不亮"));
        assertFalse(MainActivity.shouldDeferPhotoDraftAutoSubmit(false, false, false, "  "));
    }

    @Test
    public void requestsCameraPermissionOnlyAfterTheUserEntersCameraMode() {
        assertFalse(MainActivity.shouldRequestCameraPermission(false));
        assertTrue(MainActivity.shouldRequestCameraPermission(true));
    }

    @Test
    public void reArmsWakeAfterHandledTaskCommandButNotDuringAsrOrAi() {
        assertTrue(MainActivity.shouldRearmWakeAfterHandledCommand(false, false, false));
        assertFalse(MainActivity.shouldRearmWakeAfterHandledCommand(true, false, false));
        assertFalse(MainActivity.shouldRearmWakeAfterHandledCommand(false, true, false));
        assertFalse(MainActivity.shouldRearmWakeAfterHandledCommand(false, false, true));
    }

    @Test
    public void usesAShortStableWindowForVoiceControlsOnly() {
        assertEquals(650L, MainActivity.voiceTranscriptStableStopDelayMs(true));
        assertEquals(1800L, MainActivity.voiceTranscriptStableStopDelayMs(false));
    }

    @Test
    public void waitsForOfflineWakeToReleaseTheMicrophoneBeforeStartingAsr() {
        assertTrue(MainActivity.shouldWaitForWakeAudioRelease(true, 0));
        assertTrue(MainActivity.shouldWaitForWakeAudioRelease(true, 19));
        assertFalse(MainActivity.shouldWaitForWakeAudioRelease(false, 0));
        assertFalse(MainActivity.shouldWaitForWakeAudioRelease(true, 20));
    }

    @Test
    public void rejectsAmbientSpeechAfterAnOfflineWakeFalsePositiveAtStandby() {
        assertTrue(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.NONE));
        assertTrue(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.NEXT_PAGE));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                false, true, VoiceCommandRouter.Command.NONE));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, false, VoiceCommandRouter.Command.NONE));
    }

    @Test
    public void acceptsOnlyExplicitSafeEntryCommandsAfterOfflineWakeAtStandby() {
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.DIAGNOSIS));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.PHOTO));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.EXPERT));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.CAPABILITY_CENTER));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.HELP));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.GLASSES_TUTORIAL));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.VIDEO_START));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.INSPECTION));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.TASK_CENTER));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.KNOWLEDGE));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.DEVICE));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.AGENT_CENTER));
    }

    @Test
    public void keepsAnActiveOfflineWakeSessionWhenStandbyIsScheduledAgain() {
        assertEquals(WakeListeningSchedulePolicy.Action.KEEP_RUNNING,
                WakeListeningSchedulePolicy.decide(true, true, true));
        assertEquals(WakeListeningSchedulePolicy.Action.START,
                WakeListeningSchedulePolicy.decide(true, true, false));
        assertEquals(WakeListeningSchedulePolicy.Action.STOP,
                WakeListeningSchedulePolicy.decide(true, false, true));
        assertEquals(WakeListeningSchedulePolicy.Action.STOP,
                WakeListeningSchedulePolicy.decide(false, true, true));
    }

    @Test
    public void treatsAnEmptyAiStreamAsRecoverableRatherThanACompletedReply() {
        assertTrue(MainActivity.isEmptyAiResponse(""));
        assertTrue(MainActivity.isEmptyAiResponse("   "));
        assertFalse(MainActivity.isEmptyAiResponse("请检查电源输入。"));
    }

    @Test
    public void removesTheWakePhraseBeforeTranscriptBecomesTaskEvidence() {
        assertEquals("服务器无法启动", MainActivity.sanitizeTaskNarration("小叮当，服务器无法启动"));
        assertEquals("服务器无法启动", MainActivity.sanitizeTaskNarration("小丁 服务器无法启动"));
        assertEquals("", MainActivity.sanitizeTaskNarration("小叮当"));
    }

    @Test
    public void keepsVoiceRecoveryPromptsOutOfTaskEvidence() {
        assertEquals("", MainActivity.sanitizeTaskNarration("没有听清，请再说一次"));
        assertEquals("", MainActivity.sanitizeTaskNarration("没有听清，请重新提问"));
        assertEquals("", MainActivity.sanitizeTaskNarration("说话时间太短，请再说一次"));
        assertEquals("", MainActivity.sanitizeTaskNarration("语音服务未连接，请检查后端或网络"));
        assertEquals("设备没有响应", MainActivity.sanitizeTaskNarration("设备没有响应"));
    }

    @Test
    public void extractsStructuredAiFactsWithoutTreatingUnknownValuesAsConfirmed() {
        assertEquals("电源输入与状态灯均异常", MainActivity.extractStructuredSection(
                "【判断依据】\n电源输入与状态灯均异常\n【安全风险】待确认", "判断依据"));
        assertEquals("待确认", MainActivity.extractStructuredSection(
                "【安全风险】待确认", "安全风险"));
    }

    @Test
    public void asksAiToAnswerTheCurrentQuestionWithoutAForcedReportTemplate() {
        String instruction = MainActivity.buildCurrentQuestionInstruction("服务器电源灯不亮");

        assertTrue(instruction.contains("当前问题：服务器电源灯不亮"));
        assertTrue(instruction.contains("直接、简洁回答当前问题"));
        assertFalse(instruction.contains("固定使用"));
        assertFalse(instruction.contains("问题：一句话"));
        assertFalse(instruction.contains("【诊断结论】"));
        assertFalse(instruction.contains("【置信度】"));
        assertFalse(instruction.contains("【判断依据】"));
        assertFalse(instruction.contains("【安全风险】"));
        assertFalse(instruction.contains("【需补拍】"));
        assertFalse(instruction.contains("【专家协同建议】"));
    }

    private void assertCommand(String phrase, LegacyVoiceCommandRouter.Command expected) {
        assertEquals(phrase, expected, router.route(phrase));
    }
}
