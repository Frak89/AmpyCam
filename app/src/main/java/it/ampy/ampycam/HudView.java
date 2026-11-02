package it.ampy.ampycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
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
    public float uiRot = 0;                      // texts turn with the phone
    public InfoBar strip;                        // drawn here, rotated, when the phone is sideways

    /* ======== snake crawl of the Canon strip along the viewfinder edges ======== */
    public long snakeMs = 900;                 // set from the settings
    public final RectF stripRest = new RectF(); // where the real strip sits in portrait (HUD coords)
    static final int SNAKE_COLS = 72;
    private Bitmap snakeA, snakeB; private long snakeStart; private float snakeFrom, snakeTo;
    private final float[] meshV = new float[(SNAKE_COLS + 1) * 2 * 2];
    private final Paint snakePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private PathMeasure pm; private float total;

    private boolean crawling(long now) { return snakeA != null && now - snakeStart < snakeMs; }

    /** Starts the crawl from the strip position of rotation 'from' to the one of rotation 'to'. */
    public void crawl(float from, float to) {
        if (strip == null || view.isEmpty() || from == to) return;
        float sh = strip.stripHeight();
        recycleSnake();
        snakeA = render(restLen(from), sh);
        snakeB = render(restLen(to), sh);
        snakeFrom = from; snakeTo = to; snakeStart = SystemClock.uptimeMillis();
        // centreline loop: inner edges of the viewfinder, bottom edge exactly on the portrait strip
        float bottom = stripRest.isEmpty() ? view.bottom - sh / 2 : stripRest.centerY();
        RectF r = new RectF(view.left + sh / 2, view.top + sh / 2, view.right - sh / 2, bottom);
        Path path = new Path();
        path.addRoundRect(r, sh * 0.9f, sh * 0.9f, Path.Direction.CCW);
        pm = new PathMeasure(path, true);
        total = pm.getLength();
        postInvalidateOnAnimation();
    }
    private void recycleSnake() { if (snakeA != null) snakeA.recycle(); if (snakeB != null) snakeB.recycle(); snakeA = snakeB = null; }
    private Bitmap render(float len, float sh) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, (int) len), Math.max(1, (int) sh), Bitmap.Config.ARGB_8888);
        strip.drawStrip(new Canvas(b), len, sh);
        return b;
    }
    private float restLen(float rot) {
        if (rot == 0 && !stripRest.isEmpty()) return stripRest.width();
        return Math.abs(rot) == 90 ? view.height() : view.width();
    }

    /** Exact resting vertices of the strip for a rotation (column u → top/bottom points). */
    private void restAt(float rot, float u, float sh, float[] out) {
        if (rot == 0 && !stripRest.isEmpty()) {
            float x = stripRest.left + stripRest.width() * u;
            out[0] = x; out[1] = stripRest.top; out[2] = x; out[3] = stripRest.bottom;
            return;
        }
        RectF uf = userFrame(rot);
        float ux = uf.left + uf.width() * u, top = uf.bottom - sh, bot = uf.bottom;
        double a = Math.toRadians(rot); float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
        float cx = view.centerX(), cy = view.centerY();
        out[0] = cx + ux * cs - top * sn; out[1] = cy + ux * sn + top * cs;
        out[2] = cx + ux * cs - bot * sn; out[3] = cy + ux * sn + bot * cs;
    }

    private static float easeInOut(float x) {
        x = Math.max(0, Math.min(1, x));
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }
    private static float smooth(float a, float b, float x) { float t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }

    private void drawSnake(Canvas c, float t) {
        if (pm == null || total <= 0) return;
        float sh = strip.stripHeight();
        float sFrom = nearestS(edgeMid(snakeFrom, sh)), sTo = nearestS(edgeMid(snakeTo, sh));
        float delta = sTo - sFrom;
        if (delta > total / 2) delta -= total; else if (delta < -total / 2) delta += total;
        float dir = delta >= 0 ? 1 : -1;
        float lSrc = restLen(snakeFrom), lDst = restLen(snakeTo);
        float[] pos = new float[2], tan = new float[2], rs = new float[4], rd = new float[4];
        // ghosts first (the trail), then the body
        for (int g = 2; g >= 0; g--) {
            float tt = Math.max(0, t - g * 0.06f);
            float head = easeInOut(tt), tail = easeInOut(Math.max(0, tt - 0.07f) / 0.93f);
            float len = lSrc + (lDst - lSrc) * easeInOut(tt);
            float centreHead = sFrom + delta * head, centreTail = sFrom + delta * tail;
            float front = centreHead + dir * len / 2, back = centreTail - dir * len / 2;
            float lo = Math.min(front, back), hi = Math.max(front, back);
            // blend weight: exact rest shape at both ends, path shape in between
            float onPath = smooth(0f, 0.16f, tt) * (1 - smooth(0.84f, 1f, tt));
            float env = (float) Math.sin(Math.PI * tt);                      // slither strength
            float amp = sh * 0.32f * env;
            int k = 0, half = (SNAKE_COLS + 1) * 2;
            for (int i = 0; i <= SNAKE_COLS; i++) {
                float u = i / (float) SNAKE_COLS;
                float s = lo + (hi - lo) * u;
                s = ((s % total) + total) % total;
                pm.getPosTan(s, pos, tan);
                float nx = -tan[1], ny = tan[0];
                if ((view.centerX() - pos[0]) * nx + (view.centerY() - pos[1]) * ny < 0) { nx = -nx; ny = -ny; }
                // travelling wave along the body, tapered at head and tail
                float taper = (float) Math.sqrt(Math.sin(Math.PI * u));
                float wave = amp * taper * (float) Math.sin(2 * Math.PI * (u * 1.6f) - tt * 18f * dir);
                float px = pos[0] + nx * wave, py = pos[1] + ny * wave;
                float tx = px + nx * sh / 2, ty = py + ny * sh / 2, bx = px - nx * sh / 2, by = py - ny * sh / 2;
                restAt(snakeFrom, u, sh, rs); restAt(snakeTo, u, sh, rd);
                float rw = smooth(0.5f, 0.51f, tt);                               // which rest shape is near
                float rtx = rs[0] + (rd[0] - rs[0]) * rw, rty = rs[1] + (rd[1] - rs[1]) * rw;
                float rbx = rs[2] + (rd[2] - rs[2]) * rw, rby = rs[3] + (rd[3] - rs[3]) * rw;
                meshV[k] = rtx + (tx - rtx) * onPath; meshV[k + 1] = rty + (ty - rty) * onPath;
                meshV[half + k] = rbx + (bx - rbx) * onPath; meshV[half + k + 1] = rby + (by - rby) * onPath;
                k += 2;
            }
            int alpha = g == 0 ? 255 : g == 1 ? 80 : 36;
            float mix = easeInOut(tt);
            snakePaint.setAlpha((int) (alpha * (1 - mix)));
            c.drawBitmapMesh(snakeA, SNAKE_COLS, 1, meshV, 0, null, 0, snakePaint);
            snakePaint.setAlpha((int) (alpha * mix));
            c.drawBitmapMesh(snakeB, SNAKE_COLS, 1, meshV, 0, null, 0, snakePaint);
            if (g == 0 && onPath > 0.05f) {
                float hs = ((front % total) + total) % total;
                pm.getPosTan(hs, pos, tan);
                p.setStyle(Paint.Style.FILL); p.setColor(Widgets.a(th.accent, (int) (255 * onPath)));
                p.setShadowLayer(dp(10), 0, 0, th.accent);
                c.drawCircle(pos[0], pos[1], dp(3.5f), p);
                p.clearShadowLayer();
            }
        }
        snakePaint.setAlpha(255);
    }

    /** Where the strip centre rests on the loop for a rotation. */
    private float[] edgeMid(float rot, float sh) {
        if (rot == -90) return new float[]{view.right - sh / 2, view.centerY()};
        if (rot == 90) return new float[]{view.left + sh / 2, view.centerY()};
        if (Math.abs(rot) == 180) return new float[]{view.centerX(), view.top + sh / 2};
        return new float[]{stripRest.isEmpty() ? view.centerX() : stripRest.centerX(), stripRest.isEmpty() ? view.bottom - sh / 2 : stripRest.centerY()};
    }
    private float nearestS(float[] q) {
        float[] pos = new float[2]; float best = 0, bd = Float.MAX_VALUE;
        for (int i = 0; i < 600; i++) {
            float s = total * i / 600f; pm.getPosTan(s, pos, null);
            float d = (pos[0] - q[0]) * (pos[0] - q[0]) + (pos[1] - q[1]) * (pos[1] - q[1]);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    /** Viewfinder rect expressed in the rotated (user) frame, centred on the origin. */
    private RectF userFrame() { return userFrame(uiRot); }
    private RectF userFrame(float rot) {
        boolean side = Math.abs(rot) == 90;
        float w = side ? view.height() : view.width(), h = side ? view.width() : view.height();
        return new RectF(-w / 2, -h / 2, w / 2, h / 2);
    }                     // magnify factor shown as label

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
        // level, histogram and Canon info strip live in the user's frame (they turn with the phone)
        RectF uf = userFrame();
        c.save();
        c.translate(view.centerX(), view.centerY()); c.rotate(uiRot);
        if (level) drawLevel(c, uf);
        if (histogram && an != null) drawHistogram(c, uf);
        boolean crawling = crawling(now);
        if (strip != null && uiRot != 0 && !crawling) {
            float sh = strip.stripHeight();
            c.save(); c.translate(uf.left, uf.bottom - sh); strip.drawStrip(c, uf.width(), sh); c.restore();
            if (strip.levelBlink) anim = true;
        }
        c.restore();
        if (crawling) { drawSnake(c, (now - snakeStart) / (float) Math.max(1, snakeMs)); anim = true; }
        else if (snakeA != null) recycleSnake();
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

    private void drawLevel(Canvas c, RectF view) {
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

    private void drawHistogram(Canvas c, RectF view) {
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
