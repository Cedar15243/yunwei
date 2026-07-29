package com.codex.air3nativecamera.skills;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class SceneReferenceGuideTest {
    @Test
    public void failedPhotoStepReturnsClearlyLabeledReference() {
        SceneReferenceGuide.Reference reference = SceneReferenceGuide.fallbackFor(
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485,
                true,
                false,
                true,
                false);

        assertEquals("asset://scene-reference/gateway-rs485.jpg", reference.imageId());
        assertTrue(reference.caption().contains("参考示意"));
        assertTrue(reference.caption().contains("不代表"));
    }

    @Test
    public void reliableAnalysisDoesNotReturnReference() {
        assertNull(SceneReferenceGuide.fallbackFor(
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485,
                true,
                true,
                true,
                false));
    }

    @Test
    public void acceptedLowResolutionPhotoReturnsTruthfulPositionReference() {
        SceneReferenceGuide.Reference reference = SceneReferenceGuide.fallbackFor(
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485,
                true,
                false,
                true,
                true);

        assertEquals("asset://scene-reference/gateway-rs485.jpg", reference.imageId());
        assertTrue(reference.caption().contains("本轮已识别"));
        assertTrue(reference.caption().contains("不代表"));
    }

    @Test
    public void textOnlyAndUnsupportedStepsDoNotReturnReference() {
        assertNull(SceneReferenceGuide.fallbackFor(
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485,
                false,
                false,
                true,
                false));
        assertNull(SceneReferenceGuide.fallbackFor(
                HoneywellTempHumiditySkill.STEP_DDC_POWER_MEASUREMENT,
                true,
                false,
                true,
                false));
    }

    @Test
    public void allPhotoEvidenceStepsHaveReferenceAssets() {
        String[] supportedSteps = {
                HoneywellTempHumiditySkill.STEP_PLATFORM_ANOMALY,
                HoneywellTempHumiditySkill.STEP_DDC_POWER_PHOTO,
                HoneywellTempHumiditySkill.STEP_DDC_RS485,
                HoneywellTempHumiditySkill.STEP_GATEWAY_RS485,
                HoneywellTempHumiditySkill.STEP_GATEWAY_NETWORK,
                HoneywellTempHumiditySkill.STEP_SENSOR_DEVICE,
                HoneywellTempHumiditySkill.STEP_SENSOR_WIRING,
                HoneywellTempHumiditySkill.STEP_PLATFORM_RECOVERY,
        };
        for (String step : supportedSteps) {
            SceneReferenceGuide.Reference reference = SceneReferenceGuide.fallbackFor(
                    step, true, false, true, false);
            assertTrue("Missing reference for " + step, reference != null);
            assertTrue(reference.imageId().endsWith(".jpg"));
        }
    }
}
