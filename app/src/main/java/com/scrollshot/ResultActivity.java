package com.scrollshot;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Preview screen for a stitched long image.
 *
 * Captures no longer write to the gallery automatically: the image is
 * handed over as a cache file (EXTRA_PATH) and only the 保存长图 button
 * inserts it into Pictures/ScrollShot. The top-right share icon sends
 * that cache file through PreviewProvider, so sharing does not save it
 * either. A previously saved image can still be opened by content URI
 * (EXTRA_URI), in which case the save button is shown as already saved.
 */
public class ResultActivity extends Activity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_PATH = "path";

    private Uri uri;            // gallery uri, only set once the image is saved
    private File previewFile;   // unsaved cache file
    private boolean saved;

    private Button btnSave;
    private TextView tvSaveHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        // BUG FIX (v1.9): senders have always passed EXTRA_URI as a String
        // (uri.toString()), but this receiver only read it as a Parcelable Uri,
        // so it always got null and showed "no data" / a blank image, even
        // though the long image WAS saved in Pictures/ScrollShot.
        // Accept both forms: Parcelable Uri first, then String.
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                uri = getIntent().getParcelableExtra(EXTRA_URI, Uri.class);
            } else {
                uri = getIntent().getParcelableExtra(EXTRA_URI);
            }
        } catch (Exception ignored) {
            uri = null;
        }
        if (uri == null) {
            String s = getIntent().getStringExtra(EXTRA_URI);
            if (s != null && !s.isEmpty()) uri = Uri.parse(s);
        }
        if (uri != null) saved = true;

        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path != null && !path.isEmpty()) {
            File f = new File(path);
            if (f.isFile()) previewFile = f;
        }
        if (uri == null && previewFile != null
                && CaptureSession.lastPreviewPath == null
                && CaptureSession.lastSavedUri != null) {
            // This preview was already saved (lastPreviewPath is cleared
            // on save), so reopen it in the saved state instead of
            // offering a duplicate save.
            try {
                uri = Uri.parse(CaptureSession.lastSavedUri);
                saved = true;
            } catch (Exception ignored) {
            }
        }
        if (uri == null && previewFile == null) {
            Toast.makeText(this, "没有长图数据，请重新截一张", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ImageView iv = findViewById(R.id.ivResult);
        btnSave = findViewById(R.id.btnSaveImage);
        tvSaveHint = findViewById(R.id.tvSaveHint);
        ImageButton btnShareIcon = findViewById(R.id.btnShareIcon);
        Button btnBack = findViewById(R.id.btnBack);

        try {
            Bitmap bmp;
            if (previewFile != null) {
                bmp = BitmapFactory.decodeFile(previewFile.getAbsolutePath());
            } else {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(is);
                }
            }
            iv.setImageBitmap(bmp);
        } catch (Exception e) {
            Toast.makeText(this, "打开长图失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        updateSaveState();
        btnSave.setOnClickListener(v -> {
            if (saved) {
                Toast.makeText(this, "已保存到相册 Pictures/ScrollShot", Toast.LENGTH_SHORT).show();
            } else {
                saveToGallery();
            }
        });
        btnShareIcon.setOnClickListener(v -> share());
        btnBack.setOnClickListener(v -> finish());
    }

    private void updateSaveState() {
        if (btnSave == null) return;
        btnSave.setText(saved ? "已保存到相册" : "保存长图");
        if (tvSaveHint != null) {
            tvSaveHint.setText(saved
                    ? "已保存到相册 Pictures/ScrollShot"
                    : "确认预览后再保存到相册 Pictures/ScrollShot");
        }
    }

    /** Insert the preview file into the gallery. Returns true on success. */
    private boolean saveToGallery() {
        if (saved) return true;
        if (previewFile == null) return false;
        String name = "ScrollShot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScrollShot");
        Uri out = null;
        try {
            out = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (out == null) {
                Toast.makeText(this, "保存失败：无法写入相册", Toast.LENGTH_LONG).show();
                return false;
            }
            // Copy the compressed file instead of re-encoding a very
            // tall bitmap a second time.
            try (InputStream is = new FileInputStream(previewFile);
                 OutputStream os = getContentResolver().openOutputStream(out)) {
                if (os == null) throw new IllegalStateException("无法写入相册");
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            uri = out;
            saved = true;
            CaptureSession.lastSavedUri = out.toString();
            // 查看上一张长图 now opens the gallery copy. The cache file
            // is kept: the completion notification still points at it,
            // and the next capture overwrites it anyway.
            CaptureSession.lastPreviewPath = null;
            updateSaveState();
            Toast.makeText(this, "已保存到相册 Pictures/ScrollShot", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            if (out != null) {
                try {
                    getContentResolver().delete(out, null, null);
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void share() {
        // Unsaved previews are shared straight from the cache through
        // PreviewProvider, so sharing never writes to the gallery.
        Uri sendUri = uri;
        if (!saved) {
            if (previewFile == null || !previewFile.isFile()) {
                Toast.makeText(this, "预览文件已失效，请重新截一张", Toast.LENGTH_LONG).show();
                return;
            }
            sendUri = PreviewProvider.contentUri();
        }
        if (sendUri == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, sendUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享长图"));
    }
}
