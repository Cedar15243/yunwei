package com.codex.air3nativecamera.workflow;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.sync.MvsWorkOrderDeviceClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Maps server-authoritative MVS data onto the existing operation-detail HUD. */
public final class MvsWorkOrderHudPresenter {
    public OperationDetail loading(String title) {
        List<String> items = new ArrayList<>();
        items.add("正在通过安全工单网关读取当前工程师数据…");
        return new OperationDetail(
                "维修工单",
                text(title, "我的维修工单"),
                "眼镜不会直连 MVS，也不会保存 MVS 长期凭据。",
                items,
                "",
                "",
                "",
                "");
    }

    public OperationDetail taskList(MvsWorkOrderDeviceClient.WorkOrderPage page) {
        return taskList(page, null);
    }

    public OperationDetail taskList(
            MvsWorkOrderDeviceClient.WorkOrderPage page,
            List<WorkflowExecutionCoordinator.TaskListItem> deliveredWorkflows
    ) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (page != null) {
            for (MvsWorkOrderDeviceClient.WorkOrder order : page.items()) {
                if (order == null) continue;
                StringBuilder content = new StringBuilder();
                content.append(order.orderNo());
                if (!order.assetName().isEmpty()) {
                    content.append(" · ").append(order.assetName());
                }
                if (!order.assetModel().isEmpty()) {
                    content.append(" ").append(order.assetModel());
                }
                if (!order.projectName().isEmpty()) {
                    content.append("\n项目：").append(order.projectName());
                }
                if (!order.siteName().isEmpty()) {
                    content.append(" · ").append(order.siteName());
                }
                if (!order.nodeName().isEmpty()) {
                    content.append("\n当前节点：").append(order.nodeName());
                }
                if (!order.priority().isEmpty()) {
                    content.append(" · 优先级：").append(order.priority());
                }
                items.add(content.toString());
                actions.add("mvs_work_order_open:" + order.orderId());
            }
        }
        appendUnboundWorkflows(items, actions, page, deliveredWorkflows);
        String view = page == null ? "executing" : page.view();
        if (items.isEmpty()) {
            items.add("当前筛选没有可显示的本人维修工单。");
            actions.add("");
        }
        String nextView = nextView(view);
        return new OperationDetail(
                "维修工单",
                "我的维修工单",
                "仅显示当前工程师本人数据 · 当前筛选：" + viewLabel(view)
                        + "。列表按需读取，详情、表单和记录不会提前拉取。",
                items,
                actions,
                "mvs_work_order_view:" + nextView,
                "查看" + viewLabel(nextView),
                "mvs_work_order_refresh:" + view,
                "刷新当前列表");
    }

    public OperationDetail gatewayFailure(
            String errorCode,
            List<WorkflowExecutionCoordinator.TaskListItem> deliveredWorkflows
    ) {
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        items.add("MVS 同步失败：" + text(errorCode, "mvs_request_failed")
                + "\n未收到服务端数据，本页没有显示 MVS 假工单。");
        actions.add("");
        appendUnboundWorkflows(items, actions, null, deliveredWorkflows);
        return new OperationDetail(
                "维修工单",
                "我的维修工单",
                "MVS 当前不可用；已安全下发到本机的受管工作流仍可继续，不影响 AI、语音和专家协同。",
                items,
                actions,
                "mvs_work_order_refresh:executing",
                "重试 MVS",
                "",
                "");
    }

    public OperationDetail taskDetail(
            MvsWorkOrderDeviceClient.WorkOrder order,
            String workflowAssignmentId
    ) {
        return taskDetail(order, workflowAssignmentId, 0);
    }

    public OperationDetail taskDetail(
            MvsWorkOrderDeviceClient.WorkOrder order,
            String workflowAssignmentId,
            int evidenceDraftCount
    ) {
        if (order == null) return failure("工单详情不可用", "mvs_work_order_response_invalid");
        if (evidenceDraftCount < 0 || evidenceDraftCount > 200) {
            throw new IllegalArgumentException("mvs evidence draft count is invalid");
        }
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        add(items, actions, "工单号：" + order.orderNo(), "");
        if (!order.projectName().isEmpty()) {
            add(items, actions, "项目：" + order.projectName(), "");
        }
        if (!order.siteName().isEmpty() || !order.addressDetail().isEmpty()) {
            add(items, actions, "现场：" + join(order.siteName(), order.addressDetail()), "");
        }
        if (!order.assetName().isEmpty() || !order.assetModel().isEmpty()) {
            add(items, actions, "设备：" + join(order.assetName(), order.assetModel()), "");
        }
        add(items, actions,
                "状态：" + join(order.orderStatus(), order.flowStatus()), "");
        if (!order.nodeName().isEmpty()) {
            add(items, actions, "当前节点：" + order.nodeName(), "");
        }
        add(items, actions, "现场照片草稿：" + evidenceDraftCount + " 张 · 仅本机保存",
                "mvs_evidence_list:" + order.orderId());
        add(items, actions, "拍摄工单现场照片",
                "mvs_evidence_capture:" + order.orderId());
        add(items, actions, "工单 SOP", resourceAction(order, "sop_tree"));
        add(items, actions, "现场附件", resourceAction(order, "attachments"));
        add(items, actions, "当前节点表单", resourceAction(order, "node_form"));
        add(items, actions, "当前节点可执行操作", resourceAction(order, "task_operations"));
        add(items, actions, "流程记录", resourceAction(order, "flow_records"));
        add(items, actions, "执行记录", resourceAction(order, "execution_records"));
        add(items, actions, "签到记录", resourceAction(order, "checkins"));
        add(items, actions, "签到要求", resourceAction(order, "checkin_required"));
        add(items, actions, "签退", "mvs_checkin_prepare:out:" + order.orderId());
        String assignmentId = clean(workflowAssignmentId);
        if (!assignmentId.isEmpty()) {
            add(items, actions, "现场工作流 · 已绑定", "workflow_open:" + assignmentId);
        }
        return new OperationDetail(
                "维修工单",
                order.orderNo(),
                "详情、SOP、表单和过程记录均来自服务端；AI 只能给建议，不能代替工程师写入 MVS。",
                items,
                actions,
                "mvs_checkin_prepare:in:" + order.orderId(),
                "签到",
                "mvs_work_order_list",
                "返回工单");
    }

    public OperationDetail evidenceDrafts(
            MvsWorkOrderDeviceClient.WorkOrder order,
            List<MvsWorkOrderEvidenceDraftStore.Draft> drafts
    ) {
        if (order == null) return failure("工单照片草稿不可用", "mvs_order_context_missing");
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (drafts != null) {
            for (MvsWorkOrderEvidenceDraftStore.Draft draft : drafts) {
                if (draft == null || !order.orderId().equals(draft.orderId())) continue;
                items.add("文件：" + fileName(draft.localReference())
                        + "\n大小：" + formatBytes(draft.byteSize())
                        + "\n状态：仅本机草稿 · 未上传 MVS");
                actions.add("mvs_evidence_discard_prepare:" + order.orderId()
                        + ":" + draft.id());
            }
        }
        if (items.isEmpty()) {
            items.add("当前工单没有本机照片草稿。");
            actions.add("");
        }
        return new OperationDetail(
                "维修工单",
                "现场照片草稿",
                "草稿只保存在本机私有目录。MVS 上传与附件绑定协议确认前，不会显示已上传或已同步。",
                items,
                actions,
                "mvs_evidence_capture:" + order.orderId(),
                "继续拍照",
                "mvs_work_order_open:" + order.orderId(),
                "返回详情");
    }

    public OperationDetail evidenceDiscardConfirmation(
            MvsWorkOrderDeviceClient.WorkOrder order,
            MvsWorkOrderEvidenceDraftStore.Draft draft
    ) {
        if (order == null || draft == null || !order.orderId().equals(draft.orderId())) {
            return failure("工单照片草稿不可用", "mvs_evidence_draft_missing");
        }
        List<String> items = new ArrayList<>();
        items.add("工单：" + order.orderNo());
        items.add("文件：" + fileName(draft.localReference()));
        items.add("删除后只移除本机私有文件和草稿索引，不会调用或修改 MVS。");
        return new OperationDetail(
                "二次确认",
                "确认删除本机草稿",
                "这是不可恢复的本机数据操作。AI 不会自动确认或删除现场证据。",
                items,
                "mvs_evidence_discard_confirmed:" + order.orderId() + ":" + draft.id(),
                "确认删除",
                "mvs_evidence_list:" + order.orderId(),
                "取消");
    }

    public OperationDetail resource(
            MvsWorkOrderDeviceClient.WorkOrder order,
            String resourceType,
            Object resource
    ) {
        if (order == null) return failure("工单资源不可用", "mvs_work_order_response_invalid");
        List<String> items = new ArrayList<>();
        if (resource instanceof JSONArray) {
            JSONArray values = (JSONArray) resource;
            for (int index = 0; index < values.length(); index++) {
                Object value = values.opt(index);
                items.add(renderResourceItem(value));
            }
        } else {
            items.add(renderResourceItem(resource));
        }
        if (items.isEmpty()) items.add("当前资源没有记录。");
        return new OperationDetail(
                "维修工单",
                resourceLabel(resourceType),
                "工单 " + order.orderNo() + " · 本页按需读取，不影响 AI 与语音主链路。",
                items,
                "",
                "",
                "mvs_work_order_open:" + order.orderId(),
                "返回详情");
    }

    public OperationDetail taskOperations(
            MvsWorkOrderDeviceClient.WorkOrder order,
            MvsWorkOrderDeviceClient.TaskOperations operations
    ) {
        if (order == null || operations == null
                || !order.definitionId().equals(operations.definitionId())
                || !order.nodeCode().equals(operations.nodeCode())) {
            return failure("当前节点操作不可用", "mvs_task_operations_context_invalid");
        }
        List<String> items = new ArrayList<>();
        for (MvsWorkOrderDeviceClient.TaskOperation operation : operations.items()) {
            if (operation == null) continue;
            items.add("操作：" + operation.label() + "\n"
                    + "服务端代码：" + operation.code() + " · 当前仅展示");
        }
        if (operations.filteredCount() > 0) {
            items.add("已隐藏 " + operations.filteredCount()
                    + " 项未知、重复或未启用操作。");
        }
        if (items.isEmpty()) items.add("当前节点没有可在眼镜展示的操作。");
        return new OperationDetail(
                "维修工单",
                "当前节点可执行操作",
                "工单 " + order.orderNo()
                        + " · 服务端按当前流程节点下发。当前版本仅展示，"
                        + "表单提交和流程推进尚未启用。",
                items,
                "",
                "",
                "mvs_work_order_open:" + order.orderId(),
                "返回详情");
    }

    public OperationDetail nodeForm(
            MvsWorkOrderDeviceClient.WorkOrder order,
            MvsWorkOrderNodeForm form,
            JSONObject draftValues
    ) {
        if (order == null || form == null || !order.orderId().equals(form.orderId())) {
            return failure("当前节点表单不可用", "mvs_node_form_context_invalid");
        }
        JSONObject values = draftValues == null ? new JSONObject() : draftValues;
        List<String> items = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (MvsWorkOrderNodeForm.Field field : form.fields()) {
            String label = field.label() + (field.required() ? "（必填）" : "");
            if (field.type() == MvsWorkOrderNodeForm.FieldType.UNSUPPORTED) {
                add(items, actions, label + " · 需手机/PC完成", "");
                continue;
            }
            Object value = values.opt(field.key());
            String action = formFieldAction(form, field);
            String suffix = renderFormValue(value);
            if (action.isEmpty()
                    && (field.type() == MvsWorkOrderNodeForm.FieldType.SIGNATURE
                    || field.type() == MvsWorkOrderNodeForm.FieldType.LOCATION)) {
                suffix += " · 当前需手机/PC完成";
            }
            add(items, actions, label + " · " + suffix, action);
        }
        if (items.isEmpty()) {
            items.add("服务端当前没有下发眼镜可安全填写的字段。请在手机/PC完成该节点表单。");
            actions.add("");
        }
        return new OperationDetail(
                "维修工单",
                "当前节点表单",
                "工单 " + order.orderNo() + " · 表单值仅本机草稿保存。MVS 写回和流程推进协议未完成联调前，不提供提交或假成功状态。",
                items,
                actions,
                "",
                "",
                "mvs_work_order_open:" + order.orderId(),
                "返回详情");
    }

    public OperationDetail confirmation(MvsWorkOrderDeviceClient.WorkOrder order, String direction) {
        if (order == null) return failure("工单不可用", "mvs_work_order_response_invalid");
        boolean checkout = "out".equals(clean(direction));
        String action = checkout ? "签退" : "签到";
        List<String> items = new ArrayList<>();
        items.add("工单：" + order.orderNo());
        items.add("操作：" + action);
        items.add("确认后才会获取现场位置并提交到工单网关；AI 不会自动确认或自动写入。");
        return new OperationDetail(
                "二次确认",
                "确认" + action,
                "这是高风险写操作。网络失败会保留原工单页面并明确提示，不会显示假成功。",
                items,
                "mvs_checkin_confirmed:" + (checkout ? "out" : "in") + ":" + order.orderId(),
                "确认" + action,
                "mvs_work_order_open:" + order.orderId(),
                "取消");
    }

    public OperationDetail receipt(
            MvsWorkOrderDeviceClient.WorkOrder order,
            String direction,
            MvsWorkOrderDeviceClient.CheckinReceipt receipt
    ) {
        List<String> items = new ArrayList<>();
        items.add(("out".equals(direction) ? "签退" : "签到") + "已由服务端确认");
        items.add("操作回执：" + receipt.operationId());
        if (receipt.duplicate()) items.add("本次为幂等重试，未重复写入。");
        return new OperationDetail(
                "维修工单",
                "操作成功",
                "MVS 回执已返回；本地没有使用假状态代替服务端结果。",
                items,
                "mvs_work_order_open:" + order.orderId(),
                "返回详情",
                "mvs_work_order_list",
                "返回工单");
    }

    public OperationDetail failure(String title, String errorCode) {
        List<String> items = new ArrayList<>();
        items.add("请求阶段：维修工单网关");
        items.add("错误：" + text(errorCode, "mvs_request_failed"));
        items.add("照片、当前任务和既有工作流不会被清除，可稍后重试。");
        return new OperationDetail(
                "维修工单",
                text(title, "服务暂不可用"),
                "未收到权威服务端成功回执，本次操作没有生效。",
                items,
                "mvs_work_order_list",
                "返回工单",
                "",
                "");
    }

    private static void add(List<String> items, List<String> actions, String item, String action) {
        items.add(item);
        actions.add(clean(action));
    }

    private static void appendUnboundWorkflows(
            List<String> items,
            List<String> actions,
            MvsWorkOrderDeviceClient.WorkOrderPage page,
            List<WorkflowExecutionCoordinator.TaskListItem> workflows
    ) {
        if (workflows == null) return;
        for (WorkflowExecutionCoordinator.TaskListItem task : workflows) {
            if (task == null || matchesMvsOrder(page, task)) continue;
            StringBuilder content = new StringBuilder("受管工作流 · ")
                    .append(text(task.displayCode(), task.workOrderId()))
                    .append(" · ")
                    .append(text(task.title(), "工单详情待同步"));
            if (!task.assetLabel().isEmpty()) {
                content.append("\n设备：").append(task.assetLabel());
            }
            content.append("\n状态：").append(task.entryAction().name());
            items.add(content.toString());
            actions.add("workflow_open:" + task.assignmentId());
        }
    }

    private static boolean matchesMvsOrder(
            MvsWorkOrderDeviceClient.WorkOrderPage page,
            WorkflowExecutionCoordinator.TaskListItem task
    ) {
        if (page == null) return false;
        for (MvsWorkOrderDeviceClient.WorkOrder order : page.items()) {
            if (order != null && (order.orderId().equals(task.workOrderId())
                    || order.orderNo().equals(task.displayCode()))) {
                return true;
            }
        }
        return false;
    }

    private static String resourceAction(
            MvsWorkOrderDeviceClient.WorkOrder order,
            String resource
    ) {
        String suffix = "";
        if ("checkin_required".equals(resource) && !order.definitionId().isEmpty()) {
            suffix = ":" + order.definitionId();
        }
        return "mvs_work_order_resource:" + resource + ":" + order.orderId() + suffix;
    }

    private static String renderResourceItem(Object value) {
        if (value == null || value == JSONObject.NULL) return "暂无内容";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String title = first(object, "label", "name", "fileName", "originalName",
                    "nodeName", "title", "fieldName");
            String status = first(object, "status", "flowStatus", "result", "type", "direction");
            String time = first(object, "time", "createdAt", "operateTime", "checkinTime");
            String detail = first(object, "description", "remark", "value", "content");
            StringBuilder result = new StringBuilder(text(title, "记录"));
            if (!status.isEmpty()) result.append(" · ").append(status);
            if (!time.isEmpty()) result.append("\n时间：").append(time);
            if (!detail.isEmpty()) result.append("\n").append(detail);
            if (result.toString().equals("记录")) return object.toString();
            return result.toString();
        }
        return String.valueOf(value);
    }

    private static String formFieldAction(
            MvsWorkOrderNodeForm form,
            MvsWorkOrderNodeForm.Field field
    ) {
        if (field.type() == MvsWorkOrderNodeForm.FieldType.TEXT
                || field.type() == MvsWorkOrderNodeForm.FieldType.NUMBER
                || field.type() == MvsWorkOrderNodeForm.FieldType.SINGLE_CHOICE
                || field.type() == MvsWorkOrderNodeForm.FieldType.MULTI_CHOICE
                || field.type() == MvsWorkOrderNodeForm.FieldType.DATE) {
            return "mvs_form_edit:" + form.orderId() + ":" + field.key();
        }
        if (field.type() == MvsWorkOrderNodeForm.FieldType.PHOTO) {
            return "mvs_form_photo_pick:" + form.orderId() + ":" + field.key();
        }
        return "";
    }

    private static String renderFormValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "未填写";
        if (value instanceof JSONArray) {
            JSONArray values = (JSONArray) value;
            if (values.length() == 0) return "未填写";
            return "已选择 " + values.length() + " 项";
        }
        if (value instanceof JSONObject) return "已保存";
        String accepted = clean(String.valueOf(value));
        if (accepted.isEmpty()) return "未填写";
        return accepted.length() > 120 ? accepted.substring(0, 120) + "…" : accepted;
    }

    private static String first(JSONObject value, String... keys) {
        for (String key : keys) {
            Object item = value.opt(key);
            if (item instanceof String && !clean((String) item).isEmpty()) {
                return clean((String) item);
            }
            if (item instanceof Number || item instanceof Boolean) return String.valueOf(item);
        }
        return "";
    }

    private static String resourceLabel(String resource) {
        if ("node_form".equals(resource)) return "当前节点表单";
        if ("sop_tree".equals(resource)) return "工单 SOP";
        if ("attachments".equals(resource)) return "现场附件";
        if ("flow_records".equals(resource)) return "流程记录";
        if ("execution_records".equals(resource)) return "执行记录";
        if ("checkins".equals(resource)) return "签到记录";
        if ("checkin_form".equals(resource)) return "签到表单";
        if ("checkin_required".equals(resource)) return "签到要求";
        if ("task_operations".equals(resource)) return "当前节点可执行操作";
        return "工单详情";
    }

    private static String fileName(String reference) {
        String value = clean(reference);
        int separator = value.lastIndexOf('/');
        return separator >= 0 ? value.substring(separator + 1) : value;
    }

    private static String formatBytes(long value) {
        if (value < 1024L) return value + " B";
        return String.format(java.util.Locale.ROOT, "%.1f KB", value / 1024.0d);
    }

    private static String nextView(String view) {
        if ("pending".equals(view)) return "pending_execute";
        if ("pending_execute".equals(view)) return "executing";
        if ("executing".equals(view)) return "completed";
        return "pending";
    }

    private static String viewLabel(String view) {
        if ("pending".equals(view)) return "待接收";
        if ("pending_execute".equals(view)) return "待执行";
        if ("completed".equals(view)) return "已完成";
        return "执行中";
    }

    private static String join(String first, String second) {
        String left = clean(first);
        String right = clean(second);
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + " · " + right;
    }

    private static String text(String value, String fallback) {
        String result = clean(value);
        return result.isEmpty() ? fallback : result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
