package com.scrollshot;

import android.graphics.Bitmap;

/**
 * Shared state between MainActivity, CaptureService and
 * ScrollAccessibilityService. All three run in the same app process,
 * so static fields are a simple, reliable bridge (no AIDL needed).
 * Nothing here is persisted to disk and no screenshot leaves the device
 * except when the user explicitly taps Share / Save.
 */
public class CaptureSession {
    public static volatile ScrollAccessibilityService accessibilityService;
    public static volatile CaptureService captureService;

    public static volatile boolean capturing = false;
    /** True while the user is on the target page and has pressed Start on the overlay. */
    public static volatile boolean captureRequested = false;

    /** Last scroll distance in pixels (full-resolution frame coordinates), set by the accessibility service. */
    public static volatile int lastScrollDistancePx = 0;
    /** 0 = vertical, 1 = horizontal */
    public static volatile int lastDirection = 0;

    /**
     * Scroll step as a fraction of the scrollable node's size.
     * CaptureService adapts it (stitch-rate feedback): when the last
     * pair of frames had too little overlap or failed to match, the
     * step shrinks so the next pair overlaps more; when stitching is
     * healthy it grows back toward the default. Range ~0.20..0.25.
     */
    public static volatile double scrollStepFactor = 0.22;

    public static volatile Bitmap lastSavedBitmap = null;
    public static volatile String lastSavedUri = null;
    /**
     * Cache file of the last stitched long image, generated for the
     * preview screen. Nothing is written to the gallery until the user
     * taps 保存长图 there; this file is the "上一张长图" fallback while
     * it is still unsaved.
     */
    public static volatile String lastPreviewPath = null;
    public static volatile String lastError = null;
    public static volatile String statusText = "";
    /** Screen-capture grant, kept in memory (not via Intent extra) to avoid
     *  parceling problems on Android 13+ when the service reads it back. */
    public static volatile android.content.Intent mediaProjectionData = null;
    public static volatile int mediaProjectionResultCode = 0;

    public static synchronized void setStatus(String s) {
        statusText = s;
    }

    /**
     * Write the stitched long image to a fixed cache file for preview.
     * Every capture overwrites the same file, so the cache never grows.
     * Returns the absolute path, or null if compression failed.
     */
    public static String writePreview(android.content.Context context, Bitmap bmp) {
        if (bmp == null) return null;
        // Very tall images can OOM while PNG-compressing.
        if (bmp.getHeight() > 20000 && bmp.getWidth() > 720) {
            int newW = 720;
            int newH = (int) Math.round((double) bmp.getHeight() * newW / bmp.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true);
            bmp.recycle();
            bmp = scaled;
        }
        java.io.File dir = new java.io.File(context.getCacheDir(), "preview");
        if (!dir.exists() && !dir.mkdirs()) return null;
        java.io.File out = new java.io.File(dir, "long.png");
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            return out.getAbsolutePath();
        } catch (Exception e) {
            lastError = e.getMessage();
            return null;
        }
    }
}
