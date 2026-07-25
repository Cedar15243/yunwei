package com.codex.air3nativecamera.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class VoiceEventStateMachineTest {
    private final VoiceCommandRouter router = new VoiceCommandRouter();

    @Test
    public void photoThenDescriptionCreatesOneAiReadyEvent() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();

        assertEquals(VoiceEventStateMachine.Signal.CAPTURE_PHOTO, state.onCommand(router.route("拍照")));
        assertEquals(VoiceEventStateMachine.Signal.START_DESCRIPTION, state.onPhotoCaptured());
        assertEquals(VoiceEventStateMachine.Signal.SUBMIT_TO_AI, state.onDescriptionFinal("平台显示 502，需要排查原因"));
        assertEquals(VoiceEventStateMachine.State.AI_READY, state.state());
        assertEquals("平台显示 502，需要排查原因", state.eventDescription());
        assertEquals(true, state.hasPhoto());
    }

    @Test
    public void photoWithoutDescriptionSubmitsAsImageOnlyAfterTheVisibleWaitWindow() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();

        state.onCommand(router.route("拍照"));
        state.onPhotoCaptured();

        assertEquals(VoiceEventStateMachine.Signal.SUBMIT_TO_AI, state.onDescriptionTimeout());
        assertEquals(VoiceEventStateMachine.State.AI_READY, state.state());
        assertEquals("", state.eventDescription());
        assertEquals(true, state.hasPhoto());
    }

    @Test
    public void emptyDescriptionDoesNotSubmitThePendingPhoto() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();

        state.onCommand(router.route("拍照"));
        state.onPhotoCaptured();

        assertEquals(VoiceEventStateMachine.Signal.NONE, state.onDescriptionFinal("   "));
        assertEquals(VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION, state.state());
        assertEquals(true, state.hasPhoto());
    }

    @Test
    public void manualPhotoCaptureAlsoWaitsForDescription() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();

        state.beginPhotoCapture();

        assertEquals(VoiceEventStateMachine.Signal.START_DESCRIPTION, state.onPhotoCaptured());
        assertEquals(VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION, state.state());
        assertEquals(true, state.hasPhoto());
    }

    @Test
    public void confirmDoesNotSendAPhotoWithoutDescription() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();

        state.onCommand(router.route("拍照"));
        state.onPhotoCaptured();

        assertEquals(VoiceEventStateMachine.Signal.NONE, state.onCommand(router.route("确认")));
        assertEquals(VoiceEventStateMachine.State.WAITING_FOR_DESCRIPTION, state.state());
        assertEquals("", state.eventDescription());
    }

    @Test
    public void explicitImageOnlyCommandSubmitsPhotoWithoutInventingDescription() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();

        state.onCommand(router.route("拍照"));
        state.onPhotoCaptured();

        assertEquals(VoiceEventStateMachine.Signal.SUBMIT_TO_AI, state.onCommand(router.route("仅发送图片")));
        assertEquals(VoiceEventStateMachine.State.AI_READY, state.state());
        assertEquals("", state.eventDescription());
    }

    @Test
    public void narrationIsNotMisclassifiedAsAnalysisCommand() {
        assertEquals(VoiceCommandRouter.Command.NONE, router.route("我想分析这个问题"));
        assertEquals(VoiceCommandRouter.Command.EXPERT, router.route("小叮当，专家"));
        assertEquals(VoiceCommandRouter.Command.PHOTO, router.route("小叮当拍照"));
    }

    @Test
    public void photoCommandAcceptsNaturalPhotoPhrases() {
        assertEquals(VoiceCommandRouter.Command.PHOTO, router.route("\u62cd\u7167\u7247"));
        assertEquals(VoiceCommandRouter.Command.PHOTO, router.route("\u62cd\u4e00\u5f20\u7167\u7247"));
    }

    @Test
    public void backCommandAcceptsCameraExitPhrases() {
        assertEquals(VoiceCommandRouter.Command.BACK, router.route("返回"));
        assertEquals(VoiceCommandRouter.Command.BACK, router.route("返回聊天"));
        assertEquals(VoiceCommandRouter.Command.BACK, router.route("退出相机"));
        assertEquals(VoiceCommandRouter.Command.BACK, router.route("不拍了"));
    }

    @Test
    public void cancelClearsActiveEvent() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();
        state.onCommand(router.route("拍照"));
        state.onPhotoCaptured();

        assertEquals(VoiceEventStateMachine.Signal.CANCEL_EVENT, state.onCommand(router.route("取消")));
        assertEquals(VoiceEventStateMachine.State.IDLE, state.state());
        assertEquals(false, state.hasPhoto());
    }

    @Test
    public void cancelOutsidePhotoWorkflowDoesNotClearChatContext() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();

        assertEquals(VoiceEventStateMachine.Signal.NONE, state.onCommand(router.route("取消")));
        assertEquals(VoiceEventStateMachine.State.IDLE, state.state());
    }

    @Test
    public void retakeRequestsAnotherPhotoAfterTheFirstPhoto() {
        VoiceEventStateMachine state = new VoiceEventStateMachine();
        state.onCommand(router.route("拍照"));
        state.onPhotoCaptured();

        assertEquals(VoiceEventStateMachine.Signal.CAPTURE_PHOTO, state.onCommand(router.route("重拍")));
        assertEquals(VoiceEventStateMachine.State.CAPTURE_REQUESTED, state.state());
    }
}
