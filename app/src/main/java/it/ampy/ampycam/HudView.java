package it.ampy.ampycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Viewfinder overlays: grids, dual-axis electronic level, histogram, zebra / peaking, AF frame, faces, REC, countdown. */
public class HudView extends View {
    public static final String[] GRIDS = {"NESSUNA", "TERZI", "TERZI + DIAGONALI", "GRIGLIA 4×4", "SEZIONE AUREA", "SPIRALE AUREA", "CENTRO"};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Theme th;
    public final RectF view = new RectF();       // capture frame on screen
    public final RectF full = new RectF();       // whole preview (sensor field of view)
    public int grid = 1;
    public boolean level = true, histogram = true, showOverlay = true;
    public float roll, pitch;                    // degrees
    public Analyzer.Out an;
    public final RectF af = new RectF();
    public int afState;                          // 0 idle, 1 scanning, 2 ok, 3 fail
    public long afShownAt;
    public boolean afLocked, aeLocked;
    public final List<RectF> faces = new ArrayList<>();
    public String countdown = "";
    public boolean recording; public long recStart;
    public float nightProgress = -1;
    public String nightText = "";
    public float exposureSlider = Float.NaN;     // -1..1 Pixel-style slider next to the AF ring
    public String toast = ""; public long toastAt;
    public int mfAssist = 0;
    public float uiRot = 0;                      // texts turn with the phone                     // magnify factor shown as label

    public HudView(Context c, Theme th) {
        super(c);
        this.th = th;
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setLetterSpacing(0.08f);
    }
    public void setTheme(Theme th) { this.th = th; invalidate(); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private static int a(int c, int al) { return (c & 0x00ffffff) | (Math.max(0, Math.min(255, al)) << 24); }

    public void showToast(String s) { toast = s; toastAt = SystemClock.uptimeMillis(); postInvalidateOnAnimation(); }

    @Override protected void onDraw(Canvas c) {
        if (view.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        boolean anim = false;
        if (showOverlay && an != null && an.overlay != null) c.drawBitmap(an.overlay, null, full.isEmpty() ? view : full, bp);
        if (!full.isEmpty() && (full.width() - view.width() > 2 || full.height() - view.height() > 2)) {
            // Canon-style aspect mask: everything outside the recorded frame is dimmed
            p.setStyle(Paint.Style.FILL); p.setColor(0xb0000000);
            c.drawRect(full.left, full.top, full.right, view.top, p); c.drawRect(full.left, view.bottom, full.right, full.bottom, p);
            c.drawRect(full.left, view.top, view.left, view.bottom, p); c.drawRect(view.right, view.top, full.right, view.bottom, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(0x66ffffff); c.drawRect(view, p);
        }
        drawGrid(c);
        if (level) drawLevel(c);
        if (histogram && an != null) drawHistogram(c);
        // faces
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.5f)); p.setColor(a(th.secondary, 220));
        for (RectF f : faces) bracket(c, f, dp(10));
        // AF frame
        if (!af.isEmpty() && (afLocked || now - afShownAt < 2600 || afState == 1)) {
            int col = afState == 2 ? 0xff57ff8a : afState == 3 ? 0xffff6177 : 0xffffffff;
            p.setColor(a(col, 230)); p.setStrokeWidth(dp(2));
            float pulse = afState == 1 ? (float) Math.sin(now / 60.0) * dp(4) : 0;
            RectF r = new RectF(af); r.inset(pulse, pulse);
            bracket(c, r, dp(14));
            if (afLocked) { t.setTextSize(dp(10)); t.setColor(0xffffd16a); t.setTextAlign(Paint.Align.CENTER); c.drawText("AE/AF LOCK", af.centerX(), af.top - dp(8), t); }
            if (!Float.isNaN(exposureSlider)) {
                float x = af.right + dp(22), y0 = af.centerY() - dp(70), y1 = af.centerY() + dp(70);
                p.setColor(a(0xffffffff, 160)); p.setStrokeWidth(dp(1.5f)); c.drawLine(x, y0, x, y1, p);
                float y = af.centerY() - exposureSlider * dp(70);
                p.setStyle(Paint.Style.FILL); p.setColor(0xffffd16a); c.drawCircle(x, y, dp(7), p);
                t.setTextSize(dp(10)); t.setTextAlign(Paint.Align.LEFT); c.drawText("☀", x + dp(10), y + dp(4), t);
                p.setStyle(Paint.Style.STROKE);
            }
            anim = afState == 1 || now - afShownAt < 2600;
        }
        // recording frame
        if (recording) {
            float k = 0.5f + 0.5f * (float) Math.sin(now / 260.0);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2 + 2 * k)); p.setColor(a(0xffff2b4e, (int) (120 + 100 * k)));
            c.drawRect(view.left + dp(2), view.top + dp(2), view.right - dp(2), view.bottom - dp(2), p);
            long ms = now - recStart;
            String s = String.format(Locale.US, "● REC %02d:%02d:%02d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60);
            t.setTextSize(dp(14)); t.setTextAlign(Paint.Align.CENTER);
            t.setColor(a(0xffff2b4e, (ms / 500) % 2 == 0 ? 255 : 150));
            c.drawText(s, view.centerX(), view.top + dp(28), t);
            anim = true;
        }
        if (!countdown.isEmpty()) {
            c.save(); c.rotate(uiRot, view.centerX(), view.centerY());
            t.setTextSize(dp(110)); t.setTextAlign(Paint.Align.CENTER);
            float j = (now / 40) % 3 - 1;
            t.setColor(a(th.secondary, 160)); c.drawText(countdown, view.centerX() - dp(3) + j, view.centerY() + dp(36), t);
            t.setColor(a(th.accent, 160)); c.drawText(countdown, view.centerX() + dp(3), view.centerY() + dp(36), t);
            t.setColor(0xffffffff); c.drawText(countdown, view.centerX(), view.centerY() + dp(36), t);
            c.restore();
            anim = true;
        }
        if (nightProgress >= 0) {
            float r = dp(46), cx = view.centerX(), cy = view.centerY();
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(4));
            p.setColor(0x55ffffff); c.drawCircle(cx, cy, r, p);
            p.setColor(th.accent); c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, 360 * nightProgress, false, p);
            t.setTextSize(dp(12)); t.setTextAlign(Paint.Align.CENTER); t.setColor(0xffffffff);
            c.drawText(nightText, cx, cy + r + dp(24), t);
            c.drawText("TIENI FERMO", cx, cy + dp(4), t);
            anim = true;
        }
        if (!toast.isEmpty() && now - toastAt < 1800) {
            t.setTextSize(dp(13)); t.setTextAlign(Paint.Align.CENTER);
            float w = t.measureText(toast) + dp(28);
            float y = (uiRot == 90 || uiRot == -90) ? view.centerY() : view.top + dp(64);
            c.save(); c.rotate(uiRot, view.centerX(), y - dp(5));
            p.setStyle(Paint.Style.FILL); p.setColor(0xcc070b13);
            c.drawRoundRect(view.centerX() - w / 2, y - dp(20), view.centerX() + w / 2, y + dp(10), dp(6), dp(6), p);
            t.setColor(th.accent); c.drawText(toast, view.centerX(), y, t);
            c.restore();
            anim = true;
        }
        if (mfAssist > 1) {
            t.setTextSize(dp(12)); t.setTextAlign(Paint.Align.RIGHT); t.setColor(0xffffd16a);
            c.drawText("× " + mfAssist, view.right - dp(12), view.bottom - dp(12), t);
        }
        p.setStyle(Paint.Style.FILL);
        if (anim) postInvalidateOnAnimation();
    }

    private void bracket(Canvas c, RectF r, float len) {
        c.drawLine(r.left, r.top, r.left + len, r.top, p); c.drawLine(r.left, r.top, r.left, r.top + len, p);
        c.drawLine(r.right, r.top, r.right - len, r.top, p); c.drawLine(r.right, r.top, r.right, r.top + len, p);
        c.drawLine(r.left, r.bottom, r.left + len, r.bottom, p); c.drawLine(r.left, r.bottom, r.left, r.bottom - len, p);
        c.drawLine(r.right, r.bottom, r.right - len, r.bottom, p); c.drawLine(r.right, r.bottom, r.right, r.bottom - len, p);
    }

    private void drawGrid(Canvas c) {
        if (grid == 0) return;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(0.8f)); p.setColor(0x88ffffff);
        float l = view.left, tp = view.top, w = view.width(), h = view.height();
        switch (grid) {
            case 1: case 2:
                for (int i = 1; i < 3; i++) { c.drawLine(l + w * i / 3, tp, l + w * i / 3, tp + h, p); c.drawLine(l, tp + h * i / 3, l + w, tp + h * i / 3, p); }
                if (grid == 2) { p.setColor(0x55ffffff); c.drawLine(l, tp, l + w, tp + h, p); c.drawLine(l + w, tp, l, tp + h, p); }
                break;
            case 3:
                for (int i = 1; i < 4; i++) { c.drawLine(l + w * i / 4, tp, l + w * i / 4, tp + h, p); c.drawLine(l, tp + h * i / 4, l + w, tp + h * i / 4, p); }
                break;
            case 4: {
                float a = 0.382f, b = 0.618f;
                p.setColor(a(th.accent, 150));
                c.drawLine(l + w * a, tp, l + w * a, tp + h, p); c.drawLine(l + w * b, tp, l + w * b, tp + h, p);
                c.drawLine(l, tp + h * a, l + w, tp + h * a, p); c.drawLine(l, tp + h * b, l + w, tp + h * b, p);
                break;
            }
            case 5: {
                // Fibonacci spiral approximated by quarter arcs in successive golden rectangles
                p.setColor(a(th.accent, 170));
                float x = l, y = tp, ww = w, hh = h;
                Path path = new Path();
                for (int i = 0; i < 9; i++) {
                    float s;
                    RectF arc;
                    switch (i % 4) {
                        case 0: s = Math.min(hh, ww * 0.618f); arc = new RectF(x, y, x + 2 * s, y + 2 * s); c.drawRect(x, y, x + s, y + hh, p);
                            path.addArc(arc, 180, 90); x += s; ww -= s; break;
                        case 1: s = Math.min(ww, hh * 0.618f); arc = new RectF(x + ww - 2 * s, y, x + ww, y + 2 * s);
                            path.addArc(arc, 270, 90); y += s; hh -= s; break;
                        case 2: s = Math.min(hh, ww * 0.618f); arc = new RectF(x + ww - 2 * s, y + hh - 2 * s, x + ww, y + hh);
                            path.addArc(arc, 0, 90); ww -= s; break;
                        default: s = Math.min(ww, hh * 0.618f); arc = new RectF(x, y + hh - 2 * s, x + 2 * s, y + hh);
                            path.addArc(arc, 90, 90); hh -= s; break;
                    }
                }
                c.drawPath(path, p);
                break;
            }
            case 6: {
                float cx = view.centerX(), cy = view.centerY();
                c.drawLine(cx - dp(16), cy, cx + dp(16), cy, p); c.drawLine(cx, cy - dp(16), cx, cy + dp(16), p);
                break;
            }
            default: break;
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawLevel(Canvas c) {
        float cx = view.centerX(), cy = view.centerY();
        float len = Math.min(view.width(), view.height()) * 0.28f;
        boolean ok = Math.abs(roll) < 1f;
        boolean pok = Math.abs(pitch) < 1.5f;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.5f));
        // fixed reference ticks
        p.setColor(0x66ffffff);
        c.drawLine(cx - len - dp(14), cy, cx - len, cy, p); c.drawLine(cx + len, cy, cx + len + dp(14), cy, p);
        // rolling bar
        c.save(); c.rotate(roll, cx, cy);
        p.setColor(ok ? 0xff57ff8a : 0xffffffff);
        c.drawLine(cx - len, cy, cx - dp(16), cy, p); c.drawLine(cx + dp(16), cy, cx + len, cy, p);
        c.restore();
        // pitch scale on the right (Canon dual-axis)
        float x = view.right - dp(18), half = dp(50);
        p.setColor(0x66ffffff);
        for (int i = -2; i <= 2; i++) c.drawLine(x - dp(4), cy + i * half / 2, x + dp(4), cy + i * half / 2, p);
        float py = cy + Math.max(-1, Math.min(1, pitch / 20f)) * half;
        p.setStyle(Paint.Style.FILL); p.setColor(pok ? 0xff57ff8a : 0xffffffff);
        c.drawRect(x - dp(7), py - dp(1.5f), x + dp(7), py + dp(1.5f), p);
        if (ok) {
            t.setTextSize(dp(9)); t.setTextAlign(Paint.Align.CENTER); t.setColor(0xff57ff8a);
            c.drawText("0.0°", cx, cy - dp(8), t);
        }
    }

    private void drawHistogram(Canvas c) {
        float w = dp(116), h = dp(50), x = view.left + dp(10), y = view.top + dp(44);
        p.setStyle(Paint.Style.FILL); p.setColor(0x99000000);
        c.drawRect(x, y, x + w, y + h, p);
        float bw = w / 64f;
        float scale = h / (float) Math.max(1, Math.min(an.max, an.max));
        p.setColor(0xccffffff);
        Path lp = new Path(); lp.moveTo(x, y + h);
        for (int i = 0; i < 64; i++) lp.lineTo(x + i * bw, y + h - Math.min(h, (float) Math.sqrt(an.lum[i] / (float) an.max) * h));
        lp.lineTo(x + w, y + h); lp.close();
        c.drawPath(lp, p);
        int[][] ch = {an.r, an.g, an.b}; int[] cols = {0xffff4060, 0xff40ff80, 0xff4090ff};
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1));
        for (int k = 0; k < 3; k++) {
            p.setColor(a(cols[k], 200));
            Path q = new Path();
            for (int i = 0; i < 64; i++) {
                float yy = y + h - Math.min(h, (float) Math.sqrt(ch[k][i] / (float) an.max) * h);
                if (i == 0) q.moveTo(x, yy); else q.lineTo(x + i * bw, yy);
            }
            c.drawPath(q, p);
        }
        p.setColor(0x55ffffff);
        c.drawRect(x, y, x + w, y + h, p);
        p.setStyle(Paint.Style.FILL);
        if (an.clipHi > 0.01f) { p.setColor(0xffff6177); c.drawRect(x + w - dp(6), y, x + w, y + dp(6), p); }
        if (an.clipLo > 0.03f) { p.setColor(0xff4090ff); c.drawRect(x, y, x + dp(6), y + dp(6), p); }
        if (scale < 0) c.drawColor(0); // keep 'scale' used for older javac lint
    }

    /** Helper for activities: draw nothing but keep overlay bitmap reference fresh. */
    public void setAnalysis(Analyzer.Out o) { an = o; invalidate(); }
    public Bitmap overlay() { return an == null ? null : an.overlay; }
}
