package com.codex.air3nativecamera.voice;

/** Routes complete short commands that remain available on the chat surface. */
public final class LegacyVoiceCommandRouter {
    public enum Command {
        NONE, OPEN_EXPERT, OPEN_CAMERA, TAKE_PHOTO, RETAKE_PHOTO, SEND, BACK_TO_CHAT,
        START_VOICE, NEW_PROJECT, NEXT_PROJECT, PREVIOUS_PROJECT, LATEST_PROJECT, SHOW_RECORDS
    }

    private static final int MAX_COMMAND_CHARS = 16;
    private static final String[] OPEN_CAMERA = {"打开相机", "准备拍照", "打开摄像头"};
    private static final String[] OPEN_EXPERT = {"呼叫专家", "打开专家协同"};
    private static final String[] PHOTO = {"现场拍照", "拍照", "拍一张", "拍张照", "照一下", "看一下", "扫一下"};
    private static final String[] PHOTO_EXTENDED = {"拍照片", "拍一张照片", "拍个照片"};
    private static final String[] RETAKE = {"重拍", "重新拍", "再拍", "重新照", "再照"};
    private static final String[] SEND = {"发送", "开始分析", "帮我分析", "就这张", "用这张"};
    private static final String[] BACK = {"返回", "回到聊天", "取消", "不拍了", "退出相机"};
    private static final String[] SPEAK = {"继续说", "继续问", "我再说", "追问", "继续提问"};
    private static final String[] NEW_PROJECT = {"新建项目", "建立项目", "新的项目"};
    private static final String[] NEXT_PROJECT = {"下一条记录", "下一个记录", "下一条"};
    private static final String[] PREVIOUS_PROJECT = {"上一条记录", "上一个记录", "上一条"};
    private static final String[] LATEST_PROJECT = {"最新记录", "最近记录", "回到最新"};
    private static final String[] RECORDS = {"查看记录", "打开记录", "历史记录"};

    public Command route(String text) {
        String normalized = compact(text);
        if (!isCommandPhrase(normalized)) return Command.NONE;
        if (matches(normalized, OPEN_EXPERT)) return Command.OPEN_EXPERT;
        if (matches(normalized, OPEN_CAMERA)) return Command.OPEN_CAMERA;
        if (contains(normalized, RETAKE)) return Command.RETAKE_PHOTO;
        if (matches(normalized, PHOTO) || matches(normalized, PHOTO_EXTENDED)) return Command.TAKE_PHOTO;
        if (matches(normalized, SEND)) return Command.SEND;
        if (matches(normalized, BACK)) return Command.BACK_TO_CHAT;
        if (matches(normalized, SPEAK)) return Command.START_VOICE;
        if (matches(normalized, NEW_PROJECT)) return Command.NEW_PROJECT;
        if (matches(normalized, NEXT_PROJECT)) return Command.NEXT_PROJECT;
        if (matches(normalized, PREVIOUS_PROJECT)) return Command.PREVIOUS_PROJECT;
        if (matches(normalized, LATEST_PROJECT)) return Command.LATEST_PROJECT;
        if (matches(normalized, RECORDS)) return Command.SHOW_RECORDS;
        return Command.NONE;
    }

    private static String compact(String text) {
        return VoiceCommandRouter.normalize(text).replace("叮当", "").replace("小叮", "")
                .replace("小丁", "").replace("请", "");
    }

    private static boolean isCommandPhrase(String text) {
        return text != null && text.length() > 0 && text.length() <= MAX_COMMAND_CHARS;
    }

    private static boolean matches(String text, String[] phrases) {
        for (String phrase : phrases) {
            if (text.equals(phrase) || text.startsWith(phrase) || text.endsWith(phrase)) return true;
        }
        return false;
    }

    private static boolean contains(String text, String[] phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }
}
