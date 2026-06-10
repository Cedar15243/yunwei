import { useEffect, useMemo, useRef, useState } from "react";
import {
  Pressable,
  ScrollView,
  Text,
  useWindowDimensions,
  View,
} from "react-native";

type VoiceState = "idle" | "listening" | "partial" | "final" | "analyzing";

type ChatMessage = {
  id: string;
  role: "assistant" | "user";
  kind: "text" | "image";
  text: string;
  streaming?: boolean;
};

type ProjectThread = {
  id: string;
  title: string;
  updatedAt: string;
  messages: ChatMessage[];
};

const initialProjects: ProjectThread[] = [
  {
    id: "field-diagnosis",
    title: "现场诊断",
    updatedAt: "刚刚",
    messages: [
      {
        id: "welcome",
        role: "assistant",
        kind: "text",
        text: "先拍一张现场图，再点语音说明问题。我会边听边转文字，听清后自动分析。",
      },
    ],
  },
  {
    id: "network-room",
    title: "机房网络",
    updatedAt: "12 分钟前",
    messages: [
      {
        id: "history-1",
        role: "assistant",
        kind: "text",
        text: "继续上次网络排查时，可以直接补拍现场图并说当前现象。",
      },
    ],
  },
];

const partialSamples = [
  "这个屏幕",
  "这个屏幕上有一个告警",
  "这个屏幕上有一个告警，下一步怎么处理",
];

const aiResponse =
  "我看到了现场图和你的描述。先不要重复操作，优先确认告警时间、设备编号和当前状态；如果告警仍在刷新，先拍一张更近的局部图，我会继续判断下一步。";

export default function DingdangOpsAiPrototype() {
  const { width } = useWindowDimensions();
  const compact = width < 900;
  const [projects, setProjects] = useState<ProjectThread[]>(initialProjects);
  const [activeProjectId, setActiveProjectId] = useState(initialProjects[0].id);
  const [hasPhoto, setHasPhoto] = useState(false);
  const [draftText, setDraftText] = useState("");
  const [voiceState, setVoiceState] = useState<VoiceState>("idle");
  const [partialIndex, setPartialIndex] = useState(0);
  const streamTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const activeProject = useMemo(
    () => projects.find((project) => project.id === activeProjectId) ?? projects[0],
    [activeProjectId, projects],
  );

  useEffect(() => {
    return () => {
      if (streamTimer.current) {
        clearInterval(streamTimer.current);
      }
    };
  }, []);

  function updateActiveProject(update: (project: ProjectThread) => ProjectThread) {
    setProjects((current) =>
      current.map((project) =>
        project.id === activeProjectId ? update(project) : project,
      ),
    );
  }

  function appendMessage(message: ChatMessage) {
    updateActiveProject((project) => ({
      ...project,
      updatedAt: "刚刚",
      messages: [...project.messages, message],
    }));
  }

  function handleCameraPress() {
    setHasPhoto(true);
    appendMessage({
      id: `image-${Date.now()}`,
      role: "user",
      kind: "image",
      text: "照片已在输入框，等语音问题一起发送。",
    });
  }

  function handleVoicePress() {
    if (voiceState === "listening" || voiceState === "partial") {
      autoSendFinalTranscript();
      return;
    }
    setDraftText("");
    setPartialIndex(0);
    setVoiceState("listening");
    simulatePartialTranscript();
  }

  function simulatePartialTranscript() {
    if (streamTimer.current) {
      clearInterval(streamTimer.current);
    }
    streamTimer.current = setInterval(() => {
      setPartialIndex((current) => {
        const next = Math.min(current + 1, partialSamples.length);
        const partial = partialSamples[next - 1] ?? partialSamples[0];
        setDraftText(partial);
        setVoiceState("partial");
        if (next >= partialSamples.length && streamTimer.current) {
          clearInterval(streamTimer.current);
          streamTimer.current = null;
        }
        return next;
      });
    }, 650);
  }

  function autoSendFinalTranscript() {
    if (streamTimer.current) {
      clearInterval(streamTimer.current);
      streamTimer.current = null;
    }
    const finalText = draftText.trim() || partialSamples[partialSamples.length - 1];
    setDraftText(finalText);
    setVoiceState("final");
    setTimeout(() => {
      handleSend(finalText);
    }, 260);
  }

  function handleSend(overrideText?: string) {
    const finalText = (overrideText ?? draftText).trim();
    if (!hasPhoto) {
      setDraftText("请先拍照");
      setVoiceState("idle");
      return;
    }
    if (!finalText || finalText === "请先拍照") {
      setDraftText("请先说问题");
      setVoiceState("idle");
      return;
    }
    setVoiceState("analyzing");
    appendMessage({
      id: `text-${Date.now()}`,
      role: "user",
      kind: "text",
      text: finalText,
    });
    appendMessage({
      id: `ai-${Date.now()}`,
      role: "assistant",
      kind: "text",
      text: aiResponse,
      streaming: true,
    });
    setDraftText("");
    setHasPhoto(false);
    setTimeout(() => {
      updateActiveProject((project) => ({
        ...project,
        messages: project.messages.map((message) =>
          message.streaming ? { ...message, streaming: false } : message,
        ),
      }));
      setVoiceState("idle");
    }, 1200);
  }

  function createNewProject() {
    const project: ProjectThread = {
      id: `project-${Date.now()}`,
      title: "现场诊断",
      updatedAt: "刚刚",
      messages: [
        {
          id: `welcome-${Date.now()}`,
          role: "assistant",
          kind: "text",
          text: "新项目已创建。拍照后直接说问题，系统会自动发送分析。",
        },
      ],
    };
    setProjects((current) => [project, ...current]);
    setActiveProjectId(project.id);
    setHasPhoto(false);
    setDraftText("");
    setVoiceState("idle");
  }

  const stateText =
    voiceState === "listening"
      ? "正在听"
      : voiceState === "partial"
        ? "实时转写"
        : voiceState === "final"
          ? "已听清，自动发送"
          : voiceState === "analyzing"
            ? "AI 正在生成"
            : "在线";

  return (
    <View
      style={{
        flex: 1,
        flexDirection: compact ? "column" : "row",
        backgroundColor: "#ffffff",
      }}
    >
      <View
        style={{
          width: compact ? "100%" : 292,
          borderRightWidth: compact ? 0 : 1,
          borderBottomWidth: compact ? 1 : 0,
          borderColor: "#e6e6e6",
          padding: 12,
          gap: 12,
          backgroundColor: "#f8f8f8",
        }}
      >
        <Text style={{ fontSize: 20, fontWeight: "700", color: "#111" }}>
          会话记录
        </Text>
        <Pressable
          onPress={createNewProject}
          style={{
            minHeight: 52,
            justifyContent: "center",
            paddingHorizontal: 12,
            borderRadius: 8,
            backgroundColor: "#ffffff",
            borderWidth: 1,
            borderColor: "#dcdcdc",
          }}
        >
          <Text style={{ fontSize: 16, color: "#111" }}>新建项目</Text>
        </Pressable>
        <ScrollView contentContainerStyle={{ gap: 8 }}>
          {projects.map((project) => (
            <Pressable
              key={project.id}
              onPress={() => setActiveProjectId(project.id)}
              style={{
                padding: 10,
                minHeight: 60,
                justifyContent: "center",
                borderRadius: 8,
                backgroundColor:
                  project.id === activeProjectId ? "#ffffff" : "transparent",
                borderWidth: project.id === activeProjectId ? 1 : 0,
                borderColor: "#d8d8d8",
              }}
            >
              <Text style={{ fontSize: 15, fontWeight: "600", color: "#111" }}>
                {project.title}
              </Text>
              <Text style={{ fontSize: 12, color: "#767676", marginTop: 4 }}>
                {project.updatedAt}
              </Text>
            </Pressable>
          ))}
        </ScrollView>
      </View>

      <View style={{ flex: 1, padding: 14, gap: 10 }}>
        <View
          style={{
            minHeight: 48,
            flexDirection: "row",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 12,
          }}
        >
          <Text
            style={{ flex: 1, fontSize: 19, fontWeight: "700", color: "#111" }}
            numberOfLines={1}
          >
            叮当运维AI · 当前项目 · {activeProject.title}
          </Text>
          <Text style={{ fontSize: 15, color: "#555" }}>{stateText}</Text>
        </View>

        <ScrollView
          style={{ flex: 1 }}
          contentContainerStyle={{ gap: 10, paddingVertical: 6 }}
        >
          {activeProject.messages.map((message) => {
            const user = message.role === "user";
            return (
              <View
                key={message.id}
                style={{
                  alignItems: user ? "flex-end" : "flex-start",
                }}
              >
                <Text
                  selectable
                  style={{
                    maxWidth: "78%",
                    paddingHorizontal: 16,
                    paddingVertical: 12,
                    borderRadius: 8,
                    backgroundColor: user ? "#ebf5ff" : "#f3f3f3",
                    color: "#171717",
                    fontSize: 17,
                    lineHeight: 25,
                  }}
                >
                  {message.text}
                  {message.streaming ? "▌" : ""}
                </Text>
              </View>
            );
          })}
        </ScrollView>

        <View
          style={{
            minHeight: 92,
            borderTopWidth: 1,
            borderColor: "#eeeeee",
            flexDirection: "row",
            alignItems: "center",
            gap: 10,
            paddingTop: 10,
          }}
        >
          <Pressable
            accessibilityLabel="拍照"
            onPress={handleCameraPress}
            style={toolButtonStyle}
          >
            <Text style={toolButtonText}>+</Text>
          </Pressable>
          <View style={{ flex: 1, minHeight: 56, justifyContent: "center" }}>
            {hasPhoto ? (
              <Text style={{ color: "#3d6b45", fontSize: 14 }}>
                照片已在输入框
              </Text>
            ) : null}
            <Text
              selectable
              style={{
                color: draftText ? "#111" : "#8a8a8a",
                fontSize: 17,
                lineHeight: 24,
              }}
            >
              {draftText || "点语音开始说问题"}
            </Text>
          </View>
          <Pressable
            accessibilityLabel="语音"
            onPress={handleVoicePress}
            style={[
              toolButtonStyle,
              {
                backgroundColor:
                  voiceState === "listening" || voiceState === "partial"
                    ? "#111111"
                    : "#ffffff",
              },
            ]}
          >
            <Text
              style={[
                toolButtonText,
                {
                  color:
                    voiceState === "listening" || voiceState === "partial"
                      ? "#ffffff"
                      : "#111111",
                },
              ]}
            >
              {voiceState === "listening" || voiceState === "partial" ? "■" : "●"}
            </Text>
          </Pressable>
          <Pressable
            accessibilityLabel="发送"
            onPress={() => handleSend()}
            style={toolButtonStyle}
          >
            <Text style={toolButtonText}>↑</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

const toolButtonStyle = {
  width: 58,
  height: 58,
  borderRadius: 8,
  borderWidth: 1,
  borderColor: "#d7d7d7",
  alignItems: "center" as const,
  justifyContent: "center" as const,
  backgroundColor: "#ffffff",
};

const toolButtonText = {
  color: "#111111",
  fontSize: 28,
  fontWeight: "700" as const,
  lineHeight: 30,
};
