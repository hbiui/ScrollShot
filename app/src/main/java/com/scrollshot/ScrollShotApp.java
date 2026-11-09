package com.scrollshot;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Application that records any uncaught crash to a findable place.
 * Old code wrote a .txt file into MediaStore.Images, which many phones
 * hide or reject for non-image MIME types, so no crash file ever appeared.
 * Now it writes to Download/ScrollShot via MediaStore.Downloads (API 29+)
 * and also to the app's external files dir as a backup.
 */
public class ScrollShotApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String log = "Thread: " + t.getName() + "\n" + sw.toString();
                byte[] bytes = log.getBytes("UTF-8");
                String name = "ScrollShot_crash_" + System.currentTimeMillis() + ".txt";
                // Backup 1: external files dir (always writable for the app).
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        File f = new File(dir, name);
                        try (OutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                    }
                } catch (Exception ignored) {}
                // Backup 2: Downloads, visible in Files app.
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    if (Build.VERSION.SDK_INT >= 29) {
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ScrollShot");
                    }
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) os.write(bytes);
                        }
                    }
                } catch (Exception ignored) {}
                CaptureSession.lastError = log;
            } catch (Exception ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
