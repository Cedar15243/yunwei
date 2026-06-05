import { useMemo, useState } from "react";
import {
  Pressable,
  ScrollView,
  Text,
  useWindowDimensions,
  View,
} from "react-native";

type Tone = "blue" | "green" | "yellow" | "red";

type Step = {
  id: string;
  status: string;
  headline: string;
  subline: string;
  bottom: string;
  tone: Tone;
  command?: string;
  voice?: string;
};

const steps: Step[] = [
  {
    id: "start",
    status: "等待开始",
    headline: "AI 运维眼镜",
    subline: "请对准服务器本地控制台\n把文字放进绿色取景框",
    bottom: "单击：拍照/下一步    长按：语音    返回键：重拍",
    tone: "blue",
    voice: "语音：按住说话",
  },
  {
    id: "capture-console",
    status: "请拍摄",
    headline: "拍控制台屏幕",
    subline: "靠近屏幕并避免反光\n只拍服务器控制台或终端窗口",
    bottom: "单击：拍照上传    长按：语音    返回键：重拍",
    tone: "blue",
    voice: "语音：按住说话",
  },
  {
    id: "analyzing",
    status: "AI 分析中",
    headline: "正在识别...",
    subline: "正在读取取景框内的控制台文字",
    bottom: "请保持画面稳定，不要重复点击",
    tone: "yellow",
  },
  {
    id: "diagnostic-command",
    status: "诊断命令",
    headline: "请输入命令",
    subline: "只输入下方这一条安全命令",
    command: "sudo systemctl status ssh --no-pager",
    bottom: "执行后单击拍摄完整输出",
    tone: "green",
    voice: "语音：按住说话",
  },
  {
    id: "recovery-command",
    status: "恢复命令",
    headline: "SSH 服务未运行",
    subline: "请输入下方安全命令",
    command: "sudo systemctl start ssh",
    bottom: "执行后单击拍摄完整输出",
    tone: "green",
  },
  {
    id: "unclear",
    status: "照片不清晰",
    headline: "重新拍摄",
    subline: "靠近屏幕，避免反光",
    bottom: "单击：重拍    长按：语音    返回键：重拍",
    tone: "red",
    voice: "语音：按住说话",
  },
  {
    id: "probe",
    status: "远程复测",
    headline: "正在复测 SSH",
    subline: "后台正在检测 SSH 远程访问",
    bottom: "请等待结果",
    tone: "yellow",
  },
  {
    id: "new-issue",
    status: "复测分诊",
    headline: "发现新问题",
    subline: "SSH 已恢复，但应用端口不可达",
    bottom: "单击：继续排查    长按：转人工",
    tone: "yellow",
    voice: "语音：按住说话",
  },
  {
    id: "done",
    status: "完成",
    headline: "SSH 已恢复",
    subline: "服务器可重新远程运维",
    bottom: "单击结束任务",
    tone: "green",
  },
  {
    id: "human",
    status: "人工介入",
    headline: "停止自动指导",
    subline: "请联系运维专家接管",
    bottom: "任务已暂停",
    tone: "red",
  },
];

const palette: Record<Tone, string> = {
  blue: "#59A6FF",
  green: "#57FFB0",
  yellow: "#FFC44F",
  red: "#FF5656",
};

export default function Air3OpsHud() {
  const [stepIndex, setStepIndex] = useState(0);
  const [isVoiceActive, setIsVoiceActive] = useState(false);
  const { width, height } = useWindowDimensions();
  const step = steps[stepIndex];
  const accent = palette[step.tone];

  const frameStyle = useMemo(() => {
    const aspectWidth = Math.min(width - 32, (height - 32) * (16 / 9));
    const aspectHeight = aspectWidth * (9 / 16);
    return {
      width: Math.max(320, aspectWidth),
      height: Math.max(180, aspectHeight),
    };
  }, [height, width]);

  function nextStep() {
    setIsVoiceActive(false);
    setStepIndex((current) => (current + 1) % steps.length);
  }

  function retakeOrEscalate() {
    setIsVoiceActive(false);
    setStepIndex(step.id === "unclear" ? 9 : 5);
  }

  return (
    <ScrollView
      contentInsetAdjustmentBehavior="automatic"
      contentContainerStyle={{
        flexGrow: 1,
        alignItems: "center",
        justifyContent: "center",
        backgroundColor: "#02080C",
        padding: 16,
      }}
    >
      <Pressable
        onPress={nextStep}
        onLongPress={retakeOrEscalate}
        delayLongPress={650}
        style={{
          ...frameStyle,
          overflow: "hidden",
          borderWidth: 2,
          borderColor: "rgba(89, 166, 255, 0.62)",
          backgroundColor: "#061018",
        }}
      >
        <PreviewBackdrop />
        <View
          style={{
            position: "absolute",
            top: 0,
            right: 0,
            bottom: 0,
            left: 0,
            backgroundColor: "rgba(0, 0, 0, 0.50)",
          }}
        />
        <View
          pointerEvents="none"
          style={{
            position: "absolute",
            left: "14%",
            right: "14%",
            top: "27%",
            height: "50%",
            borderWidth: 2,
            borderColor: "#57FFB0",
            borderRadius: 8,
          }}
        >
          <View
            style={{
              position: "absolute",
              left: "50%",
              top: 0,
              bottom: 0,
              width: 1,
              backgroundColor: "rgba(87, 255, 176, 0.72)",
            }}
          />
          <View
            style={{
              position: "absolute",
              top: "50%",
              left: 0,
              right: 0,
              height: 1,
              backgroundColor: "rgba(87, 255, 176, 0.72)",
            }}
          />
        </View>

        <View
          style={{
            position: "absolute",
            top: 24,
            left: 36,
            right: 36,
            alignItems: "center",
          }}
        >
          <Text
            selectable
            style={{
              color: "#EEF4FA",
              fontSize: 24,
              fontWeight: "700",
              textAlign: "center",
            }}
          >
            {step.status}
          </Text>
        </View>

        <View
          style={{
            position: "absolute",
            left: 48,
            right: 48,
            top: step.command ? "27%" : "32%",
            alignItems: "center",
            gap: 16,
          }}
        >
          <Text
            selectable
            adjustsFontSizeToFit
            numberOfLines={2}
            minimumFontScale={0.72}
            style={{
              color: accent,
              fontSize: step.command ? 46 : 54,
              fontWeight: "800",
              textAlign: "center",
            }}
          >
            {step.headline}
          </Text>
          <Text
            selectable
            style={{
              color: "#F1F6FA",
              fontSize: 24,
              fontWeight: "600",
              lineHeight: 34,
              textAlign: "center",
            }}
          >
            {step.subline}
          </Text>
          {step.command ? (
            <View
              style={{
                width: "84%",
                borderWidth: 1,
                borderColor: `${accent}AA`,
                backgroundColor: "rgba(0, 0, 0, 0.72)",
                paddingVertical: 14,
                paddingHorizontal: 20,
                borderRadius: 8,
              }}
            >
              <Text
                selectable
                adjustsFontSizeToFit
                numberOfLines={1}
                minimumFontScale={0.68}
                style={{
                  color: "#57FFB0",
                  fontSize: 23,
                  fontWeight: "700",
                  textAlign: "center",
                  fontVariant: ["tabular-nums"],
                }}
              >
                {step.command}
              </Text>
            </View>
          ) : null}
        </View>

        <View
          style={{
            position: "absolute",
            left: 30,
            right: 30,
            bottom: 24,
            flexDirection: "row",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 16,
          }}
        >
          <Text
            selectable
            adjustsFontSizeToFit
            numberOfLines={1}
            minimumFontScale={0.7}
            style={{ color: "#C4D0DA", fontSize: 23, flex: 1 }}
          >
            {step.bottom}
          </Text>
          {step.voice ? (
            <Pressable
              onPress={() => setIsVoiceActive((value) => !value)}
              style={{
                borderWidth: 1,
                borderColor: isVoiceActive ? "#57FFB0" : "#59A6FF",
                borderRadius: 8,
                paddingHorizontal: 16,
                paddingVertical: 8,
                backgroundColor: isVoiceActive
                  ? "rgba(87, 255, 176, 0.14)"
                  : "rgba(89, 166, 255, 0.12)",
              }}
            >
              <Text
                selectable
                style={{
                  color: isVoiceActive ? "#57FFB0" : "#59A6FF",
                  fontSize: 18,
                  fontWeight: "700",
                }}
              >
                  {isVoiceActive ? "正在听取语音" : "长按语音"}
              </Text>
            </Pressable>
          ) : null}
        </View>
      </Pressable>
    </ScrollView>
  );
}

function PreviewBackdrop() {
  return (
    <View
      style={{
        position: "absolute",
        top: 0,
        right: 0,
        bottom: 0,
        left: 0,
        backgroundColor: "#293632",
      }}
    >
      {Array.from({ length: 8 }).map((_, index) => (
        <View
          key={index}
          style={{
            position: "absolute",
            left: 36 + index * 95,
            top: 74 + (index % 4) * 68,
            width: 190,
            height: 9,
            borderRadius: 5,
            backgroundColor: "rgba(12, 24, 28, 0.48)",
            transform: [{ rotate: index % 2 ? "5deg" : "-8deg" }],
          }}
        />
      ))}
      <View
        style={{
          position: "absolute",
          left: "7%",
          right: "7%",
          bottom: "18%",
          height: 54,
          borderRadius: 8,
          backgroundColor: "rgba(180, 190, 188, 0.10)",
        }}
      />
    </View>
  );
}
