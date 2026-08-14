package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses only the fixed field subset the glasses can safely render and persist. */
public final class MvsWorkOrderNodeForm {
    private static final int MAX_FIELDS = 64;
    private static final int MAX_OPTIONS = 30;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final String FIELD_KEY_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}$";
    private static final String NODE_CODE_PATTERN = "^[A-Za-z0-9_.:-]{0,199}$";

    public enum FieldType {
        TEXT,
        NUMBER,
        SINGLE_CHOICE,
        MULTI_CHOICE,
        DATE,
        PHOTO,
        SIGNATURE,
        LOCATION,
        UNSUPPORTED
    }

    public static final class Option {
        private final String value;
        private final String label;

        private Option(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() { return value; }
        public String label() { return label; }
    }

    public static final class Field {
        private final String key;
        private final String label;
        private final FieldType type;
        private final boolean required;
        private final int maximumLength;
        private final Double minimum;
        private final Double maximum;
        private final List<Option> options;

        private Field(
                String key,
                String label,
                FieldType type,
                boolean required,
                int maximumLength,
                Double minimum,
                Double maximum,
                List<Option> options
        ) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.required = required;
            this.maximumLength = maximumLength;
            this.minimum = minimum;
            this.maximum = maximum;
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
        }

        public String key() { return key; }
        public String label() { return label; }
        public FieldType type() { return type; }
        public boolean required() { return required; }
        public int maximumLength() { return maximumLength; }
        public Double minimum() { return minimum; }
        public Double maximum() { return maximum; }
        public List<Option> options() { return options; }
        public boolean editable() { return type != FieldType.UNSUPPORTED; }

        Object normalizeValue(Object value, String orderId) {
            if (type == FieldType.UNSUPPORTED) {
                throw new IllegalArgumentException("mvs form field is unsupported");
            }
            if (type == FieldType.TEXT) return textValue(value);
            if (type == FieldType.NUMBER) return numberValue(value);
            if (type == FieldType.SINGLE_CHOICE) return singleChoiceValue(value);
            if (type == FieldType.MULTI_CHOICE) return multiChoiceValue(value);
            if (type == FieldType.DATE) return dateValue(value);
            if (type == FieldType.PHOTO) return evidenceValue(value);
            if (type == FieldType.SIGNATURE) return signatureValue(value, orderId);
            if (type == FieldType.LOCATION) return locationValue(value);
            throw new IllegalArgumentException("mvs form field is unsupported");
        }

        private String textValue(Object value) {
            if (!(value instanceof String)) throw new IllegalArgumentException("mvs form text is invalid");
            String accepted = ((String) value).trim();
            if (accepted.length() > maximumLength) {
                throw new IllegalArgumentException("mvs form text is too long");
            }
            if (required && accepted.isEmpty()) throw new IllegalArgumentException("mvs form field is required");
            return accepted;
        }

        private String numberValue(Object value) {
            String accepted = value instanceof Number || value instanceof String
                    ? String.valueOf(value).trim() : "";
            if (!accepted.matches("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")) {
                throw new IllegalArgumentException("mvs form number is invalid");
            }
            double parsed;
            try {
                parsed = Double.parseDouble(accepted);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("mvs form number is invalid");
            }
            if (!Double.isFinite(parsed)) throw new IllegalArgumentException("mvs form number is invalid");
            if (minimum != null && parsed < minimum) {
                throw new IllegalArgumentException("mvs form number is below minimum");
            }
            if (maximum != null && parsed > maximum) {
                throw new IllegalArgumentException("mvs form number is above maximum");
            }
            return accepted;
        }

        private String singleChoiceValue(Object value) {
            String accepted = value instanceof String ? ((String) value).trim() : "";
            if (!optionValues().contains(accepted)) {
                throw new IllegalArgumentException("mvs form choice is invalid");
            }
            return accepted;
        }

        private JSONArray multiChoiceValue(Object value) {
            if (!(value instanceof JSONArray)) {
                throw new IllegalArgumentException("mvs form choice is invalid");
            }
            JSONArray received = (JSONArray) value;
            if (received.length() > options.size()) {
                throw new IllegalArgumentException("mvs form choice is invalid");
            }
            JSONArray result = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < received.length(); index += 1) {
                Object item = received.opt(index);
                String accepted = item instanceof String ? ((String) item).trim() : "";
                if (!optionValues().contains(accepted) || !seen.add(accepted)) {
                    throw new IllegalArgumentException("mvs form choice is invalid");
                }
                result.put(accepted);
            }
            if (required && result.length() == 0) {
                throw new IllegalArgumentException("mvs form field is required");
            }
            return result;
        }

        private String dateValue(Object value) {
            String accepted = value instanceof String ? ((String) value).trim() : "";
            String pattern = accepted.matches("^\\d{4}-\\d{2}-\\d{2}$")
                    ? "yyyy-MM-dd"
                    : accepted.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")
                    ? "yyyy-MM-dd HH:mm" : "";
            if (pattern.isEmpty() || !strictDate(accepted, pattern)) {
                throw new IllegalArgumentException("mvs form date is invalid");
            }
            return accepted;
        }

        private JSONArray evidenceValue(Object value) {
            JSONArray received;
            if (value instanceof JSONArray) {
                received = (JSONArray) value;
            } else if (value instanceof String) {
                received = new JSONArray().put(value);
            } else {
                throw new IllegalArgumentException("mvs form photo is invalid");
            }
            if (received.length() > 12) throw new IllegalArgumentException("mvs form photo is invalid");
            JSONArray result = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < received.length(); index += 1) {
                Object item = received.opt(index);
                String id = item instanceof String ? ((String) item).trim() : "";
                if (!id.matches("^mvs-evidence-draft-[A-Za-z0-9-]{8,80}$") || !seen.add(id)) {
                    throw new IllegalArgumentException("mvs form photo is invalid");
                }
                result.put(id);
            }
            if (required && result.length() == 0) {
                throw new IllegalArgumentException("mvs form field is required");
            }
            return result;
        }

        private String signatureValue(Object value, String orderId) {
            String accepted = value instanceof String ? ((String) value).trim() : "";
            String prefix = "mvs-signature/" + orderId + "/";
            if (!accepted.startsWith(prefix) || !accepted.endsWith(".png")
                    || accepted.length() > prefix.length() + 160
                    || accepted.contains("..")
                    || !accepted.substring(prefix.length()).matches("^[A-Za-z0-9_.-]+\\.png$")) {
                throw new IllegalArgumentException("mvs form signature is invalid");
            }
            return accepted;
        }

        private JSONObject locationValue(Object value) {
            if (!(value instanceof JSONObject)) {
                throw new IllegalArgumentException("mvs form location is invalid");
            }
            JSONObject received = (JSONObject) value;
            Object latValue = received.opt("lat");
            Object lngValue = received.opt("lng");
            if (!(latValue instanceof Number) || !(lngValue instanceof Number)) {
                throw new IllegalArgumentException("mvs form location is invalid");
            }
            double lat = ((Number) latValue).doubleValue();
            double lng = ((Number) lngValue).doubleValue();
            if (!Double.isFinite(lat) || !Double.isFinite(lng)
                    || lat < -90d || lat > 90d || lng < -180d || lng > 180d) {
                throw new IllegalArgumentException("mvs form location is invalid");
            }
            String address = received.optString("address", "").trim();
            if (address.length() > 1_000) {
                throw new IllegalArgumentException("mvs form location is invalid");
            }
            try {
                JSONObject result = new JSONObject().put("lat", lat).put("lng", lng);
                if (!address.isEmpty()) result.put("address", address);
                return result;
            } catch (JSONException exception) {
                throw new IllegalArgumentException("mvs form location is invalid", exception);
            }
        }

        private Set<String> optionValues() {
            Set<String> result = new HashSet<>();
            for (Option option : options) result.add(option.value);
            return result;
        }
    }

    private final String orderId;
    private final int formId;
    private final String nodeCode;
    private final List<Field> fields;
    private final String schemaFingerprint;

    private MvsWorkOrderNodeForm(
            String orderId,
            int formId,
            String nodeCode,
            List<Field> fields
    ) {
        this.orderId = orderId;
        this.formId = formId;
        this.nodeCode = nodeCode;
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        this.schemaFingerprint = fingerprint(orderId, formId, nodeCode, fields);
    }

    public String orderId() { return orderId; }
    public int formId() { return formId; }
    public String nodeCode() { return nodeCode; }
    public List<Field> fields() { return fields; }
    public String schemaFingerprint() { return schemaFingerprint; }

    public Field field(String key) {
        String accepted = key == null ? "" : key.trim();
        for (Field field : fields) {
            if (field.key.equals(accepted)) return field;
        }
        return null;
    }

    public static MvsWorkOrderNodeForm parse(String orderId, JSONObject source) {
        String acceptedOrderId = numericOrderId(orderId);
        if (source == null) throw new IllegalArgumentException("mvs node form is missing");
        int formId = positiveInteger(first(source, "formId", "id", "form_id"));
        String nodeCode = text(first(source, "nodeCode", "node_code"), 200);
        if (!nodeCode.matches(NODE_CODE_PATTERN)) {
            throw new IllegalArgumentException("mvs node form node is invalid");
        }
        JSONArray values = fields(source);
        List<Field> parsed = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; values != null && index < values.length(); index += 1) {
            if (parsed.size() >= MAX_FIELDS) throw new IllegalArgumentException("mvs node form has too many fields");
            JSONObject item = values.optJSONObject(index);
            if (item == null) {
                parsed.add(unsupported("unsupported-" + index, "未识别字段"));
                continue;
            }
            Field field = parseField(item, index);
            if (!field.key.isEmpty() && !keys.add(field.key)) {
                parsed.add(unsupported("duplicate-" + index, field.label));
            } else {
                parsed.add(field);
            }
        }
        return new MvsWorkOrderNodeForm(acceptedOrderId, formId, nodeCode, parsed);
    }

    private static Field parseField(JSONObject value, int index) {
        String key = text(first(value, "fieldKey", "key", "code", "name", "id"), 120);
        String label = text(first(value, "label", "title", "fieldName", "name"), 160);
        if (label.isEmpty()) label = "字段 " + (index + 1);
        if (!key.matches(FIELD_KEY_PATTERN)) return unsupported("unsupported-" + index, label);
        FieldType type = fieldType(text(first(value,
                "type", "componentType", "controlType", "widgetType", "kind"), 80));
        boolean required = value.optBoolean("required", false) || value.optBoolean("isRequired", false);
        int maximumLength = boundedLength(value.opt("maxLength"));
        Double minimum = finiteNumber(value.opt("min"));
        Double maximum = finiteNumber(value.opt("max"));
        if (minimum != null && maximum != null && minimum > maximum) {
            return unsupported(key, label);
        }
        List<Option> options = options(value);
        if ((type == FieldType.SINGLE_CHOICE || type == FieldType.MULTI_CHOICE)
                && options.isEmpty()) {
            type = FieldType.UNSUPPORTED;
        }
        return new Field(key, label, type, required, maximumLength, minimum, maximum, options);
    }

    private static Field unsupported(String key, String label) {
        return new Field(key, label, FieldType.UNSUPPORTED, false,
                MAX_TEXT_LENGTH, null, null, Collections.<Option>emptyList());
    }

    private static JSONArray fields(JSONObject source) {
        for (String key : new String[]{"fields", "formItems", "components", "items"}) {
            JSONArray values = source.optJSONArray(key);
            if (values != null) return values;
        }
        for (String container : new String[]{"form", "schema", "formContent", "config"}) {
            JSONObject nested = source.optJSONObject(container);
            if (nested == null) continue;
            for (String key : new String[]{"fields", "formItems", "components", "items"}) {
                JSONArray values = nested.optJSONArray(key);
                if (values != null) return values;
            }
        }
        return null;
    }

    private static FieldType fieldType(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.equals("text") || normalized.equals("textarea")
                || normalized.equals("input") || normalized.equals("string")) return FieldType.TEXT;
        if (normalized.equals("number") || normalized.equals("inputnumber")
                || normalized.equals("integer") || normalized.equals("decimal")
                || normalized.equals("amount")) return FieldType.NUMBER;
        if (normalized.equals("radio") || normalized.equals("select")
                || normalized.equals("dropdown") || normalized.equals("singlechoice")) {
            return FieldType.SINGLE_CHOICE;
        }
        if (normalized.equals("checkbox") || normalized.equals("multiselect")
                || normalized.equals("multiplechoice")) return FieldType.MULTI_CHOICE;
        if (normalized.equals("date") || normalized.equals("datepicker")
                || normalized.equals("datetime") || normalized.equals("datetimepicker")) {
            return FieldType.DATE;
        }
        if (normalized.equals("photo") || normalized.equals("image")
                || normalized.equals("picture") || normalized.equals("upload")
                || normalized.equals("file") || normalized.equals("attachment")) return FieldType.PHOTO;
        if (normalized.equals("signature") || normalized.equals("sign")) return FieldType.SIGNATURE;
        if (normalized.equals("location") || normalized.equals("map")
                || normalized.equals("coordinate") || normalized.equals("geolocation")) {
            return FieldType.LOCATION;
        }
        return FieldType.UNSUPPORTED;
    }

    private static List<Option> options(JSONObject field) {
        JSONArray values = null;
        for (String key : new String[]{"options", "choices", "values", "dictItems"}) {
            values = field.optJSONArray(key);
            if (values != null) break;
        }
        if (values == null || values.length() > MAX_OPTIONS) return Collections.emptyList();
        List<Option> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < values.length(); index += 1) {
            Object raw = values.opt(index);
            String value;
            String label;
            if (raw instanceof JSONObject) {
                JSONObject object = (JSONObject) raw;
                value = text(first(object, "value", "id", "key", "code"), 160);
                label = text(first(object, "label", "name", "text", "value"), 160);
            } else if (raw instanceof String || raw instanceof Number) {
                value = String.valueOf(raw).trim();
                label = value;
            } else {
                return Collections.emptyList();
            }
            if (value.isEmpty() || label.isEmpty() || !seen.add(value)) return Collections.emptyList();
            result.add(new Option(value, label));
        }
        return result;
    }

    private static int boundedLength(Object value) {
        if (!(value instanceof Number)) return 500;
        int length = ((Number) value).intValue();
        return length > 0 && length <= MAX_TEXT_LENGTH ? length : 500;
    }

    private static Double finiteNumber(Object value) {
        if (!(value instanceof Number)) return null;
        double result = ((Number) value).doubleValue();
        return Double.isFinite(result) ? result : null;
    }

    private static Object first(JSONObject source, String... keys) {
        for (String key : keys) {
            Object value = source.opt(key);
            if (value != null && value != JSONObject.NULL) return value;
        }
        return "";
    }

    private static String text(Object value, int maximum) {
        String result = value instanceof String || value instanceof Number
                ? String.valueOf(value).trim() : "";
        return result.length() <= maximum ? result : "";
    }

    private static int positiveInteger(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("mvs node form id is invalid");
        long result = ((Number) value).longValue();
        if (result < 1 || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("mvs node form id is invalid");
        }
        return (int) result;
    }

    private static String numericOrderId(String value) {
        String accepted = value == null ? "" : value.trim();
        if (!accepted.matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalArgumentException("mvs order id is invalid");
        }
        return accepted;
    }

    private static boolean strictDate(String value, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        return format.parse(value, position) != null && position.getIndex() == value.length();
    }

    private static String fingerprint(String orderId, int formId, String nodeCode, List<Field> fields) {
        StringBuilder source = new StringBuilder(orderId).append('|').append(formId)
                .append('|').append(nodeCode);
        for (Field field : fields) {
            source.append('\n').append(field.key).append('|').append(field.label)
                    .append('|').append(field.type.name()).append('|').append(field.required)
                    .append('|').append(field.maximumLength).append('|').append(field.minimum)
                    .append('|').append(field.maximum);
            for (Option option : field.options) {
                source.append('|').append(option.value).append('=').append(option.label);
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }
}
