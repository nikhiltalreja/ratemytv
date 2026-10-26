package dev.ratemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Show names from JioHotstar's public sitemap.
 *
 * The sitemap filename is not a contract: it has appeared as new-sitemap-SHOWS-1.xml
 * inside an index, and it can be renamed, split, or corrupt. So the fetch is
 * <ol>
 *   <li>discover the sitemap URLs from robots.txt, then from the index it names;</li>
 *   <li>fall back to the last known good URL, then to the historical default;</li>
 *   <li>parse without touching the existing map;</li>
 *   <li>replace the map only if the parse produced a plausible number of shows.</li>
 * </ol>
 * If every source fails, the previous map stays exactly as it was. If there is no map
 * at all, callers fall back to the episode label, which is what the app did before
 * this feature existed - naming is an enhancement, never a dependency.
 */
public class Sitemap {
    static final String TAG = "RATEMYTV_MAP";
    static final String PREFS = "sitemap";
    static final String KEY = "shows";
    static final String STAMP = "fetched";
    static final String LAST_URL = "lastUrl";
    /** Last-Modified of the file we are holding, sent back as If-Modified-Since. */
    static final String LAST_LM = "lastModified";
    static final String RETRY = "retryAt";

    static final String ROBOTS = "https://www.hotstar.com/robots.txt";
    static final String DEFAULT_INDEX = "https://www.hotstar.com/in/new-sitemap.xml";
    static final String DEFAULT_SHOWS =
            "https://www.hotstar.com/in/new-sitemap-SHOWS-1.xml";

    /**
     * A day, not a week. This map is now also the "is it on JioHotstar?" check for
     * Watch These, so a title added to the catalogue today should become linkable by
     * tomorrow, not next week. The file is 624 KB, so a daily refetch is cheap; the
     * retry stamp below still keeps a broken fetch from hammering the site.
     */
    static final long TTL_MS = 24L * 60 * 60 * 1000;
    static final long RETRY_MS = 6L * 60 * 60 * 1000;
    /** A miss may refetch, but not more often than this. */
    static final long MISS_MS = 60L * 60 * 1000;
    /** Below this, a parse is treated as broken rather than small. */
    static final int MIN_ROWS = 200;

    /** "koffee-with-karan" -> "Koffee with Karan". */
    static String title(String slug) {
        if (slug == null || slug.isEmpty()) return null;
        String[] parts = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (i > 0 && (p.equals("with") || p.equals("of") || p.equals("the")
                    || p.equals("and") || p.equals("a") || p.equals("in")
                    || p.equals("to") || p.equals("for"))) {
                sb.append(p);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) sb.append(p.substring(1));
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static JSONObject map(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Name for a show id, or null when the map has no entry (caller falls back).
     *
     * A miss is a reason to refetch: a series that started after the map was built is
     * not in it, and waiting a week for the TTL would leave that title unnamed for a
     * week. Rate-limited so an unknown id cannot refetch on every frame.
     */
    static String nameFor(Context c, String showId) {
        if (showId == null || showId.isEmpty()) return null;
        String slug = map(c).optString(showId, "");
        if (!slug.isEmpty()) return title(slug);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("missAt", 0) > MISS_MS) {
            p.edit().putLong("missAt", now).apply();
            refresh(c, true);
        }
        return null;
    }

    /** True once after a fetch replaced the map, so callers can re-render names. */
    static boolean consumeUpdated() {
        boolean u = updated;
        updated = false;
        return u;
    }

    static volatile boolean updated;

    static boolean stale(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now < p.getLong(RETRY, 0)) return false;
        long at = p.getLong(STAMP, 0);
        if (at == 0) return true;
        // A lookup miss on an old map is a reason to try again sooner than a week.
        return now - at > TTL_MS;
    }

    static void refresh(final Context c, final boolean force) {
        if (!force && !stale(c)) return;
        new Thread(new Runnable() {
            public void run() {
                List<String> sources = sources(c);
                JSONObject old = map(c);
                for (String url : sources) {
                    JSONObject built = parse(c, url);
                    if (built == null && lastStatus == 304) {
                        // Unchanged upstream: keep the map and move the clock forward.
                        // This used to fall through to the "no usable sitemap" path and
                        // set a retry stamp, so a healthy 304 looked like a failure.
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putLong(STAMP, System.currentTimeMillis())
                                .apply();
                        Log.i(TAG, "map unchanged; fresh for another day");
                        return;
                    }
                    if (built != null && built.length() >= MIN_ROWS) {
                        try {
                            // Keep anything the new file dropped, so a partial or
                            // reorganised sitemap cannot shrink what we know.
                            java.util.Iterator<String> it = old.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                if (!built.has(k)) built.put(k, old.optString(k));
                            }
                            SharedPreferences.Editor e = c.getSharedPreferences(PREFS,
                                    Context.MODE_PRIVATE).edit();
                            e.putString(KEY, built.toString());
                            e.putLong(STAMP, System.currentTimeMillis());
                            e.putString(LAST_URL, url);
                            // Remembered so the next refresh can ask "still the same?"
                            // instead of pulling 624 KB to find out.
                            e.putString(LAST_LM, lastModified == null ? "" : lastModified);
                            e.remove(RETRY);
                            e.apply();
                            updated = true;
                            Log.i(TAG, "map " + built.length() + " shows from " + url);
                            return;
                        } catch (Exception e) {
                            Log.w(TAG, "save: " + e);
                        }
                    }
                }
                // Nothing usable: keep the old map, and do not hammer the site.
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(RETRY, System.currentTimeMillis() + RETRY_MS).apply();
                Log.w(TAG, "no usable sitemap; kept " + old.length() + " names");
            }
        }).start();
    }

    /** robots.txt -> index -> SHOWS parts, then the last good URL, then the default. */
    static List<String> sources(Context c) {
        List<String> out = new ArrayList<String>();
        String robots = fetch(ROBOTS, 8000);
        if (robots != null) {
            addIn(out, sitemaps(robots));
        }
        for (String index : new ArrayList<String>(out)) {
            if (index.endsWith(".xml")) {
                String body = fetch(index, 12000);
                if (body != null && body.contains("<sitemapindex")) {
                    addIn(out, showsParts(body));
                }
            }
        }
        String last = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LAST_URL, "");
        if (!last.isEmpty()) addIn(out, java.util.Collections.singletonList(last));
        addIn(out, java.util.Collections.singletonList(DEFAULT_SHOWS));
        addIn(out, java.util.Collections.singletonList(DEFAULT_INDEX));
        return out;
    }

    static void addIn(List<String> out, List<String> more) {
        for (String s : more) if (!out.contains(s)) out.add(s);
    }

    static List<String> sitemaps(String robots) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("Sitemap:\\s*(https://\\S+)", Pattern.CASE_INSENSITIVE)
                .matcher(robots);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    static List<String> showsParts(String indexBody) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("<loc>([^<]+)</loc>").matcher(indexBody);
        while (m.find()) {
            String u = m.group(1);
            if (u.toUpperCase().contains("-SHOWS-")) out.add(u);
        }
        return out;
    }

    /** Parse one file; never writes anything. Null when unusable. */
    static JSONObject parse(Context c, String url) {
        // Ask conditionally when this is the file we already have: the server answers
        // 304 while it is untouched, so a day-to-day refresh moves no body at all.
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String knownUrl = p.getString(LAST_URL, "");
        String body = fetch(url, 30000, url.equals(knownUrl) ? p.getString(LAST_LM, null) : null);
        if (body == null) return null;
        if (!body.contains("<loc>")) {
            Log.i(TAG, "no <loc> in " + url);
            return null;
        }
        try {
            JSONObject out = new JSONObject();
            Matcher m = Pattern.compile("hotstar\\.com/[a-z]{2}/shows/([a-z0-9\\-]+)/(\\d+)")
                    .matcher(body);
            while (m.find()) out.put(m.group(2), m.group(1));
            return out.length() == 0 ? null : out;
        } catch (Exception e) {
            Log.w(TAG, "parse " + url + ": " + e);
            return null;
        }
    }

    static String fetch(String url, int timeoutMs) {
        return fetch(url, timeoutMs, null);
    }

    /** HTTP status of the last fetch, so a caller can tell 304 from a failure. */
    static volatile int lastStatus;
    /** Last-Modified of the file just downloaded, kept as the next conditional header. */
    static volatile String lastModified;

    static String fetch(String url, int timeoutMs, String ifModifiedSince) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(timeoutMs);
            if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
                conn.setRequestProperty("If-Modified-Since", ifModifiedSince);
            }
            int code = conn.getResponseCode();
            lastStatus = code;
            if (code == 304) {
                // Nothing changed upstream. Its ETag is no use here (the server answers
                // 200 for it), but Last-Modified works: an unchanged 624 KB sitemap
                // costs one round trip and no body.
                Log.i(TAG, "unchanged (304) " + url);
                return null;
            }
            if (code != 200) {
                Log.i(TAG, url + " -> http " + code);
                return null;
            }
            lastModified = conn.getHeaderField("Last-Modified");
            StringBuilder sb = new StringBuilder();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "fetch " + url + ": " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
