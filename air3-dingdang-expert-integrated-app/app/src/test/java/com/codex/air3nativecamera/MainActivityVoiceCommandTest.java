package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.features.inspection.InspectionCatalog;
import com.codex.air3nativecamera.features.inspection.InspectionRun;
import com.codex.air3nativecamera.voice.LegacyVoiceCommandRouter;
import com.codex.air3nativecamera.voice.VoiceCommandRouter;
import com.codex.air3nativecamera.voice.VoiceEventStateMachine;
import com.codex.air3nativecamera.voice.WakeListeningSchedulePolicy;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.GregorianCalendar;

public final class MainActivityVoiceCommandTest {
    @Test
    public void hamburgerMenuOpensOnceAndClosesWhenPressedAgain() throws Exception {
        Method policy = MainActivity.class.getDeclaredMethod(
                "shouldOpenCapabilityCenterOnMenuPress", boolean.class);
        policy.setAccessible(true);

        assertTrue((Boolean) policy.invoke(null, false));
        assertFalse((Boolean) policy.invoke(null, true));
    }

    @Test
    public void sceneWorkflowOnlyEvaluatesANewPhotoFromTheCurrentTurn() {
        assertTrue(MainActivity.shouldEvaluateSceneSkill(true, true));
        assertFalse(MainActivity.shouldEvaluateSceneSkill(true, false));
        assertFalse(MainActivity.shouldEvaluateSceneSkill(false, true));
    }

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
    public void unprovisionedSecureRuntimeNeverClaimsVoiceStandby() {
        assertEquals("设备待激活",
                MainActivity.runtimeStandbyLabel(true, false, true, false));
        assertEquals("设备未激活 · 请进入设置完成设备激活",
                MainActivity.runtimeStandbyNotice(true, false, true, false));
        assertFalse(MainActivity.shouldStartManagedOfflineWake(true, false));
    }

    @Test
    public void unprovisionedSecureRuntimeCannotStartAnyVoiceCapture() {
        assertFalse(MainActivity.canStartManagedVoiceCapture(true, false));
        assertTrue(MainActivity.canStartManagedVoiceCapture(true, true));
        assertTrue(MainActivity.canStartManagedVoiceCapture(false, false));
    }

    @Test
    public void secureRuntimeRequiresWakeAuthorizationBeforeClaimingXiaoDingdangReady() {
        assertEquals("语音未授权",
                MainActivity.runtimeStandbyLabel(true, true, true, false));
        assertEquals("小叮当唤醒未授权 · 请联系管理员完成受管配置",
                MainActivity.runtimeStandbyNotice(true, true, true, false));
        assertFalse(MainActivity.shouldStartManagedOfflineWake(true, false));

        assertEquals("语音待命",
                MainActivity.runtimeStandbyLabel(true, true, true, true));
        assertEquals("", MainActivity.runtimeStandbyNotice(true, true, true, true));
        assertTrue(MainActivity.shouldStartManagedOfflineWake(true, true));
    }

    @Test
    public void settingsVoiceModeReflectsRuntimeProvisioningInsteadOfBuildFlags() {
        assertEquals("服务不可用 · 设备待激活", MainActivity.runtimeVoiceModeLabel(
                true, false, "unavailable", true, false));
        assertEquals("小叮当未授权", MainActivity.runtimeVoiceModeLabel(
                true, true, "unavailable", true, false));
        assertEquals("声纹监听已开启", MainActivity.runtimeVoiceModeLabel(
                true, true, "voiceprint", true, false));
        assertEquals("监听关闭", MainActivity.runtimeVoiceModeLabel(
                true, true, "passive", true, false));
        assertEquals("小叮当唤醒", MainActivity.runtimeVoiceModeLabel(
                true, true, "wake", true, true));
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
        assertCommand("打开设置", LegacyVoiceCommandRouter.Command.OPEN_SETTINGS);
        assertCommand("声纹设置", LegacyVoiceCommandRouter.Command.OPEN_SETTINGS);
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
    public void persistedAiTurnsKeepFollowUpRepliesOutOfTheFirstTurnAnalysisSurface() {
        assertTrue(MainActivity.shouldKeepEstablishedTaskSurface(true, 0, 1, false));
        assertTrue(MainActivity.shouldKeepEstablishedTaskSurface(true, 0, 2, false));
        assertFalse(MainActivity.shouldKeepEstablishedTaskSurface(true, 0, 0, false));
        assertFalse(MainActivity.shouldKeepEstablishedTaskSurface(false, 0, 2, false));
    }

    @Test
    public void resetsTaskMessageBoundaryWhenHomeCaptureCreatesANewProject() {
        assertEquals(1, MainActivity.taskMessageStartIndexAfterProjectReset(true, 1));
        assertEquals(0, MainActivity.taskMessageStartIndexAfterProjectReset(true, 0));
    }

    @Test
    public void projectSwitchNeverReusesThePreviousProjectsMessageBoundary() {
        assertEquals(1, MainActivity.taskMessageStartIndexAfterProjectSwitch(true, 1, 12));
        assertEquals(0, MainActivity.taskMessageStartIndexAfterProjectSwitch(true, -1, 12));
        assertEquals(3, MainActivity.taskMessageStartIndexAfterProjectSwitch(false, 1, 3));
    }

    @Test
    public void taskWorkspaceIncludesTheLiveTranscriptThatTriggeredItsActivation() {
        assertEquals(3, MainActivity.taskMessageStartIndexOnActivation(4, 3));
        assertEquals(4, MainActivity.taskMessageStartIndexOnActivation(4, -1));
        assertEquals(4, MainActivity.taskMessageStartIndexOnActivation(4, 4));
    }

    @Test
    public void operationDetailsUseFourItemSingleScreenPages() {
        assertEquals(1, MainActivity.operationPageCount(0, 4));
        assertEquals(1, MainActivity.operationPageCount(4, 4));
        assertEquals(2, MainActivity.operationPageCount(5, 4));
        assertEquals(0, MainActivity.operationPageStart(0, 4, 9));
        assertEquals(4, MainActivity.operationPageStart(1, 4, 9));
        assertEquals(8, MainActivity.operationPageStart(20, 4, 9));
    }

    @Test
    public void stalledAiStreamHasABoundedRecoverableTerminalState() {
        assertFalse(MainActivity.shouldFailStalledAiStream(false, true));
        assertFalse(MainActivity.shouldFailStalledAiStream(true, false));
        assertTrue(MainActivity.shouldFailStalledAiStream(true, true));
    }

    @Test
    public void failedPendingImageUploadLeavesAnalysisWithAnActionableStage() {
        assertFalse(MainActivity.shouldFailPendingImageUpload(false, true));
        assertFalse(MainActivity.shouldFailPendingImageUpload(true, false));
        assertTrue(MainActivity.shouldFailPendingImageUpload(true, true));
        assertEquals("后端未授权",
                MainActivity.aiFailureNetworkState("managed_backend_credential_missing"));
        assertEquals("网络超时",
                MainActivity.aiFailureNetworkState("ai_stream_terminal_timeout"));
        assertEquals("任务同步未完成",
                MainActivity.aiFailureNetworkState("task_registration_pending"));
        assertEquals("任务上下文同步阶段",
                MainActivity.aiFailureStage("task_registration_pending", "AI 对话阶段"));

        String message = MainActivity.recoverableAiFailureMessage(
                "照片上传阶段", "managed_backend_credential_missing", "");
        assertTrue(message.contains("阶段：照片上传阶段"));
        assertTrue(message.contains("状态：后端未授权"));
        assertTrue(message.contains("照片和描述已保留"));
        assertTrue(message.contains("重试"));
    }

    @Test
    public void followUpAiReplyLeavesTheOldGuidanceStepSoTheAnswerIsVisible() {
        assertTrue(MainActivity.shouldLeaveGuidanceAfterAiReply(true, "请补拍接线端子近景"));
        assertFalse(MainActivity.shouldLeaveGuidanceAfterAiReply(false, "请补拍接线端子近景"));
        assertFalse(MainActivity.shouldLeaveGuidanceAfterAiReply(true, "  "));
    }

    @Test
    public void suppressesOfflineWakeDuringTheMediaReleaseCooldown() {
        assertFalse(MainActivity.shouldAcceptOfflineWakeDetection(1_000L, 2_000L));
        assertTrue(MainActivity.shouldAcceptOfflineWakeDetection(2_000L, 2_000L));
        assertTrue(MainActivity.shouldAcceptOfflineWakeDetection(3_000L, 2_000L));
    }

    @Test
    public void entersRepairGuidanceOnlyAfterAnAiReplyProvidesRepairSteps() {
        assertFalse(MainActivity.shouldStartGuidance(false, 1, 3));
        assertFalse(MainActivity.shouldStartGuidance(true, 0, 3));
        assertFalse(MainActivity.shouldStartGuidance(true, 1, 0));
        assertTrue(MainActivity.shouldStartGuidance(true, 1, 3));
    }

    @Test
    public void repairGuidanceRemainsInTheFullScreenConversationWorkspace() {
        assertEquals("conversation", MainActivity.guidanceHudState());
    }

    @Test
    public void v9RequiresConfirmationBeforeFinishingGuidance() {
        assertTrue(MainActivity.shouldRequestManagedTaskEnd(
                true, VoiceCommandRouter.Command.FINISH, 1, 3));
        assertTrue(MainActivity.shouldRequestManagedTaskEnd(
                true, VoiceCommandRouter.Command.NEXT, 3, 3));
        assertFalse(MainActivity.shouldRequestManagedTaskEnd(
                true, VoiceCommandRouter.Command.NEXT, 2, 3));
    }

    @Test
    public void legacyRuntimeKeepsItsExistingGuidanceCompletionBehavior() {
        assertFalse(MainActivity.shouldRequestManagedTaskEnd(
                false, VoiceCommandRouter.Command.FINISH, 1, 3));
        assertFalse(MainActivity.shouldRequestManagedTaskEnd(
                false, VoiceCommandRouter.Command.NEXT, 3, 3));
    }

    @Test
    public void navigationDropsOnlyAnUnsubmittedGovernanceDraft() {
        assertTrue(MainActivity.shouldDiscardPendingGovernanceOnNavigation(true, false));
        assertFalse(MainActivity.shouldDiscardPendingGovernanceOnNavigation(true, true));
        assertFalse(MainActivity.shouldDiscardPendingGovernanceOnNavigation(false, false));
    }

    @Test
    public void describesCapturedEvidenceAsADraftUntilTheOperatorSendsIt() {
        assertEquals("未添加现场照片", MainActivity.photoEvidenceStatus(false, false));
        assertEquals("照片待发送", MainActivity.photoEvidenceStatus(true, true));
        assertEquals("照片已加入本轮", MainActivity.photoEvidenceStatus(true, false));
    }

    @Test
    public void refreshesHudWhenPhotoCaptureEntersDescriptionWaitState() {
        assertTrue(MainActivity.shouldRefreshPhotoDraftAfterCapture(
                com.codex.air3nativecamera.voice.VoiceEventStateMachine.Signal.START_DESCRIPTION));
        assertFalse(MainActivity.shouldRefreshPhotoDraftAfterCapture(
                com.codex.air3nativecamera.voice.VoiceEventStateMachine.Signal.NONE));
    }

    @Test
    public void preservesPendingPhotoWhenTheFirstMaintenanceTaskCreatesItsProject() {
        assertFalse(MainActivity.shouldClearComposerForProjectTransition(true));
        assertTrue(MainActivity.shouldClearComposerForProjectTransition(false));
    }

    @Test
    public void shortVideoReturnsToTheSurfaceThatStartedIt() {
        assertEquals("operation:inspection",
                MainActivity.sceneVideoReturnTarget(true, true, "inspection", true));
        assertEquals("capabilities",
                MainActivity.sceneVideoReturnTarget(true, false, "", true));
        assertEquals("task",
                MainActivity.sceneVideoReturnTarget(false, false, "", true));
        assertEquals("standby",
                MainActivity.sceneVideoReturnTarget(false, false, "", false));
    }

    @Test
    public void leavingTheCameraFinishesAnyActiveVideoFlowBeforeNavigation() {
        assertTrue(MainActivity.shouldFinishSceneVideoBeforeCameraExit(true, false, false));
        assertTrue(MainActivity.shouldFinishSceneVideoBeforeCameraExit(false, true, false));
        assertTrue(MainActivity.shouldFinishSceneVideoBeforeCameraExit(false, false, true));
        assertFalse(MainActivity.shouldFinishSceneVideoBeforeCameraExit(false, false, false));
    }

    @Test
    public void rejectsCameraCallbacksFromAClosedDeviceOrSupersededSession() {
        assertTrue(MainActivity.isCurrentCameraCallback(4L, 4L, true));
        assertFalse(MainActivity.isCurrentCameraCallback(4L, 5L, true));
        assertFalse(MainActivity.isCurrentCameraCallback(4L, 4L, false));

        assertTrue(MainActivity.isCurrentCameraSession(8L, 8L, true));
        assertFalse(MainActivity.isCurrentCameraSession(8L, 9L, true));
        assertFalse(MainActivity.isCurrentCameraSession(8L, 8L, false));
    }

    @Test
    public void cameraThreadVideoCallbacksAreMarshalledToTheMainThread() {
        assertTrue(MainActivity.shouldPostVideoStopToMainThread(false));
        assertFalse(MainActivity.shouldPostVideoStopToMainThread(true));
    }

    @Test
    public void cameraPreviewCorrectionsPreserveSourceAspectRatio() {
        float[] sixteenNine = MainActivity.previewTransformCorrections(1280, 720, 1920, 1080);
        assertEquals(1f, sixteenNine[0], 0.001f);
        assertEquals(1f, sixteenNine[1], 0.001f);

        float[] fourThree = MainActivity.previewTransformCorrections(1280, 720, 1440, 1080);
        float renderedX = (1280f / 1440f) * fourThree[0];
        float renderedY = (720f / 1080f) * fourThree[1];
        assertEquals(renderedX, renderedY, 0.001f);

        float[] rotated = MainActivity.previewTransformCorrections(1280, 720, 1080, 1920);
        renderedX = (1280f / 1080f) * rotated[0];
        renderedY = (720f / 1920f) * rotated[1];
        assertEquals(renderedX, renderedY, 0.001f);
    }

    @Test
    public void cameraWaitsForAStableTransformedFrameBeforeTheFirstCapture() {
        assertFalse(MainActivity.isCameraFrameStableForCapture(false, 3, 600L));
        assertFalse(MainActivity.isCameraFrameStableForCapture(true, 1, 600L));
        assertFalse(MainActivity.isCameraFrameStableForCapture(true, 3, 200L));
        assertTrue(MainActivity.isCameraFrameStableForCapture(true, 3, 600L));
    }

    @Test
    public void cameraRepairsAFirstJpegThatIgnoredTheRequestedQuarterTurn() {
        assertTrue(MainActivity.shouldApplyRequestedCameraRotation(
                4608, 3456, 4608, 3456, 270));
        assertFalse(MainActivity.shouldApplyRequestedCameraRotation(
                3456, 4608, 4608, 3456, 270));
        assertFalse(MainActivity.shouldApplyRequestedCameraRotation(
                4608, 3456, 4608, 3456, 0));
    }

    @Test
    public void highResolutionCameraJpegIsSampledWithoutDroppingBelowTheUploadTarget() {
        assertEquals(2, MainActivity.cameraDecodeSampleSize(4608, 3456, 1600));
        assertEquals(1, MainActivity.cameraDecodeSampleSize(1920, 1080, 1600));
        assertEquals(1, MainActivity.cameraDecodeSampleSize(1280, 720, 1600));
        assertTrue(MainActivity.isAir3Hardware("INMO", "IMA301"));
        assertFalse(MainActivity.isAir3Hardware("other", "IMA301"));
    }

    @Test
    public void clearingACommandTranscriptCannotReplaceCameraOrExpertSurfacesWithChat() {
        assertTrue(MainActivity.shouldRenderChatForCurrentSurface(true, false, false));
        assertFalse(MainActivity.shouldRenderChatForCurrentSurface(false, false, false));
        assertFalse(MainActivity.shouldRenderChatForCurrentSurface(true, true, false));
        assertFalse(MainActivity.shouldRenderChatForCurrentSurface(true, false, true));
    }

    @Test
    public void expertWaitingSurfaceOnlyYieldsToExplicitExitCommands() {
        assertFalse(MainActivity.shouldKeepExpertSurface(VoiceCommandRouter.Command.HOME));
        assertFalse(MainActivity.shouldKeepExpertSurface(VoiceCommandRouter.Command.BACK));
        assertFalse(MainActivity.shouldKeepExpertSurface(VoiceCommandRouter.Command.CANCEL));
        assertTrue(MainActivity.shouldKeepExpertSurface(VoiceCommandRouter.Command.NEXT_PAGE));
        assertTrue(MainActivity.shouldKeepExpertSurface(VoiceCommandRouter.Command.CAPABILITY_CENTER));
    }

    @Test
    public void incompleteSceneVideoFilesAreDeletedInsteadOfBecomingEvidence() throws Exception {
        File emptyVideo = File.createTempFile("dingdang-empty-video", ".mp4");
        assertTrue(emptyVideo.isFile());

        assertFalse(MainActivity.retainCompletedSceneVideo(emptyVideo));
        assertFalse(emptyVideo.exists());

        File completedVideo = File.createTempFile("dingdang-completed-video", ".mp4");
        try (FileOutputStream output = new FileOutputStream(completedVideo)) {
            output.write(1);
        }
        assertTrue(MainActivity.retainCompletedSceneVideo(completedVideo));
        assertTrue(completedVideo.delete());
    }

    @Test
    public void inspectionPhotoEvidenceIsStoredAsARecoverableLocalFile() throws Exception {
        File directory = Files.createTempDirectory("inspection-evidence").toFile();
        File evidence = MainActivity.writeEvidenceFile(directory, "water/pump-01", new byte[]{1, 2, 3});

        assertTrue(evidence.isFile());
        assertTrue(evidence.getCanonicalPath().startsWith(directory.getCanonicalPath()));
        assertEquals(3, Files.readAllBytes(evidence.toPath()).length);

        assertTrue(evidence.delete());
        assertTrue(directory.delete());
    }

    @Test
    public void startupRemovesOnlyIncompleteSceneVideoArtifacts() throws Exception {
        File evidenceDirectory = Files.createTempDirectory("dingdang-video-evidence").toFile();
        File emptyVideo = new File(evidenceDirectory, "scene-empty.mp4");
        File validVideo = new File(evidenceDirectory, "scene-valid.mp4");
        File unrelatedFile = new File(evidenceDirectory, "other.txt");
        assertTrue(emptyVideo.createNewFile());
        assertTrue(unrelatedFile.createNewFile());
        try (FileOutputStream output = new FileOutputStream(validVideo)) {
            output.write(1);
        }

        assertEquals(1, MainActivity.purgeIncompleteSceneVideos(evidenceDirectory));
        assertFalse(emptyVideo.exists());
        assertTrue(validVideo.isFile());
        assertTrue(unrelatedFile.isFile());

        assertTrue(validVideo.delete());
        assertTrue(unrelatedFile.delete());
        assertTrue(evidenceDirectory.delete());
    }

    @Test
    public void completedTaskDoesNotClaimThatAnEmptyTranscriptIsStillBeingRecognized() {
        assertEquals("服务器无法启动",
                MainActivity.hudTranscriptLabel("服务器无法启动", false));
        assertEquals("正在识别现场描述", MainActivity.hudTranscriptLabel("", true));
        assertEquals("在线 · 当前任务上下文已保留",
                MainActivity.hudTranscriptLabel("", false));
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
    public void persistedGuidanceStillHandlesStepCommandsWhileAFollowUpAnswerIsVisible() {
        assertTrue(MainActivity.shouldHandleGuidanceVoiceCommand(
                false, true, VoiceCommandRouter.Command.NEXT));
        assertTrue(MainActivity.shouldHandleGuidanceVoiceCommand(
                false, true, VoiceCommandRouter.Command.FINISH));
        assertTrue(MainActivity.shouldHandleGuidanceVoiceCommand(
                false, true, VoiceCommandRouter.Command.ABNORMAL));
        assertFalse(MainActivity.shouldHandleGuidanceVoiceCommand(
                false, true, VoiceCommandRouter.Command.NEXT_PAGE));
        assertFalse(MainActivity.shouldHandleGuidanceVoiceCommand(
                false, true, VoiceCommandRouter.Command.PREVIOUS_PAGE));
        assertTrue(MainActivity.shouldHandleGuidanceVoiceCommand(
                true, false, VoiceCommandRouter.Command.NEXT_PAGE));
    }

    @Test
    public void handledControlTranscriptIsClearedBeforeReturningToWakeStandby() {
        assertEquals("", MainActivity.transcriptAfterHandledVoiceCommand(true, "下一步。"));
        assertEquals("下一步。", MainActivity.transcriptAfterHandledVoiceCommand(false, "下一步。"));
    }

    @Test
    public void unrecognizedSpeechOnControlledSurfacesNeverCreatesANewAiTask() {
        assertTrue(MainActivity.shouldContainUnrecognizedVoiceOnCurrentSurface(
                true, false, false, false));
        assertTrue(MainActivity.shouldContainUnrecognizedVoiceOnCurrentSurface(
                false, true, false, false));
        assertTrue(MainActivity.shouldContainUnrecognizedVoiceOnCurrentSurface(
                false, false, true, false));
        assertTrue(MainActivity.shouldContainUnrecognizedVoiceOnCurrentSurface(
                false, false, false, true));
        assertFalse(MainActivity.shouldContainUnrecognizedVoiceOnCurrentSurface(
                false, false, false, false));
    }

    @Test
    public void consumesPhotoDescriptionOnlyWhileARealPhotoIsStillPending() {
        assertTrue(MainActivity.shouldConsumeVoiceAsPhotoDescription(
                VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION, true));
        assertFalse(MainActivity.shouldConsumeVoiceAsPhotoDescription(
                VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION, false));
        assertFalse(MainActivity.shouldConsumeVoiceAsPhotoDescription(
                VoiceEventStateMachine.State.IDLE, true));
    }

    @Test
    public void meaningfulSpeechCanLeaveTheAgentSkillCatalogAndStartAi() {
        assertTrue(MainActivity.shouldForwardFreeformFromAgentSkillCatalog(
                true, "平台温湿度出现报警，其他数据正常，帮我看看怎么回事"));
        assertFalse(MainActivity.shouldForwardFreeformFromAgentSkillCatalog(
                false, "平台温湿度出现报警，其他数据正常，帮我看看怎么回事"));
        assertFalse(MainActivity.shouldForwardFreeformFromAgentSkillCatalog(true, "好的"));
    }

    @Test
    public void usesAShortStableWindowForVoiceControlsOnly() {
        assertEquals(400L, MainActivity.voiceTranscriptStableStopDelayMs(true));
        assertEquals(1800L, MainActivity.voiceTranscriptStableStopDelayMs(false));
    }

    @Test
    public void forwardsEveryMeaningfulStandbyUtteranceToAiAfterARealWake() {
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.NONE, "今天是星期几"));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.NONE, "你是什么模型"));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.NONE, "讲个笑话"));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.NONE, "服务器有点奇怪"));
        assertTrue(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.NONE, "好的你确"));
    }

    @Test
    public void localAssistantAnswersIdentityAndCurrentWeekday() {
        Calendar monday = new GregorianCalendar(2026, Calendar.JULY, 27);

        assertTrue(MainActivity.localAssistantReply("你是什么模型", monday).contains("华方智联"));
        assertTrue(MainActivity.localAssistantReply("今天是星期几", monday).contains("星期一"));
        assertEquals("", MainActivity.localAssistantReply("服务器无法启动", monday));
    }

    @Test
    public void cameraAspectUsesTheDisplayOrientedBufferDimensions() {
        assertEquals(270, MainActivity.relativeCameraRotationDegrees(270, 0, false));
        assertTrue(MainActivity.cameraDimensionsAreSwapped(270));
        assertFalse(MainActivity.cameraDimensionsAreSwapped(0));
        assertEquals(16f / 9f,
                MainActivity.displayAspectRatioForBuffer(720, 1280, 270), 0.001f);
        assertEquals(16f / 9f,
                MainActivity.displayAspectRatioForBuffer(1920, 1080, 0), 0.001f);
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
                true, true, new VoiceCommandRouter().route("开始实训室设备巡检")));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.WORK_ORDER));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.TASK_CENTER));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.KNOWLEDGE));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.DEVICE));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.AGENT_CENTER));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.ENABLE_ENVIRONMENT_AGENT));
        assertFalse(MainActivity.shouldRejectOfflineWakeAtStandby(
                true, true, VoiceCommandRouter.Command.DISABLE_ENVIRONMENT_AGENT));
    }

    @Test
    public void appliesEnvironmentAgentVoiceCommandsAsGlobalIdempotentStateChanges() {
        assertEquals(Boolean.TRUE, MainActivity.environmentAgentEnabledState(
                VoiceCommandRouter.Command.ENABLE_ENVIRONMENT_AGENT));
        assertEquals(Boolean.FALSE, MainActivity.environmentAgentEnabledState(
                VoiceCommandRouter.Command.DISABLE_ENVIRONMENT_AGENT));
        assertEquals(null, MainActivity.environmentAgentEnabledState(
                VoiceCommandRouter.Command.AGENT_SKILL_ACTION));
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
    public void keepsVoiceHelpAndGlassesTutorialAboveStandbyStatusRefreshes() {
        assertEquals("voiceGuide", MainActivity.activeHudGuideState(true, false));
        assertEquals("glassesGuide", MainActivity.activeHudGuideState(false, true));
        assertEquals("voiceGuide", MainActivity.activeHudGuideState(true, true));
        assertEquals("", MainActivity.activeHudGuideState(false, false));
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
    public void rejectsSingleCharacterAsrNoiseWithoutDroppingRealCommandsOrProblems() {
        assertEquals("", MainActivity.sanitizeTaskNarration("你。"));
        assertEquals("", MainActivity.sanitizeTaskNarration("啊"));
        assertEquals("拍照", MainActivity.sanitizeTaskNarration("拍照"));
        assertEquals("下一页", MainActivity.sanitizeTaskNarration("下一页"));
        assertEquals("电源灯不亮", MainActivity.sanitizeTaskNarration("电源灯不亮"));
    }

    @Test
    public void rejectsLowInformationFreeformAsrWithoutBlockingShortFaultTerms() {
        assertTrue(MainActivity.isInsufficientFreeformVoiceInput("你确"));
        assertTrue(MainActivity.isInsufficientFreeformVoiceInput("好的"));
        assertFalse(MainActivity.isInsufficientFreeformVoiceInput("天气"));
        assertFalse(MainActivity.isInsufficientFreeformVoiceInput("你好"));
        assertFalse(MainActivity.isInsufficientFreeformVoiceInput("漏水"));
        assertFalse(MainActivity.isInsufficientFreeformVoiceInput("报警"));
        assertFalse(MainActivity.isInsufficientFreeformVoiceInput("服务器无法启动"));
    }

    @Test
    public void hardwareBackLeavesTheTaskConversationWithoutDiscardingItsTask() {
        assertEquals("project-rail", MainActivity.chatBackTarget(true, true));
        assertEquals("standby", MainActivity.chatBackTarget(false, true));
        assertEquals("chat", MainActivity.chatBackTarget(false, false));
    }

    @Test
    public void workflowBackNavigationNeverExecutesAnOptionalBusinessAction() {
        assertEquals("workflow_list", MainActivity.workflowNavigationBackAction(
                "workflow_choose:assignment-1", "workflow_standard:assignment-1"));
        assertEquals("workflow_list", MainActivity.workflowNavigationBackAction(
                "workflow_start:assignment-1", "workflow_list"));
        assertEquals("workflow_back", MainActivity.workflowNavigationBackAction(
                "workflow_confirmed:complete", "workflow_back"));
        assertEquals("workflow_back", MainActivity.workflowNavigationBackAction(
                "workflow_next", ""));
    }

    @Test
    public void workflowFormInputActionIsBoundOnlyToTheCurrentFormNode() throws Exception {
        assertEquals("siteCode", invokeWorkflowFormFieldKey(
                "workflow_input:form-1:siteCode", "form-1", "form"));
        assertEquals("", invokeWorkflowFormFieldKey(
                "workflow_input:form-2:siteCode", "form-1", "form"));
        assertEquals("", invokeWorkflowFormFieldKey(
                "workflow_input:form-1:siteCode", "form-1", "choice"));
        assertEquals("", invokeWorkflowFormFieldKey(
                "workflow_input:form-1:", "form-1", "form"));
    }

    @Test
    public void workflowNextUsesDraftAwareAdvanceOnlyForAnUnconfirmedFormStep()
            throws Exception {
        assertTrue(invokeWorkflowFormAdvancePolicy("form", false));
        assertFalse(invokeWorkflowFormAdvancePolicy("form", true));
        assertFalse(invokeWorkflowFormAdvancePolicy("voice_input", false));
    }

    @Test
    public void workflowNavigationClearsEitherKindOfPendingVoiceInput() {
        assertTrue(MainActivity.shouldCancelWorkflowInputForNavigation(
                true, false, false));
        assertTrue(MainActivity.shouldCancelWorkflowInputForNavigation(
                false, true, false));
        assertTrue(MainActivity.shouldCancelWorkflowInputForNavigation(
                false, false, true));
        assertFalse(MainActivity.shouldCancelWorkflowInputForNavigation(
                false, false, false));
    }

    @Test
    public void voiceprintSettingsNavigationInvalidatesVisibleRecordingOrPendingState() {
        assertTrue(MainActivity.shouldInvalidateVoiceprintSettingsOnNavigation(
                "voiceprint_settings", false, false));
        assertTrue(MainActivity.shouldInvalidateVoiceprintSettingsOnNavigation(
                "", true, false));
        assertTrue(MainActivity.shouldInvalidateVoiceprintSettingsOnNavigation(
                "", false, true));
        assertFalse(MainActivity.shouldInvalidateVoiceprintSettingsOnNavigation(
                "", false, false));
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
    public void localAsrWaitsForTheCloudGraceWindowUnlessCloudAlreadyFailed() {
        assertFalse(MainActivity.shouldDeliverLocalAsrImmediately(false));
        assertTrue(MainActivity.shouldDeliverLocalAsrImmediately(true));
        assertEquals(250L, MainActivity.localAsrGraceMillis(false));
        assertEquals(1200L, MainActivity.localAsrGraceMillis(true));
    }

    @Test
    public void localAsrSourcesAlwaysProduceAnExplicitUserNotice() {
        assertEquals("", MainActivity.localAsrSourceNotice("cloud"));
        assertEquals("网络语音不可用，已使用本地识别",
                MainActivity.localAsrSourceNotice("local-after-primary-failure"));
        assertEquals("网络语音响应较慢，已使用本地识别",
                MainActivity.localAsrSourceNotice("local-timeout-fallback"));
        assertEquals("已使用本地识别",
                MainActivity.localAsrSourceNotice("local"));
    }

    @Test
    public void secureRuntimeRequiresExpertConfirmationBeforeEnteringVideoCollaboration() {
        assertTrue(MainActivity.shouldConfirmExpertEntry(true));
        assertFalse(MainActivity.shouldConfirmExpertEntry(false));
    }

    @Test
    public void expertConfirmationCommandsAreDistinctFromWorkflowStepConfirmation() {
        assertEquals("expert_confirmed", MainActivity.expertConfirmationAction(true));
        assertEquals("expert_cancel", MainActivity.expertConfirmationAction(false));
        assertEquals("workflow_expert_confirmed",
                MainActivity.workflowExpertConfirmationAction(true));
        assertEquals("workflow_expert_cancel",
                MainActivity.workflowExpertConfirmationAction(false));
    }

    @Test
    public void expertConfirmationVoiceOnlyAcceptsExplicitConfirmOrCancelCommands() {
        assertEquals("expert_confirmed", MainActivity.expertConfirmationVoiceAction(
                true, false, VoiceCommandRouter.Command.CONFIRM));
        assertEquals("expert_cancel", MainActivity.expertConfirmationVoiceAction(
                true, false, VoiceCommandRouter.Command.CANCEL));
        assertEquals("expert_cancel", MainActivity.expertConfirmationVoiceAction(
                true, false, VoiceCommandRouter.Command.BACK));
        assertEquals("workflow_expert_confirmed", MainActivity.expertConfirmationVoiceAction(
                false, true, VoiceCommandRouter.Command.CONFIRM));
        assertEquals("workflow_expert_cancel", MainActivity.expertConfirmationVoiceAction(
                false, true, VoiceCommandRouter.Command.CANCEL));
        assertEquals("", MainActivity.expertConfirmationVoiceAction(
                false, false, VoiceCommandRouter.Command.CONFIRM));
        assertEquals("", MainActivity.expertConfirmationVoiceAction(
                true, false, VoiceCommandRouter.Command.EXPERT));
    }

    @Test
    public void pausingTheAppCancelsAnyVisibleExpertConfirmation() {
        assertEquals("expert_cancel",
                MainActivity.expertConfirmationLifecycleAction(true, false));
        assertEquals("workflow_expert_cancel",
                MainActivity.expertConfirmationLifecycleAction(false, true));
        assertEquals("", MainActivity.expertConfirmationLifecycleAction(false, false));
    }

    @Test
    public void cancellingExpertConfirmationRestoresTheSurfaceThatOpenedIt() {
        assertEquals("ability", MainActivity.expertConfirmationReturnTarget(
                true, true, true));
        assertEquals("capabilities", MainActivity.expertConfirmationReturnTarget(
                true, false, true));
        assertEquals("task", MainActivity.expertConfirmationReturnTarget(
                false, false, true));
        assertEquals("home", MainActivity.expertConfirmationReturnTarget(
                false, false, false));
    }

    @Test
    public void secureRuntimeNeverExposesLegacyPlannedSkillOrAgentCatalogs() {
        assertFalse(MainActivity.shouldExposeLegacyPlannedCatalog(true));
        assertTrue(MainActivity.shouldExposeLegacyPlannedCatalog(false));
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
        assertTrue(instruction.contains("只给出一个当前最需要执行的下一步"));
        assertTrue(instruction.contains("只能包含一个动作和一个检查对象"));
        assertTrue(instruction.contains("不要罗列多个可能原因"));
        assertTrue(instruction.contains("回答必须与当前问题直接相关"));
        assertTrue(instruction.contains("不得被历史任务或当前检测步骤带偏"));
        assertTrue(instruction.contains("不得输出检查状态或下一步模板"));
        assertTrue(instruction.contains("180 个汉字"));
        assertFalse(instruction.contains("固定使用"));
        assertFalse(instruction.contains("问题：一句话"));
        assertFalse(instruction.contains("【诊断结论】"));
        assertFalse(instruction.contains("【置信度】"));
        assertFalse(instruction.contains("【判断依据】"));
        assertFalse(instruction.contains("【安全风险】"));
        assertFalse(instruction.contains("【需补拍】"));
        assertFalse(instruction.contains("【专家协同建议】"));
    }

    @Test
    public void parsesVisualProtocolForEveryNewPhotoWithoutBindingOrdinaryTasksToASceneSkill() {
        assertTrue(MainActivity.shouldParseVisualProtocol(false, false, true));
        assertTrue(MainActivity.shouldParseVisualProtocol(true, false, false));
        assertTrue(MainActivity.shouldParseVisualProtocol(false, true, false));
        assertFalse(MainActivity.shouldParseVisualProtocol(false, false, false));
    }

    @Test
    public void resolvesSpokenInspectionTaskNamesWithoutAffectingGeneralCommands() {
        assertEquals("lab-training-room", MainActivity.inspectionTaskIdFromVoice("小叮当，开始实训室设备巡检"));
        assertEquals("water-power-heating", MainActivity.inspectionTaskIdFromVoice("开始水电暖巡检"));
        assertEquals("air-conditioning", MainActivity.inspectionTaskIdFromVoice("开始空调巡检"));
        assertEquals("fire-safety", MainActivity.inspectionTaskIdFromVoice("开始消防巡检"));
        assertEquals("", MainActivity.inspectionTaskIdFromVoice("服务器无法启动"));
    }

    @Test
    public void selectsTheFirstInspectionOnlyInsideTheInspectionCatalog() {
        assertEquals("lab-training-room",
                MainActivity.inspectionTaskIdFromVoice("进入第一个选项", true));
        assertEquals("lab-training-room",
                MainActivity.inspectionTaskIdFromVoice("开始第一个巡检任务", true));
        assertEquals("", MainActivity.inspectionTaskIdFromVoice("进入第一个选项", false));
        assertEquals("", MainActivity.inspectionTaskIdFromVoice("第一个", true));
    }

    @Test
    public void resolvesNamedSkillEnableDisableOnlyInsideTheSkillCatalog() {
        assertEquals("set_agent:network_ops:enabled",
                MainActivity.agentSkillActionFromVoice("启用网络运维技能", true));
        assertEquals("set_agent:water_ops:disabled",
                MainActivity.agentSkillActionFromVoice("停用水电暖巡检技能", true));
        assertEquals("set_agent:hvac_ops:enabled",
                MainActivity.agentSkillActionFromVoice("打开暖通空调技能", true));
        assertEquals("set_agent:fire_ops:disabled",
                MainActivity.agentSkillActionFromVoice("关闭消防巡检技能", true));
        assertEquals("set_agent:environment_ops:enabled",
                MainActivity.agentSkillActionFromVoice("启用环境诊断技能", true));
        assertEquals("set_agent:safety_ops:disabled",
                MainActivity.agentSkillActionFromVoice("禁用安全作业技能", true));
        assertEquals("", MainActivity.agentSkillActionFromVoice("启用网络运维技能", false));
        assertEquals("", MainActivity.agentSkillActionFromVoice("打开这个技能", true));
        assertEquals("", MainActivity.agentSkillActionFromVoice("服务器无法启动", true));
    }

    @Test
    public void rejectsLateInspectionAiCallbacksAfterHomeOrANewerPhotoRequest() {
        InspectionRun run = InspectionRun.start(
                InspectionCatalog.defaultCatalog().find("lab-training-room"));
        String pointId = run.currentPoint().id();

        assertTrue(MainActivity.isCurrentInspectionRequest(
                run, pointId, 4L, run, true, 4L));
        assertFalse(MainActivity.isCurrentInspectionRequest(
                run, pointId, 4L, run, false, 5L));
        assertFalse(MainActivity.isCurrentInspectionRequest(
                run, pointId, 4L, run, true, 5L));
        assertFalse(MainActivity.isCurrentInspectionRequest(
                run, pointId, 4L,
                InspectionRun.start(InspectionCatalog.defaultCatalog().find("lab-training-room")),
                true, 4L));
    }

    @Test
    public void voiceHelpUsesTheVisibleSurfaceInsteadOfAStoredInspectionRun() {
        assertEquals("home", MainActivity.resolveVoiceGuideContext(true, false, false, false));
        assertEquals("inspection", MainActivity.resolveVoiceGuideContext(true, false, false, true));
        assertEquals("capabilities", MainActivity.resolveVoiceGuideContext(false, false, false, true));
        assertEquals("task", MainActivity.resolveVoiceGuideContext(true, false, true, false));
        assertEquals("expert", MainActivity.resolveVoiceGuideContext(true, true, false, false));
    }

    @Test
    public void aiStreamCallbacksRequireTheCurrentRequestGenerationAndPlaceholder() {
        assertTrue(MainActivity.isActiveGptRequest(3, 8, 8));
        assertFalse(MainActivity.isActiveGptRequest(-1, 8, 8));
        assertFalse(MainActivity.isActiveGptRequest(3, 9, 8));
    }

    @Test
    public void referenceImagesNeverBecomeAiEvidence() {
        assertTrue(MainActivity.isUserEvidenceImage("user", "image", "backend-image-1"));
        assertFalse(MainActivity.isUserEvidenceImage(
                "assistant", "image", "asset://scene-reference/gateway-rs485.jpg"));
        assertFalse(MainActivity.isUserEvidenceImage(
                "user", "image", "asset://scene-reference/gateway-rs485.jpg"));
        assertFalse(MainActivity.isUserEvidenceImage("assistant", "text", "backend-image-1"));
    }

    @Test
    public void referenceImagesCanBeDisplayedInTheTaskHudWithoutBecomingAiEvidence() {
        assertTrue(MainActivity.isTaskHudDisplayImage(
                "user", "image", "backend-image-1"));
        assertTrue(MainActivity.isTaskHudDisplayImage(
                "assistant", "image", "asset://scene-reference/ddc-power.jpg"));
        assertFalse(MainActivity.isTaskHudDisplayImage(
                "assistant", "image", "backend-image-1"));
        assertFalse(MainActivity.isTaskHudDisplayImage(
                "assistant", "text", "asset://scene-reference/ddc-power.jpg"));
    }

    private void assertCommand(String phrase, LegacyVoiceCommandRouter.Command expected) {
        assertEquals(phrase, expected, router.route(phrase));
    }

    private static String invokeWorkflowFormFieldKey(
            String action,
            String currentNodeId,
            String currentNodeType
    ) throws Exception {
        Method method;
        try {
            method = MainActivity.class.getDeclaredMethod(
                    "workflowFormFieldKeyFromAction",
                    String.class,
                    String.class,
                    String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("workflow form input binding policy is missing", missing);
        }
        method.setAccessible(true);
        return (String) method.invoke(null, action, currentNodeId, currentNodeType);
    }

    private static boolean invokeWorkflowFormAdvancePolicy(
            String currentNodeType,
            boolean confirmed
    ) throws Exception {
        Method method;
        try {
            method = MainActivity.class.getDeclaredMethod(
                    "shouldAdvanceWorkflowForm", String.class, boolean.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("workflow form advance policy is missing", missing);
        }
        method.setAccessible(true);
        return (Boolean) method.invoke(null, currentNodeType, confirmed);
    }
}
