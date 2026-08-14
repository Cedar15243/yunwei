package com.codex.air3nativecamera.voice;

import java.util.HashMap;
import java.util.Map;

/** Routes only complete, short control phrases. Everything else remains event narration. */
public final class VoiceCommandRouter {
    public enum Command {
        NONE,
        PHOTO,
        VIDEO_START,
        VIDEO_STOP,
        DIAGNOSIS,
        EXPERT,
        CAPABILITY_CENTER,
        INSPECTION,
        PERCEPTION,
        RECORD,
        DEVICE,
        PROJECT_MEMORY,
        KNOWLEDGE,
        SKILL_CENTER,
        AGENT_CENTER,
        AGENT_SKILL_ACTION,
        ENABLE_ENVIRONMENT_AGENT,
        DISABLE_ENVIRONMENT_AGENT,
        TASK_CENTER,
        WORK_ORDER,
        SAFETY,
        REPORT,
        TRAINING,
        HELP,
        GLASSES_TUTORIAL,
        BACK,
        HOME,
        RESTART_TASK,
        NORMAL,
        ABNORMAL,
        NEXT,
        PREVIOUS,
        NEXT_PAGE,
        PREVIOUS_PAGE,
        APPEND,
        RETRY,
        RETAKE,
        SAVE,
        SUBMIT,
        IMAGE_ONLY,
        CONFIRM,
        CANCEL,
        REPEAT,
        FINISH,
        GUIDANCE
    }

    private final Map<String, Command> commands = new HashMap<>();

    public VoiceCommandRouter() {
        register(Command.CAPABILITY_CENTER, "AI\u80fd\u529b\u4e2d\u5fc3", "\u6253\u5f00AI\u80fd\u529b\u4e2d\u5fc3");
        register(Command.PHOTO, "\u62cd\u7167\u7247", "\u62cd\u4e00\u5f20\u7167\u7247", "\u62cd\u4e2a\u7167\u7247");
        register(Command.PHOTO, "拍照", "拍一张", "拍张照", "现场拍照", "补拍", "拍摄近景");
        register(Command.VIDEO_START, "开始录像", "录视频", "录一段视频", "开始录制",
                "短视频取证", "开始短视频取证");
        register(Command.VIDEO_STOP, "停止录像", "结束录像", "停止录制", "结束录制");
        register(Command.DIAGNOSIS, "开始诊断", "AI诊断", "诊断");
        register(Command.EXPERT, "专家", "呼叫专家");
        register(Command.INSPECTION, "巡检", "巡检任务", "打开巡检任务",
                "开始实训室设备巡检", "开始实训室巡检", "实训室设备巡检", "实训室巡检",
                "开始水电暖巡检", "开始空调巡检", "开始消防巡检");
        register(Command.PERCEPTION, "现场感知", "现场采集");
        register(Command.RECORD, "记录");
        register(Command.DEVICE, "设备", "设备记忆", "打开设备记忆");
        register(Command.PROJECT_MEMORY, "项目记忆", "打开项目记忆", "历史项目", "查看历史项目",
                "查看之前的项目记录", "查看项目记录", "继续之前的项目");
        register(Command.KNOWLEDGE, "知识库", "知识", "华方知识库", "打开华方知识库");
        register(Command.AGENT_CENTER, "技能中心", "AI技能中心", "技能",
                "AI运维技能", "打开AI运维技能", "ai运维技能", "打开ai运维技能",
                "AI运维技能中心", "运维技能", "运维技能中心",
                "智能体中心", "AI智能体", "Agent中心", "agent中心",
                "AI Agent中心", "AIAgent中心", "打开AI Agent中心");
        register(Command.AGENT_SKILL_ACTION,
                "启用网络运维技能", "打开网络运维技能", "停用网络运维技能", "关闭网络运维技能", "禁用网络运维技能",
                "启用水电暖巡检技能", "打开水电暖巡检技能", "停用水电暖巡检技能", "关闭水电暖巡检技能", "禁用水电暖巡检技能",
                "启用暖通空调技能", "打开暖通空调技能", "停用暖通空调技能", "关闭暖通空调技能", "禁用暖通空调技能",
                "启用消防巡检技能", "打开消防巡检技能", "停用消防巡检技能", "关闭消防巡检技能", "禁用消防巡检技能",
                "启用安全作业技能", "打开安全作业技能", "停用安全作业技能", "关闭安全作业技能", "禁用安全作业技能");
        register(Command.ENABLE_ENVIRONMENT_AGENT,
                "环境诊断", "环境诊断技能",
                "启用环境诊断", "打开环境诊断", "启用环境诊断技能", "打开环境诊断技能");
        register(Command.DISABLE_ENVIRONMENT_AGENT,
                "停用环境诊断", "关闭环境诊断", "禁用环境诊断",
                "关闭环境诊断Agent", "停用环境诊断Agent",
                "关闭环境技能", "停用环境技能", "停用环境诊断技能", "关闭环境诊断技能", "禁用环境诊断技能");
        register(Command.TASK_CENTER, "任务中心", "任务", "维修任务", "打开维修任务");
        register(Command.WORK_ORDER, "工单", "打开工单");
        register(Command.SAFETY, "安全");
        register(Command.REPORT, "报告");
        register(Command.TRAINING, "培训", "演练");
        register(Command.HELP, "帮助", "语音帮助", "打开语音帮助", "语音指令帮助", "查看语音指令",
                "打开帮助", "语音命令", "使用帮助", "操作帮助", "怎么操作", "有哪些命令", "指令有哪些");
        register(Command.GLASSES_TUTORIAL, "眼镜使用教学", "打开眼镜使用教学", "眼镜教学", "打开眼镜教学",
                "眼镜教程", "眼镜怎么用");
        register(Command.BACK, "返回", "返回上一页", "返回聊天", "退出相机", "不拍了");
        register(Command.HOME, "返回首页", "回首页", "回到首页", "退回首页", "返回主页", "首页");
        register(Command.RESTART_TASK, "重新开始任务", "新任务", "重新诊断");
        register(Command.NORMAL, "正常");
        register(Command.ABNORMAL, "异常");
        register(Command.NEXT, "下一项", "下一步");
        register(Command.PREVIOUS, "上一项");
        register(Command.NEXT_PAGE, "下一页");
        register(Command.PREVIOUS_PAGE, "上一页");
        register(Command.APPEND, "补充");
        register(Command.RETRY, "重说", "重新分析");
        register(Command.RETAKE, "重拍", "重新拍摄");
        register(Command.SAVE, "保存");
        register(Command.SUBMIT, "提交", "使用照片", "用这张", "开始分析", "帮我分析");
        register(Command.IMAGE_ONLY, "仅发送图片", "只发送图片", "只发图片", "仅用图片分析");
        register(Command.CONFIRM, "确认");
        register(Command.CANCEL, "取消");
        register(Command.REPEAT, "重复", "重复本页");
        register(Command.FINISH, "说完", "完成", "我已完成");
        register(Command.GUIDANCE, "开始维修指导", "开始维修");
        // Resolve the final semantic mapping after the compatibility aliases above.
        // "Next page" belongs to paged AI content; repair guidance advances only on "next step".
        register(Command.NEXT, "下一步", "下一项", "我已完成");
        register(Command.PREVIOUS, "上一步");
        register(Command.NEXT_PAGE, "下一页", "翻下一页", "下一屏");
        register(Command.PREVIOUS_PAGE, "上一页", "翻上一页", "上一屏");
    }

    public Command route(String text) {
        String normalized = normalize(text);
        if (normalized.startsWith("小叮当")) {
            normalized = normalized.substring(3);
        } else if (normalized.startsWith("小叮") || normalized.startsWith("小丁")) {
            normalized = normalized.substring(2);
        }
        // ASR occasionally keeps a polite prefix after the wake word. Keep global navigation
        // forgiving without treating ordinary task narration as a control command.
        if (normalized.startsWith("请")) {
            normalized = normalized.substring(1);
        }
        return commands.containsKey(normalized) ? commands.get(normalized) : Command.NONE;
    }

    /** Short, unambiguous controls can finish ASR sooner than open-ended task narration. */
    public boolean isFastControlCommand(String text) {
        String normalized = normalize(text);
        Command command = route(text);
        if (command == Command.NONE) {
            return false;
        }
        // Do not cut off a plain "返回" before the operator can finish "返回首页" or
        // "返回上一页". The longer forms are explicit enough to use the fast window.
        if (command == Command.BACK && normalized.equals("返回")) {
            return false;
        }
        switch (command) {
            case PHOTO:
            case VIDEO_START:
            case VIDEO_STOP:
            case DIAGNOSIS:
            case EXPERT:
            case CAPABILITY_CENTER:
            case INSPECTION:
            case KNOWLEDGE:
            case DEVICE:
            case PROJECT_MEMORY:
            case AGENT_CENTER:
            case AGENT_SKILL_ACTION:
            case ENABLE_ENVIRONMENT_AGENT:
            case DISABLE_ENVIRONMENT_AGENT:
            case WORK_ORDER:
            case HELP:
            case GLASSES_TUTORIAL:
            case HOME:
            case NEXT:
            case PREVIOUS:
            case NEXT_PAGE:
            case PREVIOUS_PAGE:
            case RETRY:
            case RETAKE:
            case IMAGE_ONLY:
            case CONFIRM:
            case CANCEL:
            case REPEAT:
            case FINISH:
            case GUIDANCE:
                return true;
            case BACK:
                return normalized.length() > 2;
            default:
                return false;
        }
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace("。", "")
                .replace("！", "")
                .replace("？", "")
                .replace("，", "")
                .replace("、", "")
                .trim();
    }

    private void register(Command command, String... phrases) {
        for (String phrase : phrases) {
            commands.put(normalize(phrase), command);
        }
    }
}
