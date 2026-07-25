package com.codex.air3nativecamera.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceCommandRouterTest {
    private final VoiceCommandRouter router = new VoiceCommandRouter();

    @Test
    public void routesEverySupportedShortControlPhrase() {
        assertRoute("拍照", VoiceCommandRouter.Command.PHOTO);
        assertRoute("现场拍照", VoiceCommandRouter.Command.PHOTO);
        assertRoute("补拍", VoiceCommandRouter.Command.PHOTO);
        assertRoute("拍摄近景", VoiceCommandRouter.Command.PHOTO);
        assertRoute("拍照片", VoiceCommandRouter.Command.PHOTO);
        assertRoute("拍一张照片", VoiceCommandRouter.Command.PHOTO);
        assertRoute("开始录像", VoiceCommandRouter.Command.VIDEO_START);
        assertRoute("短视频取证", VoiceCommandRouter.Command.VIDEO_START);
        assertRoute("开始短视频取证", VoiceCommandRouter.Command.VIDEO_START);
        assertRoute("停止录像", VoiceCommandRouter.Command.VIDEO_STOP);
        assertRoute("开始诊断", VoiceCommandRouter.Command.DIAGNOSIS);
        assertRoute("使用照片", VoiceCommandRouter.Command.SUBMIT);
        assertRoute("开始分析", VoiceCommandRouter.Command.SUBMIT);
        assertRoute("仅发送图片", VoiceCommandRouter.Command.IMAGE_ONLY);
        assertRoute("专家", VoiceCommandRouter.Command.EXPERT);
        assertRoute("巡检", VoiceCommandRouter.Command.INSPECTION);
        assertRoute("打开巡检任务", VoiceCommandRouter.Command.INSPECTION);
        assertRoute("现场感知", VoiceCommandRouter.Command.PERCEPTION);
        assertRoute("现场采集", VoiceCommandRouter.Command.PERCEPTION);
        assertRoute("记录", VoiceCommandRouter.Command.RECORD);
        assertRoute("设备", VoiceCommandRouter.Command.DEVICE);
        assertRoute("设备记忆", VoiceCommandRouter.Command.DEVICE);
        assertRoute("知识库", VoiceCommandRouter.Command.KNOWLEDGE);
        assertRoute("华方知识库", VoiceCommandRouter.Command.KNOWLEDGE);
        assertRoute("技能中心", VoiceCommandRouter.Command.SKILL_CENTER);
        assertRoute("AI技能中心", VoiceCommandRouter.Command.SKILL_CENTER);
        assertRoute("智能体中心", VoiceCommandRouter.Command.AGENT_CENTER);
        assertRoute("Agent中心", VoiceCommandRouter.Command.AGENT_CENTER);
        assertRoute("AI Agent中心", VoiceCommandRouter.Command.AGENT_CENTER);
        assertRoute("任务中心", VoiceCommandRouter.Command.TASK_CENTER);
        assertRoute("维修任务", VoiceCommandRouter.Command.TASK_CENTER);
        assertRoute("任务", VoiceCommandRouter.Command.TASK_CENTER);
        assertRoute("工单", VoiceCommandRouter.Command.WORK_ORDER);
        assertRoute("安全", VoiceCommandRouter.Command.SAFETY);
        assertRoute("报告", VoiceCommandRouter.Command.REPORT);
        assertRoute("培训", VoiceCommandRouter.Command.TRAINING);
        assertRoute("语音帮助", VoiceCommandRouter.Command.HELP);
        assertRoute("打开语音帮助", VoiceCommandRouter.Command.HELP);
        assertRoute("打开眼镜使用教学", VoiceCommandRouter.Command.GLASSES_TUTORIAL);
        assertRoute("怎么操作", VoiceCommandRouter.Command.HELP);
        assertRoute("返回", VoiceCommandRouter.Command.BACK);
        assertRoute("返回上一页", VoiceCommandRouter.Command.BACK);
        assertRoute("返回首页", VoiceCommandRouter.Command.HOME);
        assertRoute("回到首页", VoiceCommandRouter.Command.HOME);
        assertRoute("退回首页", VoiceCommandRouter.Command.HOME);
        assertRoute("返回主页", VoiceCommandRouter.Command.HOME);
        assertRoute("首页", VoiceCommandRouter.Command.HOME);
        assertRoute("重新开始任务", VoiceCommandRouter.Command.RESTART_TASK);
        assertRoute("返回聊天", VoiceCommandRouter.Command.BACK);
        assertRoute("退出相机", VoiceCommandRouter.Command.BACK);
        assertRoute("不拍了", VoiceCommandRouter.Command.BACK);
        assertRoute("正常", VoiceCommandRouter.Command.NORMAL);
        assertRoute("异常", VoiceCommandRouter.Command.ABNORMAL);
        assertRoute("下一项", VoiceCommandRouter.Command.NEXT);
        assertRoute("下一步", VoiceCommandRouter.Command.NEXT);
        assertRoute("下一页", VoiceCommandRouter.Command.NEXT_PAGE);
        assertRoute("上一项", VoiceCommandRouter.Command.PREVIOUS);
        assertRoute("上一页", VoiceCommandRouter.Command.PREVIOUS_PAGE);
        assertRoute("补充", VoiceCommandRouter.Command.APPEND);
        assertRoute("重说", VoiceCommandRouter.Command.RETRY);
        assertRoute("重新分析", VoiceCommandRouter.Command.RETRY);
        assertRoute("重拍", VoiceCommandRouter.Command.RETAKE);
        assertRoute("重新拍摄", VoiceCommandRouter.Command.RETAKE);
        assertRoute("保存", VoiceCommandRouter.Command.SAVE);
        assertRoute("提交", VoiceCommandRouter.Command.SUBMIT);
        assertRoute("确认", VoiceCommandRouter.Command.CONFIRM);
        assertRoute("取消", VoiceCommandRouter.Command.CANCEL);
        assertRoute("重复", VoiceCommandRouter.Command.REPEAT);
        assertRoute("重复本页", VoiceCommandRouter.Command.REPEAT);
        assertRoute("说完", VoiceCommandRouter.Command.FINISH);
        assertRoute("完成", VoiceCommandRouter.Command.FINISH);
        assertRoute("我已完成", VoiceCommandRouter.Command.NEXT);
        assertRoute("开始维修指导", VoiceCommandRouter.Command.GUIDANCE);
        assertRoute("开始维修", VoiceCommandRouter.Command.GUIDANCE);
        assertRoute("维修指导", VoiceCommandRouter.Command.NONE);
    }

    @Test
    public void stripsXiaoDingDangWakeWordAndKeepsEventNarrationOutOfControlRouting() {
        assertRoute("小叮当，专家", VoiceCommandRouter.Command.EXPERT);
        assertRoute("小叮当，开始诊断", VoiceCommandRouter.Command.DIAGNOSIS);
        assertRoute("小叮当拍照", VoiceCommandRouter.Command.PHOTO);
        assertRoute("小叮当，仅发送图片", VoiceCommandRouter.Command.IMAGE_ONLY);
        assertRoute("小叮当，打开语音帮助", VoiceCommandRouter.Command.HELP);
        assertRoute("小叮当，回首页", VoiceCommandRouter.Command.HOME);
        assertRoute("小叮当，返回首页", VoiceCommandRouter.Command.HOME);
        assertRoute("小叮当，请返回首页", VoiceCommandRouter.Command.HOME);
        assertRoute("小叮，开始诊断", VoiceCommandRouter.Command.DIAGNOSIS);
        assertRoute("小丁，呼叫专家", VoiceCommandRouter.Command.EXPERT);
        assertRoute("平台页面报错五零二，需要排查原因", VoiceCommandRouter.Command.NONE);
    }

    @Test
    public void usesOneExplicitHelpPhraseWithoutTreatingWakeWordLabelsAsCommands() {
        assertRoute("小叮当，打开语音帮助", VoiceCommandRouter.Command.HELP);
        assertRoute("小叮当，语音指令帮助", VoiceCommandRouter.Command.HELP);
        assertRoute("小叮当，眼镜使用教学", VoiceCommandRouter.Command.GLASSES_TUTORIAL);
        assertRoute("小叮当，唤醒词", VoiceCommandRouter.Command.NONE);
        assertRoute("小叮当，提示词", VoiceCommandRouter.Command.NONE);
        assertRoute("小叮当", VoiceCommandRouter.Command.NONE);
    }

    @Test
    public void routesTheExplicitAiCapabilityCenterEntry() {
        assertRoute("AI能力中心", VoiceCommandRouter.Command.CAPABILITY_CENTER);
        assertRoute("打开AI能力中心", VoiceCommandRouter.Command.CAPABILITY_CENTER);
        assertRoute("小叮当，打开AI能力中心", VoiceCommandRouter.Command.CAPABILITY_CENTER);
    }

    @Test
    public void keepsPageTurnsDistinctFromRepairStepCommandsAfterWakeWordRemoval() {
        assertRoute("小叮当，下一页", VoiceCommandRouter.Command.NEXT_PAGE);
        assertRoute("小叮当，下一步", VoiceCommandRouter.Command.NEXT);
        assertRoute("翻下一页", VoiceCommandRouter.Command.NEXT_PAGE);
        assertRoute("上一屏", VoiceCommandRouter.Command.PREVIOUS_PAGE);
    }

    @Test
    public void recognizesUnambiguousControlsForFastAsrCompletion() {
        assertTrue(router.isFastControlCommand("返回首页"));
        assertTrue(router.isFastControlCommand("小叮当，呼叫专家"));
        assertTrue(router.isFastControlCommand("打开 AI 能力中心"));
        assertFalse(router.isFastControlCommand("返回"));
        assertFalse(router.isFastControlCommand("服务器无法启动"));
    }

    private void assertRoute(String phrase, VoiceCommandRouter.Command expected) {
        assertEquals(phrase, expected, router.route(phrase));
    }
}
