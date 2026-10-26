package dev.ratemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Serves the downloaded APK to the system installer. A file:// URI is not readable by
 * another app on modern Android, and androidx FileProvider is not available without a
 * dependency, so this is the five-line equivalent.
 */
public class ApkProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) {
        try {
            File f = Updater.apkFile(getContext());
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            return null;
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override public String getType(Uri u) {
        return "application/vnd.android.package-archive";
    }

    @Override public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }
}
