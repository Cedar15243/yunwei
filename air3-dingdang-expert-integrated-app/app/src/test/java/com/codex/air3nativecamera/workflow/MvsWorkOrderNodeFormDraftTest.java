package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

public final class MvsWorkOrderNodeFormDraftTest {
    @Test
    public void parsesOnlyWhitelistedFieldTypesAndCreatesStableSchemaFingerprint()
            throws Exception {
        MvsWorkOrderNodeForm form = MvsWorkOrderNodeForm.parse("42", new JSONObject()
                .put("id", 8)
                .put("nodeCode", "onsite-repair")
                .put("fields", new JSONArray()
                        .put(new JSONObject()
                                .put("fieldKey", "outletTemperature")
                                .put("label", "出水温度")
                                .put("type", "number")
                                .put("required", true)
                                .put("min", 0)
                                .put("max", 100))
                        .put(new JSONObject()
                                .put("fieldKey", "repairResult")
                                .put("label", "处理结果")
                                .put("type", "radio")
                                .put("required", true)
                                .put("options", new JSONArray()
                                        .put(new JSONObject().put("value", "resolved")
                                                .put("label", "已恢复"))
                                        .put(new JSONObject().put("value", "pending")
                                                .put("label", "待复核"))))
                        .put(new JSONObject()
                                .put("fieldKey", "vendorPlugin")
                                .put("label", "第三方插件")
                                .put("type", "custom-script"))));

        assertEquals("42", form.orderId());
        assertEquals(8, form.formId());
        assertEquals("onsite-repair", form.nodeCode());
        assertEquals(3, form.fields().size());
        assertEquals(MvsWorkOrderNodeForm.FieldType.NUMBER, form.fields().get(0).type());
        assertEquals(MvsWorkOrderNodeForm.FieldType.SINGLE_CHOICE,
                form.fields().get(1).type());
        assertEquals(MvsWorkOrderNodeForm.FieldType.UNSUPPORTED,
                form.fields().get(2).type());
        assertTrue(form.schemaFingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    public void persistsOnlyValidatedValuesForTheMatchingOrderAndSchema() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        MvsWorkOrderNodeForm form = MvsWorkOrderNodeForm.parse("42", supportedForm());
        MvsWorkOrderFormDraftStore store = new MvsWorkOrderFormDraftStore(
                storage, () -> 1_700_000_000_000L);

        store.saveField(form, "outletTemperature", "18.5");
        store.saveField(form, "repairResult", "resolved");
        assertEquals("18.5", store.valuesFor(form).optString("outletTemperature"));
        assertEquals("resolved", store.valuesFor(form).optString("repairResult"));

        try {
            store.saveField(form, "repairResult", "unapproved");
            throw new AssertionError("choice outside server options must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("choice"));
        }

        MvsWorkOrderFormDraftStore restored = new MvsWorkOrderFormDraftStore(storage);
        assertEquals("18.5", restored.valuesFor(form).optString("outletTemperature"));
        MvsWorkOrderNodeForm changed = MvsWorkOrderNodeForm.parse("42", supportedForm()
                .put("nodeCode", "onsite-repair-v2"));
        assertFalse(restored.valuesFor(changed).has("outletTemperature"));
    }

    private static JSONObject supportedForm() throws Exception {
        return new JSONObject()
                .put("formId", 8)
                .put("nodeCode", "onsite-repair")
                .put("fields", new JSONArray()
                        .put(new JSONObject()
                                .put("fieldKey", "outletTemperature")
                                .put("label", "出水温度")
                                .put("type", "number")
                                .put("required", true)
                                .put("min", 0)
                                .put("max", 100))
                        .put(new JSONObject()
                                .put("fieldKey", "repairResult")
                                .put("label", "处理结果")
                                .put("type", "radio")
                                .put("required", true)
                                .put("options", new JSONArray()
                                        .put(new JSONObject().put("value", "resolved")
                                                .put("label", "已恢复"))
                                        .put(new JSONObject().put("value", "pending")
                                                .put("label", "待复核")))));
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
