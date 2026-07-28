package com.codex.air3nativecamera.features.inspection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InspectionAiBridgeTest {
    @Test
    public void promptRequiresActualPhotoAndOneCurrentPointOnly() {
        InspectionTaskDefinition.Point point = InspectionCatalog.defaultCatalog()
                .find("lab-training-room").points().get(0);

        String prompt = InspectionAiBridge.prompt(point);

        assertTrue(prompt.contains("交换机指示灯"));
        assertTrue(prompt.contains("实际照片"));
        assertTrue(prompt.contains("SYS/ALM"));
        assertFalse(prompt.contains("模拟识别"));
    }

    @Test
    public void currentValueMetadataIsRemovedBeforeDisplay() {
        InspectionAiBridge.ParsedResponse parsed = InspectionAiBridge.parse(
                "SYS 绿灯常亮，ALM 未亮，端口灯有收发闪烁。\n[[inspection-current:SYS正常，端口有收发]]");

        assertEquals("SYS 绿灯常亮，ALM 未亮，端口灯有收发闪烁。", parsed.observation());
        assertEquals("SYS正常，端口有收发", parsed.currentValue());
        assertFalse(parsed.observation().contains("inspection-current"));
    }
}
