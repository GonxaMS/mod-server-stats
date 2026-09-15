package com.modserver.stats.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class StatsChartView extends View {
    static final int METRIC_TPS = 0;
    static final int METRIC_MSPT = 1;
    static final int METRIC_PROCESS_CPU = 2;
    static final int METRIC_SYSTEM_CPU = 3;
    static final int METRIC_MEMORY = 4;
    static final int METRIC_PLAYERS = 5;
    static final int METRIC_LATENCY = 6;

    private static final int COLOR_SURFACE_RAISED = Color.rgb(13, 27, 33);
    private static final int COLOR_GRID = Color.rgb(24, 69, 77);
    private static final int COLOR_TEXT = Color.rgb(224, 255, 246);
    private static final int COLOR_MUTED = Color.rgb(126, 165, 158);
    private static final int COLOR_CYAN = Color.rgb(0, 238, 214);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<StatsSample> samples = new ArrayList<>();
    private int metric = METRIC_TPS;

    StatsChartView(Context context) {
        super(context);
        setMinimumHeight(dp(230));
        setBackgroundColor(COLOR_SURFACE_RAISED);
    }

    void setMetric(int metric) {
        this.metric = metric;
        invalidate();
    }

    void setSamples(List<StatsSample> newSamples) {
        samples.clear();
        samples.addAll(newSamples);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(52);
        float top = dp(38);
        float right = getWidth() - dp(16);
        float bottom = getHeight() - dp(30);

        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(14));
        paint.setColor(COLOR_TEXT);
        canvas.drawText(metricLabel(metric), dp(16), dp(23), paint);

        if (samples.isEmpty()) {
            paint.setTypeface(android.graphics.Typeface.MONOSPACE);
            paint.setFakeBoldText(false);
            paint.setTextSize(dp(14));
            paint.setColor(COLOR_MUTED);
            canvas.drawText("Activa la actualizaci\u00f3n autom\u00e1tica o pulsa Actualizar.", dp(16), getHeight() / 2f, paint);
            return;
        }

        Scale scale = calculateScale();
        drawGrid(canvas, left, top, right, bottom, scale);
        drawLine(canvas, left, top, right, bottom, scale);
        drawLabels(canvas, left, top, right, bottom, scale);
    }

    private Scale calculateScale() {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (StatsSample sample : samples) {
            double value = sample.valueFor(metric);
            if (isValid(value)) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        if (min == Double.MAX_VALUE) {
            return new Scale(0.0, 1.0, -1.0);
        }

        if (metric == METRIC_TPS) {
            min = 0.0;
            max = Math.max(20.0, max);
        } else if (metric == METRIC_PROCESS_CPU || metric == METRIC_SYSTEM_CPU || metric == METRIC_MEMORY) {
            min = 0.0;
            max = 100.0;
        } else if (metric == METRIC_PLAYERS) {
            min = 0.0;
            max = Math.max(1.0, max);
        } else {
            double padding = Math.max(1.0, (max - min) * 0.15);
            min = Math.max(0.0, min - padding);
            max += padding;
        }
        if (max - min < 0.1) {
            max = min + 1.0;
        }
        return new Scale(min, max, samples.get(samples.size() - 1).valueFor(metric));
    }

    private void drawGrid(Canvas canvas, float left, float top, float right, float bottom, Scale scale) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(COLOR_GRID);
        for (int index = 0; index <= 4; index++) {
            float y = top + (bottom - top) * index / 4f;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(11));
        paint.setColor(COLOR_MUTED);
        canvas.drawText(formatValue(scale.max), dp(4), top + dp(4), paint);
        canvas.drawText(formatValue(scale.min), dp(4), bottom, paint);
    }

    private void drawLine(Canvas canvas, float left, float top, float right, float bottom, Scale scale) {
        long firstTime = samples.get(0).capturedAtMs;
        long lastTime = samples.get(samples.size() - 1).capturedAtMs;
        long duration = Math.max(1L, lastTime - firstTime);
        Path line = new Path();
        boolean hasPoint = false;
        for (StatsSample sample : samples) {
            double value = sample.valueFor(metric);
            if (!isValid(value)) {
                hasPoint = false;
                continue;
            }
            float x = samples.size() == 1
                    ? right
                    : left + (right - left) * (sample.capturedAtMs - firstTime) / (float) duration;
            float y = bottom - (float) ((value - scale.min) / (scale.max - scale.min)) * (bottom - top);
            if (!hasPoint) {
                line.moveTo(x, y);
                hasPoint = true;
            } else {
                line.lineTo(x, y);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(colorFor(metric));
        canvas.drawPath(line, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLabels(Canvas canvas, float left, float top, float right, float bottom, Scale scale) {
        StatsSample latest = samples.get(samples.size() - 1);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(14));
        paint.setColor(colorFor(metric));
        String latestLabel = isValid(scale.latest) ? formatValue(scale.latest) : "sin datos";
        float labelWidth = paint.measureText(latestLabel);
        canvas.drawText(latestLabel, right - labelWidth, dp(23), paint);

        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(11));
        paint.setColor(COLOR_MUTED);
        String start = formatTime(samples.get(0).capturedAtMs);
        String end = formatTime(latest.capturedAtMs);
        canvas.drawText(start, left, bottom + dp(19), paint);
        float endWidth = paint.measureText(end);
        canvas.drawText(end, right - endWidth, bottom + dp(19), paint);
    }

    private static boolean isValid(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private String metricLabel(int selectedMetric) {
        return switch (selectedMetric) {
            case METRIC_TPS -> "TPS";
            case METRIC_MSPT -> "MSPT";
            case METRIC_PROCESS_CPU -> "CPU de Minecraft";
            case METRIC_SYSTEM_CPU -> "CPU del equipo";
            case METRIC_MEMORY -> "Memoria";
            case METRIC_PLAYERS -> "Jugadores conectados";
            case METRIC_LATENCY -> "Tiempo de respuesta";
            default -> "Estad\u00edstica";
        };
    }

    private String formatValue(double value) {
        if (metric == METRIC_PROCESS_CPU || metric == METRIC_SYSTEM_CPU || metric == METRIC_MEMORY) {
            return String.format(Locale.getDefault(), "%.0f%%", value);
        }
        if (metric == METRIC_PLAYERS) {
            return String.format(Locale.getDefault(), "%.0f", value);
        }
        if (metric == METRIC_LATENCY) {
            return String.format(Locale.getDefault(), "%.0f ms", value);
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private static String formatTime(long timestampMs) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(timestampMs));
    }

    private static int colorFor(int selectedMetric) {
        return switch (selectedMetric) {
            case METRIC_TPS -> Color.rgb(0, 255, 145);
            case METRIC_MSPT -> COLOR_CYAN;
            case METRIC_PROCESS_CPU -> Color.rgb(255, 183, 0);
            case METRIC_SYSTEM_CPU -> Color.rgb(255, 45, 190);
            case METRIC_MEMORY -> Color.rgb(255, 75, 105);
            case METRIC_PLAYERS -> Color.rgb(90, 170, 255);
            case METRIC_LATENCY -> Color.rgb(190, 135, 255);
            default -> COLOR_TEXT;
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Scale {
        final double min;
        final double max;
        final double latest;

        Scale(double min, double max, double latest) {
            this.min = min;
            this.max = max;
            this.latest = latest;
        }
    }
}
