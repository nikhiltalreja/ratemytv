package dev.ratemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

/**
 * The Xiaomi remote's Netflix key is consumed by mitv.service as
 * android.intent.action.GLOBAL_BUTTON. If that broadcast is implicit we also see it
 * and can time the hold (key repeats while pressed).
 */
public class GlobalButtonReceiver extends BroadcastReceiver {
    static final String TAG = "RATEMYTV_KEY";
    static final long LONG_MS = 350;
    static long downAt;
    static int lastCode = -1;

    @Override public void onReceive(Context ctx, Intent intent) {
        Log.i(TAG, "action=" + intent.getAction());
        if ("dev.ratemytv.DUMP".equals(intent.getAction())) {
            dump(ctx);
            return;
        }
        if ("dev.ratemytv.SETKEY".equals(intent.getAction())) {
            String k = intent.getStringExtra("key");
            ctx.getSharedPreferences("xray", Context.MODE_PRIVATE).edit()
                    .putString("omdb", k == null ? "" : k.trim()).apply();
            Log.i(TAG, "omdb key set: " + (k == null ? "cleared" : k.trim().length() + " chars"));
            return;
        }
        if ("dev.ratemytv.FAKEUPDATE".equals(intent.getAction())) {
            // Render the update UI without cutting a release, for checking contrast.
            String tag = intent.getStringExtra("tag");
            Updater.latestTag = tag;
            Updater.apkUrl = "https://example.invalid/none.apk";
            Log.i(TAG, "fake update " + tag);
            return;
        }
        if ("dev.ratemytv.CLEARRATINGS".equals(intent.getAction())) {
            ctx.getSharedPreferences("ratings_cache", Context.MODE_PRIVATE).edit().clear().apply();
            Log.i(TAG, "ratings cache cleared");
            return;
        }
        if ("dev.ratemytv.REFRESHMAP".equals(intent.getAction())) {
            Sitemap.refresh(ctx, true);
            Log.i(TAG, "map refresh forced");
            return;
        }
        if ("dev.ratemytv.RENAME".equals(intent.getAction())) {
            // A show with no provider row has no name anywhere on the device: the
            // stream URL carries ids and the session carries only the episode label.
            // This renames the stored record, which also lets OMDb match it.
            String from = intent.getStringExtra("from");
            String to = intent.getStringExtra("to");
            if (from != null && to != null && !to.trim().isEmpty()) {
                rename(ctx, from, to.trim());
            }
            return;
        }
        if ("dev.ratemytv.FORGET".equals(intent.getAction())) {
            // Remove one wrongly learned row. Prime publishes no metadata while it
            // plays, so the card guesses from the provider and can guess wrong; this
            // is the escape hatch for that, by title.
            String title = intent.getStringExtra("title");
            if (title != null) {
                int n = Watcher.forgetRow(ctx, title);
                Log.i(TAG, "forgot " + n + " row(s) for " + title);
            }
            return;
        }
        if ("dev.ratemytv.CLEARHIST".equals(intent.getAction())) {
            ctx.getSharedPreferences("xray", Context.MODE_PRIVATE).edit()
                    .remove("local2").apply();
            Log.i(TAG, "history cleared");
            return;
        }
        if ("dev.ratemytv.LAYOUT".equals(intent.getAction())) {
            int m = "cards".equalsIgnoreCase(intent.getStringExtra("mode")) ? 1 : 0;
            ctx.getSharedPreferences("xray", Context.MODE_PRIVATE).edit()
                    .putInt("layout", m).apply();
            Log.i(TAG, "layout -> " + (m == 0 ? "list" : "cards"));
            return;
        }
        if ("dev.ratemytv.THEME".equals(intent.getAction())) {
            boolean lightTheme = "light".equalsIgnoreCase(intent.getStringExtra("mode"));
            Log.i(TAG, "theme -> " + (lightTheme ? "light" : "dark"));
            // Same path the Settings chips take: write the pref, then repaint. Writing
            // the pref alone left an open card unchanged, so the card and the setting
            // disagreed until the next launch.
            OverlayController.setLight(lightTheme);
            return;
        }
        KeyEvent ev = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (ev == null) {
            Log.i(TAG, "GLOBAL_BUTTON (no key event) action=" + intent.getAction());
            return;
        }
        Log.i(TAG, "GLOBAL_BUTTON code=" + ev.getKeyCode() + " action=" + ev.getAction()
                + " repeat=" + ev.getRepeatCount());
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() == 0 || lastCode != ev.getKeyCode()) {
                downAt = SystemClock.uptimeMillis();
                lastCode = ev.getKeyCode();
            }
        } else if (ev.getAction() == KeyEvent.ACTION_UP) {
            long held = SystemClock.uptimeMillis() - downAt;
            lastCode = -1;
            if (held >= LONG_MS) {
                Log.i(TAG, "GLOBAL_LONG " + held + "ms -> overlay");
                OverlayController.show(ctx);
            }
        }
    }

    /** Writes what the card would render to a file the browser preview can poll. */
    static void dump(Context ctx) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("pkg", Watcher.CUR_PKG == null ? "" : Watcher.CUR_PKG);
            o.put("title", Watcher.CUR_TITLE == null ? "" : Watcher.CUR_TITLE);
            o.put("pos", Watcher.CUR_POS);
            o.put("playing", Watcher.CUR_PLAYING);
            o.put("now", System.currentTimeMillis());

            org.json.JSONArray rows = new org.json.JSONArray();
            try (android.database.Cursor c = ctx.getContentResolver().query(
                    android.net.Uri.parse("content://android.media.tv/watch_next_program"),
                    new String[]{"package_name", "title", "last_playback_position_millis",
                            "duration_millis", "poster_art_uri",
                            "last_engagement_time_utc_millis"},
                    null, null, "last_engagement_time_utc_millis DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        org.json.JSONObject r = new org.json.JSONObject();
                        r.put("t", c.getString(1));
                        r.put("pkg", c.getString(0));
                        r.put("pos", c.getLong(2));
                        r.put("dur", c.getLong(3));
                        r.put("poster", c.getString(4) == null ? "" : c.getString(4));
                        r.put("eng", c.getLong(5));
                        rows.put(r);
                    }
                }
            }
            o.put("rows", rows);

            org.json.JSONArray local = new org.json.JSONArray();
            org.json.JSONObject raw = new org.json.JSONObject(
                    ctx.getSharedPreferences("xray", Context.MODE_PRIVATE)
                            .getString("local2", "{}"));
            for (Watcher.Item it : Watcher.localRows(ctx, 0)) {
                org.json.JSONObject r = new org.json.JSONObject();
                r.put("t", it.title);
                org.json.JSONObject rec = raw.optJSONObject(it.title);
                if (rec != null) {
                    r.put("sid", rec.optString("sid", ""));
                    r.put("epid", rec.optString("epid", ""));
                }
                r.put("pkg", it.pkg);
                r.put("pos", it.pos);
                r.put("dur", it.dur);
                local.put(r);
            }
            o.put("local", local);

            // Cache health, so "is it actually caching?" is answerable without root.
            org.json.JSONObject cache = new org.json.JSONObject();
            java.io.File[] posters = Cache.dir(ctx).listFiles();
            cache.put("posters", posters == null ? 0 : posters.length);
            cache.put("omdbKeys", Ratings.keyCount(ctx));
            o.put("cache", cache);

            java.io.File f = new java.io.File(ctx.getExternalFilesDir(null), "dump.json");
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(o.toString());
            w.close();
            Log.i("RATEMYTV_DUMP", "wrote " + rows.length() + " rows");
        } catch (Exception e) {
            Log.w("RATEMYTV_DUMP", "dump failed: " + e);
        }
    }

    /** Move a stored record to a new title, keeping its position, episode and time. */
    static void rename(Context ctx, String from, String to) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences("xray", Context.MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString("local2", "{}"));
            org.json.JSONObject rec = null;
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (k.equalsIgnoreCase(from)) {
                    rec = all.getJSONObject(k);
                    all.remove(k);
                    break;
                }
            }
            if (rec == null) {
                Log.i(TAG, "rename: no record for " + from);
                return;
            }
            all.put(to, rec);
            p.edit().putString("local2", all.toString()).apply();
            Log.i(TAG, "rename: " + from + " -> " + to);
        } catch (Exception e) {
            Log.w(TAG, "rename: " + e);
        }
    }
}
