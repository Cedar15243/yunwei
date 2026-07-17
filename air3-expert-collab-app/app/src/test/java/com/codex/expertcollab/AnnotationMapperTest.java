package com.codex.expertcollab;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AnnotationMapperTest {
    @Test
    public void centerMapsToSameVideoContentAcrossResolutions() {
        AnnotationMapper.Point hd = AnnotationMapper.toPixels(0.5f, 0.5f, 1920, 1080, 16f / 9f);
        AnnotationMapper.Point air3 = AnnotationMapper.toPixels(0.5f, 0.5f, 1280, 720, 16f / 9f);

        assertEquals(960f, hd.x, 0.01f);
        assertEquals(540f, hd.y, 0.01f);
        assertEquals(640f, air3.x, 0.01f);
        assertEquals(360f, air3.y, 0.01f);
    }

    @Test
    public void letterboxOffsetIsAppliedBeforeNormalizedCoordinates() {
        AnnotationMapper.Point topLeft = AnnotationMapper.toPixels(0f, 0f, 1000, 1000, 16f / 9f);
        AnnotationMapper.Point bottomRight = AnnotationMapper.toPixels(1f, 1f, 1000, 1000, 16f / 9f);

        assertEquals(0f, topLeft.x, 0.01f);
        assertEquals(218.75f, topLeft.y, 0.01f);
        assertEquals(1000f, bottomRight.x, 0.01f);
        assertEquals(781.25f, bottomRight.y, 0.01f);
    }
}
