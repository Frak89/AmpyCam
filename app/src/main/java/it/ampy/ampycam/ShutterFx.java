package it.ampy.ampycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Capture / record special effects drawn over the viewfinder, in the AmpyRocket / AmpyLink / AmpyRAW style. */
public class ShutterFx extends View {
    public static final String[] PHOTO_KEYS = {"glitch", "iris", "genie", "pixel", "crt", "holo", "shock", "polaroid", "classic", "random"};
    public static final String[] PHOTO_NAMES = {"GLITCH SHOT", "IRIDE NEON", "GENIO", "PIXEL BURN", "CRT OFF", "HOLO SCAN", "ONDA D'URTO", "POLAROID", "CLASSICO", "CASUALE"};
    public static final String[] PHOTO_DESC = {
        "Split RGB, strappi orizzontali e testo che si decodifica, poi la foto vola nella galleria",
        "Lamelle del diaframma al neon che si chiudono e riaprono",
        "Lampada magica: la foto si deforma e viene risucchiata nella miniatura",
        "La foto si sbriciola in pixel incandescenti",
        "Spegnimento di un vecchio televisore a tubo catodico",
        "Scansione olografica con griglia e dati di scatto",
        "Lampo e anelli cromatici che si espandono",
        "La foto esce come una istantanea e scivola via",
        "Lampo nero breve, come lo specchio di una reflex",
        "Un effetto diverso a ogni scatto"};
    public static final String[] VIDEO_KEYS = {"rec_glitch", "rec_scan", "rec_film", "rec_none"};
    public static final String[] VIDEO_NAMES = {"REC GLITCH", "HUD SCAN", "PELLICOLA 3-2-1", "NESSUNO"};
    public static final String[] VIDEO_DESC = {
        "Bordo rosso, REC che si decodifica, split RGB; allo stop la scena si spegne come un CRT",
        "Angoli HUD che si aprono con linea di scansione",
        "Coda di pellicola con conto alla rovescia lampo",
        "Solo l'indicatore REC"};

    private final Random R = new Random();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorMatrixColorFilter onlyR, onlyG, onlyB;
    private final PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private Theme th;
    private String fx = "none";
    private Bitmap frame;
    private RectF from = new RectF(), to = new RectF();
    private String caption = "";
    private final Glitch.Scrambler sc = new Glitch.Scrambler();
    private long start, dur;
    private Runnable end;
    private final float[] verts = new float[(GenieMesh.MW + 1) * (GenieMesh.MH + 1) * 2];

    public ShutterFx(Context c, Theme th) {
        super(c);
        this.th = th;
        onlyR = new ColorMatrixColorFilter(new ColorMatrix(new float[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0}));
        onlyG = new ColorMatrixColorFilter(new ColorMatrix(new float[]{0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0}));
        onlyB = new ColorMatrixColorFilter(new ColorMatrix(new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0}));
        tp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        tp.setLetterSpacing(0.1f);
        line.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }
    public void setTheme(Theme t) { th = t; }
    public boolean busy() { return !"none".equals(fx); }

    public static String resolve(String key, Random r) {
        if (!"random".equals(key)) return key;
        return PHOTO_KEYS[r.nextInt(PHOTO_KEYS.length - 2)];
    }

    /** frame: viewfinder snapshot drawn into 'from'; it lands into 'to' (gallery thumbnail). */
    public void play(String key, Bitmap frame, RectF from, RectF to, String caption, Runnable end) {
        finishNow();
        fx = resolve(key, R);
        this.frame = frame; this.from.set(from); this.to.set(to); this.caption = caption; this.end = end;
        switch (fx) {
            case "glitch": dur = 820; break;
            case "iris": dur = 620; break;
            case "genie": dur = 640; break;
            case "pixel": dur = 900; break;
            case "crt": dur = 620; break;
            case "holo": dur = 900; break;
            case "shock": dur = 560; break;
            case "polaroid": dur = 900; break;
            case "rec_glitch": dur = 650; break;
            case "rec_stop": dur = 620; break;
            case "rec_scan": dur = 700; break;
            case "rec_film": dur = 1000; break;
            default: dur = 140;
        }
        sc.set(caption, true);
        start = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    public void finishNow() {
        if ("none".equals(fx)) return;
        fx = "none";
        Runnable e = end; end = null;
        invalidate();
        if (e != null) e.run();
    }

    private static float ease(float t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }
    private static float clamp(float t) { return Math.max(0, Math.min(1, t)); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private static int a(int c, int al) { return (c & 0x00ffffff) | (Math.max(0, Math.min(255, al)) << 24); }

    private void lerp(RectF out, RectF a, RectF b, float t) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    @Override protected void onDraw(Canvas c) {
        if ("none".equals(fx)) return;
        float t = (SystemClock.uptimeMillis() - start) / (float) dur;
        if (t >= 1) { post(this::finishNow); return; }
        switch (fx) {
            case "glitch": glitch(c, t); break;
            case "iris": iris(c, t); break;
            case "genie": genie(c, t, true); break;
            case "pixel": pixel(c, t); break;
            case "crt": crt(c, t, true); break;
            case "rec_stop": crt(c, t, true); break;
            case "holo": holo(c, t); break;
            case "shock": shock(c, t); break;
            case "polaroid": polaroid(c, t); break;
            case "rec_glitch": recGlitch(c, t); break;
            case "rec_scan": recScan(c, t); break;
            case "rec_film": recFilm(c, t); break;
            default: classic(c, t);
        }
        postInvalidateOnAnimation();
    }

    /* ---------------- effects ---------------- */

    private void classic(Canvas c, float t) {
        c.drawColor(a(0xff000000, (int) (255 * (1 - t))));
    }

    private void rgbSplit(Canvas c, RectF dst, float split, int slices, float tear) {
        if (frame == null) return;
        c.saveLayer(dst.left - split - tear, dst.top, dst.right + split + tear, dst.bottom, null);
        p.setXfermode(null); p.setColorFilter(null); p.setColor(0xff000000); p.setStyle(Paint.Style.FILL);
        c.drawRect(dst.left - split - tear, dst.top, dst.right + split + tear, dst.bottom, p);
        p.setXfermode(add);
        RectF d = new RectF();
        p.setColorFilter(onlyR); d.set(dst); d.offset(-split, 0); c.drawBitmap(frame, null, d, p);
        p.setColorFilter(onlyG); c.drawBitmap(frame, null, dst, p);
        p.setColorFilter(onlyB); d.set(dst); d.offset(split, 0); c.drawBitmap(frame, null, d, p);
        p.setXfermode(null); p.setColorFilter(null);
        c.restore();
        // horizontal tearing slices
        Rect src = new Rect();
        for (int i = 0; i < slices; i++) {
            float y0 = R.nextFloat(), hh = 0.01f + R.nextFloat() * 0.06f;
            src.set(0, (int) (y0 * frame.getHeight()), frame.getWidth(), (int) Math.min(frame.getHeight(), (y0 + hh) * frame.getHeight()));
            float dy = dst.top + y0 * dst.height(), dh = hh * dst.height();
            float dx = (R.nextFloat() - 0.5f) * tear * 2;
            d.set(dst.left + dx, dy, dst.right + dx, dy + dh);
            p.setColorFilter(R.nextBoolean() ? null : onlyG);
            c.drawBitmap(frame, src, d, p);
            p.setColorFilter(null);
            if (R.nextInt(3) == 0) { p.setColor(a(R.nextBoolean() ? th.accent : th.secondary, 120)); c.drawRect(d, p); }
        }
    }

    private void caption(Canvas c, float cx, float cy, float size, int alpha) {
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        String s = sc.frame();
        tp.setColor(a(th.secondary, alpha)); c.drawText(s, cx - dp(2), cy, tp);
        tp.setColor(a(th.accent, alpha)); c.drawText(s, cx + dp(2), cy, tp);
        tp.setColor(a(0xffffffff, alpha)); c.drawText(s, cx, cy, tp);
    }

    private void glitch(Canvas c, float t) {
        if (t < 0.55f) {
            float k = t / 0.55f;
            float split = dp(4 + 22 * (1 - k)) * (R.nextFloat() * 0.6f + 0.7f);
            rgbSplit(c, from, split, 5 + R.nextInt(6), dp(30) * (1 - k * 0.6f));
            p.setColor(a(0xffffffff, (int) (200 * Math.max(0, 1 - t * 8)))); c.drawRect(from, p);
            caption(c, from.centerX(), from.centerY(), dp(26), 255);
            scan(c, from, k);
        } else {
            genieAt(c, (t - 0.55f) / 0.45f);
        }
    }

    private void scan(Canvas c, RectF r, float k) {
        p.setColor(0x22000000);
        for (float y = r.top; y < r.bottom; y += dp(3)) c.drawRect(r.left, y, r.right, y + dp(1), p);
        float y = r.top + r.height() * k;
        p.setShader(new LinearGradient(0, y - dp(40), 0, y, 0, a(th.accent, 150), Shader.TileMode.CLAMP));
        c.drawRect(r.left, y - dp(40), r.right, y, p);
        p.setShader(null);
    }

    private void genie(Canvas c, float t, boolean flash) {
        if (flash && t < 0.15f) {
            if (frame != null) c.drawBitmap(frame, null, from, p);
            p.setColor(a(0xffffffff, (int) (255 * (1 - t / 0.15f)))); c.drawRect(from, p);
            return;
        }
        genieAt(c, (t - 0.15f) / 0.85f);
    }

    private void genieAt(Canvas c, float k) {
        if (frame == null) return;
        GenieMesh.mesh(verts, from, to, clamp(k));
        p.setAlpha(255);
        c.drawBitmapMesh(frame, GenieMesh.MW, GenieMesh.MH, verts, 0, null, 0, p);
        line.setColor(a(th.accent, 180)); line.setStrokeWidth(dp(2));
        Path l = new Path(), r = new Path();
        for (int row = 0; row <= GenieMesh.MH; row++) {
            int i = row * (GenieMesh.MW + 1) * 2, j = i + GenieMesh.MW * 2;
            if (row == 0) { l.moveTo(verts[i], verts[i + 1]); r.moveTo(verts[j], verts[j + 1]); }
            else { l.lineTo(verts[i], verts[i + 1]); r.lineTo(verts[j], verts[j + 1]); }
        }
        c.drawPath(l, line); c.drawPath(r, line);
        p.setAlpha(255);
    }

    private void iris(Canvas c, float t) {
        float close = t < 0.3f ? ease(t / 0.3f) : t < 0.42f ? 1 : 1 - ease((t - 0.42f) / 0.35f);
        if (frame != null && t < 0.8f) c.drawBitmap(frame, null, from, p);
        if (t >= 0.8f) { genieAt(c, (t - 0.8f) / 0.2f); }
        float cx = from.centerX(), cy = from.centerY();
        float maxR = (float) Math.hypot(from.width(), from.height()) / 2;
        float rad = maxR * (1 - close) + dp(2);
        int blades = 7;
        float rot = close * 1.2f;
        Path hole = new Path();
        for (int i = 0; i < blades; i++) {
            double ang = rot + i * Math.PI * 2 / blades;
            float x = cx + (float) Math.cos(ang) * rad, y = cy + (float) Math.sin(ang) * rad;
            if (i == 0) hole.moveTo(x, y); else hole.lineTo(x, y);
        }
        hole.close();
        Path mask = new Path();
        mask.addRect(from, Path.Direction.CW);
        mask.addPath(hole);
        mask.setFillType(Path.FillType.EVEN_ODD);
        p.setColor(a(0xff05070c, t < 0.8f ? 245 : (int) (245 * (1 - (t - 0.8f) / 0.2f))));
        c.drawPath(mask, p);
        line.setStrokeWidth(dp(2)); line.setColor(a(th.accent, 230));
        line.setShadowLayer(dp(8), 0, 0, th.accent);
        c.drawPath(hole, line);
        line.clearShadowLayer();
        line.setStrokeWidth(dp(1)); line.setColor(a(th.secondary, 160));
        for (int i = 0; i < blades; i++) {
            double ang = rot + i * Math.PI * 2 / blades;
            float x = cx + (float) Math.cos(ang) * rad, y = cy + (float) Math.sin(ang) * rad;
            double tang = ang + Math.PI / 2 + 0.5;
            c.drawLine(x, y, x + (float) Math.cos(tang) * maxR, y + (float) Math.sin(tang) * maxR, line);
        }
    }

    private void pixel(Canvas c, float t) {
        if (frame == null) return;
        int cols = 22, rows = Math.max(1, Math.round(cols * from.height() / from.width()));
        float bw = from.width() / cols, bh = from.height() / rows;
        Rect src = new Rect(); RectF d = new RectF();
        Random rr = new Random(42);
        float fw = frame.getWidth() / (float) cols, fh = frame.getHeight() / (float) rows;
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            float delay = rr.nextFloat() * 0.45f + (1 - y / (float) rows) * 0.15f;
            float k = clamp((t - delay) / 0.45f);
            float tx = to.centerX(), ty = to.centerY();
            float ox = from.left + x * bw, oy = from.top + y * bh;
            float e = ease(k);
            float px = ox + (tx - ox) * e + (rr.nextFloat() - 0.5f) * dp(60) * (float) Math.sin(e * Math.PI);
            float py = oy + (ty - oy) * e - dp(80) * (float) Math.sin(e * Math.PI) * rr.nextFloat();
            float s = 1 - e * 0.85f;
            src.set((int) (x * fw), (int) (y * fh), (int) ((x + 1) * fw), (int) ((y + 1) * fh));
            d.set(px, py, px + bw * s, py + bh * s);
            p.setAlpha((int) (255 * (1 - e * 0.7f)));
            c.drawBitmap(frame, src, d, p);
            if (k > 0 && k < 1) {
                p.setAlpha(255);
                p.setColor(a(k < 0.5f ? 0xffffb000 : th.secondary, (int) (180 * Math.sin(k * Math.PI))));
                c.drawRect(d, p);
            }
        }
        p.setAlpha(255);
    }

    private void crt(Canvas c, float t, boolean toThumb) {
        if (frame == null) return;
        float cx = from.centerX(), cy = from.centerY();
        RectF d = new RectF();
        if (t < 0.32f) {
            float k = ease(t / 0.32f);
            float hh = from.height() * (1 - k) + dp(2);
            d.set(from.left, cy - hh / 2, from.right, cy + hh / 2);
            c.drawColor(a(0xff000000, (int) (255 * k)));
            c.drawBitmap(frame, null, d, p);
            p.setColor(a(0xffffffff, (int) (200 * k))); c.drawRect(d, p);
        } else if (t < 0.55f) {
            c.drawColor(0xff000000);
            float k = ease((t - 0.32f) / 0.23f);
            float ww = from.width() * (1 - k) + dp(4);
            p.setColor(0xffffffff);
            p.setShadowLayer(dp(14), 0, 0, th.accent);
            c.drawRect(cx - ww / 2, cy - dp(1.5f), cx + ww / 2, cy + dp(1.5f), p);
            p.clearShadowLayer();
        } else {
            float k = ease((t - 0.55f) / 0.45f);
            c.drawColor(a(0xff000000, (int) (255 * (1 - k))));
            float x = cx + (to.centerX() - cx) * k, y = cy + (to.centerY() - cy) * k;
            p.setColor(0xffffffff); p.setShadowLayer(dp(18), 0, 0, th.accent);
            c.drawCircle(x, y, dp(4) + dp(10) * k, p);
            p.clearShadowLayer();
        }
    }

    private void holo(Canvas c, float t) {
        if (frame == null) return;
        float sy = from.top + from.height() * clamp(t / 0.7f);
        float fade = t < 0.75f ? 1 : 1 - (t - 0.75f) / 0.25f;
        c.save(); c.clipRect(from.left, from.top, from.right, sy);
        ColorMatrix cm = new ColorMatrix(); cm.setSaturation(0.2f);
        float r = Color.r(th.accent) / 255f, g = Color.g(th.accent) / 255f, b = Color.b(th.accent) / 255f;
        cm.postConcat(new ColorMatrix(new float[]{r * 1.3f, 0, 0, 0, 0, 0, g * 1.3f, 0, 0, 0, 0, 0, b * 1.3f, 0, 0, 0, 0, 0, 1, 0}));
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        p.setAlpha((int) (230 * fade));
        c.drawBitmap(frame, null, from, p);
        p.setColorFilter(null); p.setAlpha(255);
        line.setStrokeWidth(dp(1)); line.setColor(a(th.accent, (int) (90 * fade)));
        float step = dp(28);
        for (float x = from.left; x < from.right; x += step) c.drawLine(x, from.top, x, sy, line);
        for (float y = from.top; y < sy; y += step) c.drawLine(from.left, y, from.right, y, line);
        c.restore();
        p.setColor(a(0xffffffff, (int) (255 * fade)));
        p.setShadowLayer(dp(16), 0, 0, th.accent);
        c.drawRect(from.left, sy - dp(1.5f), from.right, sy + dp(1.5f), p);
        p.clearShadowLayer();
        String[] lines = caption.split("\n");
        tp.setTextAlign(Paint.Align.LEFT); tp.setTextSize(dp(13));
        for (int i = 0; i < lines.length; i++) {
            float ly = from.top + dp(60) + i * dp(20);
            if (ly > sy) break;
            tp.setColor(a(th.accent, (int) (255 * fade)));
            int n = (int) Math.min(lines[i].length(), lines[i].length() * clamp((t * 1.6f - i * 0.08f)));
            c.drawText("> " + lines[i].substring(0, Math.max(0, n)), from.left + dp(18), ly, tp);
        }
        corners(c, from, a(th.accent, (int) (255 * fade)), dp(26));
    }

    private void corners(Canvas c, RectF r, int col, float len) {
        line.setColor(col); line.setStrokeWidth(dp(3));
        c.drawLine(r.left, r.top, r.left + len, r.top, line); c.drawLine(r.left, r.top, r.left, r.top + len, line);
        c.drawLine(r.right, r.top, r.right - len, r.top, line); c.drawLine(r.right, r.top, r.right, r.top + len, line);
        c.drawLine(r.left, r.bottom, r.left + len, r.bottom, line); c.drawLine(r.left, r.bottom, r.left, r.bottom - len, line);
        c.drawLine(r.right, r.bottom, r.right - len, r.bottom, line); c.drawLine(r.right, r.bottom, r.right, r.bottom - len, line);
    }

    private void shock(Canvas c, float t) {
        if (frame != null && t < 0.7f) {
            float s = 1.05f - 0.05f * ease(t / 0.7f);
            RectF d = new RectF(from);
            float dw = from.width() * (s - 1) / 2, dh = from.height() * (s - 1) / 2;
            d.inset(-dw, -dh);
            c.save(); c.clipRect(from); c.drawBitmap(frame, null, d, p); c.restore();
        } else if (t >= 0.7f) genieAt(c, (t - 0.7f) / 0.3f);
        p.setColor(a(0xffffffff, (int) (255 * Math.max(0, 1 - t / 0.18f)))); c.drawRect(from, p);
        float cx = from.centerX(), cy = from.centerY();
        float maxR = (float) Math.hypot(from.width(), from.height()) / 2;
        for (int i = 0; i < 3; i++) {
            float k = clamp(t * 1.4f - i * 0.12f);
            if (k <= 0 || k >= 1) continue;
            float rr = maxR * ease(k);
            line.setStrokeWidth(dp(10) * (1 - k) + dp(1));
            line.setColor(a(th.secondary, (int) (200 * (1 - k)))); c.drawCircle(cx - dp(3), cy, rr, line);
            line.setColor(a(th.accent, (int) (200 * (1 - k)))); c.drawCircle(cx + dp(3), cy, rr, line);
            line.setColor(a(0xffffffff, (int) (160 * (1 - k)))); line.setStrokeWidth(dp(2)); c.drawCircle(cx, cy, rr, line);
        }
    }

    private void polaroid(Canvas c, float t) {
        if (frame == null) return;
        float k = ease(clamp((t - 0.12f) / 0.88f));
        RectF r = new RectF();
        RectF small = new RectF(from); small.inset(from.width() * 0.12f, from.height() * 0.12f);
        if (t < 0.12f) { lerp(r, from, small, ease(t / 0.12f)); }
        else lerp(r, small, to, k);
        float pad = r.width() * 0.05f;
        c.save();
        c.rotate(-8 * (float) Math.sin(Math.min(1, t * 1.5f) * Math.PI) - 4 * k, r.centerX(), r.centerY());
        p.setColor(0xfff4f1ea);
        p.setShadowLayer(dp(18) * (1 - k), 0, dp(6), 0x99000000);
        c.drawRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad * 4, p);
        p.clearShadowLayer();
        // the picture "develops": starts dark and desaturated
        float dev = clamp(t * 1.6f);
        ColorMatrix cm = new ColorMatrix(); cm.setSaturation(dev);
        cm.postConcat(new ColorMatrix(new float[]{dev, 0, 0, 0, 0, 0, dev, 0, 0, 0, 0, 0, dev, 0, 0, 0, 0, 0, 1, 0}));
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(frame, null, r, p);
        p.setColorFilter(null);
        tp.setTextSize(Math.max(dp(6), pad * 1.6f)); tp.setTextAlign(Paint.Align.LEFT); tp.setColor(0xff3a3a3a);
        c.drawText("AmpyCam · " + caption.split("\n")[0], r.left, r.bottom + pad * 2.6f, tp);
        c.restore();
    }

    private void recGlitch(Canvas c, float t) {
        float k = 1 - t;
        if (frame != null && t < 0.45f) rgbSplit(c, from, dp(18) * k, 6, dp(26) * k);
        line.setStrokeWidth(dp(6) * k + dp(2)); line.setColor(a(0xffff2b4e, (int) (255 * k)));
        line.setShadowLayer(dp(20), 0, 0, 0xffff2b4e);
        c.drawRect(from, line);
        line.clearShadowLayer();
        caption(c, from.centerX(), from.centerY(), dp(34), (int) (255 * Math.min(1, k * 2)));
    }

    private void recScan(Canvas c, float t) {
        float k = ease(t);
        RectF r = new RectF(from);
        r.inset(from.width() * 0.5f * (1 - k), from.height() * 0.5f * (1 - k));
        corners(c, r, a(0xffff2b4e, (int) (255 * (1 - t * 0.6f))), dp(40));
        float y = from.top + from.height() * k;
        p.setColor(a(0xffff2b4e, (int) (200 * (1 - t))));
        c.drawRect(from.left, y - dp(1), from.right, y + dp(1), p);
        caption(c, from.centerX(), from.centerY(), dp(26), (int) (255 * (1 - t)));
    }

    private void recFilm(Canvas c, float t) {
        int n = 3 - Math.min(2, (int) (t * 3));
        float k = (t * 3) % 1;
        c.drawColor(a(0xff1a1408, (int) (200 * (1 - t))));
        float cx = from.centerX(), cy = from.centerY(), rad = Math.min(from.width(), from.height()) * 0.28f;
        line.setStrokeWidth(dp(2)); line.setColor(a(0xffe8dcc0, (int) (230 * (1 - t))));
        c.drawCircle(cx, cy, rad, line); c.drawCircle(cx, cy, rad * 0.85f, line);
        c.drawLine(from.left, cy, from.right, cy, line); c.drawLine(cx, from.top, cx, from.bottom, line);
        p.setColor(a(0xffe8dcc0, (int) (90 * (1 - t))));
        c.drawArc(cx - rad, cy - rad, cx + rad, cy + rad, -90, 360 * k, true, p);
        tp.setTextAlign(Paint.Align.CENTER); tp.setTextSize(rad); tp.setColor(a(0xffffffff, (int) (255 * (1 - t))));
        c.drawText(String.valueOf(n), cx, cy + rad * 0.35f, tp);
    }

    /** Tiny colour channel helpers (avoids android.graphics.Color static import clash). */
    static final class Color {
        static int r(int c) { return (c >> 16) & 0xff; }
        static int g(int c) { return (c >> 8) & 0xff; }
        static int b(int c) { return c & 0xff; }
    }
}
