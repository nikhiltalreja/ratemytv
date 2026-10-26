package dev.ratemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bookmarks are "scenes": a title plus a position in it. They live in their own
 * preferences file so clearing history does not wipe them, and they survive a
 * reinstall of the streaming app.
 */
public class Bookmarks {
    static final String PREFS = "bookmarks";
    static final String BLOB = "v1";

    static JSONObject all(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BLOB, "{}");
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject o) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(BLOB, o.toString()).apply();
    }

    /** Newest first. */
    static JSONArray forTitle(Context c, String title) {
        JSONArray out = new JSONArray();
        if (title == null) return out;
        try {
            JSONArray arr = all(c).optJSONArray(key(title));
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) out.put(arr.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    static int count(Context c, String title) {
        return forTitle(c, title).length();
    }

    /** True when a scene was stored. False means it was a duplicate or unusable. */
    static boolean add(Context c, String title, long pos, long dur) {
        if (title == null || pos <= 0) return false;
        try {
            JSONObject o = all(c);
            JSONArray arr = o.optJSONArray(key(title));
            if (arr == null) arr = new JSONArray();
            // Guards an accidental double press only. Thirty seconds used to swallow
            // real second bookmarks placed a few seconds apart.
            for (int i = 0; i < arr.length(); i++) {
                long p = arr.getJSONObject(i).optLong("pos", -1);
                if (Math.abs(p - pos) < 1500) return false;
            }
            JSONObject b = new JSONObject();
            b.put("pos", pos);
            b.put("dur", dur);
            b.put("ts", System.currentTimeMillis());
            arr.put(b);
            // Two scenes per title is the cap: each one costs a line of height in the
            // card, and the third pushed the row taller than the poster.
            while (arr.length() > 2) arr.remove(0);
            o.put(key(title), arr);
            save(c, o);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void remove(Context c, String title, long pos) {
        if (title == null) return;
        try {
            JSONObject o = all(c);
            JSONArray arr = o.optJSONArray(key(title));
            if (arr == null) return;
            JSONArray keep = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.getJSONObject(i);
                if (Math.abs(b.optLong("pos", -1) - pos) < 1000) continue;
                keep.put(b);
            }
            if (keep.length() == 0) o.remove(key(title));
            else o.put(key(title), keep);
            save(c, o);
        } catch (Exception ignored) {
        }
    }

    /** Keys are normalised titles so "MobLand" and "mobland " land together. */
    static String key(String title) {
        return Ratings.norm(title);
    }

    /** Every title that has at least one scene saved, so the cache never evicts one. */
    static java.util.List<String> titles(Context c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            org.json.JSONObject o = all(c);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) out.add(it.next());
        } catch (Exception ignored) {
        }
        return out;
    }
}
