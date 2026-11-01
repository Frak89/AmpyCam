package it.ampy.ampycam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ComposeShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/** JPEG / DNG / MP4 writing into DCIM/AmpyCam with Canon-like names and EXIF. */
public final class ImageSaver {
    static final String TAG = "AmpyCam";
    public static final String FOLDER = "DCIM/AmpyCam";

    public static final class Result { public Uri uri; public Bitmap thumb; public String name; public int[] histogram; }

    /** Options frozen at shutter time. */
    public static final class Opts {
        public String style = "standard"; public float aspect = 4 / 3f; public int quality = 95;
        public boolean portrait; public RectF subject; public int blur = 60; public String model = "AmpyCam";
        public String lens = ""; public String dial = "P"; public boolean mirror;
    }

    private ImageSaver() { }

    public static String name(int n, String ext) { return String.format(Locale.US, "IMG_%04d.%s", n, ext); }

    /* ---------------------------------------------------------------- JPEG */

    public static Result saveJpeg(Context c, byte[] jpeg, Opts o, int fileNo) throws Exception {
        File cache = new File(c.getCacheDir(), "shot_" + System.nanoTime() + ".jpg");
        File out = new File(c.getCacheDir(), "out_" + System.nanoTime() + ".jpg");
        try (FileOutputStream f = new FileOutputStream(cache)) { f.write(jpeg); }
        ExifInterface src = new ExifInterface(cache.getAbsolutePath());
        int orient = src.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        PictureStyle ps = PictureStyle.of(o.style);
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bo);
        boolean rotated = orient == ExifInterface.ORIENTATION_ROTATE_90 || orient == ExifInterface.ORIENTATION_ROTATE_270;
        int w = rotated ? bo.outHeight : bo.outWidth, h = rotated ? bo.outWidth : bo.outHeight;
        float native_ = Math.max(w, h) / (float) Math.min(w, h);
        boolean needCrop = Math.abs(native_ - o.aspect) > 0.02f;
        boolean process = needCrop || !ps.isIdentity() || o.portrait || o.mirror;
        File finalFile = cache;
        if (process) {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            bmp = orient(bmp, orient, o.mirror);
            if (needCrop) bmp = crop(bmp, o.aspect);
            if (o.portrait) bmp = portrait(bmp, o.subject, o.blur);
            if (!ps.isIdentity()) bmp = style(bmp, ps);
            try (FileOutputStream f = new FileOutputStream(out)) { bmp.compress(Bitmap.CompressFormat.JPEG, o.quality, f); }
            bmp.recycle();
            ExifInterface dst = new ExifInterface(out.getAbsolutePath());
            copyExif(src, dst);
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            stamp(dst, o, ps);
            dst.saveAttributes();
            finalFile = out;
        } else {
            stamp(src, o, ps);
            src.saveAttributes();
        }
        Result r = new Result();
        r.name = name(fileNo, "JPG");
        r.uri = insert(c, finalFile, r.name, "image/jpeg", true);
        r.thumb = thumb(finalFile.getAbsolutePath(), 320);
        cache.delete(); out.delete();
        return r;
    }

    private static void stamp(ExifInterface e, Opts o, PictureStyle ps) {
        e.setAttribute(ExifInterface.TAG_SOFTWARE, "AmpyCam 1.0 · " + ps.name + " · " + o.dial);
        e.setAttribute(ExifInterface.TAG_ARTIST, "AmpyCam");
        e.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Picture Style: " + ps.name);
    }

    private static final String[] TAGS = {
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    };
    private static void copyExif(ExifInterface a, ExifInterface b) {
        for (String t : TAGS) { String v = a.getAttribute(t); if (v != null) b.setAttribute(t, v); }
    }

    static Bitmap orient(Bitmap b, int orient, boolean mirror) {
        Matrix m = new Matrix();
        switch (orient) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            default: break;
        }
        if (mirror) m.postScale(-1, 1);
        if (m.isIdentity()) return b;
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    static Bitmap crop(Bitmap b, float aspect) {
        int w = b.getWidth(), h = b.getHeight();
        boolean land = w >= h;
        float cur = land ? w / (float) h : h / (float) w;
        int nw = w, nh = h;
        if (cur > aspect) { if (land) nw = Math.round(h * aspect); else nh = Math.round(w * aspect); }
        else { if (land) nh = Math.round(w / aspect); else nw = Math.round(h / aspect); }
        Bitmap r = Bitmap.createBitmap(b, (w - nw) / 2, (h - nh) / 2, nw, nh);
        if (r != b) b.recycle();
        return r;
    }

    static Bitmap style(Bitmap b, PictureStyle ps) {
        Bitmap r = b.isMutable() ? b : b.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(r);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(ps.matrix()));
        Bitmap src = r == b ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
        c.drawBitmap(src, 0, 0, p);
        if (src != r) src.recycle();
        if (r != b && !b.isRecycled()) b.recycle();
        return r;
    }

    /** Fake bokeh: background from a strongly down-scaled copy, sharp subject through a soft radial mask. */
    static Bitmap portrait(Bitmap b, RectF subj, int strength) {
        int w = b.getWidth(), h = b.getHeight();
        float k = 0.02f + 0.08f * (1 - strength / 100f);
        Bitmap small = Bitmap.createScaledBitmap(b, Math.max(8, (int) (w * k)), Math.max(8, (int) (h * k)), true);
        Bitmap mid = Bitmap.createScaledBitmap(small, Math.max(16, w / 8), Math.max(16, h / 8), true);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(mid, null, new RectF(0, 0, w, h), fp);
        float cx, cy, rad;
        if (subj != null && subj.width() > 0) {
            cx = subj.centerX() * w; cy = (subj.centerY() + subj.height() * 0.6f) * h;
            rad = Math.max(subj.width() * w, subj.height() * h) * 1.6f;
        } else { cx = w / 2f; cy = h * 0.5f; rad = Math.min(w, h) * 0.55f; }
        BitmapShader sharp = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        RadialGradient mask = new RadialGradient(cx, cy, rad, new int[]{0xff000000, 0xff000000, 0x00000000}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new ComposeShader(mask, sharp, PorterDuff.Mode.SRC_IN));
        c.drawRect(0, 0, w, h, p);
        small.recycle(); mid.recycle(); b.recycle();
        return out;
    }

    static Bitmap thumb(String path, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        try {
            int or = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (b != null) b = orient(b, or, false);
        } catch (Exception ignored) { }
        return b;
    }

    /* ---------------------------------------------------------------- DNG */

    public static Uri saveDng(Context c, Image raw, CaptureResult res, CameraCharacteristics cc, int orientation, int fileNo) throws Exception {
        File f = new File(c.getCacheDir(), "raw_" + System.nanoTime() + ".dng");
        try (DngCreator d = new DngCreator(cc, res); FileOutputStream os = new FileOutputStream(f)) {
            d.setOrientation(exifFor(orientation));
            d.setDescription("AmpyCam RAW");
            d.writeImage(os, raw);
        }
        Uri u = insert(c, f, name(fileNo, "DNG"), "image/x-adobe-dng", true);
        f.delete();
        return u;
    }
    static int exifFor(int deg) {
        switch ((deg % 360 + 360) % 360) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /* ---------------------------------------------------------------- night stacking */

    /** Averages YUV_420_888 frames into NV21; noise drops by √N. */
    public static final class NightStacker {
        int w, h, n; int[] y, vu;
        public synchronized void add(Image img) {
            Image.Plane[] p = img.getPlanes();
            if (y == null) { w = img.getWidth(); h = img.getHeight(); y = new int[w * h]; vu = new int[w * h / 2]; }
            ByteBuffer yb = p[0].getBuffer(); int yr = p[0].getRowStride(), yp = p[0].getPixelStride();
            for (int r = 0; r < h; r++) {
                int base = r * yr, o = r * w;
                for (int x = 0; x < w; x++) y[o + x] += yb.get(base + x * yp) & 0xff;
            }
            ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
            int ur = p[1].getRowStride(), up = p[1].getPixelStride();
            for (int r = 0; r < h / 2; r++) {
                int o = r * w;
                for (int x = 0; x < w / 2; x++) {
                    int idx = r * ur + x * up;
                    vu[o + x * 2] += vb.get(idx) & 0xff;
                    vu[o + x * 2 + 1] += ub.get(idx) & 0xff;
                }
            }
            n++;
        }
        public synchronized int count() { return n; }
        /** Average, lift the shadows a little, encode. */
        public synchronized byte[] jpeg(int quality) {
            byte[] nv = new byte[w * h * 3 / 2];
            int[] lut = new int[256];
            for (int i = 0; i < 256; i++) lut[i] = (int) Math.min(255, Math.round(255 * Math.pow(i / 255.0, 0.82)));
            for (int i = 0; i < y.length; i++) nv[i] = (byte) lut[Math.min(255, y[i] / n)];
            int off = w * h;
            for (int i = 0; i < vu.length; i++) {
                int v = vu[i] / n; v = 128 + (int) ((v - 128) * 1.12f);
                nv[off + i] = (byte) Math.max(0, Math.min(255, v));
            }
            YuvImage yi = new YuvImage(nv, android.graphics.ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            yi.compressToJpeg(new android.graphics.Rect(0, 0, w, h), quality, bo);
            y = null; vu = null;
            return bo.toByteArray();
        }
    }

    /** Night JPEG has no orientation from the HAL: write it ourselves before the normal pipeline. */
    public static byte[] withOrientation(Context c, byte[] jpeg, int deg) throws Exception {
        File f = new File(c.getCacheDir(), "night_" + System.nanoTime() + ".jpg");
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(jpeg); }
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        e.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifFor(deg)));
        e.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
        e.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
        e.saveAttributes();
        byte[] out = readAll(new FileInputStream(f));
        f.delete();
        return out;
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); return o.toByteArray(); }
    }

    /* ---------------------------------------------------------------- MediaStore */

    static Uri insert(Context c, File f, String name, String mime, boolean image) throws Exception {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        Uri coll;
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            coll = image ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "AmpyCam");
            dir.mkdirs();
            v.put(MediaStore.MediaColumns.DATA, new File(dir, name).getAbsolutePath());
            coll = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        Uri u = cr.insert(coll, v);
        if (u == null) throw new IllegalStateException("MediaStore insert fallito");
        try (OutputStream os = cr.openOutputStream(u); InputStream is = new FileInputStream(f)) {
            byte[] b = new byte[65536]; int n; while ((n = is.read(b)) > 0) os.write(b, 0, n);
        }
        if (Build.VERSION.SDK_INT >= 29) { ContentValues d = new ContentValues(); d.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(u, d, null, null); }
        return u;
    }

    /** Pending MP4 entry: returns {uri, pfd}. Call finishVideo afterwards. */
    public static Object[] newVideo(Context c, int fileNo) throws Exception {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        String name = String.format(Locale.US, "MVI_%04d.MP4", fileNo);
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        Uri coll;
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "AmpyCam");
            dir.mkdirs();
            v.put(MediaStore.MediaColumns.DATA, new File(dir, name).getAbsolutePath());
            coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        Uri u = cr.insert(coll, v);
        if (u == null) throw new IllegalStateException("MediaStore video fallito");
        ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "rw");
        return new Object[]{u, pfd};
    }

    public static void finishVideo(Context c, Uri u, ParcelFileDescriptor pfd, boolean ok) {
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        try {
            if (!ok) { c.getContentResolver().delete(u, null, null); return; }
            if (Build.VERSION.SDK_INT >= 29) { ContentValues d = new ContentValues(); d.put(MediaStore.MediaColumns.IS_PENDING, 0); c.getContentResolver().update(u, d, null, null); }
        } catch (Exception e) { Log.w(TAG, "finishVideo", e); }
    }

    public static Bitmap videoThumb(Context c, Uri u) {
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(c, u);
            Bitmap b = r.getFrameAtTime(0);
            r.release();
            if (b == null) return null;
            float s = 320f / Math.max(b.getWidth(), b.getHeight());
            return Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * s)), Math.max(1, (int) (b.getHeight() * s)), true);
        } catch (Exception e) { return null; }
    }
}
