package it.ampy.ampycam;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** ⚙ AmpyCam settings (menu-style, like the red/blue tabs of an EOS body). */
public class SettingsActivity extends Activity {
    private CamPrefs p;
    private Theme th;
    private LinearLayout list;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = CamPrefs.get(this);
        th = Theme.load(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(th.bg);
        sv.setFitsSystemWindows(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(list);
        setContentView(sv);
        build();
    }

    private TextView text(String s, float sp, int c, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(c);
        t.setTypeface(bold ? Ui.MONO_BOLD : Ui.MONO); t.setLetterSpacing(0.05f);
        return t;
    }
    private void section(String s) {
        TextView t = text("■ " + s, 12, th.secondary, true);
        t.setPadding(0, dp(20), 0, dp(4));
        list.addView(t);
    }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(); g.setColor(th.panel); g.setStroke(dp(1), th.border); g.setCornerRadius(dp(th.round));
        c.setBackground(g);
        c.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        list.addView(c, lp);
        return c;
    }
    interface IntCb { void on(int i); }
    private void choice(String title, String[] labels, int sel, IntCb cb) {
        LinearLayout c = card();
        c.addView(text(title, 11, th.muted, false));
        c.addView(text(labels[Math.max(0, Math.min(labels.length - 1, sel))] + "  ▾", 15, th.accent, true));
        c.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title)
            .setSingleChoiceItems(labels, sel, (d, i) -> { cb.on(i); d.dismiss(); rebuild(); }).show());
    }
    private void toggle(String key, String title, String sub, boolean def) {
        LinearLayout c = card();
        Switch s = new Switch(this);
        s.setText(title); s.setTextColor(th.fg); s.setTextSize(14); s.setTypeface(Ui.MONO);
        s.setChecked(p.b(key, def));
        s.setOnCheckedChangeListener((b, v) -> p.put(key, v));
        c.addView(s);
        if (sub != null) c.addView(text(sub, 11, th.muted, false));
    }
    private void rebuild() { list.removeAllViews(); build(); }

    private void build() {
        list.addView(text("// AMPYCAM", 22, th.accent, true));
        list.addView(text("Fotocamera cyber con controlli da reflex Canon", 12, th.muted, false));

        section("ASPETTO");
        int ti = 0; for (int i = 0; i < Theme.KEYS.length; i++) if (Theme.KEYS[i].equals(p.theme())) ti = i;
        choice("TEMA (come AmpyRocket / AmpyRAW)", Theme.NAMES, ti, i -> { p.put("theme", Theme.KEYS[i]); th = Theme.load(this); ((ScrollView) list.getParent()).setBackgroundColor(th.bg); });
        String fx = p.s("fx", "glitch"); int fi = 0;
        String[] fl = new String[ShutterFx.PHOTO_KEYS.length];
        for (int i = 0; i < fl.length; i++) { fl[i] = ShutterFx.PHOTO_NAMES[i] + " — " + ShutterFx.PHOTO_DESC[i]; if (ShutterFx.PHOTO_KEYS[i].equals(fx)) fi = i; }
        choice("EFFETTO SCATTO FOTO", fl, fi, i -> p.put("fx", ShutterFx.PHOTO_KEYS[i]));
        String vfx = p.s("vfx", "rec_glitch"); int vi = 0;
        String[] vl = new String[ShutterFx.VIDEO_KEYS.length];
        for (int i = 0; i < vl.length; i++) { vl[i] = ShutterFx.VIDEO_NAMES[i] + " — " + ShutterFx.VIDEO_DESC[i]; if (ShutterFx.VIDEO_KEYS[i].equals(vfx)) vi = i; }
        choice("EFFETTO AVVIO / STOP VIDEO", vl, vi, i -> p.put("vfx", ShutterFx.VIDEO_KEYS[i]));
        toggle("liveStyle", "Picture Style nel mirino", "Anteprima dal vivo del look scelto (come la simulazione Canon)", true);

        section("SCATTO");
        toggle("sound", "Suono otturatore", null, true);
        toggle("haptics", "Vibrazione", null, true);
        toggle("volShutter", "Tasti volume = pulsante di scatto", "Tieni premuto con RAFFICA per lo scatto continuo", true);
        toggle("mirrorFront", "Specchia le foto della fotocamera frontale", null, true);
        toggle("faces", "Rilevamento volti (AF + Ritratto)", null, true);

        section("ASSISTENZA AL FUOCO / ESPOSIZIONE");
        int[] pc = {0xffff2bd6, 0xff00ffe2, 0xffff2b4e, 0xffffd16a, 0xffffffff};
        String[] pcn = {"MAGENTA", "CIANO", "ROSSO", "GIALLO", "BIANCO"};
        int pci = 0; for (int i = 0; i < pc.length; i++) if (pc[i] == p.i("peakColor", pc[0])) pci = i;
        choice("COLORE FOCUS PEAKING", pcn, pci, i -> p.put("peakColor", pc[i]));
        int[] pl = {30, 60, 85}; String[] pln = {"BASSO", "MEDIO", "ALTO"};
        int pli = 1; for (int i = 0; i < pl.length; i++) if (pl[i] == p.i("peakLevel", 60)) pli = i;
        choice("SENSIBILITÀ PEAKING", pln, pli, i -> p.put("peakLevel", pl[i]));
        int[] zl = {230, 240, 245, 250}; String[] zln = {"90%", "95%", "98%", "100%"};
        int zli = 2; for (int i = 0; i < zl.length; i++) if (zl[i] == p.i("zebraLevel", 245)) zli = i;
        choice("SOGLIA ZEBRA", zln, zli, i -> p.put("zebraLevel", zl[i]));

        section("VIDEO");
        String[] res = {"720", "1080", "2160"}; String[] resN = {"HD 1280×720", "FULL HD 1920×1080", "4K UHD 3840×2160"};
        int ri = 1; for (int i = 0; i < res.length; i++) if (res[i].equals(p.s("videoRes", "1080"))) ri = i;
        choice("RISOLUZIONE", resN, ri, i -> p.put("videoRes", res[i]));
        int[] fps = {24, 30, 60}; int fpi = 1; for (int i = 0; i < fps.length; i++) if (fps[i] == p.i("videoFps", 30)) fpi = i;
        choice("FREQUENZA FOTOGRAMMI", new String[]{"24p (cinema)", "30p", "60p (fluido)"}, fpi, i -> p.put("videoFps", fps[i]));
        toggle("hevc", "Codec HEVC (H.265)", "File più piccoli; disattiva per la massima compatibilità (H.264)", false);
        toggle("stab", "Stabilizzazione digitale", null, true);

        section("FILE");
        TextView info = text("Cartella: DCIM/AmpyCam · nomi IMG_xxxx.JPG / .DNG, MVI_xxxx.MP4\nScatti totali (shutter count): " + p.shutterCount()
            + "\nProssimo file: " + ImageSaver.name(p.i("fileNo", 0) + 1, "JPG"), 12, th.fg, false);
        LinearLayout c = card(); c.addView(info);
        LinearLayout r = card();
        r.addView(text("AZZERA NUMERAZIONE FILE", 14, th.warn, true));
        r.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Azzera numerazione?").setMessage("Il prossimo file sarà IMG_0001. Le foto esistenti non vengono toccate.")
            .setPositiveButton("AZZERA", (d, w) -> { p.put("fileNo", 0); rebuild(); }).setNegativeButton("ANNULLA", null).show());

        section("ABOUT");
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView about = text("AmpyCam " + ver + "\nCreatore: Davide Fraccaro · GitHub Frak89\nFamiglia Ampy: AmpyRocket · AmpyLink · AmpyRAW Cyberdark", 12, th.muted, false);
        about.setGravity(Gravity.START);
        card().addView(about);
    }
}
