package it.ampy.ampycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Canon-style shooting info strip: mode badge, Tv, Av, ISO, exposure level indicator, WB, style, shots left, battery. */
public class InfoBar extends View {
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Theme th;
    public String badge = "P", tv = "1/125", av = "F1.9", iso = "AUTO", wb = "AWB", style = "ST", drive = "□", af = "SERVO", met = "(◉)";
    public String extra = "";
    public boolean tvSel, isoSel, evSel, tvAuto, isoAuto = true, raw, flashOn;
    public float level;             // exposure level indicator, stops
    public float aebStep;
    public boolean levelBlink;      // out of range
    public int shots = 999, battery = 100;

    public InfoBar(Context c, Theme th) {
        super(c); this.th = th;
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }
    public void setTheme(Theme t) { th = t; invalidate(); }
    float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(58)); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        p.setColor(0xd9070b13); c.drawRect(0, 0, w, h, p);
        p.setColor(Theme.a(th.accent, 70)); c.drawRect(0, 0, w, dp(1), p);
        float y1 = dp(24), x = dp(10);
        // mode badge
        t.setTextSize(dp(13));
        float bw = t.measureText(badge) + dp(12);
        p.setColor(badge.equals("A+") ? 0xff3ccf6e : th.accent);
        c.drawRoundRect(x, dp(8), x + bw, dp(28), dp(4), dp(4), p);
        t.setColor(0xff070b13); c.drawText(badge, x + dp(6), y1, t);
        x += bw + dp(12);
        x = field(c, x, y1, tv, tvSel, tvAuto ? 0xff86adbf : 0xffecfcff);
        x = field(c, x, y1, av, false, 0xffecfcff);
        x = field(c, x, y1, "ISO " + iso, isoSel, isoAuto ? 0xff86adbf : 0xffecfcff);
        // right: shots + battery
        t.setTextSize(dp(12)); t.setColor(0xffecfcff); t.setTextAlign(Paint.Align.RIGHT);
        c.drawText("[" + (shots > 9999 ? "9999" : String.valueOf(shots)) + "]", w - dp(10), y1, t);
        float bx = w - dp(14) - t.measureText("[9999]") - dp(30);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.2f)); p.setColor(battery < 15 ? 0xffff6177 : 0xffecfcff);
        c.drawRect(bx, dp(12), bx + dp(22), dp(24), p);
        p.setStyle(Paint.Style.FILL);
        c.drawRect(bx + dp(22), dp(15), bx + dp(24), dp(21), p);
        int bars = battery > 70 ? 3 : battery > 35 ? 2 : battery > 10 ? 1 : 0;
        for (int i = 0; i < bars; i++) c.drawRect(bx + dp(2) + i * dp(6.6f), dp(14), bx + dp(7) + i * dp(6.6f), dp(22), p);
        t.setTextAlign(Paint.Align.LEFT);
        // second row: exposure level indicator
        float y2 = h - dp(10);
        float sx = dp(12), sw = Math.min(w * 0.46f, dp(190)), step = sw / 6f;
        t.setTextSize(dp(9)); t.setColor(evSel ? th.accent : 0xffecfcff);
        String[] lab = {"3", "2", "1", "0", "1", "2", "3"};
        for (int i = 0; i <= 6; i++) {
            float xx = sx + i * step;
            c.drawText(i == 0 ? "-" : i == 6 ? "+" : "", xx - dp(9), y2 - dp(8), t);
            c.drawText(lab[i], xx - dp(2.5f), y2 - dp(8), t);
            p.setColor(0xccecfcff); c.drawRect(xx - dp(0.6f), y2 - dp(6), xx + dp(0.6f), y2 - dp(2), p);
            if (i < 6) for (int k = 1; k < 3; k++) { float tx = xx + step * k / 3f; c.drawRect(tx - dp(0.5f), y2 - dp(4), tx + dp(0.5f), y2 - dp(2), p); }
        }
        boolean blink = levelBlink && (System.currentTimeMillis() / 300) % 2 == 0;
        float lv = Math.max(-3.2f, Math.min(3.2f, level));
        if (!blink) {
            p.setColor(evSel ? th.accent : 0xffffd16a);
            drawMarker(c, sx + (lv + 3) * step, y2);
            if (aebStep > 0) {
                p.setColor(0xaaffd16a);
                drawMarker(c, sx + (Math.max(-3, lv - aebStep) + 3) * step, y2);
                drawMarker(c, sx + (Math.min(3, lv + aebStep) + 3) * step, y2);
            }
        }
        // icons
        t.setTextSize(dp(10)); t.setColor(0xffecfcff);
        String icons = wb + "  " + style + "  " + drive + "  " + met + "  " + af + (raw ? "  RAW+L" : "  L") + (flashOn ? "  ⚡" : "") + (extra.isEmpty() ? "" : "  " + extra);
        float ix = sx + sw + dp(14);
        c.drawText(ellipsize(icons, w - ix - dp(8)), ix, y2 - dp(2), t);
        if (levelBlink) postInvalidateDelayed(300);
    }

    private void drawMarker(Canvas c, float x, float y) {
        android.graphics.Path m = new android.graphics.Path();
        m.moveTo(x, y + dp(0)); m.lineTo(x - dp(4), y + dp(6)); m.lineTo(x + dp(4), y + dp(6)); m.close();
        c.drawPath(m, p);
    }

    private String ellipsize(String s, float max) {
        if (t.measureText(s) <= max) return s;
        while (s.length() > 1 && t.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private float field(Canvas c, float x, float y, String s, boolean sel, int color) {
        t.setTextSize(dp(15));
        float w = t.measureText(s);
        if (sel) { p.setColor(Theme.a(th.accent, 60)); c.drawRect(x - dp(4), dp(7), x + w + dp(4), dp(30), p); p.setColor(th.accent); c.drawRect(x - dp(4), dp(29), x + w + dp(4), dp(31), p); }
        t.setColor(sel ? th.accent : color);
        c.drawText(s, x, y, t);
        return x + w + dp(16);
    }
}
