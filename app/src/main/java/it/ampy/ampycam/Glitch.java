package it.ampy.ampycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

import java.util.Random;

/** Glitch text (decode letter by letter + RGB split), shared by labels and effects. Same engine as AmpyRocket. */
public final class Glitch {
    public static final String GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&@*+=<>/\\|{}[]?!ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄ";
    static final Random R = new Random();
    private Glitch() { }

    public static final class Scrambler {
        private String target = ""; private char[] shown = new char[0]; private long[] lockAt = new long[0];
        public int stepMs = 28, spreadMs = 380;
        public void set(String s, boolean animate) {
            if (s == null) s = ""; target = s; shown = s.toCharArray(); lockAt = new long[s.length()];
            long start = SystemClock.uptimeMillis();
            for (int i = 0; i < lockAt.length; i++) lockAt[i] = animate ? start + (long) (spreadMs * (i + 1f) / Math.max(1, lockAt.length)) + R.nextInt(100) : 0;
        }
        public String target() { return target; }
        public boolean active() { long n = SystemClock.uptimeMillis(); for (long l : lockAt) if (l > n) return true; return false; }
        public String frame() {
            long n = SystemClock.uptimeMillis();
            for (int i = 0; i < shown.length; i++) {
                char t = target.charAt(i);
                if (n >= lockAt[i] || t == ' ' || t == ':' || t == '.' || t == '/' || t == '\n') shown[i] = t;
                else if ((n / stepMs + i) % 2 == 0) shown[i] = GLYPHS.charAt(R.nextInt(GLYPHS.length()));
            }
            return new String(shown);
        }
    }

    /** Small label that decodes whenever its text changes. */
    public static final class Label extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Scrambler sc = new Scrambler();
        private int color, glowA, glowB;
        private boolean center = true;
        public Label(Context c, float sp, int color, int glowA, int glowB, boolean bold) {
            super(c);
            this.color = color; this.glowA = glowA; this.glowB = glowB;
            p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
            p.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
            p.setLetterSpacing(0.06f);
        }
        public Label left() { center = false; return this; }
        public void setColors(int color, int a, int b) { this.color = color; glowA = a; glowB = b; invalidate(); }
        public void setText(String s) {
            if (s.equals(sc.target())) return;
            boolean relayout = s.length() != sc.target().length();
            sc.set(s, true);
            if (relayout) requestLayout();
            postInvalidateOnAnimation();
        }
        public String getText() { return sc.target(); }
        @Override protected void onMeasure(int w, int h) {
            float tw = p.measureText(sc.target().isEmpty() ? " " : sc.target());
            Paint.FontMetrics fm = p.getFontMetrics();
            setMeasuredDimension(resolveSize((int) Math.ceil(tw + getPaddingLeft() + getPaddingRight() + 6), w),
                resolveSize((int) Math.ceil(fm.descent - fm.ascent + getPaddingTop() + getPaddingBottom() + 2), h));
        }
        @Override protected void onDraw(Canvas c) {
            String s = sc.frame();
            Paint.FontMetrics fm = p.getFontMetrics();
            float tw = p.measureText(s);
            float x = center ? (getWidth() - tw) / 2f : getPaddingLeft();
            float y = (getHeight() - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            boolean act = sc.active();
            float sp = act ? 2.5f : 0.8f;
            p.setColor((glowB & 0xffffff) | (act ? 0xc0000000 : 0x70000000)); c.drawText(s, x - sp, y, p);
            p.setColor((glowA & 0xffffff) | (act ? 0xc0000000 : 0x70000000)); c.drawText(s, x + sp, y, p);
            p.setColor(color); c.drawText(s, x, y, p);
            if (act) postInvalidateOnAnimation();
        }
    }
}
