package com.codex.air3nativecamera.voice;

import java.util.HashMap;
import java.util.Map;

/** Routes only complete, short control phrases. Everything else remains event narration. */
public final class VoiceCommandRouter {
    public enum Command {
        NONE,
        PHOTO,
        EXPERT,
        INSPECTION,
        RECORD,
        DEVICE,
        KNOWLEDGE,
        WORK_ORDER,
        SAFETY,
        REPORT,
        TRAINING,
        HELP,
        BACK,
        NORMAL,
        ABNORMAL,
        NEXT,
        PREVIOUS,
        APPEND,
        RETRY,
        RETAKE,
        SAVE,
        SUBMIT,
        CONFIRM,
        CANCEL,
        REPEAT,
        FINISH
    }

    private final Map<String, Command> commands = new HashMap<>();

    public VoiceCommandRouter() {
        register(Command.PHOTO, "\u62cd\u7167\u7247", "\u62cd\u4e00\u5f20\u7167\u7247", "\u62cd\u4e2a\u7167\u7247");
        register(Command.PHOTO, "拍照", "拍一张", "拍张照");
        register(Command.EXPERT, "专家", "呼叫专家");
        register(Command.INSPECTION, "巡检");
        register(Command.RECORD, "记录");
        register(Command.DEVICE, "设备");
        register(Command.KNOWLEDGE, "知识库", "知识");
        register(Command.WORK_ORDER, "工单");
        register(Command.SAFETY, "安全");
        register(Command.REPORT, "报告");
        register(Command.TRAINING, "培训", "演练");
        register(Command.HELP, "帮助");
        register(Command.BACK, "返回", "返回聊天", "退出相机", "不拍了");
        register(Command.NORMAL, "正常");
        register(Command.ABNORMAL, "异常");
        register(Command.NEXT, "下一项");
        register(Command.PREVIOUS, "上一项");
        register(Command.APPEND, "补充");
        register(Command.RETRY, "重说");
        register(Command.RETAKE, "重拍");
        register(Command.SAVE, "保存");
        register(Command.SUBMIT, "提交");
        register(Command.CONFIRM, "确认");
        register(Command.CANCEL, "取消");
        register(Command.REPEAT, "重复");
        register(Command.FINISH, "说完");
    }

    public Command route(String text) {
        String normalized = normalize(text);
        if (normalized.startsWith("叮当")) {
            normalized = normalized.substring(2);
        }
        return commands.containsKey(normalized) ? commands.get(normalized) : Command.NONE;
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
