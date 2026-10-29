package dev.ratemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** OMDb lookups: IMDb + Rotten Tomatoes + Metacritic in a few KB, no page load. */
public class Ratings {
    static final String TAG = "RATEMYTV";

    static class Info {
        /** The catalogue's own name for this title, when it answered with one. */
        String title;
        String year, runtime, genre, imdb, imdbVotes, rt, mc, plot, actors, type, poster;
    }

    /** Free key from omdbapi.com, set with the SETKEY broadcast. */
    static String key(Context ctx) {
        try {
            String k = ctx.getSharedPreferences("xray", Context.MODE_PRIVATE).getString("omdb", null);
            if (k != null && !k.trim().isEmpty()) return k.trim();
        } catch (Exception e) {
            // fall through
        }
        try {
            File f = new File(ctx.getExternalFilesDir(null), "omdb.key");
            if (!f.exists()) return null;
            BufferedReader r = new BufferedReader(new FileReader(f));
            String k = r.readLine();
            r.close();
            return k == null || k.trim().isEmpty() ? null : k.trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ---- persistent cache -------------------------------------------------
    // OMDb's free tier is 1,000 calls a day. A title's score does not change
    // between two glances at the card, so every lookup is stored on disk and
    // never repeated. Look at the same 60 titles all week: 60 calls, not 600.

    static final String PREFS = "ratings_cache";
    static final String BLOB = "v1";
    static final int MAX_ENTRIES = 600;

    static String cacheKey(String title, String wantType) {
        return norm(title) + "|" + (wantType == null ? "" : wantType);
    }

    static Info cached(Context c, String title) {
        return cached(c, title, null);
    }

    /** Marks a title OMDb has already refused, so we stop asking every build. */
    static boolean knownMiss(Context c, String title, String wantType) {
        try {
            String blob = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(BLOB, "{}");
            org.json.JSONObject o = new org.json.JSONObject(blob)
                    .optJSONObject(cacheKey(title, wantType));
            if (o == null) return false;
            if (!o.optBoolean("miss", false)) return false;
            // Ask again after a week: a new show does get added to OMDb.
            long age = System.currentTimeMillis() - o.optLong("ts", 0);
            return age < 7L * 24 * 3600 * 1000;
        } catch (Exception e) {
            return false;
        }
    }

    static void rememberMiss(Context c, String title, String wantType) {
        if (title == null) return;
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString(BLOB, "{}"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("miss", true);
            o.put("ts", System.currentTimeMillis());
            all.put(cacheKey(title, wantType), o);
            p.edit().putString(BLOB, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static Info cached(Context c, String title, String wantType) {
        if (title == null) return null;
        try {
            String blob = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(BLOB, "{}");
            org.json.JSONObject o = new org.json.JSONObject(blob)
                    .optJSONObject(cacheKey(title, wantType));
            if (o == null || o.optBoolean("miss", false)) return null;
            Info i = new Info();
            i.year = nn(o.optString("year"));
            i.runtime = nn(o.optString("runtime"));
            i.genre = nn(o.optString("genre"));
            i.imdb = nn(o.optString("imdb"));
            i.imdbVotes = nn(o.optString("votes"));
            i.rt = nn(o.optString("rt"));
            i.mc = nn(o.optString("mc"));
            i.plot = nn(o.optString("plot"));
            i.actors = nn(o.optString("actors"));
            i.type = nn(o.optString("type"));
            i.poster = nn(o.optString("poster"));
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    static void remember(Context c, String title, Info i) {
        remember(c, title, null, i);
    }

    static void remember(Context c, String title, String wantType, Info i) {
        if (title == null || i == null) return;
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString(BLOB, "{}"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("year", nz(i.year));
            o.put("runtime", nz(i.runtime));
            o.put("genre", nz(i.genre));
            o.put("imdb", nz(i.imdb));
            o.put("votes", nz(i.imdbVotes));
            o.put("rt", nz(i.rt));
            o.put("mc", nz(i.mc));
            o.put("plot", nz(i.plot));
            o.put("actors", nz(i.actors));
            o.put("type", nz(i.type));
            o.put("poster", nz(i.poster));
            o.put("ts", System.currentTimeMillis());
            all.put(cacheKey(title, wantType), o);

            if (all.length() > MAX_ENTRIES) {
                // drop the least recently written
                String oldest = null;
                long ts = Long.MAX_VALUE;
                java.util.Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    long t = all.optJSONObject(key).optLong("ts", 0);
                    if (t < ts) { ts = t; oldest = key; }
                }
                if (oldest != null) all.remove(oldest);
            }
            p.edit().putString(BLOB, all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "cache write: " + e);
        }
    }

    /** Disk cache first. Only a genuine miss costs a call. */
    static Info lookup(Context c, String title) {
        return lookup(c, title, null);
    }

    /**
     * wantType is "series" or "movie" when we know which one this row is. Without it
     * OMDb happily answers "Captive" with the 2015 Kate Mara film when the viewer is
     * watching a series of the same name.
     */
    static Info lookup(Context c, String title, String wantType) {
        Info i = cached(c, title, wantType);
        if (i != null) {
            if (i.imdb == null) {
                // Cached before a score existed. The catalogue may have one now; its own
                // cache is what makes this a prefs read, not a request.
                Catalogue.fill(c, title, wantType, i.year, i);
            }
            return i;
        }
        // Before OMDb: the Watch These pass already mined this title's score, votes,
        // year, genres and art on first run. Answering from that index is what lets the
        // card work with no OMDb key at all - OMDb becomes the fallback for what the
        // catalogue did not cover (regional titles, mostly).
        WatchThese.Pick mined = WatchThese.find(c, title);
        if (mined != null && mined.imdb != null && !mined.imdb.isEmpty()) {
            Info fromList = new Info();
            fromList.imdb = mined.imdb;
            if (mined.votes > 0) fromList.imdbVotes = votes(String.valueOf(mined.votes));
            if (mined.year > 0) fromList.year = String.valueOf(mined.year);
            if (mined.genres != null) fromList.genre = mined.genres.replace("|", ", ");
            if (mined.poster != null) fromList.poster = mined.poster;
            fromList.type = wantType;
            remember(c, title, wantType, fromList);
            Log.i(TAG, "mined: " + title + " -> " + mined.imdb + " (no key needed)");
            return fromList;
        }
        if (!knownMiss(c, title, wantType)) {
            String k = key(c);
            if (k != null) {
                i = fetch(title, k, wantType);
                if (i == null && wantType != null) {
                    // Not every episodic-looking row is a series: a film whose title
                    // carries "S1 E01" would vanish if we only ever asked for series.
                    String other = "series".equals(wantType) ? "movie" : "series";
                    i = fetch(title, k, other);
                    if (i != null) {
                        Log.i(TAG, "omdb: " + title + " is " + other + ", not " + wantType);
                    }
                }
            }
        }
        if (i == null) {
            // OMDb has never heard of the title. That is the normal case for Indian
            // regional shows, and it used to mean no score at all: the catalogue was only
            // consulted when OMDb had *found* the title with a blank rating.
            i = Catalogue.info(c, title, wantType);
            if (i != null) {
                Log.i(TAG, "catalogue only: " + title + " imdb=" + i.imdb + " votes=" + i.imdbVotes
                        + " year=" + i.year + " type=" + i.type
                        + " plot=" + (i.plot == null ? "-" : "yes")
                        + " cast=" + i.actors);
                // Not stored in ratings_cache: that would pin a scoreless OMDb miss
                // forever. The catalogue cache holds the same answer for a week regardless.
                return i;
            }
            // Last resort: the title may be a filename ("The.Cycle.2026.1080p...").
            // Search + exact-match verification, then cache whatever it found.
            Info resolved = resolve(c, title, wantType);
            if (resolved != null) return resolved;
            rememberMiss(c, title, wantType);
            return null;
        }
        if (i.imdb == null) {
            // OMDb knows the title but has no score for it: its copy lags for new
            // releases. The catalogue carries the number; one small POST, cached a week.
            if (Catalogue.fill(c, title, wantType, i.year, i)) {
                Log.i(TAG, "catalogue: " + title + " imdb=" + i.imdb + " rt=" + i.rt);
            }
        }
        remember(c, title, wantType, i);
        return i;
    }

    /** Delete cached text for titles that are gone. Returns how many went. */
    static int prune(Context c, java.util.Set<String> keep) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString(BLOB, "{}"));
            int removed = 0;
            java.util.List<String> dead = new java.util.ArrayList<String>();
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                // Keys are "title|kind" now; the keep-set is plain titles. Comparing
                // them raw deleted the whole cache on every list build.
                int bar = k.indexOf('|');
                String title = bar > 0 ? k.substring(0, bar) : k;
                if (!keep.contains(title)) dead.add(k);
            }
            for (String k : dead) {
                all.remove(k);
                removed++;
            }
            if (removed > 0) p.edit().putString(BLOB, all.toString()).apply();
            return removed;
        } catch (Exception e) {
            return 0;
        }
    }

    static String nz(String s) { return s == null ? "" : s; }

    static String nn(String s) { return s == null || s.isEmpty() ? null : s; }

    static Info fetch(String title, String apiKey) {
        return fetch(title, apiKey, null);
    }

    /** OMDb record -> Info. One mapping, so the two paths cannot drift. */
    static Info fromJson(org.json.JSONObject o) {
        if (o == null || !"True".equals(o.optString("Response"))) return null;
        Info i = new Info();
        i.title = clean(o.optString("Title"));
        i.year = clean(o.optString("Year"));
        i.runtime = clean(o.optString("Runtime"));
        String g = o.optString("Genre");
        i.genre = g.isEmpty() || "N/A".equals(g) ? null : g.split(",")[0].trim();
        i.imdb = clean(o.optString("imdbRating"));
        i.imdbVotes = votes(o.optString("imdbVotes"));
        i.mc = clean(o.optString("Metascore"));
        i.plot = clean(o.optString("Plot"));
        i.actors = clean(o.optString("Actors"));
        i.type = clean(o.optString("Type"));
        i.poster = clean(o.optString("Poster"));
        try {
            org.json.JSONArray arr = o.optJSONArray("Ratings");
            if (arr != null) {
                for (int k = 0; k < arr.length(); k++) {
                    org.json.JSONObject r = arr.getJSONObject(k);
                    if ("Rotten Tomatoes".equals(r.optString("Source"))) {
                        i.rt = r.optString("Value");
                    }
                }
            }
        } catch (Exception ignored) { }
        return i;
    }

    /** Lower-case, punctuation and spacing flattened, for exact-match checks. */
    static String flat(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * Resolve a filename-like title by asking the catalogue to search, then verifying.
     *
     * No tag vocabulary: we hand the catalogue progressively longer prefixes ("Severance",
     * "Severance S02E03", ...) and accept a hit only when its own title equals the prefix
     * and its year matches a year in the filename. That is what stops "The Cycle 2026"
     * resolving to "The Pendragon Cycle" - close is not good enough, because a wrong
     * score is worse than no score.
     */
    static Info resolve(Context c, String raw, String wantType) {
        String key = key(c);
        if (key == null || raw == null) return null;
        String clean = raw.replaceAll("[._]", " ").replaceAll("\\s+", " ").trim();
        String[] tok = clean.split(" ");
        if (tok.length < 2) return null;

        String year = null;
        for (String t : tok) {
            if (t.matches("(19|20)\\d{2}")) { year = t; break; }
        }

        int max = Math.min(6, tok.length);
        for (int n = 1; n <= max; n++) {
            StringBuilder q = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) q.append(' ');
                q.append(tok[i]);
            }
            String probe = q.toString();
            Info hit = searchOne(probe, key, wantType);
            if (hit == null) continue;
            if (!flat(hit.title).equals(flat(probe))) {
                Log.i(TAG, "resolve: [" + probe + "] -> " + hit.title + " (not exact)");
                continue;
            }
            if (year != null && hit.year != null && !hit.year.startsWith(year)) {
                Log.i(TAG, "resolve: [" + probe + "] -> " + hit.title + " (" + hit.year
                        + ") wrong year");
                continue;
            }
            Log.i(TAG, "resolve: " + raw + " -> " + hit.title + " (" + hit.year + ")");
            remember(c, raw, wantType, hit);
            return hit;
        }
        return null;
    }

    /** First search hit for a query, expanded to a full record by its id. */
    static Info searchOne(String q, String apiKey, String wantType) {
        try {
            String typeQ = wantType == null ? "" : "&type=" + enc(wantType);
            JSONObject s = get("https://www.omdbapi.com/?s=" + enc(q) + typeQ
                    + "&apikey=" + enc(apiKey));
            JSONArray arr = s == null ? null : s.optJSONArray("Search");
            if (arr == null || arr.length() == 0) return null;
            JSONObject m = arr.getJSONObject(0);
            JSONObject byId = get("https://www.omdbapi.com/?i="
                    + enc(m.optString("imdbID")) + "&apikey=" + enc(apiKey));
            if (byId == null || !"True".equals(byId.optString("Response"))) return null;
            Info i = fromJson(byId);
            return i;
        } catch (Exception e) {
            Log.w(TAG, "searchOne: " + e);
            return null;
        }
    }

    static Info fetch(String title, String apiKey, String wantType) {
        if (title == null || title.isEmpty() || apiKey == null) return null;
        String typeQ = wantType == null ? "" : "&type=" + enc(wantType);
        try {
            JSONObject o = get("https://www.omdbapi.com/?t=" + enc(title) + typeQ
                    + "&apikey=" + enc(apiKey));
            if (o == null) return null;

            boolean wrongKind = wantType != null && "True".equals(o.optString("Response"))
                    && !wantType.equalsIgnoreCase(o.optString("Type"));
            // OMDb's t= is fuzzy: an exact title elsewhere wins.
            if ("True".equals(o.optString("Response"))
                    && (!same(o.optString("Title"), title) || wrongKind)) {
                JSONObject s = get("https://www.omdbapi.com/?s=" + enc(title) + typeQ
                        + "&apikey=" + enc(apiKey));
                JSONArray arr = s == null ? null : s.optJSONArray("Search");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.getJSONObject(i);
                        if (wantType != null && !wantType.equalsIgnoreCase(m.optString("Type"))) {
                            continue;
                        }
                        if (same(m.optString("Title"), title)) {
                            JSONObject byId = get("https://www.omdbapi.com/?i="
                                    + enc(m.optString("imdbID")) + "&apikey=" + enc(apiKey));
                            if (byId != null) o = byId;
                            break;
                        }
                    }
                }
            }
            if (!"True".equals(o.optString("Response"))) {
                Log.i(TAG, "omdb: " + o.optString("Error"));
                return null;
            }
            if (wantType != null && !wantType.equalsIgnoreCase(o.optString("Type"))) {
                Log.i(TAG, "omdb: " + title + " is " + o.optString("Type")
                        + ", wanted " + wantType);
                return null;
            }

            Info i = new Info();
            i.year = clean(o.optString("Year"));
            i.runtime = clean(o.optString("Runtime"));
            String g = o.optString("Genre");
            i.genre = g.isEmpty() || "N/A".equals(g) ? null : g.split(",")[0].trim();
            i.imdb = clean(o.optString("imdbRating"));
            i.imdbVotes = votes(o.optString("imdbVotes"));
            i.mc = clean(o.optString("Metascore"));
            i.plot = clean(o.optString("Plot"));
            i.actors = clean(o.optString("Actors"));
            i.type = clean(o.optString("Type"));
            i.poster = clean(o.optString("Poster"));
            JSONArray arr = o.optJSONArray("Ratings");
            if (arr != null) {
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject r = arr.getJSONObject(k);
                    if ("Rotten Tomatoes".equals(r.optString("Source"))) {
                        i.rt = clean(r.optString("Value"));
                    }
                }
            }
            return i;
        } catch (Exception e) {
            Log.w(TAG, "omdb: " + e);
            return null;
        }
    }

    static JSONObject get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            Http.json(c);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "http: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String clean(String s) {
        return s == null || s.isEmpty() || "N/A".equals(s) ? null : s;
    }

    /** "139,000" -> "139k". */
    static String votes(String s) {
        if (s == null || "N/A".equals(s) || s.isEmpty()) return null;
        try {
            long n = Long.parseLong(s.replace(",", "").trim());
            if (n >= 1000000) return (n / 100000) / 10.0 + "M";
            if (n >= 1000) return Math.round(n / 1000.0) + "k";
            return String.valueOf(n);
        } catch (Exception e) {
            return null;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** How many titles are cached on disk. */
    static int keyCount(Context c) {
        try {
            return new org.json.JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(BLOB, "{}")).length();
        } catch (Exception e) {
            return 0;
        }
    }
}
