package it.ampy.ampycam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.RggbChannelVector;
import android.media.Image;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AmpyCam main screen: Pixel-style layout, Canon EOS controls, Ampy special effects. */
public class CameraActivity extends Activity implements CameraEngine.Callback, SensorEventListener {
    private CamPrefs pr;
    private final CamState st = new CamState();
    private Theme th;
    private CameraEngine eng;
    private CameraEngine.Info info;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();
    private final Analyzer an = new Analyzer();
    private final Random rnd = new Random();

    private FrameLayout root;
    private TextureView tv;
    private HudView hud;
    private ShutterFx fx;
    private InfoBar infoBar;
    private Widgets.Shutter shutter;
    private Widgets.Thumb thumb;
    private Widgets.Carousel carousel;
    private Widgets.Dial dial;
    private LinearLayout topBar, proPanel, dialRow, paramRow, zoomRow, bottom;
    private TextView flashChip, driveChip, aspectChip, fxChip;
    private final List<TextView> dialChips = new ArrayList<>(), paramChips = new ArrayList<>(), zoomChips = new ArrayList<>();
    private int param = 0; // 0 Tv, 1 ISO, 2 EV, 3 WB, 4 MF, 5 AF
    private static final String[] PARAMS = {"Tv", "ISO", "±EV", "WB", "MF", "AF"};

    private final RectF previewRect = new RectF(), captureRect = new RectF();
    private Size previewSize;
    private boolean surfaceReady, opened, analysing;
    private int deviceDeg = 0;
    private OrientationEventListener orient;
    private SensorManager sm;
    private MediaActionSound sound;

    // live metering
    private long lastExp = 10_000_000L; private int lastIso = 100; private float lastAperture = 1.85f, lastFocal = 6.8f;
    private double loopIso = 100, loopExp = 10_000_000; private float meterErr = 0;
    private float zoom = 1f;
    private Uri lastUri; private boolean lastIsVideo;
    private Object[] videoOut;
    private boolean burstHeld;
    private int burstCount;
    private ImageSaver.NightStacker stacker;
    private int intervalLeft;
    private float magnify = 1;
    private int battery = 100;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /* ================================================================ lifecycle */

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pr = CamPrefs.get(this);
        st.load(pr);
        th = Theme.load(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        eng = new CameraEngine(this, this);
        build();
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int d = ((o + 45) / 90 * 90) % 360;
                if (d != deviceDeg) { deviceDeg = d; rotateIcons(); }
            }
        };
        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        sound.load(MediaActionSound.START_VIDEO_RECORDING);
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        sound.load(MediaActionSound.FOCUS_COMPLETE);
    }

    @Override protected void onResume() {
        super.onResume();
        st.load(pr);
        applyLivePreviewStyle();
        refresh();
        Theme nt = Theme.load(this);
        if (!nt.key.equals(th.key)) { th = nt; applyTheme(); }
        immersive();
        orient.enable();
        Sensor rv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rv == null) rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) sm.registerListener(this, rv, SensorManager.SENSOR_DELAY_UI);
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) { int l = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 100), s = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100); battery = s > 0 ? l * 100 / s : 100; }
        hud.grid = pr.i("grid", 1); hud.level = pr.b("level", true); hud.histogram = pr.b("histogram", true);
        if (perms()) maybeOpen();
        startAnalysis();
    }

    @Override protected void onPause() {
        if (eng.isRecording()) toggleVideo();
        st.save(pr);
        orient.disable();
        sm.unregisterListener(this);
        analysing = false;
        eng.close();
        opened = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        eng.shutdown(); saver.shutdown(); analyzer.shutdown();
        if (sound != null) sound.release();
        super.onDestroy();
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            if (getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().hide(WindowInsets.Type.navigationBars());
                getWindow().getInsetsController().setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private boolean perms() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && !pr.b("askedAudio", false)) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (need.isEmpty()) return true;
        pr.put("askedAudio", true);
        requestPermissions(need.toArray(new String[0]), 7);
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) maybeOpen();
        else hud.showToast("SERVE IL PERMESSO FOTOCAMERA");
    }

    private int sessionKind() { return st.mode == CamState.M_VIDEO ? 1 : st.mode == CamState.M_NIGHT ? 3 : 0; }
    private float aspect() { return st.mode == CamState.M_VIDEO ? 16 / 9f : CamState.ASPECT[st.aspect]; }
    private boolean wantRaw() { return st.raw && (st.mode == CamState.M_PHOTO || st.mode == CamState.M_PRO); }

    private void maybeOpen() {
        if (!surfaceReady || opened) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        opened = true;
        eng.open(st.front, tv.getSurfaceTexture(), aspect(), sessionKind(), wantRaw());
    }

    /* ================================================================ UI */

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(12); t.setTextColor(0xffecfcff);
        t.setTypeface(Ui.MONO_BOLD); t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        GradientDrawable g = new GradientDrawable(); g.setColor(0x99070b13); g.setStroke(dp(1), Theme.a(th.accent, 110)); g.setCornerRadius(dp(16));
        t.setBackground(g);
        t.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); l.onClick(v); });
        return t;
    }
    private void chipSel(TextView t, boolean sel) {
        GradientDrawable g = new GradientDrawable(); g.setColor(sel ? Theme.a(th.accent, 220) : 0x99070b13);
        g.setStroke(dp(1), Theme.a(th.accent, sel ? 255 : 110)); g.setCornerRadius(dp(16));
        t.setBackground(g); t.setTextColor(sel ? 0xff070b13 : 0xffecfcff);
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        tv = new TextureView(this);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { surfaceReady = true; if (perms()) maybeOpen(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { surfaceReady = false; return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
        root.addView(tv, new FrameLayout.LayoutParams(1, 1));
        hud = new HudView(this, th);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));

        // top bar
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        flashChip = chip("", v -> { st.flash = (st.flash + 1) % 4; if (info != null && !info.flash && st.flash != 0) { st.flash = 0; hud.showToast("NESSUN FLASH SU QUESTA FOTOCAMERA"); } push(); refresh(); });
        driveChip = chip("", v -> pick("DRIVE / AUTOSCATTO", CamState.DRIVE_NAMES, st.drive, i -> { st.drive = i; refresh(); }));
        aspectChip = chip("", v -> { if (st.mode == CamState.M_VIDEO) return; st.aspect = (st.aspect + 1) % 4; reconfigure(); });
        fxChip = chip("", v -> pickFx());
        TextView q = chip("Q", v -> openQ());
        TextView set = chip("⚙", v -> startActivity(new Intent(this, SettingsActivity.class)));
        for (TextView t : new TextView[]{flashChip, driveChip, aspectChip, fxChip, q, set}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(6);
            topBar.addView(t, lp);
        }
        HorizontalScrollView topScroll = new HorizontalScrollView(this);
        topScroll.setHorizontalScrollBarEnabled(false);
        topScroll.addView(topBar);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(topScroll, tl);
        topScroll.setOnApplyWindowInsetsListener((v, ins) -> { v.setPadding(0, ins.getSystemWindowInsetTop(), 0, 0); return ins; });

        // bottom stack
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        infoBar = new InfoBar(this, th);
        infoBar.setOnClickListener(v -> openQ());
        bottom.addView(infoBar, new LinearLayout.LayoutParams(-1, -2));

        proPanel = new LinearLayout(this);
        proPanel.setOrientation(LinearLayout.VERTICAL);
        proPanel.setBackgroundColor(0xcc070b13);
        dialRow = new LinearLayout(this); dialRow.setGravity(Gravity.CENTER); dialRow.setPadding(0, dp(6), 0, dp(2));
        for (int i = 0; i < CamState.DIAL.length; i++) {
            final int k = i;
            TextView c = chip(CamState.DIAL[i], v -> setDial(k));
            dialChips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = dp(3); lp.rightMargin = dp(3);
            dialRow.addView(c, lp);
        }
        c1LongPress();
        proPanel.addView(dialRow);
        paramRow = new LinearLayout(this); paramRow.setGravity(Gravity.CENTER); paramRow.setPadding(0, dp(2), 0, dp(4));
        for (int i = 0; i < PARAMS.length; i++) {
            final int k = i;
            TextView c = chip(PARAMS[i], v -> { param = k; refreshDial(); refresh(); });
            paramChips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = dp(3); lp.rightMargin = dp(3);
            paramRow.addView(c, lp);
        }
        proPanel.addView(paramRow);
        dial = new Widgets.Dial(this, th);
        proPanel.addView(dial, new LinearLayout.LayoutParams(-1, dp(58)));
        bottom.addView(proPanel, new LinearLayout.LayoutParams(-1, -2));

        zoomRow = new LinearLayout(this); zoomRow.setGravity(Gravity.CENTER); zoomRow.setPadding(0, dp(8), 0, dp(4));
        bottom.addView(zoomRow, new LinearLayout.LayoutParams(-1, -2));

        carousel = new Widgets.Carousel(this, th, CamState.MODE_NAMES);
        carousel.setSelected(st.mode);
        carousel.setListener(this::setMode);
        bottom.addView(carousel, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(28), dp(6), dp(28), dp(18));
        thumb = new Widgets.Thumb(this, th);
        thumb.setOnClickListener(v -> openLast());
        row.addView(thumb, new LinearLayout.LayoutParams(dp(58), dp(58)));
        View sp1 = new View(this); row.addView(sp1, new LinearLayout.LayoutParams(0, 1, 1));
        shutter = new Widgets.Shutter(this, th);
        shutter.setListener(new Widgets.Shutter.L() {
            @Override public void halfPress() { onHalfPress(); }
            @Override public void press() { onShutter(); }
            @Override public void release(boolean lp) { onShutterRelease(); }
        });
        row.addView(shutter, new LinearLayout.LayoutParams(dp(84), dp(84)));
        View sp2 = new View(this); row.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1));
        TextView flip = chip("⟲", v -> switchCamera());
        flip.setTextSize(22);
        row.addView(flip, new LinearLayout.LayoutParams(dp(58), dp(58)));
        bottom.addView(row);
        bottom.setBackgroundColor(0x00000000);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        fx = new ShutterFx(this, th);
        root.addView(fx, new FrameLayout.LayoutParams(-1, -1));
        fx.setClickable(false);

        setupGestures();
        setContentView(root);
        refresh();
        refreshDial();
        updateModeUi();
    }

    private void c1LongPress() {
        TextView c1 = dialChips.get(CamState.D_C1);
        c1.setOnLongClickListener(v -> {
            st.saveC1(pr);
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            hud.showToast("IMPOSTAZIONI REGISTRATE SU C1");
            return true;
        });
    }

    private void applyTheme() {
        hud.setTheme(th); fx.setTheme(th); infoBar.setTheme(th); shutter.setTheme(th); thumb.setTheme(th); carousel.setTheme(th); dial.setTheme(th);
        refresh();
    }

    private void rotateIcons() {
        float r = -deviceDeg == -270 ? 90 : -deviceDeg;
        if (deviceDeg == 270) r = 90; else if (deviceDeg == 90) r = -90; else if (deviceDeg == 180) r = 180; else r = 0;
        for (View v : new View[]{thumb, flashChip, driveChip, aspectChip, fxChip}) v.animate().rotation(r).setDuration(250).start();
    }

    private void updateModeUi() {
        boolean pro = st.mode == CamState.M_PRO;
        proPanel.setVisibility(pro ? View.VISIBLE : View.GONE);
        shutter.setState(st.mode == CamState.M_VIDEO ? (eng.isRecording() ? Widgets.Shutter.REC : Widgets.Shutter.VIDEO) : Widgets.Shutter.PHOTO);
        aspectChip.setAlpha(st.mode == CamState.M_VIDEO ? 0.4f : 1f);
        refresh();
    }

    /** Lay the TextureView out so the preview keeps its aspect, Pixel-style (top aligned, under the chips). */
    private void layoutPreview() {
        if (previewSize == null) return;
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0) { root.post(this::layoutPreview); return; }
        float pa = Math.max(previewSize.getWidth(), previewSize.getHeight()) / (float) Math.min(previewSize.getWidth(), previewSize.getHeight());
        int ph = Math.round(W * pa), pw = W;
        if (ph > H) { ph = H; pw = Math.round(H / pa); }
        int top = pa > 1.6f ? Math.max(0, (H - ph) / 2) : dp(56);
        if (top + ph > H) top = Math.max(0, H - ph);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pw, ph);
        lp.topMargin = top; lp.leftMargin = (W - pw) / 2;
        tv.setLayoutParams(lp);
        previewRect.set(lp.leftMargin, top, lp.leftMargin + pw, top + ph);
        // capture frame inside the preview (3:2 and 1:1 are cropped from 4:3)
        float target = aspect(), cur = ph / (float) pw;
        captureRect.set(previewRect);
        if (target > cur + 0.01f) { float nw = ph / target; captureRect.inset((pw - nw) / 2, 0); }
        else if (target < cur - 0.01f) { float nh = pw * target; captureRect.inset(0, (ph - nh) / 2); }
        hud.view.set(captureRect);
        hud.invalidate();
        tv.setPivotX(pw / 2f); tv.setPivotY(ph / 2f);
        applyLivePreviewStyle();
    }

    private void applyLivePreviewStyle() {
        PictureStyle ps = PictureStyle.of(st.style);
        if (ps.isIdentity() || !pr.b("liveStyle", true)) { tv.setLayerType(View.LAYER_TYPE_NONE, null); return; }
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(ps.matrix()));
        tv.setLayerType(View.LAYER_TYPE_HARDWARE, p);
    }

    /* ================================================================ refresh texts */

    private double shutterSec() { return st.shutter; }

    private void refresh() {
        flashChip.setText(new String[]{"⚡ OFF", "⚡ A", "⚡ ON", "☀ T"}[st.flash]);
        driveChip.setText(CamState.DRIVE_ICONS[st.drive] + (st.intervalSec > 0 ? " ∞" + st.intervalSec + "s" : ""));
        aspectChip.setText(st.mode == CamState.M_VIDEO ? "16:9" : CamState.ASPECT_NAMES[st.aspect]);
        int fi = 0; for (int i = 0; i < ShutterFx.PHOTO_KEYS.length; i++) if (ShutterFx.PHOTO_KEYS[i].equals(st.fx)) fi = i;
        int vi = 0; for (int i = 0; i < ShutterFx.VIDEO_KEYS.length; i++) if (ShutterFx.VIDEO_KEYS[i].equals(st.vfx)) vi = i;
        fxChip.setText("✦ " + (st.mode == CamState.M_VIDEO ? ShutterFx.VIDEO_NAMES[vi] : ShutterFx.PHOTO_NAMES[fi]));
        for (int i = 0; i < dialChips.size(); i++) chipSel(dialChips.get(i), st.dial == i);
        for (int i = 0; i < paramChips.size(); i++) chipSel(paramChips.get(i), param == i);
        for (TextView z : zoomChips) chipSel(z, Math.abs((Float) z.getTag() - zoom) < 0.05f);
        int d = st.effDial();
        boolean manualTv = d == CamState.D_TV || d == CamState.D_M;
        infoBar.badge = st.mode == CamState.M_PRO ? CamState.DIAL[st.dial] : st.mode == CamState.M_NIGHT ? "☾" : st.mode == CamState.M_PORTRAIT ? "◐" : st.mode == CamState.M_VIDEO ? "▶" : "A+";
        infoBar.tv = manualTv ? Expo.shutter(shutterSec()) : Expo.shutterNs(lastExp);
        infoBar.tvAuto = !manualTv;
        infoBar.av = Expo.aperture(lastAperture);
        boolean isoFixed = st.iso > 0 && st.isPro();
        infoBar.iso = isoFixed ? String.valueOf(st.iso) : "A " + lastIso;
        infoBar.isoAuto = !isoFixed;
        infoBar.tvSel = st.isPro() && param == 0; infoBar.isoSel = st.isPro() && param == 1; infoBar.evSel = st.isPro() && param == 2;
        infoBar.wb = st.wb == CamState.WB_K ? st.kelvin + "K" : CamState.WB_ICONS[st.wb];
        infoBar.style = PictureStyle.of(st.style).name.length() > 4 ? PictureStyle.of(st.style).name.substring(0, 4) : PictureStyle.of(st.style).name;
        infoBar.drive = CamState.DRIVE_ICONS[st.drive];
        infoBar.af = CamState.AF_NAMES[st.af];
        infoBar.met = CamState.MET_ICONS[st.metering];
        infoBar.raw = wantRaw();
        infoBar.flashOn = st.flash != 0;
        infoBar.aebStep = CamState.AEB_STEPS[st.aeb];
        infoBar.battery = battery;
        infoBar.shots = shotsLeft();
        boolean fullManual = d == CamState.D_M && st.iso > 0;
        infoBar.level = fullManual ? -meterErr : st.ev;
        infoBar.levelBlink = fullManual && Math.abs(meterErr) > 3;
        infoBar.extra = (st.aeLock ? "✱" : "") + (st.htp ? " D+" : "") + (st.silent ? " 🔇" : "");
        infoBar.invalidate();
    }

    private int shotsLeft() {
        try {
            StatFs s = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            long per = wantRaw() ? 30_000_000L : 5_000_000L;
            return (int) Math.min(99999, s.getAvailableBytes() / per);
        } catch (Exception e) { return 999; }
    }

    /* ================================================================ PRO dial */

    private List<Double> shutterList() { return info == null ? Expo.shutterList(125_000, 1_000_000_000L) : Expo.shutterList(info.minExp, info.maxExp); }
    private List<Integer> isoList() { return info == null ? Expo.isoList(50, 6400) : Expo.isoList(info.minIso, info.maxIso); }

    private void refreshDial() {
        switch (param) {
            case 0: {
                List<Double> l = shutterList(); String[] s = new String[l.size()];
                for (int i = 0; i < s.length; i++) s[i] = Expo.shutter(l.get(i));
                dial.set("TEMPO DI SCATTO (Tv)", s, Expo.nearestShutter(l, st.shutter), i -> {
                    st.shutter = shutterList().get(i);
                    if (st.dial == CamState.D_P || st.dial == CamState.D_AV || st.dial == CamState.D_AUTO) setDial(CamState.D_TV);
                    push(); refresh();
                });
                break;
            }
            case 1: {
                List<Integer> l = isoList(); String[] s = new String[l.size() + 1]; s[0] = "AUTO";
                for (int i = 0; i < l.size(); i++) s[i + 1] = String.valueOf(l.get(i));
                dial.set("SENSIBILITÀ ISO", s, st.iso == 0 ? 0 : Expo.nearestIso(l, st.iso) + 1, i -> {
                    st.iso = i == 0 ? 0 : isoList().get(i - 1);
                    if (st.iso > 0) { loopExp = lastExp; }
                    push(); refresh();
                });
                break;
            }
            case 2: {
                String[] s = new String[19];
                for (int i = 0; i < 19; i++) s[i] = Expo.ev((i - 9) / 3f);
                dial.set("COMPENSAZIONE ESPOSIZIONE", s, Math.round(st.ev * 3) + 9, i -> { st.ev = (i - 9) / 3f; push(); refresh(); });
                break;
            }
            case 3: {
                String[] s = new String[CamState.WB_NAMES.length - 1 + 76];
                int k = 0;
                for (int i = 0; i < CamState.WB_NAMES.length - 1; i++) s[k++] = CamState.WB_ICONS[i];
                for (int kv = 2500; kv <= 10000; kv += 100) s[k++] = kv + "K";
                int idx = st.wb == CamState.WB_K ? CamState.WB_NAMES.length - 1 + (st.kelvin - 2500) / 100 : st.wb;
                dial.set("BILANCIAMENTO DEL BIANCO", s, idx, i -> {
                    if (i < CamState.WB_NAMES.length - 1) st.wb = i;
                    else { st.wb = CamState.WB_K; st.kelvin = 2500 + (i - (CamState.WB_NAMES.length - 1)) * 100; }
                    push(); refresh();
                });
                break;
            }
            case 4: {
                String[] s = new String[61];
                float min = info == null ? 10 : info.minFocus;
                for (int i = 0; i <= 60; i++) {
                    float dio = min * i / 60f;
                    s[i] = dio <= 0.001f ? "∞" : (1 / dio) >= 1 ? String.format(Locale.US, "%.1fm", 1 / dio) : String.format(Locale.US, "%.0fcm", 100 / dio);
                }
                int idx = min > 0 ? Math.round(st.focus / min * 60) : 0;
                dial.set("MESSA A FUOCO MANUALE (peaking attivo)", s, idx, i -> {
                    st.af = CamState.AF_MF; st.focus = (info == null ? 0 : info.minFocus) * i / 60f; push(); refresh();
                });
                break;
            }
            default: {
                String[] s = {"ONE SHOT", "SERVO", "MF", "VALUTATIVA", "PARZIALE", "SPOT", "MEDIA CENTR."};
                dial.set("AF · MISURAZIONE", s, st.af, i -> {
                    if (i < 3) st.af = i; else st.metering = i - 3;
                    push(); refresh();
                });
            }
        }
    }

    private void setDial(int d) {
        if (d == CamState.D_C1) {
            if (!st.loadC1(pr)) { hud.showToast("C1 VUOTO · TIENI PREMUTO C1 PER REGISTRARE"); }
            else hud.showToast("C1 RICHIAMATO");
        }
        st.dial = d;
        if (d == CamState.D_AUTO) { st.iso = 0; st.ev = 0; st.wb = CamState.WB_AUTO; st.af = CamState.AF_SERVO; }
        if (d == CamState.D_TV || d == CamState.D_M) param = 0;
        else if (d == CamState.D_AV || d == CamState.D_P) param = 2;
        loopIso = lastIso; loopExp = lastExp;
        if (d == CamState.D_AV) hud.showToast(String.format(Locale.US, "Av: OBIETTIVO %s FISSO · PRIORITÀ ISO BASSI", Expo.aperture(lastAperture)));
        refreshDial(); push(); refresh();
    }

    /* ================================================================ controls → engine */

    private void push() {
        if (info == null) return;
        CameraEngine.Controls c = new CameraEngine.Controls();
        int d = st.effDial();
        boolean tvMode = d == CamState.D_TV || d == CamState.D_M;
        boolean isoFixed = st.iso > 0 && d != CamState.D_AUTO;
        c.aeManual = (tvMode || isoFixed) && info.manualSensor;
        if (c.aeManual) {
            c.expNs = tvMode ? (long) (st.shutter * 1e9) : (long) loopExp;
            c.iso = isoFixed ? st.iso : (int) Math.round(loopIso);
        }
        double step = info.evStep.doubleValue();
        c.evIdx = (int) Math.round(st.ev / (step <= 0 ? 1 / 6.0 : step));
        c.aeLock = st.aeLock;
        c.flash = st.flash;
        c.wb = st.wb;
        if (st.wb == CamState.WB_K) {
            RggbChannelVector base = eng.lastAutoGains();
            float[] k = Expo.kelvinRgb(st.kelvin), ref = Expo.kelvinRgb(5200);
            float br = base == null ? 2.0f : base.getRed(), bb = base == null ? 1.8f : base.getBlue();
            float rg = br * (ref[0] / ref[1]) / (k[0] / k[1]);
            float bg = bb * (ref[2] / ref[1]) / (k[2] / k[1]);
            c.wbGains = new RggbChannelVector(rg, 1, 1, bg);
        }
        c.af = st.mode == CamState.M_VIDEO && st.af == CamState.AF_ONESHOT ? CamState.AF_SERVO : st.af;
        c.focus = st.focus;
        c.zoom = zoom;
        c.video = st.mode == CamState.M_VIDEO;
        c.night = st.mode == CamState.M_NIGHT;
        c.stab = pr.b("stab", true);
        c.sharp = !st.style.equals("neutral") && !st.style.equals("faithful");
        c.faces = pr.b("faces", true);
        if (st.afPointSet) {
            MeteringRectangle r = region(st.afX, st.afY, st.metering == CamState.MET_SPOT ? 0.04f : 0.1f);
            if (r != null) {
                c.afRegions = new MeteringRectangle[]{r};
                if (st.metering == CamState.MET_PARTIAL || st.metering == CamState.MET_SPOT || !st.isPro()) c.aeRegions = new MeteringRectangle[]{r};
            }
        } else if (st.metering == CamState.MET_CENTER) {
            MeteringRectangle r = region(0.5f, 0.5f, 0.25f);
            if (r != null) c.aeRegions = new MeteringRectangle[]{r};
        }
        eng.setControls(c);
    }

    /** Normalized point on the capture frame (portrait) → sensor active-array rectangle. */
    private MeteringRectangle region(float x, float y, float half) {
        if (info == null || info.active == null) return null;
        float[] s = toSensor(x, y);
        int w = info.active.width(), h = info.active.height();
        int hw = (int) (w * half), hh = (int) (h * half);
        int cx = (int) (s[0] * w), cy = (int) (s[1] * h);
        int l = Math.max(0, cx - hw), t = Math.max(0, cy - hh);
        int r = Math.min(w - 1, cx + hw), b = Math.min(h - 1, cy + hh);
        return new MeteringRectangle(new Rect(l, t, r, b), MeteringRectangle.METERING_WEIGHT_MAX - 1);
    }
    private float[] toSensor(float x, float y) {
        int so = info.sensorOrientation;
        if (info.front) x = 1 - x;
        switch (so) {
            case 90: return new float[]{y, 1 - x};
            case 270: return new float[]{1 - y, x};
            case 180: return new float[]{1 - x, 1 - y};
            default: return new float[]{x, y};
        }
    }
    private float[] fromSensor(float sx, float sy) {
        float x, y;
        switch (info.sensorOrientation) {
            case 90: x = 1 - sy; y = sx; break;
            case 270: x = sy; y = 1 - sx; break;
            case 180: x = 1 - sx; y = 1 - sy; break;
            default: x = sx; y = sy;
        }
        if (info.front) x = 1 - x;
        return new float[]{x, y};
    }

    /* ================================================================ gestures */

    private float downX, downY; private boolean evDrag;
    private void setupGestures() {
        ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { setZoom(zoom * d.getScaleFactor(), false); return true; }
        });
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) { tapFocus(e.getX(), e.getY()); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (st.af == CamState.AF_MF) { toggleMagnify(); return true; }
                return false;
            }
            @Override public void onLongPress(MotionEvent e) {
                st.aeLock = !st.aeLock; hud.afLocked = st.aeLock;
                if (st.aeLock) tapFocus(e.getX(), e.getY());
                hud.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                hud.showToast(st.aeLock ? "✱ AE/AF LOCK" : "AE/AF SBLOCCATO");
                push(); refresh();
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null) return false;
                if (Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 900 && !evDrag) {
                    int m = st.mode + (vx < 0 ? 1 : -1);
                    if (m >= 0 && m < CamState.MODE_NAMES.length) { carousel.setSelected(m); setMode(m); }
                    return true;
                }
                return false;
            }
        });
        hud.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); evDrag = false; }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !sg.isInProgress() && !Float.isNaN(hud.exposureSlider) && !st.isPro()) {
                float dy = downY - e.getY(), dx = Math.abs(e.getX() - downX);
                if (Math.abs(dy) > dp(16) && Math.abs(dy) > dx) {
                    evDrag = true;
                    float ev = Math.max(-2, Math.min(2, st.ev + dy / dp(160) * 2));
                    downY = e.getY();
                    if (Math.abs(ev - st.ev) > 0.01f) { st.ev = ev; hud.exposureSlider = ev / 2; hud.afShownAt = SystemClock.uptimeMillis(); push(); refresh(); }
                }
            }
            if (!sg.isInProgress() && !evDrag) gd.onTouchEvent(e);
            return true;
        });
    }

    private void tapFocus(float x, float y) {
        if (!captureRect.contains(x, y)) return;
        st.afX = (x - captureRect.left) / captureRect.width();
        st.afY = (y - captureRect.top) / captureRect.height();
        st.afPointSet = true;
        float s = dp(70);
        hud.af.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2);
        hud.afShownAt = SystemClock.uptimeMillis();
        hud.afState = st.af == CamState.AF_MF ? 0 : 1;
        hud.exposureSlider = st.isPro() ? Float.NaN : st.ev / 2;
        push();
        if (st.af != CamState.AF_MF) eng.triggerAf();
        hud.invalidate();
    }

    private void toggleMagnify() {
        magnify = magnify >= 10 ? 1 : magnify == 1 ? 5 : 10;
        float px = captureRect.left + st.afX * captureRect.width() - previewRect.left;
        float py = captureRect.top + st.afY * captureRect.height() - previewRect.top;
        tv.setPivotX(px); tv.setPivotY(py);
        tv.animate().scaleX(magnify).scaleY(magnify).setDuration(180).start();
        hud.mfAssist = (int) magnify;
        hud.invalidate();
    }

    private void setZoom(float z, boolean animateChip) {
        if (info == null) return;
        zoom = Math.max(info.minZoom, Math.min(Math.min(info.maxZoom, 30), z));
        push();
        hud.showToast(String.format(Locale.US, "%.1f×  ·  %dmm", zoom, Math.round(info.equiv35(lastFocal) * (info.zoomRatioApi ? 1 : zoom))));
        refresh();
    }

    private void buildZoomChips() {
        zoomRow.removeAllViews(); zoomChips.clear();
        if (info == null) return;
        float[] stops = {0.5f, 0.6f, 1f, 2f, 5f, 10f};
        for (float z : stops) {
            if (z < info.minZoom - 0.01f || z > info.maxZoom + 0.01f) continue;
            if (z == 0.6f && info.minZoom > 0.55f) continue;
            if (z == 0.5f && info.minZoom > 0.5f + 0.01f && info.minZoom < 0.7f) continue;
            String label = (z == Math.floor(z) ? String.valueOf((int) z) : String.valueOf(z)).replace(".0", "") + "×";
            TextView c = chip(label, v -> setZoom((Float) v.getTag(), true));
            c.setTag(z);
            zoomChips.add(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(34)); lp.leftMargin = dp(4); lp.rightMargin = dp(4);
            zoomRow.addView(c, lp);
        }
        if (info.minZoom < 0.99f && zoomChips.isEmpty()) {
            TextView c = chip(String.format(Locale.US, "%.1f×", info.minZoom), v -> setZoom(info.minZoom, true)); c.setTag(info.minZoom); zoomChips.add(c); zoomRow.addView(c);
        }
        refresh();
    }

    /* ================================================================ modes */

    private void setMode(int m) {
        if (eng.isRecording()) { carousel.setSelected(st.mode); return; }
        int oldKind = sessionKind();
        float oldAspect = aspect();
        boolean oldRaw = wantRaw();
        st.mode = m;
        st.save(pr);
        if (m == CamState.M_PORTRAIT && zoom < 1.5f && info != null && info.maxZoom >= 2) zoom = 2f;
        hud.showToast(CamState.MODE_NAMES[m] + (m == CamState.M_PRO ? " · " + CamState.DIAL[st.dial] : m == CamState.M_NIGHT ? " · STACKING " + st.nightFrames + " FOTO" : ""));
        tv.animate().alpha(0.25f).setDuration(90).withEndAction(() -> tv.animate().alpha(1f).setDuration(260).start()).start();
        if (oldKind != sessionKind() || oldAspect != aspect() || oldRaw != wantRaw()) eng.reconfigure(sessionKind(), aspect(), wantRaw());
        refreshDial();
        updateModeUi();
        layoutPreview();
        push();
    }

    private void reconfigure() {
        st.save(pr);
        eng.reconfigure(sessionKind(), aspect(), wantRaw());
        layoutPreview();
        refresh();
    }

    private void switchCamera() {
        if (eng.isRecording()) return;
        st.front = !st.front; zoom = 1; st.afPointSet = false;
        st.save(pr);
        tv.animate().rotationY(90).setDuration(140).withEndAction(() -> {
            opened = false; maybeOpen();
            tv.setRotationY(-90); tv.animate().rotationY(0).setDuration(180).start();
        }).start();
    }

    /* ================================================================ pickers / Q menu */

    interface IntCb { void on(int i); }
    private void pick(String title, String[] items, int sel, IntCb cb) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(items, sel, (d, i) -> { cb.on(i); st.save(pr); push(); refresh(); d.dismiss(); })
            .setNegativeButton("CHIUDI", null).show();
    }

    private void pickFx() {
        if (st.mode == CamState.M_VIDEO) {
            String[] it = new String[ShutterFx.VIDEO_KEYS.length];
            for (int i = 0; i < it.length; i++) it[i] = ShutterFx.VIDEO_NAMES[i] + " — " + ShutterFx.VIDEO_DESC[i];
            int sel = 0; for (int i = 0; i < it.length; i++) if (ShutterFx.VIDEO_KEYS[i].equals(st.vfx)) sel = i;
            pick("EFFETTO VIDEO", it, sel, i -> st.vfx = ShutterFx.VIDEO_KEYS[i]);
        } else {
            String[] it = new String[ShutterFx.PHOTO_KEYS.length];
            for (int i = 0; i < it.length; i++) it[i] = ShutterFx.PHOTO_NAMES[i] + " — " + ShutterFx.PHOTO_DESC[i];
            int sel = 0; for (int i = 0; i < it.length; i++) if (ShutterFx.PHOTO_KEYS[i].equals(st.fx)) sel = i;
            pick("EFFETTO SCATTO", it, sel, i -> { st.fx = ShutterFx.PHOTO_KEYS[i]; previewFx(); });
        }
    }

    /** Plays the selected effect on the live view so the user sees it before shooting. */
    private void previewFx() {
        ui.postDelayed(() -> {
            Bitmap f = frozenFrame();
            if (f != null) fx.play(st.fx, f, captureRect, thumb.screenRect(), "ANTEPRIMA\n" + ShutterFx.PHOTO_NAMES[0], null);
        }, 250);
    }

    private void openQ() {
        String[] tiles = {
            "DRIVE: " + CamState.DRIVE_NAMES[st.drive],
            "AF: " + CamState.AF_NAMES[st.af],
            "MISURAZIONE: " + CamState.MET_NAMES[st.metering],
            "BIL. BIANCO: " + (st.wb == CamState.WB_K ? st.kelvin + "K" : CamState.WB_NAMES[st.wb]),
            "PICTURE STYLE: " + PictureStyle.of(st.style).name,
            "QUALITÀ: " + (st.raw ? "RAW + JPEG L" : "JPEG L") + " · Q" + st.jpegQ,
            "FORMATO: " + CamState.ASPECT_NAMES[st.aspect],
            "BRACKETING AEB: " + CamState.AEB_NAMES[st.aeb],
            "ISO AUTO MAX: " + st.isoMax,
            "FLASH: " + CamState.FLASH_NAMES[st.flash],
            "PRIORITÀ TONI CHIARI (D+): " + (st.htp ? "ON" : "OFF"),
            "INTERVALLOMETRO: " + (st.intervalSec == 0 ? "OFF" : st.intervalSec + "s × " + st.intervalShots),
            "OTTURATORE SILENZIOSO: " + (st.silent ? "ON" : "OFF"),
            "SFOCATURA RITRATTO: " + st.portraitBlur + "%",
            "FOTO NOTTE (STACK): " + st.nightFrames,
            "EFFETTO SCATTO / VIDEO",
            "GRIGLIA: " + HudView.GRIDS[hud.grid],
            "LIVELLA ELETTRONICA: " + (hud.level ? "ON" : "OFF"),
            "ISTOGRAMMA: " + (hud.histogram ? "ON" : "OFF"),
            "FOCUS PEAKING: " + (pr.b("peaking", false) ? "ON" : "OFF"),
            "ZEBRA: " + (pr.b("zebra", false) ? "ON" : "OFF"),
            "REGISTRA SU C1",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Q · CONTROLLO RAPIDO")
            .setItems(tiles, (d, i) -> qAction(i))
            .setNegativeButton("CHIUDI", null).show();
    }

    private void qAction(int i) {
        switch (i) {
            case 0: pick("DRIVE", CamState.DRIVE_NAMES, st.drive, k -> st.drive = k); break;
            case 1: pick("MODALITÀ AF", new String[]{"ONE SHOT (blocca al tocco)", "SERVO (continuo, soggetti in movimento)", "MF (manuale con peaking)"}, st.af, k -> { st.af = k; if (k == CamState.AF_MF) pr.put("peaking", true); }); break;
            case 2: pick("MISURAZIONE", CamState.MET_NAMES, st.metering, k -> st.metering = k); break;
            case 3: {
                String[] w = new String[CamState.WB_NAMES.length];
                for (int k = 0; k < w.length; k++) w[k] = CamState.WB_ICONS[k] + "  " + CamState.WB_NAMES[k] + (k == CamState.WB_K ? " (" + st.kelvin + "K, regola con la ghiera in PRO)" : "");
                pick("BILANCIAMENTO DEL BIANCO", w, st.wb, k -> st.wb = k); break;
            }
            case 4: {
                String[] s = new String[PictureStyle.ALL.length];
                for (int k = 0; k < s.length; k++) s[k] = PictureStyle.ALL[k].name + " — " + PictureStyle.ALL[k].desc;
                pick("PICTURE STYLE", s, PictureStyle.index(st.style), k -> { st.style = PictureStyle.ALL[k].key; applyLivePreviewStyle(); }); break;
            }
            case 5: pick("QUALITÀ IMMAGINE", new String[]{"JPEG L · Q95 (fine)", "JPEG L · Q85 (normale)", "RAW (DNG) + JPEG L · Q95"}, st.raw ? 2 : st.jpegQ >= 95 ? 0 : 1, k -> {
                boolean old = st.raw; st.raw = k == 2; st.jpegQ = k == 1 ? 85 : 95;
                if (st.raw && info != null && !info.raw) { st.raw = false; hud.showToast("RAW NON SUPPORTATO"); }
                if (old != st.raw) reconfigure();
            }); break;
            case 6: pick("FORMATO", CamState.ASPECT_NAMES, st.aspect, k -> { st.aspect = k; reconfigure(); }); break;
            case 7: pick("BRACKETING AEB (3 SCATTI 0 / − / +)", CamState.AEB_NAMES, st.aeb, k -> st.aeb = k); break;
            case 8: {
                String[] s = {"800", "1600", "3200", "6400", "12800"}; int[] v = {800, 1600, 3200, 6400, 12800};
                int sel = 3; for (int k = 0; k < v.length; k++) if (v[k] == st.isoMax) sel = k;
                pick("LIMITE ISO AUTO", s, sel, k -> st.isoMax = v[k]); break;
            }
            case 9: pick("FLASH", CamState.FLASH_NAMES, st.flash, k -> st.flash = k); break;
            case 10: st.htp = !st.htp; if (st.htp && st.iso > 0 && st.iso < 200) st.iso = 200; hud.showToast(st.htp ? "D+ PRIORITÀ TONI CHIARI: ISO ≥ 200, −⅓ EV" : "D+ OFF"); push(); refresh(); break;
            case 11: pick("INTERVALLOMETRO", new String[]{"OFF", "ogni 2s × 10", "ogni 5s × 20", "ogni 10s × 30", "ogni 30s × 60", "ogni 60s × 120"}, 0, k -> {
                int[] sec = {0, 2, 5, 10, 30, 60}, n = {0, 10, 20, 30, 60, 120}; st.intervalSec = sec[k]; st.intervalShots = n[k];
            }); break;
            case 12: st.silent = !st.silent; refresh(); st.save(pr); break;
            case 13: pick("SFOCATURA RITRATTO", new String[]{"20%", "40%", "60%", "80%", "100%"}, Math.max(0, st.portraitBlur / 20 - 1), k -> st.portraitBlur = (k + 1) * 20); break;
            case 14: pick("FOTOGRAMMI NOTTE", new String[]{"4 (veloce)", "6", "8", "12", "16 (treppiede)"}, 2, k -> st.nightFrames = new int[]{4, 6, 8, 12, 16}[k]); break;
            case 15: pickFx(); break;
            case 16: pick("GRIGLIA", HudView.GRIDS, hud.grid, k -> { hud.grid = k; pr.put("grid", k); hud.invalidate(); }); break;
            case 17: hud.level = !hud.level; pr.put("level", hud.level); hud.invalidate(); break;
            case 18: hud.histogram = !hud.histogram; pr.put("histogram", hud.histogram); hud.invalidate(); break;
            case 19: pr.put("peaking", !pr.b("peaking", false)); break;
            case 20: pr.put("zebra", !pr.b("zebra", false)); break;
            default: st.saveC1(pr); hud.showToast("REGISTRATO SU C1");
        }
    }

    /* ================================================================ shutter */

    private void onHalfPress() {
        if (st.mode == CamState.M_VIDEO) return;
        if (st.af == CamState.AF_ONESHOT && !st.aeLock) eng.triggerAf();
    }

    private void onShutter() {
        if (fx.busy() && st.mode != CamState.M_VIDEO) fx.finishNow();
        if (st.mode == CamState.M_VIDEO) { toggleVideo(); return; }
        if (!hud.countdown.isEmpty() || intervalLeft > 0) { cancelTimers(); return; }
        if (st.intervalSec > 0) { intervalLeft = st.intervalShots; intervalTick(); return; }
        switch (st.drive) {
            case CamState.DR_T2: countdown(2, 1); break;
            case CamState.DR_T10: countdown(10, 1); break;
            case CamState.DR_T10C: countdown(10, 3); break;
            case CamState.DR_H: case CamState.DR_L: burstHeld = true; burstCount = 0; burstLoop(); break;
            default: takePicture(true);
        }
    }

    private void onShutterRelease() { burstHeld = false; }

    private void burstLoop() {
        if (!burstHeld && burstCount > 0) return;
        takePicture(burstCount == 0);
        burstCount++;
        if (burstCount >= 99) { burstHeld = false; return; }
        ui.postDelayed(() -> { if (burstHeld) burstLoop(); }, st.drive == CamState.DR_H ? 110 : 330);
    }

    private final Runnable countTick = new Runnable() { @Override public void run() { } };
    private int countLeft, countShots;
    private void countdown(int sec, int shots) {
        countLeft = sec; countShots = shots;
        stepCountdown();
    }
    private void stepCountdown() {
        if (countLeft <= 0) {
            hud.countdown = "";
            for (int i = 0; i < countShots; i++) ui.postDelayed(() -> takePicture(true), i * 450L);
            return;
        }
        hud.countdown = String.valueOf(countLeft);
        hud.invalidate();
        if (!st.silent && pr.sound()) sound.play(MediaActionSound.FOCUS_COMPLETE);
        countLeft--;
        ui.postDelayed(this::stepCountdown, 1000);
    }
    private void cancelTimers() {
        ui.removeCallbacksAndMessages(null);
        hud.countdown = ""; intervalLeft = 0; burstHeld = false;
        hud.showToast("ANNULLATO");
        startAnalysis();
    }
    private void intervalTick() {
        if (intervalLeft <= 0) { hud.showToast("INTERVALLOMETRO COMPLETATO"); return; }
        takePicture(true);
        intervalLeft--;
        hud.showToast("INTERVALLO · RESTANO " + intervalLeft);
        ui.postDelayed(this::intervalTick, st.intervalSec * 1000L);
    }

    private int jpegOrientation() {
        if (info == null) return 0;
        int dev = deviceDeg;
        if (info.front) return (info.sensorOrientation - dev + 360) % 360;
        return (info.sensorOrientation + dev) % 360;
    }

    private Bitmap frozenFrame() {
        try {
            int w = Math.max(1, tv.getWidth() / 2), h = Math.max(1, tv.getHeight() / 2);
            Bitmap b = tv.getBitmap(w, h);
            if (b == null) return null;
            float sx = w / previewRect.width(), sy = h / previewRect.height();
            int x = (int) ((captureRect.left - previewRect.left) * sx), y = (int) ((captureRect.top - previewRect.top) * sy);
            int cw = Math.min(w - x, (int) (captureRect.width() * sx)), ch = Math.min(h - y, (int) (captureRect.height() * sy));
            if (cw <= 0 || ch <= 0) return b;
            Bitmap c = Bitmap.createBitmap(b, Math.max(0, x), Math.max(0, y), cw, ch);
            PictureStyle ps = PictureStyle.of(st.style);
            if (!ps.isIdentity()) c = ImageSaver.style(c, ps);
            return c;
        } catch (Exception e) { return null; }
    }

    private String exposureCaption(String name) {
        int d = st.effDial();
        String tvS = d == CamState.D_TV || d == CamState.D_M ? Expo.shutter(st.shutter) : Expo.shutterNs(lastExp);
        String isoS = st.iso > 0 && st.isPro() ? String.valueOf(st.iso) : String.valueOf(lastIso);
        int mm = info == null ? 24 : Math.round(info.equiv35(lastFocal) * (info.zoomRatioApi ? zoom : 1));
        return name + "\n" + tvS + "  " + Expo.aperture(lastAperture) + "  ISO " + isoS + "\n" + mm + "mm · " + PictureStyle.of(st.style).name + " · " + CamState.DIAL[d];
    }

    private void takePicture(boolean withFx) {
        if (info == null) return;
        CameraEngine.Shot shot = new CameraEngine.Shot();
        shot.orientation = jpegOrientation();
        shot.jpegQ = st.jpegQ;
        shot.raw = wantRaw() && info.raw;
        int d = st.effDial();
        boolean tvMode = d == CamState.D_TV || d == CamState.D_M;
        long baseExp = tvMode ? (long) (st.shutter * 1e9) : lastExp;
        int baseIso = st.iso > 0 && st.isPro() ? st.iso : lastIso;
        if (st.htp && baseIso < 200) baseIso = 200;
        if (st.mode == CamState.M_NIGHT) {
            shot.yuv = true;
            long e = Math.min(Math.max(baseExp * 2, 33_000_000L), 250_000_000L);
            int iso = (int) Math.max(info.minIso, Math.min(info.maxIso, baseIso * (double) baseExp / e * 1.4));
            for (int i = 0; i < st.nightFrames; i++) shot.frames.add(new long[]{e, iso});
            stacker = new ImageSaver.NightStacker();
            hud.nightProgress = 0; hud.nightText = "0 / " + st.nightFrames;
            shutter.setState(Widgets.Shutter.BUSY);
        } else if (st.aeb > 0 && info.manualSensor) {
            float s = CamState.AEB_STEPS[st.aeb];
            for (float off : new float[]{0, -s, s}) {
                double e = baseExp * Math.pow(2, off);
                double iso = baseIso;
                if (e > info.maxExp) { iso *= e / info.maxExp; e = info.maxExp; }
                if (e < info.minExp) { iso *= e / info.minExp; e = info.minExp; }
                shot.frames.add(new long[]{(long) e, (long) iso});
            }
        } else if (st.htp && !tvMode && info.manualSensor && st.iso == 0) {
            // highlight tone priority: one third of a stop under, shadows lifted later by the style curve
            shot.frames.add(new long[]{(long) (baseExp * 0.79), Math.max(200, baseIso)});
        } else {
            shot.frames.add(new long[]{0, 0});
        }
        ImageSaver.Opts o = new ImageSaver.Opts();
        o.style = st.style; o.aspect = CamState.ASPECT[st.aspect]; o.quality = st.jpegQ; o.dial = CamState.DIAL[d];
        o.portrait = st.mode == CamState.M_PORTRAIT; o.blur = st.portraitBlur;
        o.mirror = info.front && pr.b("mirrorFront", true);
        if (o.portrait) o.subject = mainFace();
        shot.meta.put("opts", o);
        if (withFx) {
            Bitmap f = frozenFrame();
            String fxKey = st.mode == CamState.M_NIGHT ? "holo" : st.fx;
            if (f != null) fx.play(fxKey, f, captureRect, thumb.screenRect(), exposureCaption(ImageSaver.name(pr.i("fileNo", 0) + 1, "JPG")), null);
        } else {
            fx.play("classic", null, captureRect, thumb.screenRect(), "", null);
        }
        if (!st.silent && pr.sound()) sound.play(MediaActionSound.SHUTTER_CLICK);
        eng.capture(shot);
    }

    private RectF mainFace() {
        if (hud.faces.isEmpty()) return null;
        RectF best = null;
        for (RectF f : hud.faces) if (best == null || f.width() * f.height() > best.width() * best.height()) best = f;
        return new RectF((best.left - captureRect.left) / captureRect.width(), (best.top - captureRect.top) / captureRect.height(),
            (best.right - captureRect.left) / captureRect.width(), (best.bottom - captureRect.top) / captureRect.height());
    }

    /* ================================================================ video */

    private void toggleVideo() {
        if (!eng.isRecording() && videoOut == null) {
            try {
                int no = pr.nextFileNumber();
                videoOut = ImageSaver.newVideo(this, no);
                CameraEngine.VideoSpec v = new CameraEngine.VideoSpec();
                List<Size> sizes = CameraEngine.videoSizes(info);
                String res = pr.s("videoRes", "1080");
                Size pick = null;
                for (Size s : sizes) { if (res.equals("2160") && s.getHeight() == 2160) pick = s; if (res.equals("1080") && s.getHeight() == 1080) pick = s; if (res.equals("720") && s.getHeight() == 720) pick = s; }
                if (pick == null && !sizes.isEmpty()) pick = sizes.get(sizes.size() > 1 ? 1 : 0);
                if (pick != null) v.size = pick;
                v.fps = pr.i("videoFps", 30);
                v.hevc = pr.b("hevc", false);
                v.bitrate = (int) ((v.size.getHeight() >= 2160 ? 50_000_000 : v.size.getHeight() >= 1080 ? 20_000_000 : 10_000_000) * (v.fps >= 60 ? 1.5 : 1));
                v.audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                v.orientation = jpegOrientation();
                v.pfd = (ParcelFileDescriptor) videoOut[1];
                Bitmap f = frozenFrame();
                if (!"rec_none".equals(st.vfx)) fx.play(st.vfx, f, captureRect, thumb.screenRect(), "● REC  " + v.size.getHeight() + "p" + v.fps, null);
                if (!st.silent && pr.sound()) sound.play(MediaActionSound.START_VIDEO_RECORDING);
                shutter.setState(Widgets.Shutter.BUSY);
                eng.startRecording(v);
            } catch (Exception e) {
                hud.showToast("VIDEO: " + e.getMessage());
                videoOut = null;
            }
        } else if (eng.isRecording()) {
            Bitmap f = frozenFrame();
            if (!"rec_none".equals(st.vfx)) fx.play("rec_stop", f, captureRect, thumb.screenRect(), "", null);
            if (!st.silent && pr.sound()) sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
            eng.stopRecording();
        }
    }

    /* ================================================================ engine callbacks */

    @Override public void onReady(CameraEngine.Info i) {
        ui.post(() -> {
            boolean first = info == null || !info.id.equals(i.id);
            info = i;
            previewSize = eng.previewSize();
            if (first) { zoom = Math.max(i.minZoom, Math.min(i.maxZoom, st.mode == CamState.M_PORTRAIT && i.maxZoom >= 2 ? 2 : 1)); buildZoomChips(); refreshDial(); }
            if (st.raw && !i.raw) st.raw = false;
            layoutPreview();
            push();
            refresh();
        });
    }

    @Override public void onError(String msg) { ui.post(() -> hud.showToast("⚠ " + msg)); }

    @Override public void onFrameResult(long expNs, int iso, float focusDist, int afState, int aeState, Face[] faces, float aperture, float focal) {
        ui.post(() -> {
            boolean changed = expNs > 0 && (Math.abs(expNs - lastExp) > lastExp * 0.05 || iso != lastIso);
            if (expNs > 0) lastExp = expNs;
            if (iso > 0) lastIso = iso;
            lastAperture = aperture; lastFocal = focal;
            int ns = hud.afState;
            if (afState == 3 /*ACTIVE_SCAN*/ || afState == 1 /*PASSIVE_SCAN*/) ns = 1;
            else if (afState == 4 /*FOCUSED_LOCKED*/ || afState == 2 /*PASSIVE_FOCUSED*/) ns = 2;
            else if (afState == 5 /*NOT_FOCUSED_LOCKED*/ || afState == 6) ns = 3;
            if (ns != hud.afState) {
                if (ns == 2 && hud.afState == 1 && st.af == CamState.AF_ONESHOT && !st.silent && pr.sound()) sound.play(MediaActionSound.FOCUS_COMPLETE);
                hud.afState = ns; hud.invalidate();
            }
            hud.faces.clear();
            if (faces != null && info != null && info.active != null) {
                for (Face f : faces) {
                    Rect b = f.getBounds();
                    float[] p1 = fromSensor(b.left / (float) info.active.width(), b.top / (float) info.active.height());
                    float[] p2 = fromSensor(b.right / (float) info.active.width(), b.bottom / (float) info.active.height());
                    hud.faces.add(new RectF(captureRect.left + Math.min(p1[0], p2[0]) * captureRect.width(), captureRect.top + Math.min(p1[1], p2[1]) * captureRect.height(),
                        captureRect.left + Math.max(p1[0], p2[0]) * captureRect.width(), captureRect.top + Math.max(p1[1], p2[1]) * captureRect.height()));
                }
            }
            if (changed) refresh();
        });
    }

    @Override public void onShutter(CameraEngine.Shot shot, int index) { }

    private int fileNoFor(CameraEngine.Shot shot, int index) {
        synchronized (shot.meta) {
            Object o = shot.meta.get("no" + index);
            if (o != null) return (Integer) o;
            int n = pr.nextFileNumber();
            shot.meta.put("no" + index, n);
            return n;
        }
    }

    @Override public void onJpeg(CameraEngine.Shot shot, int index, byte[] jpeg) {
        int no = fileNoFor(shot, index);
        ImageSaver.Opts o = (ImageSaver.Opts) shot.meta.get("opts");
        saver.execute(() -> {
            try {
                ImageSaver.Result r = ImageSaver.saveJpeg(this, jpeg, o, no);
                ui.post(() -> delivered(r.uri, r.thumb, false));
            } catch (Exception e) { ui.post(() -> hud.showToast("ERRORE SALVATAGGIO: " + e.getMessage())); }
        });
    }

    @Override public void onRaw(CameraEngine.Shot shot, int index, Image raw, TotalCaptureResult result, CameraCharacteristics cc) {
        int no = fileNoFor(shot, index);
        try { ImageSaver.saveDng(this, raw, result, cc, shot.orientation, no); }
        catch (Exception e) { ui.post(() -> hud.showToast("DNG: " + e.getMessage())); }
        finally { raw.close(); }
    }

    @Override public void onYuvFrame(CameraEngine.Shot shot, int index, Image yuv) {
        ImageSaver.NightStacker s = stacker;
        if (s == null) return;
        s.add(yuv);
        int done = s.count(), total = shot.frames.size();
        ui.post(() -> { hud.nightProgress = done / (float) total; hud.nightText = done + " / " + total; shutter.setProgress(hud.nightProgress); });
        if (done == total) {
            stacker = null;
            ImageSaver.Opts o = (ImageSaver.Opts) shot.meta.get("opts");
            int no = fileNoFor(shot, 0);
            saver.execute(() -> {
                try {
                    byte[] j = ImageSaver.withOrientation(this, s.jpeg(96), shot.orientation);
                    ImageSaver.Result r = ImageSaver.saveJpeg(this, j, o, no);
                    ui.post(() -> { endNight(); delivered(r.uri, r.thumb, false); });
                } catch (Exception e) { ui.post(() -> { endNight(); hud.showToast("NOTTE: " + e.getMessage()); }); }
            });
        }
    }

    private void endNight() { hud.nightProgress = -1; shutter.setProgress(-1); shutter.setState(Widgets.Shutter.PHOTO); }

    @Override public void onShotFailed(CameraEngine.Shot shot, String why) {
        ui.post(() -> { if (shot.yuv) { stacker = null; endNight(); } hud.showToast("⚠ " + why); });
    }

    @Override public void onRecording(boolean started) {
        ui.post(() -> {
            if (started) {
                hud.recording = true; hud.recStart = SystemClock.uptimeMillis();
                shutter.setState(Widgets.Shutter.REC);
            } else {
                hud.recording = false;
                shutter.setState(st.mode == CamState.M_VIDEO ? Widgets.Shutter.VIDEO : Widgets.Shutter.PHOTO);
                if (videoOut != null) {
                    Object[] out = videoOut; videoOut = null;
                    Uri u = (Uri) out[0];
                    saver.execute(() -> {
                        ImageSaver.finishVideo(this, u, (ParcelFileDescriptor) out[1], true);
                        Bitmap b = ImageSaver.videoThumb(this, u);
                        ui.post(() -> delivered(u, b, true));
                    });
                }
            }
            hud.invalidate();
        });
    }

    private void delivered(Uri u, Bitmap thumbBmp, boolean video) {
        lastUri = u; lastIsVideo = video;
        if (thumbBmp != null) thumb.setBitmap(thumbBmp);
        thumb.pulse();
        refresh();
    }

    private void openLast() {
        if (lastUri == null) { hud.showToast("NESSUNA FOTO IN QUESTA SESSIONE"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, lastIsVideo ? "video/mp4" : "image/jpeg");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { hud.showToast("NESSUNA GALLERIA"); }
    }

    /* ================================================================ live analysis (histogram, peaking, zebra, metering loop) */

    private void startAnalysis() {
        if (analysing) return;
        analysing = true;
        ui.postDelayed(analysisTick, 300);
    }

    private boolean analysisBusy;
    private final Runnable analysisTick = new Runnable() {
        @Override public void run() {
            if (!analysing) return;
            ui.postDelayed(this, 180);
            if (analysisBusy || !tv.isAvailable() || previewRect.isEmpty() || fx.busy()) return;
            int w = 200, h = Math.max(1, Math.round(200 * previewRect.height() / previewRect.width()));
            Bitmap b;
            try { b = tv.getBitmap(w, h); } catch (Exception e) { return; }
            if (b == null) return;
            analysisBusy = true;
            boolean peaking = pr.b("peaking", false) || st.af == CamState.AF_MF;
            boolean zebra = pr.b("zebra", false);
            int peakColor = pr.i("peakColor", 0xffff2bd6);
            int zl = pr.i("zebraLevel", 245), pl = pr.i("peakLevel", 60);
            int met = st.metering; float ax = st.afPointSet ? st.afX : 0.5f, ay = st.afPointSet ? st.afY : 0.5f;
            analyzer.execute(() -> {
                Analyzer.Out o = an.run(b, met, ax, ay, zebra, zl, peaking, peakColor, pl);
                ui.post(() -> { analysisBusy = false; onAnalysis(o); });
            });
        }
    };

    private void onAnalysis(Analyzer.Out o) {
        hud.setAnalysis(o);
        if (info == null || st.aeLock) return;
        float err = Analyzer.errorStops(o.meanLuma, st.ev - (st.htp ? 1 / 3f : 0));
        meterErr = err;
        int d = st.effDial();
        boolean tvMode = d == CamState.D_TV || d == CamState.D_M;
        boolean isoFixed = st.iso > 0 && d != CamState.D_AUTO;
        float k = Math.max(-1.5f, Math.min(1.5f, err)) * 0.55f;
        boolean changed = false;
        if (tvMode && !isoFixed) {
            double ni = Math.max(info.minIso, Math.min(Math.min(info.maxIso, st.isoMax), loopIso * Math.pow(2, k)));
            if (Math.abs(ni - loopIso) > loopIso * 0.04) { loopIso = ni; changed = true; }
        } else if (!tvMode && isoFixed) {
            double maxE = Math.min(info.maxExp, d == CamState.D_AV ? 1_000_000_000L : 125_000_000L);
            double ne = Math.max(info.minExp, Math.min(maxE, loopExp * Math.pow(2, k)));
            if (Math.abs(ne - loopExp) > loopExp * 0.04) { loopExp = ne; changed = true; }
        }
        if (changed) push();
        if (d == CamState.D_M && isoFixed) refresh();
    }

    /* ================================================================ sensors & keys */

    @Override public void onSensorChanged(SensorEvent e) {
        float[] rm = new float[9], o = new float[3];
        SensorManager.getRotationMatrixFromVector(rm, e.values);
        SensorManager.getOrientation(rm, o);
        float pitch = (float) Math.toDegrees(o[1]), roll = (float) Math.toDegrees(o[2]);
        // phone held upright: roll of the horizon = rotation around the viewing axis
        float g0 = rm[6], g1 = rm[7];
        float horizon = (float) Math.toDegrees(Math.atan2(g0, g1));
        if (deviceDeg == 90) horizon -= 90; else if (deviceDeg == 270) horizon += 90; else if (deviceDeg == 180) horizon += 180;
        while (horizon > 180) horizon -= 360; while (horizon < -180) horizon += 360;
        hud.roll = horizon * 0.3f + hud.roll * 0.7f;
        hud.pitch = (float) (Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, rm[8])))) * 0.3f + hud.pitch * 0.7f);
        if (hud.level) hud.invalidate();
        if (pitch == roll) hud.invalidate();
    }
    @Override public void onAccuracyChanged(Sensor s, int a) { }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_CAMERA) && pr.b("volShutter", true)) {
            if (e.getRepeatCount() == 0) { onHalfPress(); onShutter(); }
            return true;
        }
        return super.onKeyDown(code, e);
    }
    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_CAMERA) && pr.b("volShutter", true)) { onShutterRelease(); return true; }
        return super.onKeyUp(code, e);
    }
}
