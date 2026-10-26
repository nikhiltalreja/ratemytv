package dev.ratemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;

/**
 * Poster bytes on disk, keyed by title (and the URL, so a changed poster does not
 * collide with the old one). The OMDb text cache lives in Ratings; this is the
 * image half, plus the rule that both are thrown away when the title does.
 *
 * File names start with the normalised title, which is what makes eviction cheap:
 * dropping a title is a prefix scan, not an index to keep in sync.
 */
public class Cache {
    static final String TAG = "RATEMYTV";
    static final String SUFFIX = ".img";

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "posters");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String key(String title) {
        String k = Ratings.norm(title);
        return k.isEmpty() ? "untitled" : k;
    }

    static String name(String title, String url) {
        return key(title) + "-" + Integer.toHexString(url == null ? 0 : url.hashCode()) + SUFFIX;
    }

    static Bitmap load(Context c, String title, String url) {
        if (url == null || url.isEmpty()) return null;
        File f = new File(dir(c), name(title, url));
        if (!f.exists()) return null;
        try {
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm != null) return bm;
        } catch (Exception e) {
            Log.w(TAG, "poster load: " + e);
        }
        f.delete();                 // corrupt: do not keep poisoning the cache
        return null;
    }

    static void save(Context c, String title, String url, Bitmap bm) {
        if (url == null || url.isEmpty() || bm == null) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(dir(c), name(title, url)));
            bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (Exception e) {
            Log.w(TAG, "poster save: " + e);
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Drop everything belonging to titles that are no longer in the card: their
     * OMDb text and their poster files. Called with the titles still on screen, so
     * it can only delete what has genuinely gone.
     */
    static void prune(Context c, Set<String> keep) {
        try {
            File[] files = dir(c).listFiles();
            int removed = 0;
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    int dash = n.lastIndexOf('-');
                    String title = dash > 0 ? n.substring(0, dash) : n.replace(SUFFIX, "");
                    if (!keep.contains(title)) {
                        if (f.delete()) removed++;
                    }
                }
            }
            int keys = Ratings.prune(c, keep);
            if (removed > 0 || keys > 0) {
                Log.i(TAG, "cache prune: " + removed + " posters, " + keys + " titles");
            }
        } catch (Exception e) {
            Log.w(TAG, "prune: " + e);
        }
    }

    static Set<String> keySet(Set<String> titles) {
        Set<String> out = new java.util.HashSet<String>();
        for (String t : titles) out.add(key(t));
        return out;
    }
}
