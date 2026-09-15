package com.modserver.stats.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Gives Android's package installer read-only access to the downloaded update APK. */
public final class ApkUpdateProvider extends ContentProvider {
    private static final String FILE_NAME = "modserverstats-update.apk";

    private File updateFile() {
        File directory = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(directory == null ? getContext().getCacheDir() : directory, FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.startsWith("r")) {
            throw new FileNotFoundException("Read-only update provider");
        }
        File file = updateFile();
        if (!file.isFile() || !file.canRead()) {
            throw new FileNotFoundException("Update APK not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File file = updateFile();
        MatrixCursor cursor = new MatrixCursor(new String[]{"_display_name", "_size"});
        cursor.addRow(new Object[]{FILE_NAME, file.exists() ? file.length() : 0L});
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
