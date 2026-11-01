package it.ampy.ampycam;

import android.content.Context;
import android.content.SharedPreferences;

/** All persistent settings. Keys are plain strings so the Q menu / settings can be generic. */
public final class CamPrefs {
    private final SharedPreferences sp;
    private CamPrefs(Context c) { sp = c.getApplicationContext().getSharedPreferences("ampycam", Context.MODE_PRIVATE); }
    public static CamPrefs get(Context c) { return new CamPrefs(c); }

    public String s(String k, String d) { return sp.getString(k, d); }
    public int i(String k, int d) { return sp.getInt(k, d); }
    public float f(String k, float d) { return sp.getFloat(k, d); }
    public boolean b(String k, boolean d) { return sp.getBoolean(k, d); }
    public long l(String k, long d) { return sp.getLong(k, d); }
    public void put(String k, String v) { sp.edit().putString(k, v).apply(); }
    public void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
    public void put(String k, float v) { sp.edit().putFloat(k, v).apply(); }
    public void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    public void put(String k, long v) { sp.edit().putLong(k, v).apply(); }
    public SharedPreferences raw() { return sp; }

    public String theme() { return s("theme", "cyberpunk"); }
    public boolean haptics() { return b("haptics", true); }
    public boolean sound() { return b("sound", true); }

    /** Canon-style file counter: IMG_0001 … IMG_9999, then folder roll-over like a real body. */
    public synchronized int nextFileNumber() {
        int n = i("fileNo", 0) + 1;
        if (n > 9999) n = 1;
        put("fileNo", n);
        put("shutterCount", l("shutterCount", 0) + 1);
        return n;
    }
    public long shutterCount() { return l("shutterCount", 0); }
}
