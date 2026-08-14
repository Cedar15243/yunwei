package com.codex.air3nativecamera.features.operations;

import com.codex.air3nativecamera.sync.DeviceMemoryDeviceClient;
import com.codex.air3nativecamera.sync.ExecutionContextDeviceClient;
import com.codex.air3nativecamera.sync.SkillKnowledgeManifestClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/** Maps governed server state onto the existing operation-detail HUD. */
public final class ExecutionContextHudPresenter {
    public OperationDetail loading(String abilityId) {
        return new OperationDetail(
                tag(abilityId),
                title(abilityId),
                "正在读取当前账号、设备和项目授权范围内的真实数据。",
                Collections.singletonList("正在从服务端读取，请稍候。"),
                "",
                "",
                "",
                "");
    }

    public OperationDetail projectCatalog(ExecutionContextDeviceClient.ProjectCatalog catalog) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (catalog != null) {
            for (ExecutionContextDeviceClient.ProjectSummary project : catalog.items()) {
                if (project == null) continue;
                items.add(project.title()
                        + "\n状态：" + projectStatus(project.status())
                        + " · 任务：" + project.taskCount()
                        + " · 进行中：" + project.activeTaskCount()
                        + " · 记忆版本：" + project.memoryRevision());
                actions.add("managed_project_open:" + project.localProjectId());
            }
        }
        String description = items.isEmpty()
                ? "服务端当前没有该设备可访问的项目，不会用本地示例记录代替。"
                : "仅显示服务端授权给当前账号和设备的项目、任务与记忆版本。";
        return new OperationDetail(
                "项目记录",
                "项目记忆",
                description,
                items,
                actions,
                "managed_project_refresh",
                "刷新项目",
                "",
                "");
    }

    public OperationDetail deviceMemoryCatalog(DeviceMemoryDeviceClient.Catalog catalog) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (catalog != null) {
            for (DeviceMemoryDeviceClient.Item item : catalog.items()) {
                if (item == null) continue;
                StringBuilder content = new StringBuilder()
                        .append(item.system()).append(" / ")
                        .append(item.brand()).append(" ").append(item.model())
                        .append("\n\u6570\u91cf\uff1a").append(item.quantity())
                        .append("  \u72b6\u6001\uff1a").append(deviceStatus(item.status()))
                        .append("  \u6545\u969c\uff1a").append(item.faultCount())
                        .append("  \u7ef4\u4fee\uff1a").append(item.repairCount());
                if (!item.keyParameter().isEmpty()) {
                    content.append("\n\u5173\u952e\u53c2\u6570\uff1a").append(item.keyParameter());
                }
                if (item.lastInspectionAt() != null && !item.lastInspectionAt().isEmpty()) {
                    content.append("\n\u6700\u540e\u5de1\u68c0\uff1a").append(item.lastInspectionAt());
                }
                if (item.linkedProjects().isEmpty()) {
                    content.append("\n\u5173\u8054\u9879\u76ee\uff1a\u65e0");
                } else {
                    content.append("\n\u5173\u8054\u9879\u76ee\uff1a");
                    for (DeviceMemoryDeviceClient.ProjectHistory project : item.linkedProjects()) {
                        content.append("\n- ").append(project.title())
                                .append(" / ").append(project.status())
                                .append(" / \u4efb\u52a1 ").append(project.taskCount());
                    }
                }
                items.add(content.toString());
                actions.add("");
            }
        }
        String description = items.isEmpty()
                ? "\u670d\u52a1\u7aef\u5df2\u786e\u8ba4\uff1a\u5f53\u524d\u8bbe\u5907\u6ca1\u6709\u6388\u6743\u8bbe\u5907\uff0c\u4e0d\u663e\u793a\u672c\u5730\u793a\u4f8b\u3002"
                : "\u4ec5\u663e\u793a\u5f53\u524d\u8d26\u53f7\u3001\u8bbe\u5907\u548c\u9879\u76ee\u6388\u6743\u8303\u56f4\u5185\u7684\u771f\u5b9e\u8bbe\u5907\u8bb0\u5fc6\uff0c\u4e0d\u4f7f\u7528\u672c\u5730\u56fa\u5b9a\u6570\u636e\u3002";
        return new OperationDetail(
                "\u8bbe\u5907\u8bb0\u5fc6",
                "\u8bbe\u5907\u8bb0\u5fc6",
                description,
                items,
                actions,
                "managed_device_memory_refresh",
                "\u5237\u65b0\u8bbe\u5907\u8bb0\u5fc6",
                "",
                "");
    }

    public OperationDetail projectDetail(
            ExecutionContextDeviceClient.ProjectDetail detail,
            Set<String> locallyRecoverableTaskIds
    ) {
        if (detail == null) return failure("project_memory", "project_not_found");
        JSONObject payload = detail.toJson();
        JSONObject memory = payload.optJSONObject("projectMemory");
        JSONArray tasks = payload.optJSONArray("tasks");
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        String summary = memory == null ? "" : display(memory.optString("summary", ""));
        int revision = memory == null ? 0 : Math.max(0, memory.optInt("revision", 0));
        items.add((summary.isEmpty() ? "项目摘要：尚未形成" : "项目摘要：" + summary)
                + "\n记忆版本：" + revision);
        actions.add("");
        addArray(items, actions, "已确认事实", memory == null ? null
                : memory.optJSONArray("confirmedFacts"));
        addArray(items, actions, "已排除事实", memory == null ? null
                : memory.optJSONArray("excludedFacts"));
        addArray(items, actions, "风险", memory == null ? null
                : memory.optJSONArray("risks"));

        for (ExecutionContextDeviceClient.ProjectInstruction instruction
                : detail.instructions()) {
            String action = display(instruction.action());
            if (action.isEmpty()) continue;
            items.add("项目指令 v" + instruction.version()
                    + " · " + instructionStatus(instruction.status())
                    + "\n" + action);
            actions.add("managed_project_instruction_open:" + detail.localProjectId()
                    + ":" + instruction.instructionId());
        }

        Set<String> recoverable = locallyRecoverableTaskIds == null
                ? Collections.<String>emptySet() : locallyRecoverableTaskIds;
        for (int index = 0; tasks != null && index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            String localTaskId = identifier(task.optString("localTaskId", ""));
            String status = display(task.optString("status", ""));
            String taskTitle = display(task.optString("title", ""));
            String endSummary = display(task.optString("endSummary", ""));
            String activeSkill = display(task.optString("activeSkillVersionId", ""));
            StringBuilder content = new StringBuilder("任务：")
                    .append(taskTitle.isEmpty() ? localTaskId : taskTitle)
                    .append("\n状态：").append(taskStatus(status));
            if (!activeSkill.isEmpty()) content.append(" · Skill：").append(activeSkill);
            if (!endSummary.isEmpty()) content.append("\n结束摘要：").append(endSummary);
            boolean canResume = !localTaskId.isEmpty()
                    && recoverable.contains(localTaskId)
                    && "active".equalsIgnoreCase(status);
            if (!canResume && !isClosedTask(status)
                    && !"active".equalsIgnoreCase(status)) {
                content.append("\n服务端任务状态不允许恢复，请刷新或联系管理员。");
            } else if (!canResume && !isClosedTask(status)) {
                content.append("\n本机执行记录不可用，当前仅可查看云端记录。");
            }
            items.add(content.toString());
            actions.add(canResume
                    ? "managed_project_resume:" + detail.localProjectId() + ":" + localTaskId
                    : "");
        }

        boolean projectOpen = "active".equalsIgnoreCase(detail.status());
        return new OperationDetail(
                "项目记录",
                detail.title(),
                "项目记忆、指令、任务状态和结束摘要均来自服务端；只有本机仍保留的未完成任务可恢复。",
                items,
                actions,
                projectOpen ? "managed_project_new_task:" + detail.localProjectId() : "",
                projectOpen ? "在此项目新建任务" : "",
                "managed_project_list",
                "返回项目");
    }

    public OperationDetail projectInstructionDetail(
            ExecutionContextDeviceClient.ProjectDetail detail,
            String instructionId
    ) {
        if (detail == null) return failure("project_memory", "project_not_found");
        ExecutionContextDeviceClient.ProjectInstruction instruction =
                detail.instruction(instructionId);
        if (instruction == null) {
            return failure("project_memory", "project_instruction_not_found");
        }
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        addItem(items, actions,
                "版本：v" + instruction.version()
                        + " · 状态：" + instructionStatus(instruction.status()), "");
        addItem(items, actions, "触发条件：" + instructionCondition(instruction.condition()), "");
        addItem(items, actions, "规则内容：" + instruction.action(), "");
        if (!instruction.exceptions().isEmpty()) {
            addItem(items, actions,
                    "例外条件：" + join(instruction.exceptions()), "");
        }
        String identity = detail.localProjectId() + ":" + instruction.instructionId();
        if (!"deleted".equals(instruction.status())) {
            addItem(items, actions,
                    "修改规则\n下一段语音将作为新的完整项目规则，并在确认后生成新版本。",
                    "managed_project_instruction_edit:" + identity);
            if ("active".equals(instruction.status())) {
                addItem(items, actions,
                        "停用规则\n停用后不再进入新的 AI 执行上下文，历史版本保留。",
                        "managed_project_instruction_disable:" + identity);
            } else {
                addItem(items, actions,
                        "重新启用\n确认后从下一轮匹配的 AI 对话重新生效。",
                        "managed_project_instruction_enable:" + identity);
            }
            addItem(items, actions,
                    "删除规则\n写入审计墓碑版本，不物理删除历史记录。",
                    "managed_project_instruction_delete:" + identity);
        }
        return new OperationDetail(
                "项目指令",
                "项目指令 v" + instruction.version(),
                "deleted".equals(instruction.status())
                        ? "该规则已删除，仅保留审计记录，不能再次启用或改版。"
                        : "所有修改都需要人工二次确认；服务端版本冲突时不会覆盖最新规则。",
                items,
                actions,
                "managed_project_open:" + detail.localProjectId(),
                "返回项目",
                "",
                "");
    }

    public OperationDetail skillManifestCatalog(
            SkillKnowledgeManifestClient.Manifest manifest
    ) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (manifest != null) {
            for (SkillKnowledgeManifestClient.SkillItem skill : manifest.skills()) {
                items.add(skill.name() + " · v" + skill.version()
                        + "\n本地授权目录缓存 · 等待服务端确认 · 版本摘要："
                        + skill.contentSha256().substring(0, 12));
                actions.add("");
            }
        }
        String description = items.isEmpty()
                ? "本机没有可展示的有效 Skill 授权缓存，正在等待服务端确认。"
                : "缓存只用于快速展示名称和版本；服务端确认前不能启用、停用或执行 Skill。";
        return new OperationDetail(
                "受管 Skill",
                "AI运维技能",
                description,
                items,
                actions,
                "managed_skill_refresh",
                "确认授权",
                "",
                "");
    }

    public OperationDetail knowledgeCatalog(
            SkillKnowledgeManifestClient.Manifest manifest,
            boolean networkConfirmed
    ) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (manifest != null) {
            for (SkillKnowledgeManifestClient.KnowledgeItem knowledge : manifest.knowledge()) {
                items.add(knowledge.title() + " · v" + knowledge.version()
                        + "\n" + knowledge.summary()
                        + "\n引用：" + knowledge.sourceReference()
                        + " · 摘要：" + knowledge.contentSha256().substring(0, 12));
                actions.add("");
            }
        }
        String description;
        if (networkConfirmed) {
            description = items.isEmpty()
                    ? "服务端已确认：当前项目没有授权知识引用，不会显示本地示例资料。"
                    : "服务端已确认当前账号、设备和项目可引用的知识目录；眼镜不保存知识正文。";
        } else {
            description = items.isEmpty()
                    ? "本机没有有效知识目录缓存，等待服务端确认。"
                    : "正在显示本地知识目录缓存，等待服务端确认；缓存不能替代授权。";
        }
        return new OperationDetail(
                "受管知识",
                "华方知识库",
                description,
                items,
                actions,
                "managed_knowledge_refresh",
                "刷新知识",
                "",
                "");
    }

    public OperationDetail skillCatalog(ExecutionContextDeviceClient.SkillCatalog catalog) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (catalog != null) {
            for (ExecutionContextDeviceClient.Skill skill : catalog.items()) {
                if (skill == null) continue;
                String state = skill.activeForTask()
                        ? "服务端已启用" : "服务端已授权 · 当前任务未启用";
                items.add(skill.name() + " · v" + skill.version()
                        + "\n" + state + " · 版本摘要："
                        + skill.sha256().substring(0, 12));
                actions.add((skill.activeForTask()
                        ? "managed_skill_deactivate:" : "managed_skill_activate:")
                        + skill.versionId());
            }
        }
        String description = items.isEmpty()
                ? "当前任务没有可用的已授权 Skill，不会显示本地假启用状态。"
                : "启用和停用会写入服务端任务快照，并从下一轮 AI 对话开始真实生效。";
        return new OperationDetail(
                "受管 Skill",
                "AI运维技能",
                description,
                items,
                actions,
                "managed_skill_refresh",
                "刷新技能",
                "",
                "");
    }

    public String skillActionFromVoice(
            String text,
            boolean catalogVisible,
            ExecutionContextDeviceClient.SkillCatalog catalog
    ) {
        if (!catalogVisible || catalog == null) return "";
        String normalized = normalizeVoice(text);
        String action = "";
        String spokenName = normalized;
        String[] activate = new String[]{"启用", "打开"};
        String[] deactivate = new String[]{"停用", "关闭", "禁用"};
        for (String prefix : activate) {
            if (normalized.startsWith(prefix)) {
                action = "managed_skill_activate:";
                spokenName = normalized.substring(prefix.length());
                break;
            }
        }
        if (action.isEmpty()) {
            for (String prefix : deactivate) {
                if (normalized.startsWith(prefix)) {
                    action = "managed_skill_deactivate:";
                    spokenName = normalized.substring(prefix.length());
                    break;
                }
            }
        }
        if (action.isEmpty() || spokenName.isEmpty()) return "";
        for (ExecutionContextDeviceClient.Skill skill : catalog.items()) {
            String name = normalizeVoice(skill.name());
            if (spokenName.equals(name) || spokenName.equals(name + "技能")) {
                return action + skill.versionId();
            }
        }
        return "";
    }

    public OperationDetail failure(String abilityId, String errorCode) {
        String code = errorCode == null ? "request_failed" : errorCode.trim();
        String action = "agent_center".equals(abilityId)
                ? "managed_skill_refresh"
                : "knowledge".equals(abilityId)
                ? "managed_knowledge_refresh"
                : "device_brain".equals(abilityId)
                ? "managed_device_memory_refresh" : "managed_project_refresh";
        return new OperationDetail(
                tag(abilityId),
                title(abilityId),
                "服务请求失败，未改变本地任务、项目记忆或 Skill 状态。",
                Collections.singletonList(errorMessage(code)),
                action,
                "重试",
                "",
                "");
    }

    private static void addArray(
            List<String> items,
            List<String> actions,
            String label,
            JSONArray values
    ) {
        if (values == null || values.length() == 0) return;
        List<String> accepted = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            String value = display(values.optString(index, ""));
            if (!value.isEmpty()) accepted.add(value);
        }
        if (!accepted.isEmpty()) {
            items.add(label + "：" + join(accepted));
            actions.add("");
        }
    }

    private static void addItem(
            List<String> items,
            List<String> actions,
            String item,
            String action
    ) {
        items.add(item);
        actions.add(action == null ? "" : action);
    }

    private static String instructionCondition(JSONObject condition) {
        if (condition == null || condition.length() == 0) {
            return "本项目后续所有 AI 对话";
        }
        List<String> labels = new ArrayList<>();
        appendCondition(labels, condition, "containsAny", "问题包含");
        appendCondition(labels, condition, "systems", "系统属于");
        appendCondition(labels, condition, "assetIds", "设备属于");
        return labels.isEmpty() ? "服务端受控条件" : join(labels);
    }

    private static void appendCondition(
            List<String> labels,
            JSONObject condition,
            String key,
            String label
    ) {
        JSONArray values = condition.optJSONArray(key);
        if (values == null || values.length() == 0) return;
        List<String> accepted = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            String value = display(values.optString(index, ""));
            if (!value.isEmpty()) accepted.add("“" + value + "”");
        }
        if (!accepted.isEmpty()) labels.add(label + join(accepted));
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append("；");
            result.append(value);
        }
        return result.toString();
    }

    private static String errorMessage(String code) {
        if ("device_session_missing".equals(code)) return "设备会话不可用，请检查账号绑定和网络后重试。";
        if ("skill_not_authorized".equals(code)) return "当前 Skill 未授权，未执行启用或停用。";
        if ("project_not_found".equals(code)) return "项目不存在、已撤销或当前设备无权访问。";
        if ("task_not_found".equals(code)) return "任务不存在、已关闭或当前设备无权访问。";
        if ("task_not_active".equals(code)) return "任务已结束，不能再次修改或恢复。";
        if ("active_task_required".equals(code)) return "请先进入一个未结束的维修任务，再查看或切换 Skill。";
        if ("project_memory_revision_conflict".equals(code)) return "项目记忆已更新，请刷新后重试。";
        if ("content_manifest_cache_expired".equals(code)) return "本地授权目录已过期，必须重新连接服务端确认。";
        if ("content_manifest_unavailable".equals(code)) return "服务端尚未同步当前项目的权威 Skill/知识清单。";
        if ("content_manifest_expired".equals(code)) return "服务端授权清单已过期，当前不能启用 Skill 或引用知识。";
        if ("device_memory_response_invalid".equals(code)) return "设备记忆服务返回不合法，已拒绝展示，请重试。";
        return "请求阶段：受管上下文 · 错误：" + display(code) + " · 可重试。";
    }

    private static String title(String abilityId) {
        if ("agent_center".equals(abilityId)) return "AI运维技能";
        if ("knowledge".equals(abilityId)) return "华方知识库";
        if ("device_brain".equals(abilityId)) return "设备记忆";
        return "项目记忆";
    }

    private static String tag(String abilityId) {
        if ("agent_center".equals(abilityId)) return "受管 Skill";
        if ("knowledge".equals(abilityId)) return "受管知识";
        if ("device_brain".equals(abilityId)) return "受管设备";
        return "项目记录";
    }

    private static String projectStatus(String status) {
        if ("active".equalsIgnoreCase(status)) return "进行中";
        if ("closed".equalsIgnoreCase(status)) return "已关闭";
        return display(status);
    }

    private static String taskStatus(String status) {
        if ("active".equalsIgnoreCase(status)) return "进行中";
        if ("paused".equalsIgnoreCase(status)) return "已暂停";
        if ("completed".equalsIgnoreCase(status)) return "已完成";
        if ("closed".equalsIgnoreCase(status)) return "已关闭";
        return display(status);
    }

    private static String instructionStatus(String status) {
        if ("active".equalsIgnoreCase(status)) return "生效中";
        if ("disabled".equalsIgnoreCase(status)) return "已停用";
        if ("deleted".equalsIgnoreCase(status)) return "已删除";
        return display(status);
    }

    private static String deviceStatus(String status) {
        if ("normal".equalsIgnoreCase(status)) return "正常";
        if ("attention".equalsIgnoreCase(status)) return "需关注";
        if ("maintenance".equalsIgnoreCase(status)) return "维修中";
        if ("decommissioned".equalsIgnoreCase(status)) return "已停用";
        return display(status);
    }

    private static boolean isClosedTask(String status) {
        return "completed".equalsIgnoreCase(status) || "closed".equalsIgnoreCase(status);
    }

    private static String identifier(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,199}$") ? clean : "";
    }

    private static String display(String value) {
        String clean = value == null ? "" : value.trim();
        StringBuilder result = new StringBuilder(clean.length());
        for (int index = 0; index < clean.length(); index++) {
            char character = clean.charAt(index);
            if (character >= 32 || character == '\n') result.append(character);
        }
        return result.toString();
    }

    private static String normalizeVoice(String value) {
        return display(value).toLowerCase(Locale.ROOT)
                .replace("小叮当", "")
                .replace("小叮", "")
                .replace("小丁", "")
                .replaceAll("[\\s，。！？,.!?]", "");
    }
}
