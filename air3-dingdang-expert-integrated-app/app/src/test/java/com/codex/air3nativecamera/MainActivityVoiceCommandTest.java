package com.codex.air3nativecamera;

import static org.junit.Assert.assertEquals;

import com.codex.air3nativecamera.voice.LegacyVoiceCommandRouter;
import org.junit.Test;

public final class MainActivityVoiceCommandTest {
    private final LegacyVoiceCommandRouter router = new LegacyVoiceCommandRouter();

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

    private void assertCommand(String phrase, LegacyVoiceCommandRouter.Command expected) {
        assertEquals(phrase, expected, router.route(phrase));
    }
}
