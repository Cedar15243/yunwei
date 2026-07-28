package com.codex.air3nativecamera.features.operations;

import com.codex.air3nativecamera.features.AIAgentConfig;
import com.codex.air3nativecamera.features.AISkillConfig;
import com.codex.air3nativecamera.task.MaintenanceTask;
import com.codex.air3nativecamera.task.TaskSession;
import com.codex.air3nativecamera.features.inspection.InspectionCatalog;
import com.codex.air3nativecamera.features.inspection.InspectionTaskDefinition;
import com.codex.air3nativecamera.features.inspection.InspectionRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Builds local-only capability content. It intentionally has no Android or network dependency. */
public final class OperationDetailFactory {
    private final KnowledgeCatalog knowledgeCatalog;
    private final List<AISkillConfig> skills;
    private final List<AIAgentConfig> agents;
    private final InspectionCatalog inspectionCatalog;
    private final DeviceMemoryCatalog deviceMemoryCatalog;
    private final AgentPackageCatalog agentPackageCatalog;

    private OperationDetailFactory(
            KnowledgeCatalog knowledgeCatalog,
            List<AISkillConfig> skills,
            List<AIAgentConfig> agents) {
        this.knowledgeCatalog = knowledgeCatalog;
        this.skills = skills;
        this.agents = agents;
        this.inspectionCatalog = InspectionCatalog.defaultCatalog();
        this.deviceMemoryCatalog = DeviceMemoryCatalog.defaultCatalog();
        this.agentPackageCatalog = AgentPackageCatalog.defaultCatalog();
    }

    public static OperationDetailFactory defaultFactory() {
        return new OperationDetailFactory(
                KnowledgeCatalog.defaultCatalog(),
                AISkillConfig.defaultConfigs(),
                AIAgentConfig.defaultConfigs());
    }

    public boolean authorizeAgentPackage(String id) {
        return agentPackageCatalog.authorize(id);
    }

    public boolean toggleAgentPackage(String id) {
        return agentPackageCatalog.toggle(id);
    }

    public boolean setAgentPackageAuthorized(String id, boolean authorized) {
        return agentPackageCatalog.setAuthorized(id, authorized);
    }

    public boolean isAgentPackageAuthorized(String id) {
        AgentPackageCatalog.AgentPackage item = agentPackageCatalog.find(id);
        return item != null && item.authorized();
    }

    public JSONObject agentAuthorizationJson() {
        return agentPackageCatalog.toJson();
    }

    public void restoreAgentAuthorizations(JSONObject json) {
        AgentPackageCatalog restored = AgentPackageCatalog.fromJson(json);
        for (AgentPackageCatalog.AgentPackage item : agentPackageCatalog.packages()) {
            AgentPackageCatalog.AgentPackage restoredItem = restored.find(item.id());
            agentPackageCatalog.setAuthorized(item.id(),
                    restoredItem != null && restoredItem.authorized());
        }
    }

    public OperationDetail create(String abilityId, MaintenanceTask task, InspectionChecklist checklist) {
        return create(abilityId, task, checklist, null);
    }

    public OperationDetail create(String abilityId, MaintenanceTask task, InspectionChecklist checklist,
            InspectionRun inspectionRun) {
        return create(abilityId, task, checklist, inspectionRun,
                java.util.Collections.<TaskSession>emptyList());
    }

    public OperationDetail create(String abilityId, MaintenanceTask task, InspectionChecklist checklist,
            InspectionRun inspectionRun, List<TaskSession> taskSessions) {
        if ("inspection".equals(abilityId)) {
            return inspection(inspectionRun);
        }
        if ("perception".equals(abilityId)) {
            return perception(task);
        }
        if ("video_evidence".equals(abilityId)) {
            return videoEvidence(task);
        }
        if ("tasks".equals(abilityId)) {
            return tasks(task, taskSessions);
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

    private OperationDetail inspection(InspectionRun run) {
        if (run != null) {
            if (run.isCompleted()) {
                List<String> summary = new ArrayList<>();
                summary.add("巡检点位：" + run.completedPointCount() + " / "
                        + run.definition().points().size());
                int abnormalCount = 0;
                for (InspectionRun.PointRecord record : run.records()) {
                    if (record.outcome() == InspectionRun.Outcome.ABNORMAL) abnormalCount++;
                }
                summary.add("正常：" + (run.completedPointCount() - abnormalCount)
                        + " 项\n异常：" + abnormalCount + " 项");
                summary.add("照片、AI 观察、前后读数和现场确认均已保存在本次巡检记录中。");
                return new OperationDetail("巡检已完成", run.definition().title(),
                        "全部点位已完成，返回后可选择其他巡检任务。",
                        summary, "", "", "", "");
            }
            InspectionTaskDefinition.Point point = run.currentPoint();
            InspectionRun.PointRecord record = run.currentRecord();
            List<String> items = new ArrayList<>();
            items.add("任务进度：" + run.currentPointNumber() + " / " + run.definition().points().size());
            items.add(point.title() + "\n" + point.detail());
            items.add(record.photoReference().length() == 0 ? "照片：等待拍摄" : "照片：已关联当前点位");
            items.add(record.aiObservation().length() == 0
                    ? "AI识别：等待照片" : "AI识别：" + record.aiObservation());
            if (record.previousValue().length() > 0 || record.currentValue().length() > 0) {
                String currentValue = record.currentValue().equals(record.aiObservation())
                        ? "见 AI 识别结果" : record.currentValue();
                items.add("上次：" + record.previousValue() + "\n本次：" + currentValue);
            }
            String primaryAction = record.aiObservation().length() == 0
                    ? "capture_inspection_photo" : "confirm_inspection_normal";
            String primaryLabel = record.aiObservation().length() == 0 ? "拍摄当前点位" : "确认正常并继续";
            String secondaryAction = record.aiObservation().length() == 0
                    ? "cancel_inspection" : "confirm_inspection_abnormal";
            String secondaryLabel = record.aiObservation().length() == 0 ? "退出巡检" : "记录异常并继续";
            List<String> itemActions = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) itemActions.add("");
            if (record.photoReference().length() > 0) {
                items.set(2, "照片：已关联当前点位，可重新拍摄");
                itemActions.set(2, "capture_inspection_photo");
            }
            return new OperationDetail("巡检执行中", run.definition().title(),
                    "一项一屏：拍照、AI识别、人工确认、对比上次值后进入下一项。",
                    items, itemActions, primaryAction, primaryLabel, secondaryAction, secondaryLabel);
        }
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (InspectionTaskDefinition task : inspectionCatalog.tasks()) {
            String scope = task.scope() == InspectionTaskDefinition.Scope.MY_TASK
                    ? "我的巡检任务" : "行业巡检任务";
            items.add(scope + " · " + task.title() + "\n" + task.summary()
                    + "\n可说：" + inspectionVoiceCommand(task.id()));
            actions.add("start_inspection:" + task.id());
        }
        return new OperationDetail("巡检任务", "巡检任务",
                "选择任务后逐点拍照、AI识别、人工确认并对比上次记录。也可说“进入第一个选项”。",
                items, actions, "", "", "", "");
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
        items.add("视频证据已关联当前任务，可在专家协同或维修复核时查看。" );
        return new OperationDetail("现场证据", "短视频取证", "记录设备运行、异响或操作过程，便于后续复核。",
                items, "capture_video", "开始录像", "", "");
    }

    private OperationDetail tasks(MaintenanceTask task, List<TaskSession> taskSessions) {
        List<String> items = new ArrayList<>();
        if (task == null) {
            List<String> actions = new ArrayList<>();
            int added = 0;
            for (int i = taskSessions.size() - 1; i >= 0 && added < 3; i--) {
                TaskSession session = taskSessions.get(i);
                if (session.status() == TaskSession.Status.COMPLETED) continue;
                MaintenanceTask paused = session.maintenanceTask();
                items.add("已暂停 · " + paused.initialProblem() + "\n阶段："
                        + phaseLabel(paused.phase()) + " · 现场证据："
                        + paused.evidenceLabels().size() + "项");
                actions.add("resume_task:" + session.id());
                added++;
            }
            items.add("临时现场诊断\n从拍照或语音描述创建新的独立维修任务");
            actions.add("");
            return new OperationDetail("维修记录", "维修任务", "可继续已暂停任务，或开始新的独立现场诊断。",
                    items, actions, "start_diagnosis", "开始新诊断", "", "");
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
                items, "continue_task", "继续当前任务", "", "");
    }

    private OperationDetail deviceProfile(MaintenanceTask task) {
        List<String> items = new ArrayList<>();
        for (DeviceMemoryCatalog.DeviceRecord record : deviceMemoryCatalog.records()) {
            items.add(record.system() + " · " + record.brand() + " " + record.model()
                    + " · " + record.quantity() + "台\n状态：" + record.status()
                    + " · 最近巡检：" + record.lastInspection()
                    + " · 故障/维修：" + record.faultCount() + "/" + record.repairCount()
                    + " · " + record.keyParameter());
        }
        return new OperationDetail("本机设备目录", "设备记忆", "本机设备目录已加载，共 "
                + deviceMemoryCatalog.totalQuantity() + " 台；按系统查看品牌、型号、巡检与维修记录。",
                items, "", "", "", "");
    }

    private OperationDetail knowledge() {
        List<String> items = new ArrayList<>();
        for (KnowledgeCatalog.Entry entry : knowledgeCatalog.entries()) {
            items.add(entry.system() + " · " + entry.title() + " · v" + entry.version()
                    + "\n" + entry.summary() + " · 更新 " + entry.updatedAt());
        }
        return new OperationDetail("知识连接状态", "华方知识库",
                "本机资料已连接；企业知识服务未连接。按系统查看已加载的手册、SOP 与技术资料。",
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
        List<String> actions = new ArrayList<>();
        for (AgentPackageCatalog.AgentPackage agent : agentPackageCatalog.packages()) {
            String content = agent.system() + " · " + agent.title() + " · v" + agent.version()
                    + "\n" + agent.authorizationLabel() + " · Skill："
                    + String.join("、", agent.skills());
            if (agent.authorized()) {
                content += "\n权限：" + agent.permissionScope() + " · " + agent.description();
            }
            content += "\n可说：" + (agent.authorized() ? "停用" : "启用") + agent.title();
            if ("environment_ops".equals(agent.id())) {
                content += "\n启用后返回首页，现场拍照，再描述“平台报警，看看怎么回事”";
            }
            items.add(content);
            actions.add("set_agent:" + agent.id() + ":"
                    + (agent.authorized() ? "disabled" : "enabled"));
        }
        return new OperationDetail("本机技能授权", "AI运维技能",
                "按现场需要启用或停用各系统技能包。请说完整名称，例如“启用环境诊断技能”。",
                items, actions, "", "", "", "");
    }

    private static String inspectionVoiceCommand(String taskId) {
        if ("lab-training-room".equals(taskId)) return "开始实训室设备巡检";
        if ("water-power-heating".equals(taskId)) return "开始水电暖巡检";
        if ("air-conditioning".equals(taskId)) return "开始空调巡检";
        if ("fire-safety".equals(taskId)) return "开始消防巡检";
        return "打开巡检任务";
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
