package it.ampy.ampycam;

/** Everything the photographer can set. Lives on the UI thread; persisted in CamPrefs. */
public final class CamState {
    // carousel modes (Pixel-style)
    public static final int M_NIGHT = 0, M_PORTRAIT = 1, M_PHOTO = 2, M_VIDEO = 3, M_PRO = 4;
    public static final String[] MODE_NAMES = {"NOTTE", "RITRATTO", "FOTO", "VIDEO", "PRO"};

    // Canon mode dial (PRO)
    public static final int D_AUTO = 0, D_P = 1, D_TV = 2, D_AV = 3, D_M = 4, D_C1 = 5;
    public static final String[] DIAL = {"A+", "P", "Tv", "Av", "M", "C1"};

    public static final int AF_ONESHOT = 0, AF_SERVO = 1, AF_MF = 2;
    public static final String[] AF_NAMES = {"ONE SHOT", "SERVO", "MF"};
    public static final int MET_EVAL = 0, MET_PARTIAL = 1, MET_SPOT = 2, MET_CENTER = 3;
    public static final String[] MET_NAMES = {"VALUTATIVA", "PARZIALE", "SPOT", "MEDIA CENTR."};
    public static final String[] MET_ICONS = {"(◉)", "( o )", "( · )", "[ ◎ ]"};
    public static final int DR_SINGLE = 0, DR_H = 1, DR_L = 2, DR_T2 = 3, DR_T10 = 4, DR_T10C = 5;
    public static final String[] DRIVE_NAMES = {"SINGOLO", "RAFFICA H+", "RAFFICA L", "AUTOSCATTO 2s", "AUTOSCATTO 10s", "10s + 3 SCATTI"};
    public static final String[] DRIVE_ICONS = {"□", "□H+", "□L", "⏱2", "⏱10", "⏱10C"};
    public static final int FL_OFF = 0, FL_AUTO = 1, FL_ON = 2, FL_TORCH = 3;
    public static final String[] FLASH_NAMES = {"FLASH OFF", "FLASH AUTO", "FLASH ON", "TORCIA"};
    public static final int WB_AUTO = 0, WB_DAY = 1, WB_SHADE = 2, WB_CLOUDY = 3, WB_TUNG = 4, WB_FLUO = 5, WB_K = 6;
    public static final String[] WB_NAMES = {"AWB", "LUCE DIURNA", "OMBRA", "NUVOLOSO", "TUNGSTENO", "FLUORESCENTE", "K"};
    public static final String[] WB_ICONS = {"AWB", "☀", "⌂", "☁", "✺", "⎓", "K"};
    public static final int AS_32 = 0, AS_43 = 1, AS_169 = 2, AS_11 = 3;
    public static final String[] ASPECT_NAMES = {"3:2", "4:3", "16:9", "1:1"};
    public static final float[] ASPECT = {3 / 2f, 4 / 3f, 16 / 9f, 1f};
    public static final float[] AEB_STEPS = {0, 1 / 3f, 2 / 3f, 1, 2};
    public static final String[] AEB_NAMES = {"OFF", "±⅓", "±⅔", "±1", "±2"};

    public int mode = M_PHOTO;
    public int dial = D_P;
    public double shutter = 1 / 125.;   // seconds (Tv / M)
    public int iso = 0;                 // 0 = AUTO
    public int isoMax = 6400;
    public float ev = 0;                // stops
    public int wb = WB_AUTO;
    public int kelvin = 5200;
    public int af = AF_SERVO;
    public float focus = 0;             // diopters (MF), 0 = infinity
    public int metering = MET_EVAL;
    public int drive = DR_SINGLE;
    public int aeb = 0;
    public int flash = FL_OFF;
    public int aspect = AS_43;
    public boolean raw = false;
    public int jpegQ = 95;
    public String style = "standard";
    public String fx = "glitch";
    public String vfx = "rec_glitch";
    public float zoom = 1f;
    public boolean front = false;
    public float afX = 0.5f, afY = 0.5f;
    public boolean afPointSet = false;
    public boolean aeLock = false;
    public int intervalSec = 0, intervalShots = 10;
    public boolean silent = false;
    public boolean htp = false;         // highlight tone priority
    public int portraitBlur = 60;       // 0..100
    public int nightFrames = 8;
    public int c1Base = D_M;

    public boolean isPro() { return mode == M_PRO; }
    /** Effective Canon dial: non-PRO modes behave like the green A+ square. */
    public int effDial() { return mode == M_PRO ? (dial == D_C1 ? c1Base : dial) : D_AUTO; }
    public boolean manualShutter() { int d = effDial(); return d == D_TV || d == D_M; }
    public boolean manualIso() { int d = effDial(); return d != D_AUTO && iso > 0; }

    public void load(CamPrefs p) {
        mode = p.i("mode", M_PHOTO); dial = p.i("dial", D_P); shutter = p.f("shutter", 1 / 125f); iso = p.i("iso", 0);
        isoMax = p.i("isoMax", 6400); ev = p.f("ev", 0); wb = p.i("wb", 0); kelvin = p.i("kelvin", 5200); af = p.i("af", AF_SERVO);
        focus = p.f("focus", 0); metering = p.i("metering", 0); drive = p.i("drive", 0); aeb = p.i("aeb", 0); flash = p.i("flash", 0);
        aspect = p.i("aspect", AS_43); raw = p.b("raw", false); jpegQ = p.i("jpegQ", 95); style = p.s("style", "standard");
        fx = p.s("fx", "glitch"); vfx = p.s("vfx", "rec_glitch"); front = p.b("front", false); silent = p.b("silent", false);
        htp = p.b("htp", false); portraitBlur = p.i("portraitBlur", 60); nightFrames = p.i("nightFrames", 8);
        intervalSec = p.i("intervalSec", 0); intervalShots = p.i("intervalShots", 10);
        c1Base = p.i("c1.base", D_M);
        if (mode < 0 || mode > 4) mode = M_PHOTO;
    }
    public void save(CamPrefs p) {
        p.put("mode", mode); p.put("dial", dial); p.put("shutter", (float) shutter); p.put("iso", iso); p.put("isoMax", isoMax);
        p.put("ev", ev); p.put("wb", wb); p.put("kelvin", kelvin); p.put("af", af); p.put("focus", focus); p.put("metering", metering);
        p.put("drive", drive); p.put("aeb", aeb); p.put("flash", flash); p.put("aspect", aspect); p.put("raw", raw); p.put("jpegQ", jpegQ);
        p.put("style", style); p.put("fx", fx); p.put("vfx", vfx); p.put("front", front); p.put("silent", silent); p.put("htp", htp);
        p.put("portraitBlur", portraitBlur); p.put("nightFrames", nightFrames); p.put("intervalSec", intervalSec); p.put("intervalShots", intervalShots);
    }

    /** C1 custom shooting mode: store / recall the PRO settings, like the C1 position of an EOS dial. */
    public void saveC1(CamPrefs p) {
        p.put("c1.shutter", (float) shutter); p.put("c1.iso", iso); p.put("c1.ev", ev); p.put("c1.wb", wb); p.put("c1.kelvin", kelvin);
        p.put("c1.af", af); p.put("c1.metering", metering); p.put("c1.drive", drive); p.put("c1.aeb", aeb); p.put("c1.style", style);
        c1Base = dial == D_C1 ? c1Base : dial;
        p.put("c1.base", c1Base); p.put("c1.set", true);
    }
    public boolean loadC1(CamPrefs p) {
        if (!p.b("c1.set", false)) return false;
        shutter = p.f("c1.shutter", 1 / 125f); iso = p.i("c1.iso", 0); ev = p.f("c1.ev", 0); wb = p.i("c1.wb", 0); kelvin = p.i("c1.kelvin", 5200);
        af = p.i("c1.af", AF_SERVO); metering = p.i("c1.metering", 0); drive = p.i("c1.drive", 0); aeb = p.i("c1.aeb", 0); style = p.s("c1.style", "standard");
        return true;
    }
}
