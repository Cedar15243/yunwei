package com.codex.air3nativecamera.features.operations;

import com.codex.air3nativecamera.features.AIAgentConfig;
import com.codex.air3nativecamera.features.AISkillConfig;
import com.codex.air3nativecamera.task.MaintenanceTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds local-only capability content. It intentionally has no Android or network dependency. */
public final class OperationDetailFactory {
    private final KnowledgeCatalog knowledgeCatalog;
    private final List<AISkillConfig> skills;
    private final List<AIAgentConfig> agents;

    private OperationDetailFactory(
            KnowledgeCatalog knowledgeCatalog,
            List<AISkillConfig> skills,
            List<AIAgentConfig> agents) {
        this.knowledgeCatalog = knowledgeCatalog;
        this.skills = skills;
        this.agents = agents;
    }

    public static OperationDetailFactory defaultFactory() {
        return new OperationDetailFactory(
                KnowledgeCatalog.defaultCatalog(),
                AISkillConfig.defaultConfigs(),
                AIAgentConfig.defaultConfigs());
    }

    public OperationDetail create(String abilityId, MaintenanceTask task, InspectionChecklist checklist) {
        if ("inspection".equals(abilityId)) {
            return inspection(checklist == null ? InspectionChecklist.defaultChecklist() : checklist);
        }
        if ("perception".equals(abilityId)) {
            return perception(task);
        }
        if ("video_evidence".equals(abilityId)) {
            return videoEvidence(task);
        }
        if ("tasks".equals(abilityId)) {
            return tasks(task);
        }
        if ("device_brain".equals(abilityId)) {
            return deviceProfile(task);
        }
        if ("knowledge".equals(abilityId)) {
            return knowledge();
        }
        if ("skill_center".equals(abilityId)) {
            return skills();
        }
        if ("agent_center".equals(abilityId)) {
            return agents();
        }
        return new OperationDetail("AI能力", "能力说明", "该能力没有可展示的本地内容。",
                new ArrayList<String>(), "", "", "", "");
    }

    private OperationDetail inspection(InspectionChecklist checklist) {
        List<String> items = new ArrayList<>();
        items.add("本地巡检进度：" + checklist.progressLabel());
        InspectionChecklist.Item next = null;
        for (InspectionChecklist.Item item : checklist.items()) {
            boolean completed = checklist.isCompleted(item.id());
            items.add((completed ? "已完成" : "待检查") + " · " + item.title() + "\n" + item.detail());
            if (!completed && next == null) {
                next = item;
            }
        }
        String action = next == null ? "" : "complete_inspection:" + next.id();
        String label = next == null ? "巡检已完成" : "完成：" + next.title();
        return new OperationDetail("本地巡检", "巡检任务", "仅记录当前设备的本地检查进度，不自动识别设备或生成报告。",
                items, action, label, "capture_photo", "补拍现场照片");
    }

    private OperationDetail perception(MaintenanceTask task) {
        DeviceProfile profile = DeviceProfile.from(task);
        List<String> items = new ArrayList<>();
        items.add("当前任务证据：" + profile.evidenceCount() + "项");
        if (profile.evidenceLabels().isEmpty()) {
            items.add("尚未采集照片、视频或语音说明。");
        } else {
            items.addAll(profile.evidenceLabels());
        }
        return new OperationDetail("现场证据", "现场拍照", "照片和语音说明会关联到当前维修任务。",
                items, "capture_photo", "拍摄现场照片", "", "");
    }

    private OperationDetail videoEvidence(MaintenanceTask task) {
        DeviceProfile profile = DeviceProfile.from(task);
        List<String> items = new ArrayList<>();
        items.add("当前任务证据：" + profile.evidenceCount() + "项");
        items.add("单段最长 15 秒，仅保存为当前任务的本地视频证据。" );
        items.add("当前不宣称自动理解视频内容或执行 OCR。" );
        return new OperationDetail("现场证据", "短视频取证", "记录设备运行、异响或操作过程，便于后续复核。",
                items, "capture_video", "开始录像", "", "");
    }

    private OperationDetail tasks(MaintenanceTask task) {
        List<String> items = new ArrayList<>();
        if (task == null) {
            items.add("暂无进行中的维修任务。");
            items.add("开始诊断后，这里会显示现场证据、诊断与维修进度。" );
            return new OperationDetail("当前任务", "维修任务", "仅显示本机当前任务，不伪装成云端工单系统。",
                    items, "start_diagnosis", "开始 AI 诊断", "", "");
        }
        items.add("问题：" + task.initialProblem());
        items.add("阶段：" + phaseLabel(task.phase()));
        items.add("现场证据：" + task.evidenceLabels().size() + "项");
        if (task.repairStepCount() > 0) {
            items.add("维修进度：" + task.currentRepairStepNumber() + "/" + task.repairStepCount());
            items.add("当前步骤：" + task.currentRepairStep());
        } else {
            items.add("维修步骤：等待 AI 生成");
        }
        return new OperationDetail("当前任务", "维修任务", "本机保留当前维修过程与证据，退出后仍可回到当前任务。",
                items, "continue_task", "继续当前任务", "capture_photo", "补拍现场照片");
    }

    private OperationDetail deviceProfile(MaintenanceTask task) {
        DeviceProfile profile = DeviceProfile.from(task);
        List<String> items = new ArrayList<>();
        items.add("当前问题：" + profile.currentProblem());
        for (Map.Entry<String, String> fact : profile.facts().entrySet()) {
            items.add(fact.getKey() + "：" + fact.getValue());
        }
        items.add("本地证据：" + profile.evidenceCount() + "项");
        items.add("仅汇总当前任务，不代表已接入设备历史档案。" );
        return new OperationDetail("本地设备记忆", "设备记忆", "将本次任务确认的设备事实和证据集中展示。",
                items, "", "", "", "");
    }

    private OperationDetail knowledge() {
        List<String> items = new ArrayList<>();
        items.add("只读目录 · 不连接云端知识库，也不会自动执行步骤。" );
        for (KnowledgeCatalog.Entry entry : knowledgeCatalog.entries()) {
            items.add(entry.title() + "\n" + entry.summary());
        }
        return new OperationDetail("只读参考", "华方知识库", "供现场人员查看安全与维修参考，AI 诊断仍使用现有接口。",
                items, "", "", "", "");
    }

    private OperationDetail skills() {
        List<String> items = new ArrayList<>();
        items.add("能力预览 · 当前不执行领域 Skill 或设备操作。" );
        for (AISkillConfig skill : skills) {
            items.add(skill.title() + "\n" + skill.summary());
        }
        return new OperationDetail("能力预览", "AI技能中心", "为未来领域扩展保留数据模型和说明。",
                items, "", "", "", "");
    }

    private OperationDetail agents() {
        List<String> items = new ArrayList<>();
        items.add("能力预览 · 当前不会自主编排任务、调用模型或控制设备。" );
        for (AIAgentConfig agent : agents) {
            items.add(agent.title() + "\n" + agent.summary() + "\n关联 Skill：" + joinSkillIds(agent.skillIds()));
        }
        return new OperationDetail("能力预览", "AI Agent中心", "展示未来智能体与领域 Skill 的关联结构。",
                items, "", "", "", "");
    }

    private String joinSkillIds(List<String> skillIds) {
        StringBuilder result = new StringBuilder();
        for (String id : skillIds) {
            for (AISkillConfig skill : skills) {
                if (skill.id().equals(id)) {
                    if (result.length() > 0) {
                        result.append("、");
                    }
                    result.append(skill.title());
                }
            }
        }
        return result.length() == 0 ? "待配置" : result.toString();
    }

    private static String phaseLabel(MaintenanceTask.Phase phase) {
        if (phase == MaintenanceTask.Phase.GUIDANCE) {
            return "维修指导";
        }
        if (phase == MaintenanceTask.Phase.COMPLETED) {
            return "已完成";
        }
        return "诊断中";
    }
}
