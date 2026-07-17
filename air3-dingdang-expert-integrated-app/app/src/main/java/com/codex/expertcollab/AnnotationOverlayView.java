package com.codex.expertcollab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnnotationOverlayView extends View {
    private static final float VIDEO_ASPECT = 16f / 9f;

    private static final class Annotation {
        final String tool;
        final List<float[]> points = new ArrayList<>();

        Annotation(String tool) {
            this.tool = tool;
        }
    }

    private final Map<String, Annotation> annotations = new LinkedHashMap<>();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AnnotationOverlayView(Context context) {
        this(context, null);
    }

    public AnnotationOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        strokePaint.setColor(Color.rgb(255, 95, 103));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(dp(4));
        fillPaint.setColor(Color.rgb(255, 95, 103));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void apply(String type, JSONObject payload) {
        String objectId = payload.optString("objectId", "");
        if ("annotation.begin".equals(type) && !objectId.isEmpty()) {
            Annotation annotation = new Annotation(payload.optString("tool", "pen"));
            appendPoints(annotation, payload.optJSONArray("points"));
            annotations.put(objectId, annotation);
        } else if ("annotation.append".equals(type) && annotations.containsKey(objectId)) {
            appendPoints(annotations.get(objectId), payload.optJSONArray("points"));
        } else if ("annotation.undo".equals(type)) {
            annotations.remove(objectId);
        } else if ("annotation.clear".equals(type)) {
            annotations.clear();
        }
        invalidate();
    }

    public void clearAnnotations() {
        annotations.clear();
        invalidate();
    }

    private void appendPoints(Annotation annotation, JSONArray points) {
        if (annotation == null || points == null) {
            return;
        }
        for (int index = 0; index < points.length(); index++) {
            JSONObject point = points.optJSONObject(index);
            if (point == null) {
                continue;
            }
            double x = point.optDouble("x", -1);
            double y = point.optDouble("y", -1);
            if (x >= 0 && x <= 1 && y >= 0 && y <= 1) {
                annotation.points.add(new float[]{(float) x, (float) y});
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Annotation annotation : annotations.values()) {
            if (annotation.points.isEmpty()) {
                continue;
            }
            if ("circle".equals(annotation.tool)) {
                drawCircle(canvas, annotation);
            } else if ("arrow".equals(annotation.tool)) {
                drawArrow(canvas, annotation);
            } else {
                drawPen(canvas, annotation);
            }
        }
    }

    private AnnotationMapper.Point map(float[] point) {
        return AnnotationMapper.toPixels(point[0], point[1], getWidth(), getHeight(), VIDEO_ASPECT);
    }

    private void drawPen(Canvas canvas, Annotation annotation) {
        AnnotationMapper.Point first = map(annotation.points.get(0));
        Path path = new Path();
        path.moveTo(first.x, first.y);
        for (int index = 1; index < annotation.points.size(); index++) {
            AnnotationMapper.Point point = map(annotation.points.get(index));
            path.lineTo(point.x, point.y);
        }
        canvas.drawPath(path, strokePaint);
    }

    private void drawCircle(Canvas canvas, Annotation annotation) {
        AnnotationMapper.Point first = map(annotation.points.get(0));
        AnnotationMapper.Point last = map(annotation.points.get(annotation.points.size() - 1));
        canvas.drawOval(
                Math.min(first.x, last.x),
                Math.min(first.y, last.y),
                Math.max(first.x, last.x),
                Math.max(first.y, last.y),
                strokePaint);
    }

    private void drawArrow(Canvas canvas, Annotation annotation) {
        AnnotationMapper.Point first = map(annotation.points.get(0));
        AnnotationMapper.Point last = map(annotation.points.get(annotation.points.size() - 1));
        canvas.drawLine(first.x, first.y, last.x, last.y, strokePaint);
        double angle = Math.atan2(last.y - first.y, last.x - first.x);
        float size = dp(18);
        Path head = new Path();
        head.moveTo(last.x, last.y);
        head.lineTo(
                last.x - size * (float) Math.cos(angle - Math.PI / 6),
                last.y - size * (float) Math.sin(angle - Math.PI / 6));
        head.lineTo(
                last.x - size * (float) Math.cos(angle + Math.PI / 6),
                last.y - size * (float) Math.sin(angle + Math.PI / 6));
        head.close();
        canvas.drawPath(head, fillPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
