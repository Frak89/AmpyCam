package it.ampy.ampycam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Canon-style exposure tables (1/3 stop) and formatting. */
public final class Expo {
    private Expo() { }

    /** Shutter speeds in seconds, 1/3 stop, Canon notation. */
    public static final double[] SHUTTER = {
        30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3.2, 2.5, 2, 1.6, 1.3, 1, 0.8, 0.6, 0.5, 0.4, 0.3,
        1 / 4., 1 / 5., 1 / 6., 1 / 8., 1 / 10., 1 / 13., 1 / 15., 1 / 20., 1 / 25., 1 / 30., 1 / 40., 1 / 50., 1 / 60.,
        1 / 80., 1 / 100., 1 / 125., 1 / 160., 1 / 200., 1 / 250., 1 / 320., 1 / 400., 1 / 500., 1 / 640., 1 / 800.,
        1 / 1000., 1 / 1250., 1 / 1600., 1 / 2000., 1 / 2500., 1 / 3200., 1 / 4000., 1 / 5000., 1 / 6400., 1 / 8000.,
        1 / 10000., 1 / 12800., 1 / 16000.
    };
    public static final int[] ISO = {50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600};

    public static String shutter(double s) {
        if (s >= 0.3) {
            // 0"3 … 30"
            if (Math.abs(s - Math.rint(s)) < 0.05) return ((int) Math.rint(s)) + "\"";
            int whole = (int) Math.floor(s);
            int dec = (int) Math.round((s - whole) * 10);
            return whole + "\"" + dec;
        }
        double d = 1 / s;
        if (d < 10) return "1/" + String.format(Locale.US, "%.0f", d);
        return "1/" + Math.round(d);
    }
    public static String shutterNs(long ns) { return shutter(ns / 1e9); }

    public static String ev(float ev) {
        if (Math.abs(ev) < 0.05f) return "±0";
        int whole = (int) Math.floor(Math.abs(ev) + 0.01f);
        float frac = Math.abs(ev) - whole;
        String f = frac < 0.17f ? "" : frac < 0.5f ? "⅓" : frac < 0.84f ? "⅔" : "";
        if (frac >= 0.84f) whole++;
        return (ev > 0 ? "+" : "−") + (whole > 0 ? whole : (f.isEmpty() ? "0" : "")) + f;
    }

    public static String aperture(float f) { return String.format(Locale.US, "F%.1f", f); }

    /** Shutter list limited to what the sensor can do. */
    public static List<Double> shutterList(long minNs, long maxNs) {
        List<Double> o = new ArrayList<>();
        for (double s : SHUTTER) { long ns = (long) (s * 1e9); if (ns >= minNs * 0.97 && ns <= maxNs * 1.03) o.add(s); }
        if (o.isEmpty()) o.add(1 / 125.);
        return o;
    }
    public static List<Integer> isoList(int min, int max) {
        List<Integer> o = new ArrayList<>();
        for (int v : ISO) if (v >= min && v <= max) o.add(v);
        if (o.isEmpty()) o.add(min);
        return o;
    }

    /** Index of the nearest table value (in stops). */
    public static int nearestShutter(List<Double> l, double s) {
        int best = 0; double bd = 1e9;
        for (int i = 0; i < l.size(); i++) { double d = Math.abs(Math.log(l.get(i) / s)); if (d < bd) { bd = d; best = i; } }
        return best;
    }
    public static int nearestIso(List<Integer> l, double iso) {
        int best = 0; double bd = 1e9;
        for (int i = 0; i < l.size(); i++) { double d = Math.abs(Math.log(l.get(i) / iso)); if (d < bd) { bd = d; best = i; } }
        return best;
    }

    /** Approximate RGB of a black body (Tanner Helland), 0..1. Used for Kelvin white balance. */
    public static float[] kelvinRgb(int k) {
        double t = k / 100.0, r, g, b;
        if (t <= 66) r = 255; else r = 329.698727446 * Math.pow(t - 60, -0.1332047592);
        if (t <= 66) g = 99.4708025861 * Math.log(t) - 161.1195681661; else g = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        if (t >= 66) b = 255; else if (t <= 19) b = 0; else b = 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return new float[]{(float) clamp(r / 255, 0.01, 1), (float) clamp(g / 255, 0.01, 1), (float) clamp(b / 255, 0.01, 1)};
    }
    static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
}
