package it.ampy.ampycam;

import android.graphics.Bitmap;

/** Live-view analysis on a small viewfinder snapshot: histogram, metering, zebra, focus peaking. */
public final class Analyzer {
    public static final class Out {
        public final int[] lum = new int[64], r = new int[64], g = new int[64], b = new int[64];
        public int max = 1;
        public float meanLuma;      // 0..255, weighted by the metering mode
        public float clipHi, clipLo; // fraction of clipped pixels
        public Bitmap overlay;      // zebra + peaking, same size as the input (or null)
        public float sharpness;
    }

    private int[] px = new int[0];
    private int[] luma = new int[0];
    private Bitmap overlay;
    private int[] ov = new int[0];

    /** metering: CamState.MET_*, (ax, ay) AF point normalized. */
    public Out run(Bitmap bmp, int metering, float ax, float ay, boolean zebra, int zebraLevel, boolean peaking, int peakColor, int peakLevel) {
        int w = bmp.getWidth(), h = bmp.getHeight(), n = w * h;
        if (px.length != n) { px = new int[n]; luma = new int[n]; ov = new int[n]; }
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        Out o = new Out();
        double sum = 0, wsum = 0;
        int hi = 0, lo = 0;
        float rad = metering == CamState.MET_SPOT ? 0.06f : metering == CamState.MET_PARTIAL ? 0.12f : 0.35f;
        float cx = metering == CamState.MET_PARTIAL || metering == CamState.MET_SPOT ? ax : 0.5f;
        float cy = metering == CamState.MET_PARTIAL || metering == CamState.MET_SPOT ? ay : 0.5f;
        for (int y = 0; y < h; y++) {
            float fy = y / (float) h - cy;
            for (int x = 0; x < w; x++) {
                int i = y * w + x, c = px[i];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                int l = (r * 77 + g * 150 + b * 29) >> 8;
                luma[i] = l;
                o.lum[l >> 2]++; o.r[r >> 2]++; o.g[g >> 2]++; o.b[b >> 2]++;
                if (l >= 250) hi++; else if (l <= 4) lo++;
                float fx = x / (float) w - cx;
                float d2 = (fx * fx + fy * fy) / (rad * rad);
                double wt;
                switch (metering) {
                    case CamState.MET_SPOT: case CamState.MET_PARTIAL: wt = d2 < 1 ? 1 : 0; break;
                    case CamState.MET_CENTER: wt = Math.exp(-d2 * 0.8) + 0.08; break;
                    default: wt = 0.35 + Math.exp(-d2 * 0.5); // evaluative-ish: centre heavy, whole frame counts
                }
                sum += l * wt; wsum += wt;
            }
        }
        o.meanLuma = (float) (wsum > 0 ? sum / wsum : 118);
        o.clipHi = hi / (float) n; o.clipLo = lo / (float) n;
        for (int i = 0; i < 64; i++) o.max = Math.max(o.max, Math.max(o.lum[i], Math.max(o.r[i], Math.max(o.g[i], o.b[i]))));
        if (zebra || peaking) {
            int thr = 8 + (100 - peakLevel) / 3;
            long ts = System.nanoTime() / 60_000_000L;
            double sharp = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = y * w + x, v = 0;
                if (zebra && luma[i] >= zebraLevel && (((x + y + ts) / 3) & 1) == 0) v = 0xd0ffffff;
                if (peaking && x > 0 && y > 0 && x < w - 1 && y < h - 1) {
                    int gx = luma[i + 1] - luma[i - 1], gy = luma[i + w] - luma[i - w];
                    int gx2 = (luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1]) - (luma[i - w - 1] + 2 * luma[i - 1] + luma[i + w - 1]);
                    int gy2 = (luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1]) - (luma[i - w - 1] + 2 * luma[i - w] + luma[i - w + 1]);
                    int mag = Math.abs(gx2) + Math.abs(gy2);
                    sharp += Math.abs(gx) + Math.abs(gy);
                    if (mag > thr * 4) v = peakColor;
                }
                ov[i] = v;
            }
            o.sharpness = (float) (sharp / n);
            if (overlay == null || overlay.getWidth() != w || overlay.getHeight() != h) overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            overlay.setPixels(ov, 0, w, 0, 0, w, h);
            o.overlay = overlay;
        }
        return o;
    }

    /** Exposure error in stops between the measured mean and 18% grey (+ev bias). */
    public static float errorStops(float meanLuma, float ev) {
        double lin = Math.pow(Math.max(1, meanLuma) / 255.0, 2.2);
        double target = Math.pow(118 / 255.0, 2.2) * Math.pow(2, ev);
        return (float) (Math.log(target / lin) / Math.log(2));
    }
}
