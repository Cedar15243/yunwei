import { useMemo, useState } from "react";
import {
  Pressable,
  ScrollView,
  Text,
  useWindowDimensions,
  View,
} from "react-native";

type PercentValue = `${number}%`;
type ResultType =
  | "ready"
  | "uploading"
  | "recording_voice"
  | "transcribing_voice"
  | "ai_analyzing"
  | "instruction"
  | "recognition_problem"
  | "network_error"
  | "remote_probe"
  | "completed"
  | "human_suggested";

type FeedbackCode =
  | "wrong_target"
  | "unclear_photo"
  | "insufficient_info"
  | "voice_unclear"
  | "image_voice_conflict"
  | "ai_unavailable"
  | "network_error"
  | null;

type Tone = "blue" | "green" | "yellow" | "red";
type ActionKey = "capture" | "voice" | "retake";
type TextOverflowMode = "single" | "paged";

type HudState = {
  id: string;
  resultType: ResultType;
  feedbackCode: FeedbackCode;
  step: string;
  displayTitle: string;
  displayText: string;
  fullText?: string;
  displayPages?: string[];
  textOverflowMode?: TextOverflowMode;
  displayHint: string;
  safeCommandKey?: string;
  humanEscalationSuggestion: boolean;
  tone: Tone;
  activeAction: ActionKey;
  statusText: string;
};

const hudStates: HudState[] = [
  {
    id: "ready",
    resultType: "ready",
    feedbackCode: null,
    step: "locate_server",
    displayTitle: "对准服务器本地控制台",
    displayText: "中心点击拍照，AI 会识别画面并给出下一步安全指令",
    displayHint: "把服务器控制台文字放入绿色框内",
    humanEscalationSuggestion: false,
    tone: "green",
    activeAction: "capture",
    statusText: "相机已就绪。请保持 25-35cm 距离，避免反光。",
  },
  {
    id: "uploading",
    resultType: "uploading",
    feedbackCode: null,
    step: "photo_uploading",
    displayTitle: "照片上传中",
    displayText: "请保持画面稳定，正在把图片交给 AI 识别",
    displayHint: "请稍等，不要重复点击。",
    humanEscalationSuggestion: false,
    tone: "blue",
    activeAction: "capture",
    statusText: "照片正在上传。请不要移动眼镜或重复点击。",
  },
  {
    id: "recording-voice",
    resultType: "recording_voice",
    feedbackCode: null,
    step: "recording_voice",
    displayTitle: "正在录音",
    displayText: "请用一句短话说明你刚刚做了什么",
    displayHint: "松开后开始转文字。",
    humanEscalationSuggestion: false,
    tone: "blue",
    activeAction: "voice",
    statusText: "正在记录现场语音。松开后开始转文字。",
  },
  {
    id: "transcribing-voice",
    resultType: "transcribing_voice",
    feedbackCode: null,
    step: "transcribing_voice",
    displayTitle: "语音转文字中",
    displayText: "自部署语音模型正在识别你的话",
    displayHint: "请稍等。",
    humanEscalationSuggestion: false,
    tone: "blue",
    activeAction: "voice",
    statusText: "语音已提交。正在生成文字结果。",
  },
  {
    id: "ai-analyzing",
    resultType: "ai_analyzing",
    feedbackCode: null,
    step: "ai_analyzing",
    displayTitle: "AI 综合分析中",
    displayText: "图片和文字已交给主 AI，正在生成现场指导",
    displayHint: "请不要移动画面。",
    humanEscalationSuggestion: false,
    tone: "blue",
    activeAction: "capture",
    statusText: "请保持当前画面，等待下一步指令。",
  },
  {
    id: "instruction-ssh-status",
    resultType: "instruction",
    feedbackCode: null,
    step: "run_diagnostic_command",
    displayTitle: "请输入检查命令",
    displayText: "sudo systemctl status ssh --no-pager",
    displayHint: "输入完成后，中心点击拍摄输出结果。",
    safeCommandKey: "ssh_status",
    humanEscalationSuggestion: false,
    tone: "green",
    activeAction: "capture",
    statusText: "AI 已返回状态检查命令。输入完成后中心点击拍摄输出结果。",
  },
  {
    id: "instruction-ssh-start",
    resultType: "instruction",
    feedbackCode: null,
    step: "run_recovery_command",
    displayTitle: "请输入恢复命令",
    displayText: "sudo systemctl start ssh",
    displayHint: "输入完成后，中心点击拍摄输出结果。",
    safeCommandKey: "ssh_start",
    humanEscalationSuggestion: false,
    tone: "green",
    activeAction: "capture",
    statusText: "AI 已返回下一步。输入完成后中心点击拍摄输出结果。",
  },
  {
    id: "instruction-long-guidance",
    resultType: "instruction",
    feedbackCode: null,
    step: "run_recovery_command",
    displayTitle: "按顺序检查并恢复 SSH",
    displayText: "先确认屏幕上是否仍显示 ssh 服务 inactive。",
    fullText:
      "先确认屏幕上是否仍显示 ssh 服务 inactive。如果是，请输入允许列表中的恢复命令 sudo systemctl start ssh。命令执行后不要关闭当前终端，等待新的输出稳定后再拍照。若出现权限提示，请长按中心说明你看到的提示文字。",
    displayPages: [
      "先确认屏幕上是否仍显示 ssh 服务 inactive。",
      "如果是，请输入允许列表中的恢复命令：sudo systemctl start ssh",
      "命令执行后不要关闭当前终端，等待新的输出稳定后再拍照。",
      "若出现权限提示，请长按中心说明你看到的提示文字。",
    ],
    textOverflowMode: "paged",
    displayHint: "中心点击先翻页，最后一页再拍照。",
    safeCommandKey: "ssh_start",
    humanEscalationSuggestion: false,
    tone: "green",
    activeAction: "capture",
    statusText: "AI 返回了较长指导。请按页读完，最后一页再执行下一步。",
  },
  {
    id: "wrong-target",
    resultType: "recognition_problem",
    feedbackCode: "wrong_target",
    step: "needs_better_photo",
    displayTitle: "拍错目标",
    displayText: "当前画面不是服务器本地控制台",
    displayHint: "请只拍登录界面、黑底终端或命令输出。",
    humanEscalationSuggestion: false,
    tone: "yellow",
    activeAction: "retake",
    statusText: "请重新对准登录界面、黑底终端或命令输出。",
  },
  {
    id: "unclear-photo",
    resultType: "recognition_problem",
    feedbackCode: "unclear_photo",
    step: "needs_better_photo",
    displayTitle: "照片不清晰",
    displayText: "AI 看不清屏幕文字，无法可靠判断",
    displayHint: "靠近屏幕，避免反光，把文字放进绿色框。",
    humanEscalationSuggestion: false,
    tone: "yellow",
    activeAction: "retake",
    statusText: "请靠近屏幕，避免反光，把文字放入绿色框。",
  },
  {
    id: "insufficient-info",
    resultType: "recognition_problem",
    feedbackCode: "insufficient_info",
    step: "needs_more_context",
    displayTitle: "信息不足",
    displayText: "当前画面缺少完整命令输出",
    displayHint: "请拍完整终端内容后重试。",
    humanEscalationSuggestion: false,
    tone: "yellow",
    activeAction: "retake",
    statusText: "请拍完整终端内容，让 AI 看到上下文。",
  },
  {
    id: "voice-unclear",
    resultType: "recognition_problem",
    feedbackCode: "voice_unclear",
    step: "voice_unclear",
    displayTitle: "语音不清楚",
    displayText: "AI 没听清你的补充说明",
    displayHint: "请重新长按，说短一点。",
    humanEscalationSuggestion: false,
    tone: "yellow",
    activeAction: "voice",
    statusText: "请重新长按中心，说短一点、慢一点。",
  },
  {
    id: "network-error",
    resultType: "network_error",
    feedbackCode: "network_error",
    step: "network_error",
    displayTitle: "网络连接失败",
    displayText: "暂时连接不到 AI 运维服务",
    displayHint: "确认网络后，中心点击重试。",
    humanEscalationSuggestion: false,
    tone: "red",
    activeAction: "capture",
    statusText: "请确认网络后中心点击重试。",
  },
  {
    id: "remote-probe",
    resultType: "remote_probe",
    feedbackCode: null,
    step: "verify_remote_access",
    displayTitle: "正在远程复测",
    displayText: "后台正在检测 SSH 是否恢复",
    displayHint: "请稍等，不要重复输入命令。",
    humanEscalationSuggestion: false,
    tone: "blue",
    activeAction: "capture",
    statusText: "请稍等，不要重复输入命令。",
  },
  {
    id: "completed",
    resultType: "completed",
    feedbackCode: null,
    step: "completed",
    displayTitle: "SSH 已恢复",
    displayText: "本次远程访问恢复完成",
    displayHint: "可以停止操作，等待下一次任务。",
    humanEscalationSuggestion: false,
    tone: "green",
    activeAction: "capture",
    statusText: "本次会话完成。可以停止操作，等待下一次任务。",
  },
  {
    id: "human-suggested",
    resultType: "human_suggested",
    feedbackCode: null,
    step: "needs_human_expert",
    displayTitle: "建议转人工",
    displayText: "当前情况可能超出安全自动指导范围",
    displayHint: "AI 只是建议，是否转人工由你决定。",
    humanEscalationSuggestion: true,
    tone: "red",
    activeAction: "voice",
    statusText: "AI 只是建议，是否转人工由你决定。",
  },
];

const palette: Record<Tone, string> = {
  blue: "#5EC7FF",
  green: "#5EFFAD",
  yellow: "#FFD166",
  red: "#FF5D73",
};

const actionItems: Array<{
  key: ActionKey;
  number: string;
  title: string;
  detail: string;
}> = [
  {
    key: "capture",
    number: "1",
    title: "中心点击",
    detail: "拍照 / 下一步",
  },
  {
    key: "voice",
    number: "2",
    title: "长按中心",
    detail: "语音确认 / 补充说明",
  },
  {
    key: "retake",
    number: "3",
    title: "返回键",
    detail: "重拍 / 返回上一步",
  },
];

export default function Air3OpsHud() {
  const [stateIndex, setStateIndex] = useState(0);
  const [pageIndexByState, setPageIndexByState] = useState<
    Record<string, number>
  >({});
  const { width, height } = useWindowDimensions();
  const state = hudStates[stateIndex];
  const accent = palette[state.tone];
  const displayPages =
    state.textOverflowMode === "paged" && state.displayPages?.length
      ? state.displayPages
      : [state.displayText];
  const currentPageIndex = Math.min(
    pageIndexByState[state.id] ?? 0,
    displayPages.length - 1,
  );
  const currentPageText = displayPages[currentPageIndex] ?? state.displayText;
  const pageLabel =
    displayPages.length > 1
      ? `第 ${currentPageIndex + 1}/${displayPages.length} 页`
      : null;

  const frameStyle = useMemo(() => {
    const availableWidth = Math.max(320, width - 32);
    const availableHeight = Math.max(180, height - 32);
    const byWidth = availableWidth;
    const byHeight = availableHeight * (16 / 9);
    const frameWidth = Math.min(byWidth, byHeight);
    const frameHeight = frameWidth * (9 / 16);
    return {
      width: frameWidth,
      height: frameHeight,
    };
  }, [height, width]);

  function moveNext() {
    if (currentPageIndex < displayPages.length - 1) {
      setPageIndexByState((current) => ({
        ...current,
        [state.id]: currentPageIndex + 1,
      }));
      return;
    }
    setStateIndex((current) => {
      const next = (current + 1) % hudStates.length;
      setPageIndexByState((pages) => ({
        ...pages,
        [hudStates[next].id]: 0,
      }));
      return next;
    });
  }

  function moveToVoice() {
    setPageIndexByState((pages) => ({
      ...pages,
      [hudStates[2].id]: 0,
    }));
    setStateIndex(2);
  }

  function moveToRetake() {
    const current = hudStates[stateIndex];
    if (currentPageIndex > 0) {
      setPageIndexByState((pages) => ({
        ...pages,
        [current.id]: currentPageIndex - 1,
      }));
      return;
    }
    if (current.feedbackCode === "voice_unclear") {
      setPageIndexByState((pages) => ({
        ...pages,
        [hudStates[2].id]: 0,
      }));
      setStateIndex(2);
      return;
    }
    setPageIndexByState((pages) => ({
      ...pages,
      [hudStates[0].id]: 0,
    }));
    setStateIndex(0);
  }

  function handleAction(action: ActionKey) {
    if (action === "voice") {
      moveToVoice();
      return;
    }
    if (action === "retake") {
      moveToRetake();
      return;
    }
    moveNext();
  }

  return (
    <ScrollView
      contentInsetAdjustmentBehavior="automatic"
      contentContainerStyle={{
        flexGrow: 1,
        alignItems: "center",
        justifyContent: "center",
        backgroundColor: "#02090B",
        padding: 16,
      }}
    >
      <Pressable
        onPress={moveNext}
        onLongPress={moveToVoice}
        delayLongPress={650}
        style={[
          frameStyle,
          {
            overflow: "hidden",
            borderWidth: 1,
            borderColor: "rgba(94, 255, 173, 0.22)",
            backgroundColor: "#02090B",
          },
        ]}
      >
        <PreviewBackdrop />
        <TopTaskBar />
        <GuideFrame />
        <InstructionPanel
          state={state}
          accent={accent}
          pageText={currentPageText}
          pageLabel={pageLabel}
        />
        <Crosshair accent={accent} />
        <GuideNote />
        <StatusRow statusText={state.statusText} />
        <OperationBar
          activeAction={state.activeAction}
          accent={accent}
          onAction={handleAction}
        />
      </Pressable>
    </ScrollView>
  );
}

function TopTaskBar() {
  return (
    <View
      pointerEvents="none"
      style={{
        position: "absolute",
        top: 0,
        left: "1.55%",
        right: "1.55%",
        height: "6.45%",
        borderWidth: 1,
        borderTopWidth: 0,
        borderColor: "rgba(94, 255, 173, 0.48)",
        borderBottomLeftRadius: 4,
        borderBottomRightRadius: 4,
        backgroundColor: "rgba(3, 11, 13, 0.70)",
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "space-between",
        paddingHorizontal: "1.6%",
      }}
    >
      <Text
        selectable
        adjustsFontSizeToFit
        numberOfLines={1}
        minimumFontScale={0.72}
        style={{
          color: "#F1FFF8",
          fontSize: 22,
          fontWeight: "800",
          flex: 1,
        }}
      >
        叮当X AI 运维眼镜
      </Text>
      <Text
        selectable
        adjustsFontSizeToFit
        numberOfLines={1}
        minimumFontScale={0.72}
        style={{
          color: "#66FFAD",
          fontSize: 15,
          fontWeight: "800",
          textAlign: "right",
          flex: 1,
        }}
      >
        服务器 SSH 恢复
      </Text>
    </View>
  );
}

function GuideFrame() {
  return (
    <View
      pointerEvents="none"
      style={{
        position: "absolute",
        left: "14%",
        top: "21.9%",
        width: "72%",
        height: "52.2%",
        borderWidth: 2,
        borderColor: "#5EFFAD",
        borderRadius: 8,
      }}
    />
  );
}

function InstructionPanel({
  state,
  accent,
  pageText,
  pageLabel,
}: {
  state: HudState;
  accent: string;
  pageText: string;
  pageLabel: string | null;
}) {
  const isCommand =
    state.resultType === "instruction" && pageText.trim().startsWith("sudo ");

  return (
    <View
      pointerEvents="none"
      style={{
        position: "absolute",
        left: "21%",
        top: "31.4%",
        width: "58%",
        minHeight: "17.4%",
        maxHeight: "28%",
        alignItems: "center",
        justifyContent: "center",
        paddingHorizontal: "2.5%",
        paddingVertical: "1.8%",
        borderWidth: 1,
        borderColor: "rgba(94, 255, 173, 0.42)",
        borderRadius: 5,
        backgroundColor: "rgba(2, 10, 12, 0.75)",
      }}
    >
      <Text
        selectable
        adjustsFontSizeToFit
        numberOfLines={2}
        minimumFontScale={0.7}
        style={{
          color: accent,
          fontSize: 28,
          fontWeight: "900",
          lineHeight: 34,
          textAlign: "center",
        }}
      >
        {state.displayTitle}
      </Text>
      {pageLabel ? (
        <Text
          selectable
          adjustsFontSizeToFit
          numberOfLines={1}
          minimumFontScale={0.72}
          style={{
            color: "rgba(215, 232, 226, 0.82)",
            fontSize: 11,
            fontWeight: "800",
            lineHeight: 15,
            marginTop: 5,
            textAlign: "center",
          }}
        >
          {pageLabel}
        </Text>
      ) : null}
      <Text
        selectable
        adjustsFontSizeToFit
        numberOfLines={isCommand ? 1 : 4}
        minimumFontScale={0.58}
        style={{
          color: "#DCEBE6",
          fontFamily: isCommand ? "monospace" : undefined,
          fontSize: isCommand ? 22 : 14,
          fontWeight: isCommand ? "800" : "600",
          lineHeight: isCommand ? 28 : 20,
          marginTop: pageLabel ? 6 : 10,
          textAlign: "center",
        }}
      >
        {pageText}
      </Text>
    </View>
  );
}

function Crosshair({ accent }: { accent: string }) {
  return (
    <View
      pointerEvents="none"
      style={{
        position: "absolute",
        left: "50%",
        top: "48.1%",
        width: 1,
        height: "5.4%",
        backgroundColor: accent,
      }}
    >
      <View
        style={{
          position: "absolute",
          left: -55,
          top: 0,
          width: 110,
          height: 1,
          backgroundColor: "rgba(94, 255, 173, 0.24)",
        }}
      />
    </View>
  );
}

function GuideNote() {
  return (
    <Text
      selectable
      adjustsFontSizeToFit
      numberOfLines={1}
      minimumFontScale={0.72}
      style={{
        position: "absolute",
        left: "10%",
        right: "10%",
        top: "75.8%",
        color: "#D7E8E2",
        fontSize: 14,
        fontWeight: "800",
        textAlign: "center",
      }}
    >
      把服务器控制台文字放入绿色框内
    </Text>
  );
}

function StatusRow({ statusText }: { statusText: string }) {
  return (
    <View
      pointerEvents="none"
      style={{
        position: "absolute",
        left: "3.2%",
        right: "3.2%",
        top: "81.8%",
        flexDirection: "row",
        gap: 16,
        justifyContent: "space-between",
      }}
    >
      <Text
        selectable
        adjustsFontSizeToFit
        numberOfLines={1}
        minimumFontScale={0.62}
        style={{
          color: "#D5E4E0",
          flex: 1,
          fontSize: 11,
          lineHeight: 14,
        }}
      >
        状态：{statusText}
      </Text>
      <Text
        selectable
        adjustsFontSizeToFit
        numberOfLines={1}
        minimumFontScale={0.62}
        style={{
          color: "rgba(190, 208, 203, 0.72)",
          flex: 1,
          fontSize: 10,
          lineHeight: 14,
          textAlign: "right",
        }}
      >
        调试信息仅写入日志，不显示给现场人员
      </Text>
    </View>
  );
}

function OperationBar({
  activeAction,
  accent,
  onAction,
}: {
  activeAction: ActionKey;
  accent: string;
  onAction: (action: ActionKey) => void;
}) {
  return (
    <View
      style={{
        position: "absolute",
        left: "1.55%",
        right: "1.55%",
        bottom: "2.35%",
        height: "13.3%",
        borderWidth: 1,
        borderColor: "rgba(180, 220, 208, 0.18)",
        borderRadius: 4,
        backgroundColor: "rgba(1, 7, 9, 0.68)",
        flexDirection: "row",
        gap: "3.2%",
        alignItems: "center",
        paddingHorizontal: "4.5%",
        paddingVertical: "1.4%",
      }}
    >
      {actionItems.map((action) => {
        const active = activeAction === action.key;
        return (
          <Pressable
            key={action.key}
            onPress={() => onAction(action.key)}
            style={{
              flex: 1,
              minWidth: 0,
              height: "100%",
              borderWidth: 1,
              borderColor: active ? accent : "rgba(94, 255, 173, 0.18)",
              borderRadius: 4,
              backgroundColor: active
                ? "rgba(94, 255, 173, 0.08)"
                : "rgba(5, 20, 22, 0.58)",
              flexDirection: "row",
              alignItems: "center",
              gap: 10,
              paddingHorizontal: 12,
            }}
          >
            <View
              style={{
                width: 28,
                height: 28,
                borderWidth: 2,
                borderColor: accent,
                borderRadius: 14,
                alignItems: "center",
                justifyContent: "center",
                flexShrink: 0,
              }}
            >
              <Text
                selectable
                style={{
                  color: accent,
                  fontSize: 13,
                  fontWeight: "900",
                }}
              >
                {action.number}
              </Text>
            </View>
            <View style={{ flex: 1, minWidth: 0 }}>
              <Text
                selectable
                adjustsFontSizeToFit
                numberOfLines={1}
                minimumFontScale={0.7}
                style={{
                  color: "#F4FFFB",
                  fontSize: 13,
                  fontWeight: "900",
                }}
              >
                {action.title}
              </Text>
              <Text
                selectable
                adjustsFontSizeToFit
                numberOfLines={1}
                minimumFontScale={0.68}
                style={{
                  color: "#9BBAB0",
                  fontSize: 11,
                  fontWeight: "600",
                  marginTop: 2,
                }}
              >
                {action.detail}
              </Text>
            </View>
          </Pressable>
        );
      })}
    </View>
  );
}

function PreviewBackdrop() {
  const rows: Array<{ top: PercentValue; width: PercentValue; hot?: boolean }> = [
    { top: "9.6%", width: "28%", hot: true },
    { top: "16.1%", width: "33%" },
    { top: "22.6%", width: "38%" },
    { top: "29.1%", width: "44%" },
    { top: "35.6%", width: "48%" },
    { top: "42.1%", width: "52%", hot: true },
    { top: "48.6%", width: "28%" },
    { top: "55.1%", width: "33%" },
    { top: "61.6%", width: "38%" },
    { top: "68.1%", width: "44%", hot: true },
  ];

  return (
    <View
      pointerEvents="none"
      style={{
        position: "absolute",
        top: 0,
        right: 0,
        bottom: 0,
        left: 0,
        backgroundColor: "#02090B",
      }}
    >
      <View
        style={{
          position: "absolute",
          top: 0,
          right: 0,
          bottom: 0,
          left: 0,
          backgroundColor: "#061013",
        }}
      />
      {rows.map((row, index) => (
        <View
          key={`${row.top}-${index}`}
          style={{
            position: "absolute",
            left: "7.7%",
            top: row.top,
            width: row.width,
            height: 3,
            backgroundColor: row.hot ? "#267C59" : "#26383F",
            opacity: row.hot ? 0.92 : 0.78,
          }}
        />
      ))}
    </View>
  );
}
