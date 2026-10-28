package dev.ratemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Scores from the public catalogue, for titles OMDb cannot score.
 *
 * Two cases, same query: OMDb returns the title with imdbRating N/A (its copy lags
 * for new releases), or OMDb does not have the title at all - which is normal for
 * Indian regional shows ("Muthu Alias Kaattaan": OMDb "Movie not found!", the catalogue
 * 6.7 from 929 votes plus a synopsis).
 *
 * Deliberately cheap: one GraphQL POST returning about a kilobyte, not the 1.1 MB
 * title page. The page is scraped only if the API shape changes, and never at runtime.
 *
 * No key: this is the same public endpoint the site itself uses. Undocumented, so every
 * failure is silent - a missing score is better than a broken card.
 */
public class Catalogue {
    static final String TAG = "RATEMYTV_CAT";
    static final String ENDPOINT = "https://apis.justwatch.com/graphql";

    static final String QUERY =
            "query S($country: Country!, $language: Language!, $q: String!) {"
            + " popularTitles(country: $country, first: 4, filter: {searchQuery: $q}) {"
            + " edges { node { objectType content(country: $country, language: $language) {"
            + " title originalReleaseYear shortDescription runtime"
            + " credits(first: 8) { name role }"
            + " scoring { imdbScore imdbVotes tomatoMeter }"
            + " } } } } }";

    /** Cache is keyed by what we asked for, so a wrong match cannot poison another title. */
    static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Fill in what OMDb could not supply on {@code info}. Returns true if anything was
     * added. The caller decides when this is worth a POST: it is the last step of a
     * lookup, never the first.
     */
    static boolean fill(Context c, String title, String wantType, String yearHint,
                        Ratings.Info info) {
        if (title == null || title.isEmpty() || info == null) return false;
        if (info.imdb != null) return false;

        String key = title.toLowerCase() + "|" + (wantType == null ? "" : wantType);
        String cached = readCache(c, key);
        if (cached != null) {
            if ("-".equals(cached)) return false;          // remembered as unavailable
            return apply(cached, info);
        }

        String body = query(title);
        if (body == null) return false;

        String packed = pick(body, title, wantType, yearHint);
        writeCache(c, key, packed == null ? "-" : packed);
        if (packed == null) return false;
        return apply(packed, info);
    }

    /** A card built from the catalogue alone, for a title OMDb has never heard of. */
    static Ratings.Info info(Context c, String title, String wantType) {
        Ratings.Info i = new Ratings.Info();
        return fill(c, title, wantType, null, i) ? i : null;
    }

    /** "6.9|354|90|2026|32 min|A story of ..." -> the fields on info. */
    static boolean apply(String packed, Ratings.Info info) {
        try {
            String[] p = packed.split("\\|", -1);
            boolean any = false;
            if (p.length > 0 && !p[0].isEmpty()) {
                info.imdb = p[0];
                any = true;
            }
            if (p.length > 1 && !p[1].isEmpty()) {
                info.imdbVotes = p[1];
                any = true;
            }
            if (p.length > 2 && !p[2].isEmpty()) {
                info.rt = p[2] + "%";
                any = true;
            }
            // Prose and year only stand in where OMDb had nothing: its plot and cast
            // are the ones the card was designed around.
            if (p.length > 3 && !p[3].isEmpty() && info.year == null) {
                info.year = p[3];
                any = true;
            }
            if (p.length > 4 && !p[4].isEmpty() && info.runtime == null) {
                info.runtime = p[4];
                any = true;
            }
            if (p.length > 5 && !p[5].isEmpty() && info.plot == null) {
                info.plot = p[5];
                any = true;
            }
            if (p.length > 6 && !p[6].isEmpty() && info.actors == null) {
                info.actors = p[6];
                any = true;
            }
            if (p.length > 7 && !p[7].isEmpty() && info.type == null) {
                info.type = p[7];
                any = true;
            }
            return any;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Choose the row that is actually this title: same name, matching kind, and the
     * nearest year when one is known. The catalogue will happily return "Phony" 2022 for a
     * 2026 series.
     */
    static String pick(String json, String title, String wantType, String yearHint) {
        try {
            JSONObject root = new JSONObject(json);
            org.json.JSONArray edges = root.getJSONObject("data")
                    .getJSONObject("popularTitles").optJSONArray("edges");
            if (edges == null) return null;
            int wantYear = 0;
            try {
                wantYear = Integer.parseInt(yearHint == null ? ""
                        : yearHint.replaceAll("[^0-9].*$", ""));
            } catch (Exception ignored) {
            }
            String best = null;
            int bestDelta = Integer.MAX_VALUE;
            for (int i = 0; i < edges.length(); i++) {
                JSONObject node = edges.getJSONObject(i).optJSONObject("node");
                if (node == null) continue;
                JSONObject content = node.optJSONObject("content");
                if (content == null) continue;
                String name = content.optString("title");
                if (name == null || !name.equalsIgnoreCase(title)) continue;

                String kind = node.optString("objectType");
                if (wantType != null) {
                    boolean isShow = "SHOW".equalsIgnoreCase(kind);
                    boolean wantsShow = "series".equalsIgnoreCase(wantType);
                    if (isShow != wantsShow) continue;
                }
                int year = content.optInt("originalReleaseYear", 0);
                int delta = wantYear > 0 && year > 0 ? Math.abs(year - wantYear) : 0;

                JSONObject scoring = content.optJSONObject("scoring");
                if (scoring == null) continue;
                String imdb = blank(scoring.opt("imdbScore"));
                String votes = blank(scoring.opt("imdbVotes"));
                if (!votes.isEmpty()) {
                    String f = Ratings.votes(votes);
                    votes = f == null ? votes : f;
                }
                String tomato = blank(scoring.opt("tomatoMeter"));
                if (imdb.isEmpty() && tomato.isEmpty()) continue;

                String yr = blank(content.opt("originalReleaseYear"));
                String runtime = blank(content.opt("runtime"));
                String plot = blank(content.opt("shortDescription"))
                        .replace('|', '/').replace('\n', ' ').replace('\r', ' ');
                String cast = cast(content.optJSONArray("credits"));
                String type = "SHOW".equalsIgnoreCase(kind) ? "series"
                        : "MOVIE".equalsIgnoreCase(kind) ? "movie" : "";
                String packed = imdb + "|" + votes + "|" + tomato + "|" + yr + "|"
                        + (runtime.isEmpty() ? "" : runtime + " min") + "|" + plot
                        + "|" + cast + "|" + type;
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = packed;
                }
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "pick: " + e);
            return null;
        }
    }

    /**
     * "Vijay Sethupathi, Milind Soman, Sudev Nair" from the credit list.
     *
     * The catalogue returns crew in the same list (a documentary's director was second),
     * so keep ACTORs only, drop the duplicates its data is prone to, and stop at three
     * the way OMDb does - the row has one line for this.
     */
    static String cast(org.json.JSONArray credits) {
        if (credits == null) return "";
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < credits.length() && taken < 3; i++) {
            JSONObject c = credits.optJSONObject(i);
            if (c == null) continue;
            if (!"ACTOR".equalsIgnoreCase(c.optString("role"))) continue;
            String name = blank(c.opt("name")).replace('|', '/').trim();
            if (name.isEmpty()) continue;
            if (sb.indexOf(name) >= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
            taken++;
        }
        return sb.toString();
    }

    static String blank(Object o) {
        if (o == null || o == JSONObject.NULL) return "";
        String s = String.valueOf(o);
        return "null".equalsIgnoreCase(s) || "N/A".equalsIgnoreCase(s) ? "" : s;
    }

    static String query(String title) {
        HttpURLConnection conn = null;
        try {
            JSONObject variables = new JSONObject();
            variables.put("country", "IN");
            variables.put("language", "en");
            variables.put("q", title);
            JSONObject payload = new JSONObject();
            payload.put("query", QUERY);
            payload.put("variables", variables);

            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(9000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            Http.graphql(conn);
            OutputStream out = conn.getOutputStream();
            out.write(payload.toString().getBytes("UTF-8"));
            out.close();
            if (conn.getResponseCode() != 200) {
                Log.i(TAG, "http " + conn.getResponseCode());
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
            Log.w(TAG, "query: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String readCache(Context c, String key) {
        try {
            JSONObject all = new JSONObject(c.getSharedPreferences("catalogue", Context.MODE_PRIVATE)
                    .getString("v3", "{}"));
            JSONObject e = all.optJSONObject(key);
            if (e == null) return null;
            if (System.currentTimeMillis() - e.optLong("ts", 0) > TTL_MS) return null;
            return e.optString("v", "-");
        } catch (Exception e) {
            return null;
        }
    }

    static void writeCache(Context c, String key, String value) {
        try {
            SharedPreferences p = c.getSharedPreferences("catalogue", Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString("v3", "{}"));
            JSONObject e = new JSONObject();
            e.put("v", value);
            e.put("ts", System.currentTimeMillis());
            all.put(key, e);
            p.edit().putString("v3", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
