package dev.ratemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the project's latest release and hands the APK to Android's own installer.
 *
 * The install itself is deliberately not ours: the system dialog shows the signature
 * and permissions, and an app that can silently replace itself is a worse idea than
 * one extra press. Because the release is signed with the same key as the installed
 * copy, Android treats it as an upgrade and keeps the data.
 */
public class Updater {
    static final String TAG = "RATEMYTV_UPD";
    static final String API =
            "https://api.github.com/repos/nikhiltalreja/ratemytv/releases/latest";

    static volatile String latestTag;
    static volatile long checkedAt;
    static volatile String apkUrl;
    static volatile boolean checking;
    static volatile boolean downloading;

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /** "v1.9.4" vs "1.9.3" -> true when the release is newer. */
    static boolean newer(String tag, String installed) {
        if (tag == null || installed == null) return false;
        String a = tag.replaceAll("^[vV]", "");
        String[] x = a.split("\\."), y = installed.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0;
            int q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean available(Context c) {
        return newer(latestTag, installedVersion(c)) && apkUrl != null;
    }

    static final long RECHECK_MS = 2L * 60 * 60 * 1000;
    /** Where the last answer is kept, so a restart does not count as a reason to ask. */
    static final String PREFS = "update";

    static void check(final Context c) {
        if (checking) return;
        // latestTag and checkedAt were memory-only, so every launch started blank and
        // called GitHub again - one request per app start for an answer that changes
        // once a release. Remember the answer and the time across processes.
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (latestTag == null) latestTag = p.getString("tag", null);
        if (apkUrl == null) apkUrl = p.getString("url", null);
        if (checkedAt == 0) checkedAt = p.getLong("at", 0);
        if (latestTag != null && System.currentTimeMillis() - checkedAt < RECHECK_MS) return;
        checking = true;
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(API).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    Http.json(conn);
                    if (conn.getResponseCode() != 200) {
                        Log.i(TAG, "release check: http " + conn.getResponseCode());
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    InputStream in = conn.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    in.close();
                    JSONObject rel = new JSONObject(sb.toString());
                    latestTag = rel.optString("tag_name");
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if ("ratemytv.apk".equals(a.optString("name"))) {
                                apkUrl = a.optString("browser_download_url");
                            }
                        }
                    }
                    checkedAt = System.currentTimeMillis();
                    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("tag", latestTag)
                            .putString("url", apkUrl)
                            .putLong("at", checkedAt).apply();
                    Log.i(TAG, "latest=" + latestTag + " installed=" + installedVersion(c)
                            + " update=" + available(c));
                } catch (Exception e) {
                    Log.w(TAG, "release check: " + e);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /**
     * Download, then open the system installer. The caller should dismiss its own
     * overlay first so nothing of ours is focused when the dialog appears.
     */
    static void install(final Context c, final Runnable onDone) {
        if (apkUrl == null || downloading) return;
        downloading = true;
        new Thread(new Runnable() {
            public void run() {
                File out = new File(c.getCacheDir(), "ratemytv-update.apk");
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    Http.browser(conn);
                    InputStream in = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                    in.close();
                    Log.i(TAG, "downloaded " + out.length() + " bytes");

                    final Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse("content://dev.ratemytv.apk/update.apk"),
                            "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            if (onDone != null) onDone.run();
                            try {
                                c.startActivity(i);
                            } catch (Exception e) {
                                Log.w(TAG, "installer: " + e);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "download: " + e);
                } finally {
                    if (conn != null) conn.disconnect();
                    downloading = false;
                }
            }
        }).start();
    }

    static File apkFile(Context c) {
        return new File(c.getCacheDir(), "ratemytv-update.apk");
    }
}
