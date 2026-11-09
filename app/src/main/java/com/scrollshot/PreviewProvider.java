package com.scrollshot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the preview PNG straight out of the app cache.
 *
 * The preview screen has nothing in the gallery yet, but a share sheet
 * needs a content:// URI the receiving app may read, so this provider
 * exposes that one cache file. It is not exported and only grants
 * read access through FLAG_GRANT_READ_URI_PERMISSION, and it refuses
 * every path except the fixed preview file.
 */
public class PreviewProvider extends ContentProvider {
    public static final String AUTHORITY = "com.scrollshot.preview";
    private static final String FILE_NAME = "long.png";

    public static Uri contentUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File previewFile(Uri uri) {
        if (uri == null || !FILE_NAME.equals(uri.getLastPathSegment())) return null;
        File f = new File(new File(getContext().getCacheDir(), "preview"), FILE_NAME);
        return f.isFile() ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        File f = previewFile(uri);
        if (f == null) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    /** Some share targets query the display name and size before opening. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = previewFile(uri);
        if (f == null) return null;
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(projection[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
