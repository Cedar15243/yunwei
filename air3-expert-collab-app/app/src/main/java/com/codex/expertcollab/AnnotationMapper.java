package com.codex.expertcollab;

public final class AnnotationMapper {
    public static final class Point {
        public final float x;
        public final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private AnnotationMapper() {}

    public static Point toPixels(float normalizedX, float normalizedY, int viewWidth, int viewHeight, float videoAspect) {
        if (normalizedX < 0f || normalizedX > 1f || normalizedY < 0f || normalizedY > 1f) {
            throw new IllegalArgumentException("normalized coordinate must be between zero and one");
        }
        if (viewWidth <= 0 || viewHeight <= 0 || videoAspect <= 0f) {
            throw new IllegalArgumentException("view and video dimensions must be positive");
        }

        float viewAspect = (float) viewWidth / (float) viewHeight;
        float contentWidth;
        float contentHeight;
        float offsetX;
        float offsetY;
        if (viewAspect > videoAspect) {
            contentHeight = viewHeight;
            contentWidth = contentHeight * videoAspect;
            offsetX = (viewWidth - contentWidth) / 2f;
            offsetY = 0f;
        } else {
            contentWidth = viewWidth;
            contentHeight = contentWidth / videoAspect;
            offsetX = 0f;
            offsetY = (viewHeight - contentHeight) / 2f;
        }
        return new Point(offsetX + normalizedX * contentWidth, offsetY + normalizedY * contentHeight);
    }
}
