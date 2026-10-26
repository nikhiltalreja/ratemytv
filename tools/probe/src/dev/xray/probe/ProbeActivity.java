package dev.xray.probe;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

public class ProbeActivity extends Activity {
    static final String TAG = "XRAY_PROBE";
    final StringBuilder sb = new StringBuilder();
    TextView tv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(10);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackgroundColor(0xDD000000);
        tv.setPadding(20, 20, 20, 20);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
        new Thread(new Runnable() {
            public void run() { ProbeActivity.this.run(); }
        }).start();
    }

    void out(String s) {
        Log.i(TAG, s);
        sb.append(s).append('\n');
        runOnUiThread(new Runnable() {
            public void run() { tv.setText(sb.toString()); }
        });
    }

    void run() {
        out("perm=" + (checkSelfPermission("android.permission.READ_TV_LISTINGS") == 0));
        row(getIntent().getStringExtra("needle") == null ? "Captive" : getIntent().getStringExtra("needle"));
        
        out("DONE");
    }

    /** Every non-empty column of the row whose title contains the given text. */
    void row(String needle) {
        try (Cursor c = getContentResolver().query(
                Uri.parse("content://android.media.tv/watch_next_program"),
                null, null, null, null)) {
            if (c == null) { out("null cursor"); return; }
            // A needle of "**" dumps every row with every non-empty column.
            boolean all = "**".equals(needle) || "ALL".equalsIgnoreCase(needle);
            while (c.moveToNext()) {
                String t = str(c, "title");
                if (!all && (t == null || !t.toLowerCase().contains(needle.toLowerCase()))) continue;
                out("=== " + t);
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String v = null;
                    try { v = c.getString(i); } catch (Exception e) { v = "<err>"; }
                    if (v != null && !v.isEmpty()) out("   " + c.getColumnName(i) + " = " + v);
                }
                if (!all) break;
            }
        } catch (Exception e) {
            out("EX " + e);
        }
    }

    static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 ? "?" : c.getString(i);
    }

    static long num(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 ? -1 : c.getLong(i);
    }

    void scan(String table, String pkgWanted) {
        int shown = 0;
        String sort = table.equals("preview_program") ? null : "last_engagement_time_utc_millis DESC";
        try (Cursor c = getContentResolver().query(
                Uri.parse("content://android.media.tv/" + table),
                null, null, null, sort)) {
            if (c == null) { out(table + ": null cursor"); return; }
            out("[" + table + "] rows=" + c.getCount() + " cols=" + c.getColumnCount());
            java.util.Map<String, Integer> pkgs = new java.util.TreeMap<>();
            int seen = 0;
            while (c.moveToNext() && seen < 60000) {
                seen++;
                String pkg = str(c, "package_name");
                Integer prevCount = pkgs.get(pkg);
                pkgs.put(pkg, prevCount == null ? 1 : prevCount + 1);
                String t = str(c, "title");
                boolean mine = pkgWanted.equals(pkg);
                boolean mick = t != null && t.toLowerCase().contains("mick");
                if (!mine && !mick) continue;
                if (shown++ >= 12) continue;
                out("  " + table + " title=" + t
                        + " pos=" + num(c, "last_playback_position_millis")
                        + " dur=" + num(c, "duration_millis")
                        + " eng=" + num(c, "last_engagement_time_utc_millis")
                        + " type=" + num(c, "type"));
            }
            out("  publishers(" + seen + "): " + pkgs);
        } catch (Exception e) {
            out(table + " EX " + e);
        }
    }
}
