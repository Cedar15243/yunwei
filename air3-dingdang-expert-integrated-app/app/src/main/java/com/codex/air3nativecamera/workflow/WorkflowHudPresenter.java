package com.codex.air3nativecamera.workflow;

import com.codex.air3nativecamera.features.operations.OperationDetail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Maps verified workflow runtime data onto the existing operation-detail HUD. */
public final class WorkflowHudPresenter {
    public OperationDetail taskList(
            List<WorkflowExecutionCoordinator.TaskListItem> tasks
    ) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (tasks != null) {
            for (WorkflowExecutionCoordinator.TaskListItem task : tasks) {
                if (task == null) continue;
                StringBuilder content = new StringBuilder();
                content.append(value(task.displayCode(), task.workOrderId()))
                        .append(" · ")
                        .append(value(task.title(), "工单详情待同步"));
                if (!task.assetLabel().isEmpty()) {
                    content.append("\n设备：").append(task.assetLabel());
                }
                content.append(" · ").append(modeLabel(task.mode()));
                if (!task.priority().isEmpty()) {
                    content.append(" · 优先级：").append(task.priority());
                }
                content.append("\n状态：").append(entryLabel(task.entryAction()));
                items.add(content.toString());
                actions.add("workflow_open:" + task.assignmentId());
            }
        }
        return new OperationDetail(
                "维修工单",
                "我的维修工单",
                "仅显示当前工程师已授权的真实工单；工作流未就绪时不会使用本地假流程代替。",
                items,
                actions,
                "",
                "",
                "",
                "");
    }

    public OperationDetail taskDetail(WorkflowExecutionCoordinator.TaskDetail detail) {
        if (detail == null) {
            return message("工单不可用", "未找到该工单或访问权限已撤销。", "工单不存在");
        }
        WorkflowExecutionCoordinator.TaskListItem item = detail.listItem();
        List<String> items = new ArrayList<>();
        items.add("工单号：" + value(item.displayCode(), item.workOrderId()));
        if (!detail.description().isEmpty()) items.add("说明：" + detail.description());
        if (!item.assetLabel().isEmpty()) items.add("设备：" + item.assetLabel());
        if (!detail.workOrderType().isEmpty()) items.add("类型：" + detail.workOrderType());
        if (!item.priority().isEmpty() || !detail.riskLevel().isEmpty()) {
            items.add("优先级：" + value(item.priority(), "未标注")
                    + " · 风险：" + value(detail.riskLevel(), "未标注"));
        }
        if (!detail.dueAt().isEmpty()) items.add("到期时间：" + detail.dueAt());
        if (!detail.workflowTitle().isEmpty()) {
            items.add("现场应用：" + detail.workflowTitle());
        } else if (!"none".equals(detail.mode())) {
            items.add("现场应用：等待安全下发");
        }

        String id = item.assignmentId();
        String primaryAction = "";
        String primaryLabel;
        String secondaryAction = "workflow_list";
        String secondaryLabel = "返回工单";
        switch (detail.entryAction()) {
            case START_WORKFLOW:
                primaryAction = "workflow_start:" + id;
                primaryLabel = "开始工作流";
                break;
            case RESUME_WORKFLOW:
                primaryAction = "workflow_resume:" + id;
                primaryLabel = "继续工作流";
                break;
            case CHOOSE_MODE:
                primaryAction = "workflow_choose:" + id;
                primaryLabel = "按工作流执行";
                secondaryAction = "workflow_standard:" + id;
                secondaryLabel = "普通任务";
                break;
            case OPEN_STANDARD_TASK:
                primaryAction = "workflow_standard:" + id;
                primaryLabel = "进入普通任务";
                break;
            case WAITING_DELIVERY:
                primaryLabel = "等待工作流下发";
                break;
            case VIEW_COMPLETED:
                primaryLabel = "工作流已完成";
                break;
            default:
                primaryLabel = "工作流不可用";
                break;
        }
        return new OperationDetail(
                "维修工单",
                value(item.title(), "工单详情"),
                entryDescription(detail.entryAction()),
                items,
                primaryAction,
                primaryLabel,
                secondaryAction,
                secondaryLabel);
    }

    public OperationDetail openResult(
            String assignmentId,
            WorkflowExecutionCoordinator.OpenResult result
    ) {
        if (result == null) {
            return message("工作流不可用", "工作流运行时没有返回有效状态。", "状态无效");
        }
        if ((result.code() == WorkflowExecutionCoordinator.OpenCode.STARTED
                || result.code() == WorkflowExecutionCoordinator.OpenCode.RESUMED)
                && result.step() != null) {
            return step(result.step());
        }
        if (result.code() == WorkflowExecutionCoordinator.OpenCode.COMPLETED) {
            return readOnly("任务已完成", "该工作流已经完成，执行记录和现场证据已保留。", "已完成");
        }
        if (result.code() == WorkflowExecutionCoordinator.OpenCode.NOT_READY) {
            return readOnly("工作流尚未就绪", "已收到工单，但安全执行包仍在下发或校验中。", "等待下发");
        }
        if (result.code() == WorkflowExecutionCoordinator.OpenCode.CHOICE_REQUIRED) {
            return new OperationDetail(
                    "执行方式",
                    "选择工单执行方式",
                    "该工单可使用企业工作流，也可进入普通维修任务。",
                    Collections.singletonList("选择后本次任务将按对应方式执行。"),
                    "workflow_choose:" + clean(assignmentId),
                    "按工作流执行",
                    "workflow_standard:" + clean(assignmentId),
                    "普通任务");
        }
        if (result.code() == WorkflowExecutionCoordinator.OpenCode.STANDARD_TASK_REQUIRED) {
            return new OperationDetail(
                    "维修工单",
                    "该工单未绑定工作流",
                    "可进入现有普通维修任务，不会生成或模拟企业流程。",
                    Collections.singletonList("执行方式：普通维修任务"),
                    "workflow_standard:" + clean(assignmentId),
                    "进入普通任务",
                    "workflow_list",
                    "返回工单");
        }
        if (result.code() == WorkflowExecutionCoordinator.OpenCode.PERSIST_FAILED) {
            return readOnly("工作流状态保存失败", "本地状态未安全落盘，任务没有开始，请重试。", "保存失败");
        }
        if (result.code() == WorkflowExecutionCoordinator.OpenCode.NOT_FOUND) {
            return readOnly("工单不可用", "工单不存在、已撤销或当前设备无权访问。", "无访问权限");
        }
        return readOnly("工作流不可用", "执行包或本地状态校验失败，任务没有开始。", "校验失败");
    }

    public OperationDetail step(WorkflowExecutionCoordinator.StepHud step) {
        if (step == null) {
            return readOnly("工作流状态异常", "当前步骤不存在，已停止执行。", "步骤缺失");
        }
        return new OperationDetail(
                step.tag(),
                step.title(),
                step.description(),
                step.items(),
                step.itemActions(),
                step.primaryAction(),
                step.primaryLabel(),
                step.secondaryAction(),
                step.secondaryLabel());
    }

    public OperationDetail actionResult(
            String assignmentId,
            WorkflowExecutionCoordinator.ActionResult result
    ) {
        if (result == null) {
            return readOnly("工作流状态异常", "执行协调器没有返回有效状态。", "状态无效");
        }
        if (result.state() != null
                && result.state().status() == WorkflowRuntimeState.Status.COMPLETED) {
            return readOnly("任务已完成", "工作流已在本地完成；未同步证据会继续留在安全队列中。", "已完成");
        }
        if (result.code() == WorkflowExecutionCoordinator.ActionCode.BLOCKED
                && result.step() != null) {
            OperationDetail current = step(result.step());
            return new OperationDetail(
                    current.tag(),
                    current.title(),
                    append(current.description(), "当前步骤尚未满足执行条件，请补全证据或网络条件。"),
                    current.items(),
                    current.itemActions(),
                    current.primaryAction(),
                    current.primaryLabel(),
                    current.secondaryAction(),
                    current.secondaryLabel());
        }
        if ((result.code() == WorkflowExecutionCoordinator.ActionCode.ADVANCED
                || result.code() == WorkflowExecutionCoordinator.ActionCode.RECORDED
                || result.code() == WorkflowExecutionCoordinator.ActionCode.REPLAY_QUEUED
                || result.code() == WorkflowExecutionCoordinator.ActionCode.EVIDENCE_UPDATED)
                && result.step() != null) {
            return step(result.step());
        }
        if (result.code() == WorkflowExecutionCoordinator.ActionCode.NOT_READY) {
            return readOnly("工作流尚未就绪", "执行包仍在下发或校验中。", "等待下发");
        }
        if (result.code() == WorkflowExecutionCoordinator.ActionCode.PERSIST_FAILED) {
            return readOnly("工作流状态保存失败", "本次操作没有安全落盘，请重试。", "保存失败");
        }
        if (result.code() == WorkflowExecutionCoordinator.ActionCode.NOT_FOUND) {
            return readOnly("工单不可用", "工单不存在、已撤销或当前设备无权访问。", "无访问权限");
        }
        return readOnly(
                "工作流操作未执行",
                "当前步骤或证据状态无效，任务没有被推进。",
                value(result.reason(), "状态无效"));
    }

    private static OperationDetail readOnly(String title, String description, String item) {
        return new OperationDetail(
                "维修工单",
                title,
                description,
                Collections.singletonList(item),
                "",
                "",
                "workflow_list",
                "返回工单");
    }

    private static OperationDetail message(String title, String description, String item) {
        return readOnly(title, description, item);
    }

    private static String entryLabel(WorkflowExecutionCoordinator.EntryAction action) {
        if (action == null) return "不可用";
        switch (action) {
            case START_WORKFLOW: return "可开始";
            case RESUME_WORKFLOW: return "可继续";
            case CHOOSE_MODE: return "请选择执行方式";
            case OPEN_STANDARD_TASK: return "普通任务";
            case WAITING_DELIVERY: return "等待工作流下发";
            case VIEW_COMPLETED: return "已完成";
            default: return "不可用";
        }
    }

    private static String entryDescription(WorkflowExecutionCoordinator.EntryAction action) {
        if (action == null) return "当前工单不可执行。";
        switch (action) {
            case START_WORKFLOW: return "企业工作流已安全下发，可开始现场执行。";
            case RESUME_WORKFLOW: return "继续上次未结束的工作流和现场步骤。";
            case CHOOSE_MODE: return "该工单允许选择企业工作流或普通维修任务。";
            case OPEN_STANDARD_TASK: return "该工单未绑定工作流，使用现有普通维修任务。";
            case WAITING_DELIVERY: return "工作流尚未完成下发和验签，当前不能执行。";
            case VIEW_COMPLETED: return "工作流已完成，现场记录保持只读。";
            default: return "工作流下发或校验失败，当前不能执行。";
        }
    }

    private static String modeLabel(String mode) {
        if ("required".equals(mode)) return "必须执行工作流";
        if ("optional".equals(mode)) return "可选工作流";
        return "普通任务";
    }

    private static String value(String value, String fallback) {
        String accepted = clean(value);
        return accepted.isEmpty() ? clean(fallback) : accepted;
    }

    private static String append(String first, String second) {
        String left = clean(first);
        String right = clean(second);
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + "\n" + right;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
