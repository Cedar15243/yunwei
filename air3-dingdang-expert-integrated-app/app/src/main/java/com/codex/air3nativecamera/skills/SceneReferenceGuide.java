package com.codex.air3nativecamera.skills;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provides explicitly labeled reference images when a field photo cannot be located reliably. */
public final class SceneReferenceGuide {
    private static final String ASSET_PREFIX = "asset://scene-reference/";
    private static final String CAPTION = "检查点参考示意：请按图靠近拍摄标注区域。"
            + "此图仅说明检查位置，不代表已识别当前现场照片。";
    private static final String ACCEPTED_CAPTION = "检查点参考示意：本轮已识别当前检查对象。"
            + "因现场照片清晰度有限，以下仅显示标准检测位置，不代表对当前照片的精确框选。";
    private static final Map<String, Reference> REFERENCES = createReferences();

    public static final class Reference {
        private final String imageId;
        private final String caption;

        Reference(String imageId, String caption) {
            this.imageId = imageId;
            this.caption = caption;
        }

        public String imageId() {
            return imageId;
        }

        public String caption() {
            return caption;
        }
    }

    private SceneReferenceGuide() {
    }

    public static Reference fallbackFor(String stepId, boolean currentTurnHasPhoto,
            boolean hasReliableMarker, boolean skillMatched, boolean stepAccepted) {
        if (!currentTurnHasPhoto || hasReliableMarker || !skillMatched) {
            return null;
        }
        Reference reference = REFERENCES.get(stepId == null ? "" : stepId.trim());
        if (reference == null || !stepAccepted) {
            return reference;
        }
        return new Reference(reference.imageId(), ACCEPTED_CAPTION);
    }

    public static boolean isReferenceImageId(String imageId) {
        return imageId != null && imageId.startsWith(ASSET_PREFIX)
                && imageId.length() > ASSET_PREFIX.length();
    }

    public static boolean usesFixedReference(String stepId) {
        String step = stepId == null ? "" : stepId.trim();
        return HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO.equals(step);
    }

    public static String assetPath(String imageId) {
        return isReferenceImageId(imageId) ? imageId.substring("asset://".length()) : "";
    }

    private static Map<String, Reference> createReferences() {
        Map<String, Reference> references = new LinkedHashMap<String, Reference>();
        add(references, HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY, "platform-anomaly.jpg");
        add(references, HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO, "ddc-power.jpg");
        add(references, HoneywellTempHumiditySkill.STEP_DDC_RS485, "ddc-rs485.jpg");
        add(references, HoneywellTempHumiditySkill.STEP_GATEWAY_RS485, "gateway-rs485.jpg");
        add(references, HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK, "gateway-network.jpg");
        add(references, HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE, "sensor-wiring-12v.jpg");
        add(references, HoneywellTempHumiditySkill.STEP_SENSOR_WIRING, "sensor-wiring-12v.jpg");
        add(references, HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY, "platform-recovery.jpg");
        return Collections.unmodifiableMap(references);
    }

    private static void add(Map<String, Reference> references, String stepId, String fileName) {
        references.put(stepId, new Reference(ASSET_PREFIX + fileName, CAPTION));
    }
}
