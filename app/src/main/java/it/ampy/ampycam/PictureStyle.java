package it.ampy.ampycam;

import android.graphics.ColorMatrix;

/** Canon Picture Styles + Ampy looks, as colour matrices (same matrix for live preview and saved JPEG). */
public final class PictureStyle {
    public final String key, name, desc;
    public final float sat, contrast, warm, tint, bright;
    public final boolean mono;
    public final float[] split; // optional extra matrix multiply (looks)

    private PictureStyle(String key, String name, String desc, float sat, float contrast, float warm, float tint, float bright, boolean mono, float[] split) {
        this.key = key; this.name = name; this.desc = desc; this.sat = sat; this.contrast = contrast; this.warm = warm; this.tint = tint;
        this.bright = bright; this.mono = mono; this.split = split;
    }

    public static final PictureStyle[] ALL = {
        new PictureStyle("auto", "AUTO", "Canon: colori vivaci adattati alla scena", 1.12f, 1.05f, 0, 0, 0, false, null),
        new PictureStyle("standard", "STANDARD", "Canon: nitido e brillante, nessuna modifica", 1, 1, 0, 0, 0, false, null),
        new PictureStyle("portrait", "RITRATTO", "Canon: incarnato morbido e caldo", 0.95f, 0.94f, 0.05f, 0.02f, 0.02f, false, null),
        new PictureStyle("landscape", "PAESAGGIO", "Canon: blu e verdi saturi, più contrasto", 1.28f, 1.1f, -0.02f, -0.02f, 0, false, null),
        new PictureStyle("finedetail", "DETTAGLIO FINE", "Canon: micro-contrasto alto, colori naturali", 1.0f, 1.12f, 0, 0, 0, false, null),
        new PictureStyle("neutral", "NEUTRO", "Canon: piatto, ideale per post-produzione", 0.82f, 0.88f, 0, 0, 0.02f, false, null),
        new PictureStyle("faithful", "FEDELE", "Canon: colori colorimetrici a 5200K", 0.9f, 0.95f, 0, 0, 0, false, null),
        new PictureStyle("mono", "MONOCROMATICO", "Canon: bianco e nero", 0, 1.08f, 0, 0, 0, true, null),
        new PictureStyle("cyberdark", "CYBERDARK", "Ampy: ombre teal, luci magenta (AmpyRAW)", 1.15f, 1.15f, -0.05f, 0.06f, -0.04f, false,
            new float[]{1.05f, 0, 0.05f, 0, 0, 0, 0.95f, 0.08f, 0, 0, 0.05f, 0.08f, 1.1f, 0, 6, 0, 0, 0, 1, 0}),
        new PictureStyle("neon", "NEON", "Ampy: saturazione estrema cyan/magenta", 1.55f, 1.12f, 0, 0.05f, 0, false,
            new float[]{1.08f, 0, 0.06f, 0, 0, 0, 1.0f, 0.06f, 0, 0, 0.04f, 0, 1.12f, 0, 0, 0, 0, 0, 1, 0}),
        new PictureStyle("portra", "KODAK PORTRA", "Pellicola: caldo, morbido, pastello", 0.9f, 0.9f, 0.07f, 0.02f, 0.03f, false,
            new float[]{1.02f, 0.04f, 0, 0, 6, 0, 1.0f, 0.02f, 0, 4, 0, 0.03f, 0.92f, 0, 0, 0, 0, 0, 1, 0}),
        new PictureStyle("gold", "KODAK GOLD", "Pellicola: gialli e rossi caldi, saturi", 1.15f, 1.04f, 0.1f, 0, 0.02f, false, null),
        new PictureStyle("tealorange", "TEAL & ORANGE", "Cinema: pelle arancio, ombre teal", 1.12f, 1.08f, 0.02f, -0.02f, 0, false,
            new float[]{1.1f, 0.02f, -0.08f, 0, 0, -0.02f, 1.0f, 0.04f, 0, 0, -0.08f, 0.06f, 1.08f, 0, 4, 0, 0, 0, 1, 0}),
        new PictureStyle("noir", "NOIR", "B/N contrastato da cinema", 0, 1.3f, 0, 0, -0.04f, true, null),
        new PictureStyle("matrix", "MATRIX", "Verde digitale", 0.7f, 1.12f, 0, -0.12f, -0.02f, false,
            new float[]{0.85f, 0.05f, 0, 0, 0, 0.05f, 1.1f, 0.05f, 0, 8, 0, 0.05f, 0.8f, 0, 0, 0, 0, 0, 1, 0}),
    };

    public static PictureStyle of(String k) {
        for (PictureStyle s : ALL) if (s.key.equals(k)) return s;
        return ALL[1];
    }
    public static int index(String k) { for (int i = 0; i < ALL.length; i++) if (ALL[i].key.equals(k)) return i; return 1; }

    public boolean isIdentity() { return key.equals("standard"); }

    public ColorMatrix matrix() {
        ColorMatrix m = new ColorMatrix();
        ColorMatrix s = new ColorMatrix(); s.setSaturation(sat); m.postConcat(s);
        float c = contrast, t = 128 * (1 - c) + bright * 255;
        m.postConcat(new ColorMatrix(new float[]{c, 0, 0, 0, t, 0, c, 0, 0, t, 0, 0, c, 0, t, 0, 0, 0, 1, 0}));
        if (warm != 0 || tint != 0) {
            m.postConcat(new ColorMatrix(new float[]{1 + warm, 0, 0, 0, warm * 30, 0, 1 - tint * 0.5f, 0, 0, -tint * 10,
                0, 0, 1 - warm, 0, -warm * 30, 0, 0, 0, 1, 0}));
        }
        if (split != null) m.postConcat(new ColorMatrix(split));
        return m;
    }
}
