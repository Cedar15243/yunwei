package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VoiceCommandRouterTest {
    private final VoiceCommandRouter router = new VoiceCommandRouter();

    @Test
    public void routesEverySupportedShortControlPhrase() {
        assertRoute("拍照", VoiceCommandRouter.Command.PHOTO);
        assertRoute("拍照片", VoiceCommandRouter.Command.PHOTO);
        assertRoute("拍一张照片", VoiceCommandRouter.Command.PHOTO);
        assertRoute("专家", VoiceCommandRouter.Command.EXPERT);
        assertRoute("巡检", VoiceCommandRouter.Command.INSPECTION);
        assertRoute("记录", VoiceCommandRouter.Command.RECORD);
        assertRoute("设备", VoiceCommandRouter.Command.DEVICE);
        assertRoute("知识库", VoiceCommandRouter.Command.KNOWLEDGE);
        assertRoute("工单", VoiceCommandRouter.Command.WORK_ORDER);
        assertRoute("安全", VoiceCommandRouter.Command.SAFETY);
        assertRoute("报告", VoiceCommandRouter.Command.REPORT);
        assertRoute("培训", VoiceCommandRouter.Command.TRAINING);
        assertRoute("帮助", VoiceCommandRouter.Command.HELP);
        assertRoute("语音命令", VoiceCommandRouter.Command.HELP);
        assertRoute("怎么操作", VoiceCommandRouter.Command.HELP);
        assertRoute("返回", VoiceCommandRouter.Command.BACK);
        assertRoute("返回聊天", VoiceCommandRouter.Command.BACK);
        assertRoute("退出相机", VoiceCommandRouter.Command.BACK);
        assertRoute("不拍了", VoiceCommandRouter.Command.BACK);
        assertRoute("正常", VoiceCommandRouter.Command.NORMAL);
        assertRoute("异常", VoiceCommandRouter.Command.ABNORMAL);
        assertRoute("下一项", VoiceCommandRouter.Command.NEXT);
        assertRoute("上一项", VoiceCommandRouter.Command.PREVIOUS);
        assertRoute("补充", VoiceCommandRouter.Command.APPEND);
        assertRoute("重说", VoiceCommandRouter.Command.RETRY);
        assertRoute("重拍", VoiceCommandRouter.Command.RETAKE);
        assertRoute("保存", VoiceCommandRouter.Command.SAVE);
        assertRoute("提交", VoiceCommandRouter.Command.SUBMIT);
        assertRoute("确认", VoiceCommandRouter.Command.CONFIRM);
        assertRoute("取消", VoiceCommandRouter.Command.CANCEL);
        assertRoute("重复", VoiceCommandRouter.Command.REPEAT);
        assertRoute("说完", VoiceCommandRouter.Command.FINISH);
    }

    @Test
    public void stripsWakeWordAndKeepsEventNarrationOutOfControlRouting() {
        assertRoute("叮当，专家", VoiceCommandRouter.Command.EXPERT);
        assertRoute("叮当拍照", VoiceCommandRouter.Command.PHOTO);
        assertRoute("叮当，语音命令", VoiceCommandRouter.Command.HELP);
        assertRoute("平台页面报错五零二，需要排查原因", VoiceCommandRouter.Command.NONE);
    }

    private void assertRoute(String phrase, VoiceCommandRouter.Command expected) {
        assertEquals(phrase, expected, router.route(phrase));
    }
}
