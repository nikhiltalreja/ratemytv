package dev.ratemytv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WATCH THESE: 2026 series above 7.0 on IMDb, each one actually on JioHotstar.
 *
 * The list is joined live, at runtime, from two public sources:
 *
 * <ol>
 *   <li><b>The index.</b> JioHotstar's show sitemap (id -> slug, 4 871 shows). It is
 *       already fetched and cached by {@link Sitemap}; this class only reads the map,
 *       so the panel adds no sitemap traffic of its own once the map exists.</li>
 *   <li><b>The scored list.</b> A public, keyless ratings catalogue, the same one the
 *       card already uses for scores OMDb lacks. One POST per 100 titles, three pages,
 *       asking for title, year, three genres and the score and vote count only: about
 *       65 KB for 300 shows. Those scores are the IMDb numbers, which is why the panel
 *       can say "IMDb" without holding an IMDb key.</li>
 * </ol>
 *
 * Everything is text: score, votes, year, genres, link. No plot, no cast, no poster,
 * no per-title lookup. The joined result is cached for {@link #TTL_MS} so opening the
 * tab repeatedly costs nothing.
 *
 * If the network is down the last good list is used, and on a first run with neither
 * network nor cache, {@link #SEED} keeps the panel from being empty.
 */
public class WatchThese {

    static final String TAG = "RATEMYTV_WT";
    static final String ENDPOINT = "https://apis.justwatch.com/graphql";
    static final String PREFS = "watchthese";
    /** Bump when the join rules change, so an old list cannot outlive them. */
    static final String V = "4";
    static final String KEY = "list" + V;
    static final String STAMP = "at" + V;
    static final String RETRY = "retryAt" + V;

    /**
     * Everything that qualifies is kept, not a top five: the fetch already paid for
     * the whole page, and a fifteen-row shelf is more useful than a five-row one. The
     * cap only bounds the prefs blob (a row is ~120 bytes; the catalogue tops out
     * around 20 at these thresholds).
     */
    static final int MAX_ROWS = 50;
    /** "Above 7.0 stars", strictly: a 7.0 exactly would be a coin flip. */
    static final double MIN_RATING = 7.0;
    /**
     * Below this a 9.x is three friends and a bot, not a rating. 1 000 also keeps a
     * 507-vote documentary from outranking a 7 600-vote series on a 0.1 score edge.
     */
    static final int MIN_VOTES = 1000;
    static final long TTL_MS = 12L * 60 * 60 * 1000;
    /** After a failed fetch, wait this long before trying again. */
    static final long RETRY_MS = 15L * 60 * 1000;

    static final String QUERY =
            "query S($country: Country!, $language: Language!, $offset: Int!) {"
            + " popularTitles(country: $country, first: 100, offset: $offset,"
            + " filter: {objectTypes: [SHOW], releaseYear: {min: 2026, max: 2026}}) {"
            + " edges { node { content(country: $country, language: $language) {"
            + " title originalReleaseYear genres { translation(language: $language) }"
            + " scoring { imdbScore imdbVotes } } } } } }";

    /**
     * Pages of 100 to ask for. The Testaments sits around #200-300 of the 2026
     * popularity order, so two pages missed it; three cost about 65 KB and catch it.
     */
    static final int PAGES = 3;

    static volatile boolean busy;

    static final class Pick {
        final String title;
        final String imdb;
        final long votes;
        final String genres;
        final int year;
        /** 0 for a series that started in 2026; >0 for a returning show's new season. */
        final int season;
        final String url;

        Pick(String title, String imdb, long votes, String genres, int year, String url) {
            this(title, imdb, votes, genres, year, 0, url);
        }

        Pick(String title, String imdb, long votes, String genres, int year, int season,
             String url) {
            this.title = title;
            this.imdb = imdb;
            this.votes = votes;
            this.genres = genres;
            this.year = year;
            this.season = season;
            this.url = url;
        }

        String votesText() {
            return String.format(Locale.US, "%,d", votes);
        }
    }

    // ------------------------------------------------------------- cache

    static List<Pick> cached(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            return parse(new JSONArray(s));
        } catch (Exception e) {
            return new ArrayList<Pick>();
        }
    }

    static long cachedAt(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(STAMP, 0);
    }

    static boolean stale(Context c) {
        long at = cachedAt(c);
        return at == 0 || System.currentTimeMillis() - at > TTL_MS;
    }

    static void save(Context c, List<Pick> picks) {
        JSONArray arr = new JSONArray();
        for (Pick p : picks) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", p.title);
                o.put("r", p.imdb);
                o.put("v", p.votes);
                o.put("g", p.genres);
                o.put("y", p.year);
                o.put("u", p.url);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString())
                .putLong(STAMP, System.currentTimeMillis())
                .apply();
    }

    static List<Pick> parse(JSONArray arr) {
        List<Pick> out = new ArrayList<Pick>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("t", "");
            String u = o.optString("u", "");
            if (t.isEmpty() || u.isEmpty()) continue;
            out.add(new Pick(t, o.optString("r", "?"), o.optLong("v", 0),
                    o.optString("g", ""), o.optInt("y", 2026), u));
        }
        return out;
    }

    // ------------------------------------------------------------ refresh

    /** Fetch in the background when the cache is older than {@link #TTL_MS}. */
    static void refresh(final Context c, boolean force) {
        if (busy) return;
        long now = System.currentTimeMillis();
        if (!force) {
            if (!stale(c)) return;
            // A failed fetch (offline, catalogue down) must not refetch the sitemap
            // on every open - unless there is nothing to show at all, in which case
            // trying again is the only useful thing to do.
            boolean have = !cached(c).isEmpty();
            if (have && now < c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(RETRY, 0)) return;
        }
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<Pick> got = fetch(c);
                    if (got != null && !got.isEmpty()) {
                        save(c, got);
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .remove(RETRY).apply();
                        Log.i(TAG, "list refreshed: " + got.size());
                        OverlayController.theseUpdated();
                    } else {
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putLong(RETRY, System.currentTimeMillis() + RETRY_MS)
                                .apply();
                        Log.i(TAG, "refresh failed; kept " + cached(c).size());
                    }
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    /** The live join. Null when nothing usable came back. */
    static List<Pick> fetch(Context c) {
        JSONObject index = Sitemap.map(c);
        if (index.length() < 200) {
            // First run: the sitemap has not landed yet. Fetch it here rather than
            // draw an empty panel; 624 KB, once, and Sitemap.refresh() will cache it.
            Log.i(TAG, "index empty; fetching sitemap");
            index = Sitemap.parse(c, Sitemap.DEFAULT_SHOWS);
        }
        if (index == null || index.length() < 200) return null;
        Log.i(TAG, "index " + index.length() + " shows");

        Map<String, String[]> byTitle = new HashMap<String, String[]>();
        Iterator<String> ids = index.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            String slug = index.optString(id, "");
            String name = Sitemap.title(slug);
            if (name == null) continue;
            byTitle.put(norm(name), new String[]{slug, id});
        }

        List<Pick> all = new ArrayList<Pick>();
        boolean any = false;
        for (int page = 0; page < PAGES; page++) {
            String body = post(page * 100);
            if (body == null) continue;
            any = true;
            collect(body, byTitle, all);
        }
        if (!any) return null;
        Log.i(TAG, "matched " + all.size() + " rated shows on JioHotstar");

        Collections.sort(all, new Comparator<Pick>() {
            public int compare(Pick a, Pick b) {
                int byRating = Double.compare(dbl(b.imdb), dbl(a.imdb));
                if (byRating != 0) return byRating;
                return Long.compare(b.votes, a.votes);
            }
        });
        return all.size() > MAX_ROWS ? new ArrayList<Pick>(all.subList(0, MAX_ROWS)) : all;
    }

    /** Read one page, keep what is rated and present on JioHotstar. */
    static void collect(String body, Map<String, String[]> byTitle, List<Pick> out) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                Log.i(TAG, "collect: no data");
                return;
            }
            JSONObject popular = data.optJSONObject("popularTitles");
            if (popular == null) {
                Log.i(TAG, "collect: no popularTitles");
                return;
            }
            JSONArray edges = popular.optJSONArray("edges");
            if (edges == null) {
                Log.i(TAG, "collect: no edges");
                return;
            }

            for (int i = 0; i < edges.length(); i++) {
                // edges[i] is {"node": {"content": ...}}; both levels are needed.
                JSONObject edge = edges.optJSONObject(i);
                if (edge == null) continue;
                JSONObject node = edge.optJSONObject("node");
                if (node == null) continue;
                JSONObject content = node.optJSONObject("content");
                if (content == null) continue;

                JSONObject scoring = content.optJSONObject("scoring");
                if (scoring == null) continue;
                double rating = scoring.optDouble("imdbScore", 0);
                long votes = scoring.optLong("imdbVotes", 0);
                if (!(rating > MIN_RATING) || votes < MIN_VOTES) continue;

                String title = content.optString("title", "");
                if (title.isEmpty()) continue;

                String[] hit = byTitle.get(norm(title));
                if (hit == null) continue;

                String genres = genres(content.optJSONArray("genres"));
                String url = "https://www.hotstar.com/in/shows/" + hit[0] + "/" + hit[1];
                boolean dup = false;
                for (int j = 0; j < out.size(); j++) {
                    if (out.get(j).url.equals(url)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                out.add(new Pick(title, String.format(Locale.US, "%.1f", rating), votes,
                        genres, content.optInt("originalReleaseYear", 2026), url));
            }
        } catch (Exception e) {
            Log.w(TAG, "collect: " + e);
        }
    }

    static String genres(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length() && i < 3; i++) {
            JSONObject g = arr.optJSONObject(i);
            if (g == null) continue;
            String name = g.optString("translation", "");
            if (name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(name);
        }
        return sb.toString();
    }

    static String post(int offset) {
        HttpURLConnection conn = null;
        try {
            JSONObject variables = new JSONObject();
            variables.put("country", "IN");
            variables.put("language", "en");
            variables.put("offset", offset);
            JSONObject payload = new JSONObject();
            payload.put("query", QUERY);
            payload.put("variables", variables);

            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "RateMyTV/1.0");
            OutputStream out = conn.getOutputStream();
            out.write(payload.toString().getBytes("UTF-8"));
            out.close();
            if (conn.getResponseCode() != 200) {
                Log.i(TAG, "http " + conn.getResponseCode() + " offset " + offset);
                return null;
            }
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "post: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // --------------------------------------------------------------- join

    /**
     * "A Knight of the Seven Kingdoms" and "a-knight-of-the-seven-kingdoms" have to
     * land on the same key: case, punctuation, ampersands and a leading article are
     * the only differences the two catalogues ever have for the same show.
     */
    static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.US).replace("&", " and ")
                .replace("'", "").replace("\u2019", "");
        x = x.replaceFirst("^(the|a|an)\\s+", "");
        x = x.replaceAll("[^a-z0-9]+", " ").trim();
        return x.replaceAll("\\s+", " ");
    }

    static double dbl(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    // --------------------------------------------------------------- seed

    /**
     * Bundled fallback, read from IMDb/OMDb on {@link #SEED_CHECKED}. Only seen on a
     * first run that is offline; the live join replaces it on the first success.
     */
    static final String SEED_CHECKED = "26 October 2026";

    static List<Pick> seed() {
        List<Pick> out = new ArrayList<Pick>();
        out.add(new Pick("A Knight of the Seven Kingdoms", "8.6", 289175,
                "Adventure \u00B7 Drama \u00B7 Fantasy", 2026,
                "https://www.hotstar.com/in/shows/a-knight-of-the-seven-kingdoms/1271461884"));
        out.add(new Pick("Star Wars: Maul - Shadow Lord", "8.5", 31215,
                "Animation \u00B7 Action \u00B7 Adventure", 2026,
                "https://www.hotstar.com/in/shows/star-wars-maul-shadow-lord/1271515796"));
        out.add(new Pick("The Testaments", "8.2", 14269,
                "Drama \u00B7 Thriller", 2026,
                "https://www.hotstar.com/in/shows/the-testaments/1271542058"));
        out.add(new Pick("Chiraiya", "8.0", 15254,
                "Drama \u00B7 Hindi", 2026,
                "https://www.hotstar.com/in/shows/chiraiya/1271526656"));
        out.add(new Pick("Lanterns", "7.8", 65291,
                "Action \u00B7 Crime \u00B7 Drama", 2026,
                "https://www.hotstar.com/in/shows/lanterns/1271680756"));
        return out;
    }

    // --------------------------------------------------------- new seasons

    /**
     * Returning series whose latest season premiered in 2026, all on JioHotstar.
     *
     * This row is by hand because the data cannot find it. The live join filters on
     * the series' original release year, and JustWatch reports "House of the Dragon"
     * as 2022 however new season 3 is — the public schema exposes no season dates and
     * no offers. Qualifying on the Hotstar page's lastmod instead drags in Game of
     * Thrones, Friends and Loki, whose pages were merely touched this year.
     *
     * Each entry was confirmed against IMDb episode dates via OMDb on
     * {@link #RETURNING_CHECKED}: HOTD S3 (Jun 2026), Euphoria S3, Jujutsu Kaisen S3,
     * MobLand S2, The Bear S5. Scores and vote counts were read the same day.
     */
    static final String RETURNING_CHECKED = "26 October 2026";

    static List<Pick> returning() {
        List<Pick> out = new ArrayList<Pick>();
        out.add(new Pick("The Bear", "8.5", 329129, "Comedy \u00B7 Drama", 2022, 5,
                "https://www.hotstar.com/in/shows/the-bear/1260108974"));
        out.add(new Pick("Jujutsu Kaisen", "8.5", 224926,
                "Action & Adventure \u00B7 Animation \u00B7 Fantasy", 2020, 3,
                "https://www.hotstar.com/in/shows/jujutsu-kaisen/1271461634"));
        out.add(new Pick("MobLand", "8.3", 136487, "Crime \u00B7 Drama", 2025, 2,
                "https://www.hotstar.com/in/shows/mobland/1271397631"));
        out.add(new Pick("House of the Dragon", "8.2", 576151,
                "Action & Adventure \u00B7 Drama \u00B7 Fantasy", 2022, 3,
                "https://www.hotstar.com/in/shows/house-of-the-dragon/1971002877"));
        out.add(new Pick("Euphoria", "8.1", 327247, "Drama", 2019, 3,
                "https://www.hotstar.com/in/shows/euphoria/1971002893"));
        return out;
    }
}
