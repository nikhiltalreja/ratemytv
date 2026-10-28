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
    static final String V = "14";
    static final String KEY = "list" + V;
    static final String STAMP = "at" + V;
    static final String RETRY = "retryAt" + V;

    /**
     * Every title that qualifies is kept — no per-service cap.
     *
     * The shelf was capped at six per service to stop one catalogue swamping the rest,
     * but that also hid most of a service's catalogue behind a number nobody asked for.
     * MAX_ROWS is only a guard against a pathological response, not a curation rule.
     */
    static final int MAX_ROWS = 400;
    /** "Above 7.0 stars", strictly: a 7.0 exactly would be a coin flip. */
    static final double MIN_RATING = 6.5;
    /**
     * Below this a 9.x is three friends and a bot, not a rating. 1 000 also keeps a
     * 507-vote documentary from outranking a 7 600-vote series on a 0.1 score edge.
     */
    static final int MIN_VOTES = 1000;
    static final long TTL_MS = 12L * 60 * 60 * 1000;
    /** After a failed fetch, wait this long before trying again. */
    static final long RETRY_MS = 15L * 60 * 1000;

    /**
     * The services the panel lists, in display order. Columns are JustWatch's package
     * name, the panel's label, and the app packages that play it ("|" when the phone
     * and TV apps differ).
     *
     * JustWatch's reseller packages ("Amazon Prime Video with Ads", "SonyLIV Amazon
     * Channel", "Apple TV Amazon Channel") are deliberately absent: they are the same
     * catalogue reached through Amazon, not a separate place to watch.
     *
     * SonyLIV and MX Player were removed on request. Aha, Sun NXT, ETV Win and
     * ManoramaMAX are not listed because JustWatch carries no offers for them in this
     * catalogue — there is nothing to join against.
     */
    static final String[][] PROVIDERS = {
            {"JioHotstar", "JIOHOTSTAR", "in.startv.hotstar"},
            {"Netflix", "NETFLIX", "com.netflix.ninja"},
            {"Amazon Prime Video", "PRIME VIDEO", "com.amazon.amazonvideo.livingroom"},
            {"Zee5", "ZEE5", "com.zee5.hipi"},
            {"Apple TV", "APPLE TV", "com.apple.atve.androidtv.appletv"},
            {"Hoichoi", "HOICHOI", "com.hoichoi"},
            {"Crunchyroll", "CRUNCHYROLL", "com.crunchyroll.crunchyroid"},
    };

    /** How many release years the shelf covers, counting back from this one. */
    static final int YEARS_BACK = 3;

    static final String QUERY =
            "query S($country: Country!, $language: Language!, $offset: Int!,"
            + " $year: Int!) {"
            + " popularTitles(country: $country, first: 100, offset: $offset,"
            + " filter: {objectTypes: [SHOW], releaseYear: {min: $year, max: $year}}) {"
            + " edges { node { content(country: $country, language: $language) {"
            + " title originalReleaseYear runtime shortDescription ageCertification"
            + " genres { translation(language: $language) }"
            + " credits(first: 6) { name role }"
            + " posterUrl"
            + " scoring { imdbScore imdbVotes tomatoMeter } }"
            + " offers(country: $country, platform: WEB) {"
            + " package { clearName } standardWebURL presentationType"
            + " deeplinkURL(platform: ANDROID_TV) } } } } }";

    /**
     * Pages of 100 per year. Asking for the three years in one window would let 2026
     * popularity crowd out 2024 entirely, so each year is queried on its own; two pages
     * a year is the same depth 2026 had when it was the only year.
     */
    static final int PAGES_PER_YEAR = 2;

    /** The release years on the shelf, newest first: 2026, 2025, 2024 today. */
    static int[] years() {
        int thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        int[] out = new int[YEARS_BACK];
        for (int i = 0; i < YEARS_BACK; i++) out[i] = thisYear - i;
        return out;
    }

    /** "2024–2026", for the panel's subtitle and the empty state. */
    static String yearSpan() {
        int[] y = years();
        return y[y.length - 1] + "\u2013" + y[0];
    }

    static volatile boolean busy;

    static final class Pick {
        final String title;
        final String imdb;
        final long votes;
        final String genres;
        final int year;
        /** 0 for a series that started in 2026; >0 for a returning show's new season. */
        final int season;
        /** Display name of the service, from {@link #PROVIDERS}. */
        final String provider;
        final String url;
        /**
         * The service's own Android TV intent URI, when the catalogue has one:
         * {@code intent://www.netflix.com/watch/<id>#Intent;package=com.netflix.ninja…}.
         * This is what actually lands on the title - the web URL only brings the app
         * forward.
         */
        final String deeplink;
        /** Plot text from the catalogue, for the detail panel. */
        final String synopsis;
        /** First three actors, "A · B · C", or null. */
        final String cast;
        /** Rotten Tomatoes percentage as "99", or null. */
        final String rt;
        /** Age rating, e.g. "A" or "TV-MA", or null. */
        final String cert;
        /** Poster path on the image CDN, already substituted, or null. */
        final String poster;
        /** Best resolution sold: "4K", "HD", "SD", or null. */
        final String quality;

        Pick(String title, String imdb, long votes, String genres, int year,
             String provider, String url) {
            this(title, imdb, votes, genres, year, 0, provider, url, null);
        }

        Pick(String title, String imdb, long votes, String genres, int year,
             String provider, String url, String deeplink) {
            this(title, imdb, votes, genres, year, 0, provider, url, deeplink);
        }

        Pick(String title, String imdb, long votes, String genres, int year, int season,
             String provider, String url) {
            this(title, imdb, votes, genres, year, season, provider, url, null);
        }

        Pick(String title, String imdb, long votes, String genres, int year, int season,
             String provider, String url, String deeplink) {
            this(title, imdb, votes, genres, year, season, provider, url, deeplink,
                    null, null, null, null, null, null);
        }

        Pick(String title, String imdb, long votes, String genres, int year, int season,
             String provider, String url, String deeplink, String synopsis, String cast,
             String rt, String cert, String poster, String quality) {
            this.title = title;
            this.imdb = imdb;
            this.votes = votes;
            this.genres = genres;
            this.year = year;
            this.season = season;
            this.provider = provider;
            this.url = url;
            this.deeplink = deeplink;
            this.synopsis = synopsis;
            this.cast = cast;
            this.rt = rt;
            this.cert = cert;
            this.poster = poster;
            this.quality = quality;
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

    // Normalised title -> pick, so a row lookup is a map hit rather than a parse of the
    // whole list. Rebuilt only when the stored list changes, because its stamp moves.
    private static java.util.Map<String, Pick> index;
    private static long indexAt = -1;

    /**
     * The mined pick for a title, or null.
     *
     * This is what lets the card answer a row without OMDb: a couple of hundred titles
     * are mined on first run, and watching one of them should never touch the network.
     */
    static Pick find(Context c, String title) {
        if (title == null) return null;
        try {
            long at = cachedAt(c);
            if (index == null || at != indexAt) {
                java.util.Map<String, Pick> m = new java.util.HashMap<>();
                for (Pick p : cached(c)) {
                    if (p != null && p.title != null) m.put(Ratings.norm(p.title), p);
                }
                index = m;
                indexAt = at;
            }
            return index.get(Ratings.norm(title));
        } catch (Exception e) {
            return null;
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
                o.put("p", p.provider);
                o.put("u", p.url);
                if (p.deeplink != null) o.put("d", p.deeplink);
                if (p.synopsis != null) o.put("s", p.synopsis);
                if (p.cast != null) o.put("c", p.cast);
                if (p.rt != null) o.put("rt", p.rt);
                if (p.cert != null) o.put("ce", p.cert);
                if (p.poster != null) o.put("po", p.poster);
                if (p.quality != null) o.put("q", p.quality);
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
                    o.optString("g", ""), o.optInt("y", 2026), 0,
                    o.optString("p", "JIOHOTSTAR"), u, o.optString("d", null),
                    o.optString("s", null), o.optString("c", null),
                    o.optString("rt", null), o.optString("ce", null),
                    o.optString("po", null), o.optString("q", null)));
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

    /**
     * The live join. Null when nothing usable came back.
     *
     * No sitemap any more: each title's offers say which service carries it and give
     * the canonical link, so the panel covers four catalogues with one source and no
     * 624 KB download. It also removes the index's 24-hour lag.
     */
    static List<Pick> fetch(Context c) {
        List<Pick> all = new ArrayList<Pick>();
        boolean any = false;
        for (int year : years()) {
            for (int page = 0; page < PAGES_PER_YEAR; page++) {
                String body = post(page * 100, year);
                if (body == null) continue;
                any = true;
                collect(body, all);
            }
        }
        if (!any) return null;
        Log.i(TAG, "matched " + all.size() + " rated shows with an offer ("
                + yearSpan() + ")");

        Collections.sort(all, new Comparator<Pick>() {
            public int compare(Pick a, Pick b) {
                int byRating = Double.compare(dbl(b.imdb), dbl(a.imdb));
                if (byRating != 0) return byRating;
                return Long.compare(b.votes, a.votes);
            }
        });

        // Everything that qualified, in rating order. No per-service quota.
        List<Pick> out = new ArrayList<Pick>();
        for (int i = 0; i < all.size() && out.size() < MAX_ROWS; i++) {
            out.add(all.get(i));
        }
        return out;
    }

    /** Read one page, keep what is rated and carried by one of the four services. */
    static void collect(String body, List<Pick> out) {
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
                // edges[i] is {"node": {"content": ..., "offers": [...]}}.
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

                String[] offer = offerFor(node.optJSONArray("offers"));
                if (offer == null) continue;

                String genres = genres(content.optJSONArray("genres"));
                boolean dup = false;
                for (int j = 0; j < out.size(); j++) {
                    if (out.get(j).title.equalsIgnoreCase(title)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                // 0 means "no Tomatometer", not a score of zero.
                int tomato = scoring.optInt("tomatoMeter", 0);
                String rt = tomato > 0 ? String.valueOf(tomato) : null;
                out.add(new Pick(title, String.format(Locale.US, "%.1f", rating), votes,
                        genres, content.optInt("originalReleaseYear", 2026), 0,
                        offer[0], offer[1], offer[2],
                        trim(content.optString("shortDescription", ""), 420),
                        castText(content.optJSONArray("credits")),
                        rt,
                        blankToNull(content.optString("ageCertification", "")),
                        poster(content.optString("posterUrl", "")),
                        offer[3]));
            }
        } catch (Exception e) {
            Log.w(TAG, "collect: " + e);
        }
    }

    /** Plot text, clipped on a word boundary so the panel does not hold a novel. */
    static String trim(String s, int max) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return null;
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max);
        return t.substring(0, cut > max / 2 ? cut : max) + "\u2026";
    }

    static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** First three ACTOR credits, joined for one line: "A · B · C". */
    static String castText(JSONArray credits) {
        if (credits == null) return null;
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < credits.length() && taken < 3; i++) {
            JSONObject c = credits.optJSONObject(i);
            if (c == null) continue;
            if (!"ACTOR".equalsIgnoreCase(c.optString("role", ""))) continue;
            String name = c.optString("name", "").trim();
            if (name.isEmpty() || sb.indexOf(name) >= 0) continue;
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(name);
            taken++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** The catalogue's poster template, filled in for a 332px wide JPEG. */
    static String poster(String template) {
        if (template == null || template.isEmpty()) return null;
        return "https://images.justwatch.com"
                + template.replace("{profile}", "s332").replace("{format}", "jpg");
    }

    /**
     * The best offer for one of our services: {display, web URL, TV deeplink, quality}.
     * Priority is the order in PROVIDERS, so a title on both JioHotstar and Netflix is
     * listed once, on the service this app is built for. The deeplink may be null.
     */
    static String[] offerFor(JSONArray offers) {
        if (offers == null) return null;
        for (String[] prov : PROVIDERS) {
            String url = null;
            String deep = null;
            String quality = null;
            for (int i = 0; i < offers.length(); i++) {
                JSONObject o = offers.optJSONObject(i);
                if (o == null) continue;
                JSONObject pkg = o.optJSONObject("package");
                if (pkg == null) continue;
                if (!prov[0].equalsIgnoreCase(pkg.optString("clearName", ""))) continue;
                String u = o.optString("standardWebURL", "");
                if (u.isEmpty()) continue;
                if (url == null) {
                    url = u;
                    String d = o.optString("deeplinkURL", "");
                    deep = d.isEmpty() ? null : d;
                }
                // One offer per resolution tier; keep the best one for the info panel.
                String tier = qualityTier(o.optString("presentationType", ""));
                if (tier != null && (quality == null || rank(tier) > rank(quality))) {
                    quality = tier;
                }
            }
            if (url != null) return new String[]{prov[1], url, deep, quality};
        }
        return null;
    }

    /** "_4K" -> "4K"; anything unrecognised is ignored rather than shown raw. */
    static String qualityTier(String presentationType) {
        if (presentationType == null) return null;
        String t = presentationType.replace("_", "").trim().toUpperCase(Locale.US);
        if (t.equals("4K") || t.equals("UHD")) return "4K";
        if (t.equals("HD")) return "HD";
        if (t.equals("SD")) return "SD";
        return null;
    }

    static int rank(String tier) {
        if ("4K".equals(tier)) return 3;
        if ("HD".equals(tier)) return 2;
        return 1;
    }

    static int providerIndex(String display) {
        for (int i = 0; i < PROVIDERS.length; i++) {
            if (PROVIDERS[i][1].equals(display)) return i;
        }
        return -1;
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

    static String post(int offset, int year) {
        HttpURLConnection conn = null;
        try {
            JSONObject variables = new JSONObject();
            variables.put("country", "IN");
            variables.put("language", "en");
            variables.put("offset", offset);
            variables.put("year", year);
            JSONObject payload = new JSONObject();
            payload.put("query", QUERY);
            payload.put("variables", variables);

            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            Http.graphql(conn);
            OutputStream out = conn.getOutputStream();
            out.write(payload.toString().getBytes("UTF-8"));
            out.close();
            if (conn.getResponseCode() != 200) {
                Log.i(TAG, "http " + conn.getResponseCode() + " offset " + offset + " year " + year);
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
                "Adventure \u00B7 Drama \u00B7 Fantasy", 2026, "JIOHOTSTAR",
                "https://www.hotstar.com/in/shows/a-knight-of-the-seven-kingdoms/1271461884"));
        out.add(new Pick("Star Wars: Maul - Shadow Lord", "8.5", 31215,
                "Animation \u00B7 Action \u00B7 Adventure", 2026, "JIOHOTSTAR",
                "https://www.hotstar.com/in/shows/star-wars-maul-shadow-lord/1271515796"));
        out.add(new Pick("The Testaments", "8.2", 14269,
                "Drama \u00B7 Thriller", 2026, "JIOHOTSTAR",
                "https://www.hotstar.com/in/shows/the-testaments/1271542058"));
        out.add(new Pick("Chiraiya", "8.0", 15254,
                "Drama \u00B7 Hindi", 2026, "JIOHOTSTAR",
                "https://www.hotstar.com/in/shows/chiraiya/1271526656"));
        out.add(new Pick("Lanterns", "7.8", 65291,
                "Action \u00B7 Crime \u00B7 Drama", 2026, "JIOHOTSTAR",
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
                "JIOHOTSTAR", "https://www.hotstar.com/in/shows/the-bear/1260108974"));
        out.add(new Pick("Jujutsu Kaisen", "8.5", 224926,
                "Action & Adventure \u00B7 Animation \u00B7 Fantasy", 2020, 3,
                "JIOHOTSTAR", "https://www.hotstar.com/in/shows/jujutsu-kaisen/1271461634"));
        out.add(new Pick("MobLand", "8.3", 136487, "Crime \u00B7 Drama", 2025, 2,
                "JIOHOTSTAR", "https://www.hotstar.com/in/shows/mobland/1271397631"));
        out.add(new Pick("House of the Dragon", "8.2", 576151,
                "Action & Adventure \u00B7 Drama \u00B7 Fantasy", 2022, 3,
                "JIOHOTSTAR",
                "https://www.hotstar.com/in/shows/house-of-the-dragon/1971002877"));
        out.add(new Pick("Euphoria", "8.1", 327247, "Drama", 2019, 3,
                "JIOHOTSTAR", "https://www.hotstar.com/in/shows/euphoria/1971002893"));
        return out;
    }
}
