package it.ampy.ampycam;

import android.graphics.RectF;

/** "Magic lamp" genie morph mesh (same maths as AmpyRocket's GenieFx). */
public final class GenieMesh {
    public static final int MW = 12, MH = 24;
    private GenieMesh() { }

    static float ease(float t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    /** Fills verts ((MW+1)*(MH+1)*2) for progress t (0 = big rect, 1 = small rect). Bottom rows lead. */
    public static void mesh(float[] verts, RectF big, RectF small, float t) {
        int k = 0;
        // target below the frame: rows closest to it squeeze first
        boolean down = small.centerY() >= big.centerY();
        for (int r = 0; r <= MH; r++) {
            float v = r / (float) MH;
            float lead = down ? v : 1 - v;
            float lt = ease(t * 1.7f - (1 - lead) * 0.7f);
            float yt = ease(t * 1.35f - 0.35f);
            float l = big.left + (small.left - big.left) * lt, rr = big.right + (small.right - big.right) * lt;
            float y = big.top + big.height() * v, ty = small.top + small.height() * v;
            y = y + (ty - y) * yt;
            for (int c = 0; c <= MW; c++) { float u = c / (float) MW; verts[k++] = l + (rr - l) * u; verts[k++] = y; }
        }
    }
}
