package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.codex.air3nativecamera.features.operations.OperationDetail;
import com.codex.air3nativecamera.sync.MvsWorkOrderDeviceClient;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;

public final class MvsWorkOrderHudPresenterTest {
    @Test
    public void rendersServerOrdersInsideTheExistingRepairOrderHud() throws Exception {
        OperationDetail detail = new MvsWorkOrderHudPresenter().taskList(
                page("executing", order()));

        assertEquals("维修工单", detail.tag());
        assertEquals("我的维修工单", detail.title());
        assertTrue(detail.description().contains("执行中"));
        assertTrue(detail.items().get(0).contains("MVS-42"));
        assertTrue(detail.items().get(0).contains("冷水机组控制器"));
        assertEquals("mvs_work_order_open:42", detail.itemActions().get(0));
        assertEquals("mvs_work_order_view:completed", detail.primaryAction());
        assertEquals("mvs_work_order_refresh:executing", detail.secondaryAction());
    }

    @Test
    public void exposesLazyResourcesBoundWorkflowAndExplicitCheckinConfirmation()
            throws Exception {
        MvsWorkOrderDeviceClient.WorkOrder order = order();
        MvsWorkOrderHudPresenter presenter = new MvsWorkOrderHudPresenter();

        OperationDetail detail = presenter.taskDetail(order, "assignment-a", 2);

        assertEquals("mvs_checkin_prepare:in:42", detail.primaryAction());
        assertEquals("mvs_work_order_list", detail.secondaryAction());
        assertTrue(detail.itemActions().contains("mvs_work_order_resource:node_form:42"));
        assertTrue(detail.itemActions().contains("mvs_work_order_resource:sop_tree:42"));
        assertTrue(detail.itemActions().contains("mvs_work_order_resource:attachments:42"));
        assertTrue(detail.itemActions().contains("mvs_evidence_capture:42"));
        assertTrue(detail.items().toString().contains("现场照片草稿：2 张"));
        assertTrue(detail.itemActions().contains("mvs_work_order_resource:flow_records:42"));
        assertTrue(detail.itemActions().contains("mvs_work_order_resource:task_operations:42"));
        assertTrue(detail.itemActions().contains("workflow_open:assignment-a"));

        OperationDetail confirmation = presenter.confirmation(order, "out");
        assertEquals("二次确认", confirmation.tag());
        assertEquals("mvs_checkin_confirmed:out:42", confirmation.primaryAction());
        assertTrue(confirmation.description().contains("高风险写操作"));
    }

    @Test
    public void rendersServerDeclaredTaskOperationsAsReadOnlyInformation() throws Exception {
        MvsWorkOrderDeviceClient.TaskOperations operations = taskOperations();

        OperationDetail detail = new MvsWorkOrderHudPresenter()
                .taskOperations(order(), operations);

        assertEquals("当前节点可执行操作", detail.title());
        assertTrue(detail.description().contains("仅展示"));
        assertTrue(detail.items().get(0).contains("提交并推进"));
        assertTrue(detail.items().get(1).contains("退回"));
        assertTrue(detail.items().get(2).contains("已隐藏 1 项"));
        assertEquals(Arrays.asList("", "", ""), detail.itemActions());
        assertFalse(detail.primaryAction().contains("skip"));
        assertFalse(detail.primaryAction().contains("submit"));
        assertFalse(detail.secondaryAction().contains("reject"));
        assertFalse(detail.secondaryAction().contains("transfer"));
    }

    @Test
    public void rendersLocalEvidenceDraftsAndRequiresConfirmationBeforeDiscard() throws Exception {
        MvsWorkOrderEvidenceDraftStore store = new MvsWorkOrderEvidenceDraftStore(
                new MemoryStorage());
        MvsWorkOrderEvidenceDraftStore.Draft draft = store.recordPhoto(
                "42",
                "mvs-work-order-evidence/42/capture-a.jpg",
                512,
                repeat('a', 64));
        MvsWorkOrderHudPresenter presenter = new MvsWorkOrderHudPresenter();

        OperationDetail drafts = presenter.evidenceDrafts(order(), store.draftsForOrder("42"));
        assertEquals("现场照片草稿", drafts.title());
        assertEquals("mvs_evidence_capture:42", drafts.primaryAction());
        assertTrue(drafts.items().get(0).contains("capture-a.jpg"));
        assertTrue(drafts.itemActions().contains(
                "mvs_evidence_discard_prepare:42:" + draft.id()));

        OperationDetail confirmation = presenter.evidenceDiscardConfirmation(order(), draft);
        assertEquals("二次确认", confirmation.tag());
        assertEquals("mvs_evidence_discard_confirmed:42:" + draft.id(),
                confirmation.primaryAction());
    }

    @Test
    public void rendersOnlyEditableNodeFormFieldsAndNeverOffersAnMvsSubmitAction()
            throws Exception {
        MvsWorkOrderNodeForm form = MvsWorkOrderNodeForm.parse("42", new JSONObject()
                .put("formId", 8)
                .put("nodeCode", "onsite-repair")
                .put("fields", new org.json.JSONArray()
                        .put(new JSONObject()
                                .put("fieldKey", "outletTemperature")
                                .put("label", "出水温度")
                                .put("type", "number")
                                .put("required", true))
                        .put(new JSONObject()
                                .put("fieldKey", "vendorPlugin")
                                .put("label", "第三方插件")
                                .put("type", "custom-script"))));
        JSONObject draft = new JSONObject().put("outletTemperature", "18.5");

        OperationDetail detail = new MvsWorkOrderHudPresenter().nodeForm(order(), form, draft);

        assertEquals("当前节点表单", detail.title());
        assertTrue(detail.description().contains("仅本机草稿"));
        assertTrue(detail.items().get(0).contains("18.5"));
        assertEquals("mvs_form_edit:42:outletTemperature", detail.itemActions().get(0));
        assertTrue(detail.items().get(1).contains("手机/PC"));
        assertEquals("", detail.itemActions().get(1));
        assertFalse(detail.primaryAction().contains("submit"));
        assertFalse(detail.secondaryAction().contains("submit"));
    }

    private static MvsWorkOrderDeviceClient.WorkOrder order() throws Exception {
        Constructor<MvsWorkOrderDeviceClient.WorkOrder> constructor =
                MvsWorkOrderDeviceClient.WorkOrder.class.getDeclaredConstructor(JSONObject.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new JSONObject()
                .put("sourceSystem", "mvs")
                .put("orderId", 42)
                .put("orderNo", "MVS-42")
                .put("orderStatus", "EXECUTING")
                .put("flowStatus", "ACTIVE")
                .put("definitionId", 99)
                .put("nodeCode", "onsite-repair")
                .put("nodeName", "现场处理")
                .put("projectName", "冷站年度维护")
                .put("siteName", "一号冷站")
                .put("assetName", "冷水机组控制器")
                .put("assetModel", "HF-CH-01")
                .put("priority", "high"));
    }

    private static MvsWorkOrderDeviceClient.TaskOperations taskOperations() throws Exception {
        Constructor<MvsWorkOrderDeviceClient.TaskOperation> operationConstructor =
                MvsWorkOrderDeviceClient.TaskOperation.class.getDeclaredConstructor(
                        JSONObject.class);
        operationConstructor.setAccessible(true);
        MvsWorkOrderDeviceClient.TaskOperation skip = operationConstructor.newInstance(
                new JSONObject().put("code", "skip").put("label", "提交并推进"));
        MvsWorkOrderDeviceClient.TaskOperation reject = operationConstructor.newInstance(
                new JSONObject().put("code", "reject").put("label", "退回"));
        Constructor<MvsWorkOrderDeviceClient.TaskOperations> constructor =
                MvsWorkOrderDeviceClient.TaskOperations.class.getDeclaredConstructor(
                        String.class, String.class, java.util.List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                "99", "onsite-repair", Arrays.asList(skip, reject), 1);
    }

    private static MvsWorkOrderDeviceClient.WorkOrderPage page(
            String view,
            MvsWorkOrderDeviceClient.WorkOrder order
    ) throws Exception {
        Constructor<MvsWorkOrderDeviceClient.WorkOrderPage> constructor =
                MvsWorkOrderDeviceClient.WorkOrderPage.class.getDeclaredConstructor(
                        java.util.List.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(Collections.singletonList(order), view);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    private static final class MemoryStorage implements WorkflowSnapshotStorage {
        private byte[] value;

        @Override public byte[] read() {
            return value == null ? null : value.clone();
        }

        @Override public void writeAtomically(byte[] next) throws IOException {
            value = next.clone();
        }
    }
}
