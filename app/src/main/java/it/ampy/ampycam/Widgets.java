package it.ampy.ampycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Shutter button, Canon main dial, mode carousel, gallery thumbnail. */
public final class Widgets {
    private Widgets() { }
    static int a(int c, int al) { return (c & 0x00ffffff) | (Math.max(0, Math.min(255, al)) << 24); }

    /* ======================================================== shutter */
    public static final class Shutter extends View {
        public interface L { void halfPress(); void press(); void release(boolean longPress); }
        public static final int PHOTO = 0, VIDEO = 1, REC = 2, BUSY = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Theme th; private int state = PHOTO; private float progress = -1; private boolean down; private long downAt;
        private L l; private boolean longFired;
        private final Runnable longPress = () -> { longFired = true; if (l != null) l.press(); };
        public Shutter(Context c, Theme th) { super(c); this.th = th; }
        public void setListener(L l) { this.l = l; }
        public void setState(int s) { state = s; invalidate(); }
        public int state() { return state; }
        public void setProgress(float f) { progress = f; invalidate(); }
        public void setTheme(Theme t) { th = t; invalidate(); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true; downAt = SystemClock.uptimeMillis(); longFired = false;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (l != null) l.halfPress();
                    postDelayed(longPress, 420);
                    invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (down && l != null) { if (!longFired) l.press(); l.release(longFired); }
                    down = false; invalidate(); return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    if (down && longFired && l != null) l.release(true);
                    down = false; invalidate(); return true;
                default: return true;
            }
        }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 4;
            float s = down ? 0.9f : 1f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.09f);
            p.setColor(state == REC ? 0xffff2b4e : 0xffecfcff);
            p.setShadowLayer(r * 0.25f, 0, 0, a(state == REC || state == VIDEO ? 0xffff2b4e : th.accent, 160));
            c.drawCircle(cx, cy, r, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.FILL);
            if (state == REC) {
                p.setColor(0xffff2b4e);
                float q = r * 0.36f * s;
                c.drawRoundRect(cx - q, cy - q, cx + q, cy + q, q * 0.25f, q * 0.25f, p);
            } else {
                p.setColor(state == VIDEO ? 0xffff2b4e : 0xffffffff);
                c.drawCircle(cx, cy, r * 0.78f * s, p);
                if (state == PHOTO) {
                    // neon inner ring like the Ampy shutter
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.04f); p.setColor(a(th.accent, 200));
                    c.drawCircle(cx, cy, r * 0.62f * s, p); p.setStyle(Paint.Style.FILL);
                }
            }
            if (progress >= 0 || state == BUSY) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.1f); p.setColor(th.accent);
                float sweep = progress >= 0 ? 360 * progress : 90;
                float startA = progress >= 0 ? -90 : (SystemClock.uptimeMillis() % 1000) * 0.36f;
                c.drawArc(cx - r, cy - r, cx + r, cy + r, startA, sweep, false, p);
                p.setStyle(Paint.Style.FILL);
                postInvalidateOnAnimation();
            }
        }
    }

    /* ======================================================== Canon main dial */
    public static final class Dial extends View {
        public interface L { void onIndex(int i); }
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Theme th; private String[] labels = {"—"}; private int index; private float drag; private float lastX; private L l;
        private String title = "";
        public Dial(Context c, Theme th) {
            super(c); this.th = th;
            t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); t.setTextAlign(Paint.Align.CENTER);
        }
        float dp(float v) { return v * getResources().getDisplayMetrics().density; }
        public void set(String title, String[] labels, int index, L l) {
            this.title = title; this.labels = labels.length == 0 ? new String[]{"—"} : labels;
            this.index = Math.max(0, Math.min(this.labels.length - 1, index)); this.l = l; drag = 0; invalidate();
        }
        public void setIndex(int i) { index = Math.max(0, Math.min(labels.length - 1, i)); invalidate(); }
        public void setTheme(Theme t) { th = t; invalidate(); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float step = dp(26);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lastX = e.getX(); getParent().requestDisallowInterceptTouchEvent(true); return true;
                case MotionEvent.ACTION_MOVE:
                    drag += e.getX() - lastX; lastX = e.getX();
                    while (drag <= -step && index < labels.length - 1) { drag += step; index++; tick(); }
                    while (drag >= step && index > 0) { drag -= step; index--; tick(); }
                    if (index == 0 && drag > 0) drag = Math.min(drag, step * 0.4f);
                    if (index == labels.length - 1 && drag < 0) drag = Math.max(drag, -step * 0.4f);
                    invalidate(); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    if (Math.abs(e.getX() - getWidth() / 2f) > dp(40) && Math.abs(drag) < dp(4) && e.getEventTime() - e.getDownTime() < 250) {
                        int dir = e.getX() > getWidth() / 2f ? 1 : -1;
                        int ni = Math.max(0, Math.min(labels.length - 1, index + dir));
                        if (ni != index) { index = ni; tick(); }
                    }
                    drag = 0; invalidate(); return true;
                default: return true;
            }
        }
        private void tick() { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (l != null) l.onIndex(index); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, step = dp(26);
            p.setColor(0xcc070b13); c.drawRect(0, 0, w, h, p);
            p.setStrokeWidth(dp(1));
            for (int i = -20; i <= 20; i++) {
                int k = index + i;
                if (k < 0 || k >= labels.length) continue;
                float x = cx + i * step + drag;
                if (x < -step || x > w + step) continue;
                boolean major = k % 3 == 0;
                float fade = 1 - Math.min(1, Math.abs(x - cx) / (w / 2f));
                p.setColor(a(i == 0 ? th.accent : 0xffecfcff, (int) (60 + 160 * fade)));
                c.drawLine(x, h - dp(major ? 16 : 10), x, h - dp(2), p);
                if (major || i == 0) {
                    t.setTextSize(dp(i == 0 ? 15 : 11)); t.setColor(a(i == 0 ? th.accent : 0xffecfcff, (int) (40 + 215 * fade)));
                    c.drawText(labels[k], x, h - dp(22), t);
                }
            }
            p.setColor(th.accent);
            c.drawRect(cx - dp(1), dp(2), cx + dp(1), h, p);
            t.setTextSize(dp(9)); t.setColor(0xff86adbf); t.setTextAlign(Paint.Align.LEFT);
            c.drawText(title, dp(8), dp(12), t);
            t.setTextAlign(Paint.Align.CENTER);
        }
    }

    /* ======================================================== mode carousel */
    public static final class Carousel extends View {
        public interface L { void onMode(int i); }
        private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Theme th; private final String[] items; private int sel; private float anim; private L l;
        private float[] centers;
        public Carousel(Context c, Theme th, String[] items) {
            super(c); this.th = th; this.items = items;
            t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); t.setTextAlign(Paint.Align.CENTER); t.setLetterSpacing(0.12f);
        }
        float dp(float v) { return v * getResources().getDisplayMetrics().density; }
        public void setListener(L l) { this.l = l; }
        public void setSelected(int i) { if (i == sel) return; anim += (i - sel); sel = i; invalidate(); }
        public int selected() { return sel; }
        public void setTheme(Theme t) { th = t; invalidate(); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && centers != null) {
                int best = sel; float bd = 1e9f;
                for (int i = 0; i < items.length; i++) { float d = Math.abs(centers[i] - e.getX()); if (d < bd) { bd = d; best = i; } }
                if (best != sel) { setSelected(best); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if (l != null) l.onMode(best); }
            }
            return true;
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), gap = dp(92);
            anim *= 0.78f; if (Math.abs(anim) < 0.01f) anim = 0;
            centers = new float[items.length];
            for (int i = 0; i < items.length; i++) {
                float x = w / 2f + (i - sel + anim) * gap;
                centers[i] = x;
                boolean s = i == sel;
                t.setTextSize(dp(s ? 13 : 12));
                float fade = 1 - Math.min(0.75f, Math.abs(x - w / 2f) / w);
                t.setColor(s ? th.accent : a(0xffecfcff, (int) (200 * fade)));
                c.drawText(items[i], x, h / 2f + dp(5), t);
                if (s) { t.setColor(th.accent); c.drawRect(x - dp(10), h - dp(6), x + dp(10), h - dp(4), t); }
            }
            if (anim != 0) postInvalidateOnAnimation();
        }
    }

    /* ======================================================== gallery thumbnail */
    public static final class Thumb extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bmp; private Theme th; private long pulseAt;
        public Thumb(Context c, Theme th) { super(c); this.th = th; ring.setStyle(Paint.Style.STROKE); }
        public void setBitmap(Bitmap b) {
            bmp = b;
            if (b != null) {
                BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float sc = Math.max(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
                Matrix m = new Matrix(); m.setScale(sc, sc);
                m.postTranslate((getWidth() - b.getWidth() * sc) / 2f, (getHeight() - b.getHeight() * sc) / 2f);
                s.setLocalMatrix(m); p.setShader(s);
            } else p.setShader(null);
            invalidate();
        }
        public void pulse() { pulseAt = SystemClock.uptimeMillis(); invalidate(); }
        public RectF screenRect() {
            int[] l = new int[2]; getLocationOnScreen(l);
            return new RectF(l[0], l[1], l[0] + getWidth(), l[1] + getHeight());
        }
        public void setTheme(Theme t) { th = t; invalidate(); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (bmp != null) setBitmap(bmp); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 3;
            float k = Math.max(0, 1 - (SystemClock.uptimeMillis() - pulseAt) / 500f);
            float rr = r * (1 + 0.12f * (float) Math.sin(k * Math.PI));
            if (bmp != null) c.drawCircle(cx, cy, rr, p);
            else { ring.setColor(0x44ffffff); ring.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, rr, ring); ring.setStyle(Paint.Style.STROKE); }
            ring.setStrokeWidth(3 + 6 * k); ring.setColor(a(th.accent, (int) (120 + 135 * k)));
            c.drawCircle(cx, cy, rr, ring);
            if (k > 0) postInvalidateOnAnimation();
        }
    }
}
