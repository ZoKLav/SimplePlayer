package com.zoeykl.simpleplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Locale;

public class AlbumFallbackVisualizerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int accent = Color.rgb(229, 57, 53);
    private boolean playing = false;
    private int positionMs = 0;
    private long songSeed = 0x51A7L;

    public AlbumFallbackVisualizerView(Context context) {
        super(context);
        setWillNotDraw(false);
        textPaint.setColor(Color.argb(190, 255, 255, 255));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        // This is intentionally not Android's Visualizer audio capture API. That path needs extra
        // permissions and device-specific sulking. This tiny creature just looks alive when art is absent.
    }

    public void setAccent(int accent) {
        this.accent = accent;
    }

    public void setPlaybackState(boolean playing, int positionMs, String stableKey) {
        this.playing = playing;
        this.positionMs = Math.max(0, positionMs);
        this.songSeed = stableHash(stableKey == null ? "" : stableKey);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int top = Color.rgb(47, 50, 58);
        int bottom = Color.rgb(11, 12, 16);
        paint.setShader(new LinearGradient(0, 0, w, h, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        float time = positionMs / 1000f;
        if (playing) time += (SystemClock.uptimeMillis() % 100000L) / 1000f;
        float seedPhase = (songSeed & 0xffff) / 65535f * 6.2831853f;

        drawGlow(canvas, w, h, time, seedPhase);
        drawBars(canvas, w, h, time, seedPhase);
        drawOrbit(canvas, w, h, time, seedPhase);
        drawLabel(canvas, w, h);
    }

    private void drawGlow(Canvas canvas, int w, int h, float time, float seedPhase) {
        int glow = Color.argb(52, Color.red(accent), Color.green(accent), Color.blue(accent));
        paint.setColor(glow);
        float cx = w * (0.42f + 0.18f * (float) Math.sin(time * 0.22f + seedPhase));
        float cy = h * (0.40f + 0.14f * (float) Math.cos(time * 0.19f + seedPhase));
        canvas.drawCircle(cx, cy, Math.max(w, h) * 0.33f, paint);
        paint.setColor(Color.argb(32, 255, 255, 255));
        canvas.drawCircle(w * 0.76f, h * 0.24f, Math.max(w, h) * 0.18f, paint);
    }

    private void drawBars(Canvas canvas, int w, int h, float time, float seedPhase) {
        int bars = 28;
        float pad = Math.max(18f, w * 0.055f);
        float usable = Math.max(1f, w - pad * 2f);
        float gap = Math.max(3f, usable / bars * 0.22f);
        float barW = (usable - gap * (bars - 1)) / bars;
        float base = h * 0.74f;
        float maxBar = h * 0.43f;
        for (int i = 0; i < bars; i++) {
            float n = i / (float) (bars - 1);
            float wave = (float) Math.sin(time * (1.1f + n * 0.9f) + seedPhase + i * 0.63f);
            float wave2 = (float) Math.sin(time * 0.37f + i * 1.71f + (songSeed % 97));
            float shape = 0.18f + 0.82f * Math.abs(wave * 0.62f + wave2 * 0.38f);
            float height = maxBar * shape * (0.55f + 0.45f * (float) Math.sin(n * 3.1415926f));
            float left = pad + i * (barW + gap);
            float right = left + barW;
            float top = base - height;
            rect.set(left, top, right, base);
            int alpha = 92 + (int) (95 * shape);
            paint.setColor(Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)));
            canvas.drawRoundRect(rect, Math.max(2f, barW * 0.35f), Math.max(2f, barW * 0.35f), paint);
        }
    }

    private void drawOrbit(Canvas canvas, int w, int h, float time, float seedPhase) {
        float cx = w * 0.5f;
        float cy = h * 0.39f;
        float radius = Math.min(w, h) * 0.22f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, w * 0.006f));
        paint.setColor(Color.argb(76, 255, 255, 255));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float a = time * (0.45f + i * 0.11f) + seedPhase + i * 2.094395f;
            float x = cx + (float) Math.cos(a) * radius;
            float y = cy + (float) Math.sin(a) * radius * 0.62f;
            paint.setColor(i == 0 ? Color.argb(210, 255, 255, 255) : Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)));
            canvas.drawCircle(x, y, Math.max(4f, Math.min(w, h) * (0.018f + i * 0.004f)), paint);
        }
    }

    private void drawLabel(Canvas canvas, int w, int h) {
        textPaint.setTextSize(Math.max(13f, Math.min(w, h) * 0.045f));
        canvas.drawText("NO ALBUM ART", w * 0.5f, h * 0.90f, textPaint);
    }

    private static long stableHash(String s) {
        long h = 1469598103934665603L;
        String lower = s.toLowerCase(Locale.US);
        for (int i = 0; i < lower.length(); i++) {
            h ^= lower.charAt(i);
            h *= 1099511628211L;
        }
        return h;
    }
}
