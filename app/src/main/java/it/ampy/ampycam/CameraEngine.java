package it.ampy.ampycam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Camera2 wrapper: preview, stills (JPEG + RAW/DNG), AEB / bursts, night YUV stacking frames, video. */
public class CameraEngine {
    static final String TAG = "AmpyCam";

    public interface Callback {
        void onReady(Info info);
        void onError(String msg);
        void onFrameResult(long expNs, int iso, float focusDist, int afState, int aeState, Face[] faces, float aperture, float focal);
        void onShutter(Shot shot, int index);
        void onJpeg(Shot shot, int index, byte[] jpeg);
        void onRaw(Shot shot, int index, Image raw, TotalCaptureResult result, CameraCharacteristics cc);
        void onYuvFrame(Shot shot, int index, Image yuv);
        void onShotFailed(Shot shot, String why);
        void onRecording(boolean started);
    }

    /** Static capabilities of the open camera. */
    public static final class Info {
        public String id; public boolean front; public CameraCharacteristics cc;
        public float minZoom = 1, maxZoom = 1;
        public long minExp = 100000, maxExp = 1000000000L;
        public int minIso = 50, maxIso = 3200;
        public float minFocus = 0; public boolean manualSensor, raw, flash, faceDetect, zoomRatioApi;
        public int evMin = -12, evMax = 12; public Rational evStep = new Rational(1, 6);
        public float[] apertures = {1.85f}, focals = {6.8f};
        public SizeF sensorMm; public Rect active; public int sensorOrientation = 90;
        public Size jpegMax, rawSize, yuvMax; public Size[] previewSizes, videoSizes;
        public int hwLevel;
        public float equiv35(float focal) { return sensorMm == null ? focal * 7 : (float) (focal * 43.27 / Math.hypot(sensorMm.getWidth(), sensorMm.getHeight())); }
    }

    /** Live controls, copied into every request. Built on the UI thread. */
    public static final class Controls {
        public boolean aeManual; public long expNs = 8000000; public int iso = 100; public int evIdx; public boolean aeLock;
        public int flash; public int wb; public RggbChannelVector wbGains;
        public int af = CamState.AF_SERVO; public float focus; public MeteringRectangle[] afRegions, aeRegions;
        public float zoom = 1; public boolean faces = true; public boolean video; public boolean stab = true; public boolean sharp = true;
        public boolean night;
    }

    /** A photo request: one or more frames. */
    public static final class Shot {
        public final long id = System.nanoTime();
        public final List<long[]> frames = new ArrayList<>(); // per frame: {expNs, iso} or {0, evOffsetIdx} for auto
        public boolean manual, raw, yuv, precapture; public int orientation, jpegQ = 95; public float evOffsetStops;
        public final Map<String, Object> meta = new HashMap<>();
        public int received;
    }

    private final Context ctx;
    private final Callback cb;
    private final CameraManager cm;
    private HandlerThread thread;
    private Handler h;
    private CameraDevice dev;
    private CameraCaptureSession session;
    private CaptureRequest.Builder preview;
    private ImageReader jpegReader, rawReader, yuvReader;
    private SurfaceTexture texture;
    private Surface previewSurface;
    private Size previewSize;
    private Info info;
    private volatile Controls ctl = new Controls();
    private TotalCaptureResult last;
    private ColorSpaceTransform lastCst;
    private volatile RggbChannelVector lastAutoGains;
    private final ArrayDeque<Object[]> pendingJpeg = new ArrayDeque<>(), pendingRaw = new ArrayDeque<>(), pendingYuv = new ArrayDeque<>();
    private final Map<Long, TotalCaptureResult> results = Collections.synchronizedMap(new HashMap<>());
    private MediaRecorder recorder;
    private boolean recording;
    private int sessionKind; // 0 photo, 1 video preview, 2 recording, 3 night
    private boolean wantRaw;

    public CameraEngine(Context c, Callback cb) {
        ctx = c.getApplicationContext(); this.cb = cb;
        cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    public Info info() { return info; }
    public Size previewSize() { return previewSize; }
    public TotalCaptureResult lastResult() { return last; }
    public boolean isRecording() { return recording; }
    public RggbChannelVector lastAutoGains() { return lastAutoGains; }

    /* ------------------------------------------------------------------ open / close */

    public static String pickCamera(CameraManager cm, boolean front) throws CameraAccessException {
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            boolean isFront = f == CameraCharacteristics.LENS_FACING_FRONT;
            if (isFront == front) return id; // first one is the logical multi-camera on Pixels
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    @SuppressLint("MissingPermission")
    public void open(boolean front, SurfaceTexture st, float aspect, int kind, boolean raw) {
        start();
        texture = st; wantRaw = raw;
        h.post(() -> {
            try {
                closeInternal();
                String id = pickCamera(cm, front);
                if (id == null) { cb.onError("Nessuna fotocamera"); return; }
                info = readInfo(id);
                previewSize = choosePreview(info.previewSizes, kind == 1 ? 16 / 9f : aspect);
                cm.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice d) { dev = d; createSession(kind); }
                    @Override public void onDisconnected(CameraDevice d) { d.close(); dev = null; }
                    @Override public void onError(CameraDevice d, int e) { d.close(); dev = null; cb.onError("Errore fotocamera " + e); }
                }, h);
            } catch (Exception e) { Log.e(TAG, "open", e); cb.onError(e.getMessage()); }
        });
    }

    private void start() {
        if (thread == null) { thread = new HandlerThread("ampycam"); thread.start(); h = new Handler(thread.getLooper()); }
    }

    public void close() {
        if (h == null) return;
        h.post(this::closeInternal);
    }

    public void shutdown() {
        if (h == null) return;
        h.post(() -> { closeInternal(); thread.quitSafely(); thread = null; h = null; });
    }

    private void closeInternal() {
        try { if (recording) stopRecordingInternal(); } catch (Exception ignored) { }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        if (dev != null) { dev.close(); dev = null; }
        if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
        if (rawReader != null) { rawReader.close(); rawReader = null; }
        if (yuvReader != null) { yuvReader.close(); yuvReader = null; }
        pendingJpeg.clear(); pendingRaw.clear(); pendingYuv.clear(); results.clear();
    }

    private Info readInfo(String id) throws CameraAccessException {
        CameraCharacteristics c = cm.getCameraCharacteristics(id);
        Info i = new Info(); i.id = id; i.cc = c;
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        i.front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) { i.minZoom = zr.getLower(); i.maxZoom = zr.getUpper(); i.zoomRatioApi = true; }
        }
        if (!i.zoomRatioApi) {
            Float mz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            i.maxZoom = mz == null ? 1 : mz;
        }
        Range<Long> er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (er != null) { i.minExp = er.getLower(); i.maxExp = Math.min(er.getUpper(), 30_000_000_000L); }
        Range<Integer> ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (ir != null) { i.minIso = ir.getLower(); i.maxIso = ir.getUpper(); }
        Integer maxAnalog = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        Float mf = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        i.minFocus = mf == null ? 0 : mf;
        Range<Integer> evr = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (evr != null) { i.evMin = evr.getLower(); i.evMax = evr.getUpper(); }
        Rational st = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (st != null) i.evStep = st;
        float[] ap = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (ap != null && ap.length > 0) i.apertures = ap;
        float[] fo = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (fo != null && fo.length > 0) i.focals = fo;
        i.sensorMm = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        i.active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        i.sensorOrientation = so == null ? 90 : so;
        Boolean fl = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        i.flash = fl != null && fl;
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) for (int cap : caps) {
            if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) i.manualSensor = true;
            if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) i.raw = true;
        }
        int[] fm = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        if (fm != null) for (int m : fm) if (m == CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE || m == CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) i.faceDetect = true;
        Integer hl = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        i.hwLevel = hl == null ? 0 : hl;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        i.previewSizes = map.getOutputSizes(SurfaceTexture.class);
        i.videoSizes = map.getOutputSizes(MediaRecorder.class);
        i.jpegMax = largest(map.getOutputSizes(ImageFormat.JPEG));
        i.yuvMax = largest(map.getOutputSizes(ImageFormat.YUV_420_888));
        if (i.raw) { Size[] rs = map.getOutputSizes(ImageFormat.RAW_SENSOR); if (rs != null && rs.length > 0) i.rawSize = largest(rs); else i.raw = false; }
        return i;
    }

    static Size largest(Size[] s) {
        Size b = null;
        if (s != null) for (Size x : s) if (b == null || (long) x.getWidth() * x.getHeight() > (long) b.getWidth() * b.getHeight()) b = x;
        return b;
    }

    /** Biggest preview size not above 1920 wide whose aspect is closest to the wanted one. */
    static Size choosePreview(Size[] sizes, float aspect) {
        if (aspect < 4 / 3f) aspect = 4 / 3f; // 1:1 and 3:2 are cropped from 4:3
        if (aspect > 1.4f && aspect < 1.7f) aspect = 4 / 3f;
        Size best = null; double bd = 1e9;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            double a = s.getWidth() / (double) s.getHeight();
            double d = Math.abs(a - aspect) * 10000 - s.getWidth();
            if (d < bd) { bd = d; best = s; }
        }
        return best != null ? best : sizes[0];
    }

    /* ------------------------------------------------------------------ sessions */

    public void reconfigure(int kind, float aspect, boolean raw) {
        if (h == null) return;
        h.post(() -> {
            if (dev == null) return;
            wantRaw = raw;
            Size ps = choosePreview(info.previewSizes, kind == 1 || kind == 2 ? 16 / 9f : aspect);
            boolean sizeChanged = !ps.equals(previewSize);
            previewSize = ps;
            createSession(kind);
            if (sizeChanged) cb.onReady(info);
        });
    }

    private void createSession(int kind) {
        try {
            if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
            sessionKind = kind;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (previewSurface != null) previewSurface.release();
            previewSurface = new Surface(texture);
            List<Surface> outs = new ArrayList<>();
            outs.add(previewSurface);
            if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
            if (rawReader != null) { rawReader.close(); rawReader = null; }
            if (yuvReader != null) { yuvReader.close(); yuvReader = null; }
            if (kind == 0) {
                jpegReader = ImageReader.newInstance(info.jpegMax.getWidth(), info.jpegMax.getHeight(), ImageFormat.JPEG, 6);
                jpegReader.setOnImageAvailableListener(this::onJpegAvailable, h);
                outs.add(jpegReader.getSurface());
                if (wantRaw && info.raw) {
                    rawReader = ImageReader.newInstance(info.rawSize.getWidth(), info.rawSize.getHeight(), ImageFormat.RAW_SENSOR, 4);
                    rawReader.setOnImageAvailableListener(this::onRawAvailable, h);
                    outs.add(rawReader.getSurface());
                }
            } else if (kind == 3) {
                Size ys = info.yuvMax;
                yuvReader = ImageReader.newInstance(ys.getWidth(), ys.getHeight(), ImageFormat.YUV_420_888, 3);
                yuvReader.setOnImageAvailableListener(this::onYuvAvailable, h);
                outs.add(yuvReader.getSurface());
            } else if (kind == 2 && recorder != null) {
                outs.add(recorder.getSurface());
            }
            dev.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    tapLock = false; tapRegions = null; h.removeCallbacks(resumeAf);
                    try {
                        preview = dev.createCaptureRequest(kind >= 1 && kind <= 2 ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                        preview.addTarget(previewSurface);
                        if (kind == 2 && recorder != null) preview.addTarget(recorder.getSurface());
                        apply(preview, ctl, false);
                        s.setRepeatingRequest(preview.build(), previewCb, h);
                        if (kind == 2 && recorder != null) { recorder.start(); recording = true; cb.onRecording(true); }
                        else cb.onReady(info);
                    } catch (Exception e) { Log.e(TAG, "repeat", e); cb.onError(e.getMessage()); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    if (kind == 0 && rawReader != null) { wantRaw = false; createSession(0); cb.onError("RAW non disponibile con questa configurazione"); return; }
                    cb.onError("Configurazione sessione fallita");
                }
            }, h);
        } catch (Exception e) { Log.e(TAG, "session", e); cb.onError(e.getMessage()); }
    }

    private final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            last = res;
            ColorSpaceTransform cst = res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            Integer awb = res.get(CaptureResult.CONTROL_AWB_MODE);
            if (cst != null && awb != null && awb != CameraMetadata.CONTROL_AWB_MODE_OFF) lastCst = cst;
            RggbChannelVector gains = res.get(CaptureResult.COLOR_CORRECTION_GAINS);
            if (gains != null && awb != null && awb == CameraMetadata.CONTROL_AWB_MODE_AUTO) lastAutoGains = gains;
            Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            Integer af = res.get(CaptureResult.CONTROL_AF_STATE);
            Integer ae = res.get(CaptureResult.CONTROL_AE_STATE);
            Face[] faces = res.get(CaptureResult.STATISTICS_FACES);
            Float ap = res.get(CaptureResult.LENS_APERTURE);
            Float fl = res.get(CaptureResult.LENS_FOCAL_LENGTH);
            cb.onFrameResult(e == null ? 0 : e, iso == null ? 0 : iso, fd == null ? 0 : fd, af == null ? 0 : af, ae == null ? 0 : ae,
                faces, ap == null ? info.apertures[0] : ap, fl == null ? info.focals[0] : fl);
        }
    };

    /* ------------------------------------------------------------------ controls */

    public void setControls(Controls c) {
        Controls old = ctl;
        ctl = c;
        if (h == null) return;
        boolean afChanged = old == null || old.af != c.af || old.video != c.video;
        h.post(() -> {
            if (session == null || preview == null) return;
            try {
                apply(preview, c, false);
                if (afChanged) { tapLock = false; h.removeCallbacks(resumeAf); restartAf(); }
                else session.setRepeatingRequest(preview.build(), previewCb, h);
            }
            catch (Exception e) { Log.w(TAG, "controls", e); }
        });
    }

    /* ---------- autofocus ---------- */
    private volatile boolean tapLock;              // focus locked on a tapped point
    private volatile MeteringRectangle[] tapRegions;

    /** CANCEL clears any earlier lock so continuous AF scans again; then repeat with IDLE. */
    private void restartAf() throws CameraAccessException {
        preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
        session.capture(preview.build(), previewCb, h);
        preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        session.setRepeatingRequest(preview.build(), previewCb, h);
    }

    private final Runnable resumeAf = () -> {
        if (session == null || preview == null) return;
        tapLock = false; tapRegions = null;
        try { apply(preview, ctl, false); restartAf(); } catch (Exception e) { Log.w(TAG, "resumeAf", e); }
    };

    /** Back to automatic continuous AF (after a tap, a mode change, a camera switch…). */
    public void resumeContinuousAf() { if (h != null) h.post(() -> { h.removeCallbacks(resumeAf); resumeAf.run(); }); }

    /**
     * Tap to focus (Pixel style): focus once on the point, keep it for holdMs, then go back to continuous AF.
     * holdMs <= 0 keeps the lock (AE/AF lock, Canon One Shot) until resumeContinuousAf().
     * With Canon Servo (servoTrack) the point is tracked continuously instead.
     */
    public void tapFocus(MeteringRectangle[] regions, long holdMs, boolean servoTrack) {
        if (h == null) return;
        h.post(() -> {
            if (session == null || preview == null) return;
            h.removeCallbacks(resumeAf);
            try {
                if (servoTrack) {
                    tapLock = false; tapRegions = regions;
                    apply(preview, ctl, false); restartAf();
                    return;
                }
                tapLock = true; tapRegions = regions;
                apply(preview, ctl, false);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(preview.build(), null, h);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                session.capture(preview.build(), previewCb, h);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                session.setRepeatingRequest(preview.build(), previewCb, h);
                if (holdMs > 0) h.postDelayed(resumeAf, holdMs);
            } catch (Exception e) { Log.w(TAG, "tapFocus", e); }
        });
    }

    /** One-shot AF on the region (Canon half-press). */
    public void triggerAf() {
        if (h == null) return;
        h.post(() -> {
            if (session == null || preview == null) return;
            try {
                apply(preview, ctl, false);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(preview.build(), null, h);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                session.capture(preview.build(), previewCb, h);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                session.setRepeatingRequest(preview.build(), previewCb, h);
            } catch (Exception e) { Log.w(TAG, "af", e); }
        });
    }

    private void apply(CaptureRequest.Builder b, Controls c, boolean still) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        // zoom
        if (info.zoomRatioApi && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(info.minZoom, Math.min(info.maxZoom, c.zoom)));
        } else if (info.active != null) {
            float z = Math.max(1, Math.min(info.maxZoom, c.zoom));
            int w = (int) (info.active.width() / z), hh = (int) (info.active.height() / z);
            int x = (info.active.width() - w) / 2, y = (info.active.height() - hh) / 2;
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(x, y, x + w, y + hh));
        }
        // exposure
        if (c.aeManual && info.manualSensor) {
            long exp = Math.max(info.minExp, Math.min(info.maxExp, c.expNs));
            int iso = Math.max(info.minIso, Math.min(info.maxIso, c.iso));
            if (!still && exp > 66_000_000L) {
                // exposure simulation: keep a fluid preview, compensate with gain
                double k = exp / 66_000_000.0; exp = 66_000_000L;
                iso = (int) Math.max(info.minIso, Math.min(info.maxIso, iso * k));
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exp, 33_333_333L));
            b.set(CaptureRequest.FLASH_MODE, c.flash == CamState.FL_TORCH || (still && c.flash == CamState.FL_ON)
                ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        } else {
            int aeMode = CameraMetadata.CONTROL_AE_MODE_ON;
            if (info.flash && c.flash == CamState.FL_AUTO) aeMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
            if (info.flash && c.flash == CamState.FL_ON) aeMode = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.max(info.evMin, Math.min(info.evMax, c.evIdx)));
            b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock);
            b.set(CaptureRequest.FLASH_MODE, info.flash && c.flash == CamState.FL_TORCH ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
            if (c.video || c.night) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps(c.night ? 15 : 30));
        }
        // white balance
        if (c.wb == CamState.WB_K && c.wbGains != null && lastCst != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF);
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, c.wbGains);
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lastCst);
        } else {
            int m;
            switch (c.wb) {
                case CamState.WB_DAY: m = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT; break;
                case CamState.WB_SHADE: m = CameraMetadata.CONTROL_AWB_MODE_SHADE; break;
                case CamState.WB_CLOUDY: m = CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT; break;
                case CamState.WB_TUNG: m = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT; break;
                case CamState.WB_FLUO: m = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT; break;
                default: m = CameraMetadata.CONTROL_AWB_MODE_AUTO;
            }
            b.set(CaptureRequest.CONTROL_AWB_MODE, m);
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST);
        }
        // focus
        if (c.af == CamState.AF_MF && info.minFocus > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0, Math.min(info.minFocus, c.focus)));
        } else if ((tapLock || c.af == CamState.AF_ONESHOT) && info.minFocus > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, c.video ? CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        // AF / AE regions: tapped point, Canon AF point, or "camera decides" (weight 0 resets older regions kept by the builder)
        MeteringRectangle[] afr = tapRegions != null ? tapRegions : c.afRegions;
        Integer maxAf = info.cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAe = info.cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if (maxAf != null && maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, afr != null ? afr : dontCare());
        if (maxAe != null && maxAe > 0 && !(c.aeManual && info.manualSensor)) {
            MeteringRectangle[] aer = c.aeRegions != null ? c.aeRegions : tapRegions;
            b.set(CaptureRequest.CONTROL_AE_REGIONS, aer != null ? aer : dontCare());
        }
        if (info.faceDetect) b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, c.faces ? CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE : CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, c.video && c.stab ? CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON : CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        if (still) {
            b.set(CaptureRequest.EDGE_MODE, c.sharp ? CameraMetadata.EDGE_MODE_HIGH_QUALITY : CameraMetadata.EDGE_MODE_OFF);
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        }
    }

    private MeteringRectangle[] dontCare() {
        Rect a = info.active != null ? info.active : new Rect(0, 0, 1, 1);
        return new MeteringRectangle[]{new MeteringRectangle(0, 0, a.width() - 1, a.height() - 1, MeteringRectangle.METERING_WEIGHT_DONT_CARE)};
    }

    private Range<Integer> bestFps(int min) {
        Range<Integer>[] rs = info.cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> best = null;
        if (rs != null) for (Range<Integer> r : rs) {
            if (r.getUpper() < 30) continue;
            if (best == null || Math.abs(r.getLower() - min) < Math.abs(best.getLower() - min) || (r.getLower().equals(best.getLower()) && r.getUpper() < best.getUpper())) best = r;
        }
        return best != null ? best : new Range<>(15, 30);
    }

    /* ------------------------------------------------------------------ stills */

    public void capture(Shot shot) {
        if (h == null) return;
        if (shot.precapture && !ctl.aeManual) {
            // flash: let AE meter the pre-flash first (AE_PRECAPTURE), then shoot
            h.post(() -> {
                try {
                    if (session != null && preview != null) {
                        preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                        session.capture(preview.build(), previewCb, h);
                        preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                    }
                } catch (Exception e) { Log.w(TAG, "precapture", e); }
            });
            shot.precapture = false;
            h.postDelayed(() -> capture(shot), 650);
            return;
        }
        h.post(() -> {
            if (session == null || dev == null) { cb.onShotFailed(shot, "Sessione non pronta"); return; }
            try {
                List<CaptureRequest> reqs = new ArrayList<>();
                Surface target = shot.yuv ? (yuvReader != null ? yuvReader.getSurface() : null) : (jpegReader != null ? jpegReader.getSurface() : null);
                if (target == null) { cb.onShotFailed(shot, "Formato non pronto"); return; }
                for (int i = 0; i < shot.frames.size(); i++) {
                    long[] f = shot.frames.get(i);
                    CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    b.addTarget(target);
                    if (shot.raw && rawReader != null) b.addTarget(rawReader.getSurface());
                    Controls c = ctl;
                    apply(b, c, true);
                    if (f[0] > 0) {
                        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.max(info.minExp, Math.min(info.maxExp, f[0])));
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, (int) Math.max(info.minIso, Math.min(info.maxIso, f[1])));
                        b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(f[0], 33_333_333L));
                    }
                    if (!shot.yuv) {
                        b.set(CaptureRequest.JPEG_ORIENTATION, shot.orientation);
                        b.set(CaptureRequest.JPEG_QUALITY, (byte) shot.jpegQ);
                    }
                    b.setTag(new Object[]{shot, i});
                    reqs.add(b.build());
                    synchronized (pendingJpeg) {
                        if (shot.yuv) pendingYuv.add(new Object[]{shot, i});
                        else pendingJpeg.add(new Object[]{shot, i});
                        if (shot.raw && rawReader != null) pendingRaw.add(new Object[]{shot, i});
                    }
                }
                CameraCaptureSession.CaptureCallback ccb = new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long ts, long fn) {
                        Object[] t = (Object[]) r.getTag(); cb.onShutter((Shot) t[0], (Integer) t[1]);
                    }
                    @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                        Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
                        if (ts != null) results.put(ts, res);
                        tryRaw();
                    }
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                        Object[] t = (Object[]) r.getTag(); cb.onShotFailed((Shot) t[0], "Scatto fallito (" + f.getReason() + ")");
                    }
                };
                if (reqs.size() == 1) session.capture(reqs.get(0), ccb, h);
                else session.captureBurst(reqs, ccb, h);
            } catch (Exception e) { Log.e(TAG, "capture", e); cb.onShotFailed(shot, e.getMessage()); }
        });
    }

    private void onJpegAvailable(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        Object[] t;
        synchronized (pendingJpeg) { t = pendingJpeg.poll(); }
        try {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()]; buf.get(bytes);
            if (t != null) cb.onJpeg((Shot) t[0], (Integer) t[1], bytes);
        } finally { img.close(); }
    }

    private final List<Image> rawImages = new ArrayList<>();
    private void onRawAvailable(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        synchronized (rawImages) { rawImages.add(img); }
        tryRaw();
    }
    private void tryRaw() {
        synchronized (rawImages) {
            for (int k = 0; k < rawImages.size(); k++) {
                Image img = rawImages.get(k);
                TotalCaptureResult res = results.remove(img.getTimestamp());
                if (res == null) continue;
                Object[] t;
                synchronized (pendingJpeg) { t = pendingRaw.poll(); }
                rawImages.remove(k--);
                if (t != null) cb.onRaw((Shot) t[0], (Integer) t[1], img, res, info.cc); else img.close();
            }
        }
        if (results.size() > 20) results.clear();
    }

    private void onYuvAvailable(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        Object[] t;
        synchronized (pendingJpeg) { t = pendingYuv.poll(); }
        try { if (t != null) cb.onYuvFrame((Shot) t[0], (Integer) t[1], img); }
        finally { img.close(); }
    }

    /* ------------------------------------------------------------------ video */

    public static final class VideoSpec {
        public Size size = new Size(1920, 1080); public int fps = 30; public boolean hevc; public int bitrate = 20_000_000;
        public boolean audio = true; public int orientation; public ParcelFileDescriptor pfd;
    }

    public void startRecording(VideoSpec v) {
        if (h == null) return;
        h.post(() -> {
            try {
                recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(ctx) : new MediaRecorder();
                if (v.audio) recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setOutputFile(v.pfd.getFileDescriptor());
                recorder.setVideoEncodingBitRate(v.bitrate);
                recorder.setVideoFrameRate(v.fps);
                recorder.setVideoSize(v.size.getWidth(), v.size.getHeight());
                recorder.setVideoEncoder(v.hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
                if (v.audio) { recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recorder.setAudioEncodingBitRate(192000); recorder.setAudioSamplingRate(48000); }
                recorder.setOrientationHint(v.orientation);
                recorder.prepare();
                createSession(2);
            } catch (Exception e) {
                Log.e(TAG, "rec", e);
                try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
                recorder = null; cb.onError("Video: " + e.getMessage()); cb.onRecording(false);
            }
        });
    }

    public void stopRecording() {
        if (h == null) return;
        h.post(() -> { stopRecordingInternal(); if (dev != null) createSession(1); });
    }

    private void stopRecordingInternal() {
        if (recorder == null) return;
        try { if (session != null) { session.stopRepeating(); session.abortCaptures(); } } catch (Exception ignored) { }
        try { if (recording) recorder.stop(); } catch (Exception e) { Log.w(TAG, "stop", e); }
        recorder.release(); recorder = null;
        boolean was = recording; recording = false;
        if (was) cb.onRecording(false);
    }

    /** Video sizes the encoder can take, biggest first, matched to 16:9. */
    public static List<Size> videoSizes(Info i) {
        List<Size> o = new ArrayList<>();
        if (i == null || i.videoSizes == null) return o;
        for (Size s : i.videoSizes) {
            double a = s.getWidth() / (double) s.getHeight();
            if (Math.abs(a - 16 / 9.0) < 0.02 && s.getWidth() <= 3840 && s.getWidth() >= 1280) o.add(s);
        }
        o.sort((a, b) -> b.getWidth() - a.getWidth());
        return o;
    }
}
