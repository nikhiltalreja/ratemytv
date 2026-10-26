package dev.ratemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which Watch Next row is playing on THIS device.
 *
 * Watch Next is account-wide, so a title watched on another TV sits in the same table.
 * Position alone is not enough either: the row is only rewritten every few minutes, so it
 * always trails live playback by an unknown amount.
 *
 * The reliable signal is the *slope*: while we play, our row's stored position advances by
 * the same amount our playback does. Other devices' rows do not. Every few seconds we
 * compare each row's advance with ours; the one that tracks it is the title.
 *
 * What it resolves is remembered per package, so the next play of the same title can be
 * recognised immediately.
 */
public class Watcher extends NotificationListenerService {
    static final String TAG = "RATEMYTV";
    /** Pre-rename name; kept so existing installs keep their history. */
    static final String PREFS = "xray";

    static class Item {
        String pkg, title, cid, poster, intent, ep;
        /** Episode name without its SxxExx code, for the line under the title. */
        String epname;
        /** Another app that also has this title, e.g. Prime for a Jio licensed film. */
        String alsoOn;
        /** ids parsed from the stream URL, kept so a watch URL can be rebuilt. */
        String sid, epid;
        /** true only for rows this TV recorded itself (real viewing history). */
        boolean mine;
        long pos, dur, ts;
        int type;
    }

    /** "S2 E4 10th Quarter" -> "S2 E04" (null when the title carries no episode). */
    static String epCode(String t) {
        if (t == null) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)\\bS(\\d+)\\s*E(\\d+)").matcher(t);
            if (!m.find()) return null;
            int e = Integer.parseInt(m.group(2));
            return "S" + m.group(1) + " E" + (e < 10 ? "0" + e : String.valueOf(e));
        } catch (Exception e) {
            return null;
        }
    }

    static volatile Item CURRENT;
    static volatile String CUR_PKG;
    static volatile long CUR_POS;
    static volatile boolean CUR_PLAYING;
    static volatile String CUR_TITLE;
    static volatile long CUR_DUR;

    static volatile String CUR_EP_ID;
    static volatile String CUR_SHOW_ID;
    static volatile String CUR_SERIES;
    static volatile String CUR_POSTER;

    /** The episode id sits in the manifest path: /v3/<showId>/<episodeId>/Ã¢â‚¬Â¦ */
    static void parseIds(android.media.MediaMetadata md) {
        CUR_EP_ID = null;
        CUR_SHOW_ID = null;
        try {
            String uri = md.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_URI);
            if (uri == null) return;

            // Three shapes are in the wild, and the folder after /hotstarint/ is the
            // only thing that differs - it names the content type and changes with the
            // app version (v3, episode1, eptitle01):
            //   .../videos/hotstarint/v3/1271397631/1271716114/...
            //   .../videos/hotstarint/episode1/1271512941/1271548701/v3/<asset>/<hash>/...
            //   .../videos/hotstarint/eptitle01/1271526656/1271526662/v3/<asset>/<hash>/...
            // Matching the folder names one at a time is what left "Chiraiya" stored as
            // "S1 E1 Griha Pravesh": eptitle01 matched neither pattern, so no ids were
            // parsed, so the sitemap was never asked. Whatever the folder is called, the
            // next two segments are the show id and the episode id.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/hotstarint/[^/]+/(\\d{6,})/(\\d{6,})/").matcher(uri);
            if (!m.find()) {
                // CDN paths without the hotstarint folder still put both ids in front
                // of the manifest's /v3/ segment.
                m = java.util.regex.Pattern.compile("/(\\d{6,})/(\\d{8,})/v3/").matcher(uri);
            }
            if (m.find()) {
                CUR_SHOW_ID = m.group(1);
                CUR_EP_ID = m.group(2);
                Log.i(TAG, "ids from stream path: show=" + CUR_SHOW_ID + " ep=" + CUR_EP_ID);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * The one key every episode of a series shares: the show id inside the stream URL
     * (/v3/<showId>/<episodeId>/) and inside the provider's deeplink
     * (hotstar://in/shows/<slug>/<showId>/…). Episode S2 E6 has no row of its own,
     * but any row for that show names the series.
     */
    Item rowForShow(String showId) {
        if (showId == null || showId.isEmpty()) return null;
        try (Cursor c = queryRows(null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String title = c.getString(1);
                String intent = c.getString(6);
                if (title == null || intent == null) continue;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/shows/[^/]+/(\\d+)/").matcher(intent);
                if (m.find() && showId.equals(m.group(1))) {
                    Log.i(TAG, "series by show id: " + title + " <- " + showId);
                    return item(c.getString(0), c, title, c.getLong(3));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "rowForShow: " + e);
        }
        return null;
    }

    /**
     * Not every episode gets its own row (JioHotstar publishes only a few), but the
     * rows it does publish carry the episode label in short_description. Matching on
     * that recovers the series name when the episode id leads nowhere.
     */
    Item rowForLabel(String pkg, String label) {
        if (label == null || label.trim().isEmpty()) return null;
        String want = label.trim().toLowerCase();
        try (Cursor c = queryRows(pkg)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (!ofPkg(c, pkg)) continue;
                String desc = c.getString(7);
                String title = c.getString(1);
                if (desc == null || title == null) continue;
                if (desc.trim().toLowerCase().equals(want)) {
                    Log.i(TAG, "series by label: " + title + " <- " + desc);
                    return item(pkg, c, title, c.getLong(3));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "rowForLabel: " + e);
        }
        return null;
    }

    /** Find the provider row for this episode; its title is the series name. */
    Item rowForEpisode(String epId) {
        if (epId == null) return null;
        try (Cursor c = queryRows(null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (epId.equals(c.getString(2))) {
                    return item(c.getString(0), c, c.getString(1), c.getLong(3));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Some apps (JioHotstar) never publish Watch Next but do title their session. */
    String sessionTitle(MediaController mc) {
        try {
            android.media.MediaMetadata md = mc.getMetadata();
            if (md == null) return null;
            StringBuilder ks = new StringBuilder();
            for (String k : md.keySet()) ks.append(k).append(' ');
            Log.i(TAG, "meta uri=" + md.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_URI)
                    + " id=" + md.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
                    + " dur=" + md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION));
            // Every text field the player fills, so we can see which one is the series.
            Log.i(TAG, "meta TITLE=[" + text(md, android.media.MediaMetadata.METADATA_KEY_TITLE)
                    + "] DISPLAY_TITLE=[" + text(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    + "] DISPLAY_SUBTITLE=[" + text(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    + "] DISPLAY_DESC=[" + text(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
                    + "] ALBUM=[" + text(md, android.media.MediaMetadata.METADATA_KEY_ALBUM)
                    + "] ARTIST=[" + text(md, android.media.MediaMetadata.METADATA_KEY_ARTIST) + "]");
            CUR_DUR = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);

            String title = text(md, android.media.MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = text(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null) title = text(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);

            // For TV episodes the series name is usually the album / subtitle.
            String show = text(md, android.media.MediaMetadata.METADATA_KEY_ALBUM);
            if (show == null) show = text(md, android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (show == null) show = text(md, android.media.MediaMetadata.METADATA_KEY_ARTIST);

            String out = title;
            if (show != null && title != null
                    && !title.toLowerCase().contains(show.toLowerCase())) {
                out = show + " \u2014 " + title;
            } else if (out == null) {
                out = show;
            }
            if (out != null) Log.i(TAG, "session title: " + out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Music players title their sessions too; a song is not "what you were watching". */
    static boolean isMusic(String pkg) {
        return pkg != null && (pkg.contains("spotify") || pkg.contains("music"));
    }

    static String text(android.media.MediaMetadata md, String key) {
        CharSequence cs = md.getText(key);
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static volatile Thread POLLER;

    @Override public void onCreate() {
        super.onCreate();
        startPoller();
    }

    void startPoller() {
        if (POLLER != null && POLLER.isAlive()) return;
        POLLER = new Thread(new Runnable() {
            public void run() { loop(); }
        });
        POLLER.setDaemon(true);
        POLLER.start();
    }

    void loop() {
        String lastPkg = null;
        long lastPos = -1;
        String seenPkg = null;      // package of the session we just watched
        long seenPos = -1;          // where it was when we last saw it
        long lastSeen = 0;
        long lastLearnCheck = 0;
        boolean seenHadTitle = false;
        long lastLocalSave = 0;
        long samplePos = -1;
        long sampleTime = 0;
        Map<String, Long> prevRows = new HashMap<>();

        while (true) {
            try {
                MediaSessionManager msm =
                        (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
                MediaController mc = pick(msm.getActiveSessions(
                        new android.content.ComponentName(this, Watcher.class)));
                if (mc != null) {
                    PlaybackState st = mc.getPlaybackState();
                    if (st != null) {
                        String pkg = mc.getPackageName();
                        long pos = st.getPosition();
                        boolean playing = st.getState() == PlaybackState.STATE_PLAYING;
                        CUR_PKG = pkg;
                        CUR_POS = pos;
                        CUR_PLAYING = playing;
                        CUR_TITLE = isMusic(pkg) ? null : sessionTitle(mc);
                        if (!isMusic(pkg) && mc.getMetadata() != null) {
                            parseIds(mc.getMetadata());
                            Item series = rowForEpisode(CUR_EP_ID);
                            if (series == null) series = rowForShow(CUR_SHOW_ID);
                            if (series == null) series = rowForLabel(pkg, CUR_TITLE);
                            CUR_SERIES = series != null ? series.title : null;
                            CUR_POSTER = series != null ? series.poster : null;
                        }

                        boolean newSession = !pkg.equals(lastPkg);
                        boolean jumped = lastPos >= 0 && Math.abs(pos - lastPos) > 30000;

                        if (newSession || jumped) {
                            CURRENT = null;
                            prevRows.clear();
                            samplePos = -1;
                            sampleTime = 0;
                            Item it = null;
                            if (CUR_TITLE == null) {
                                it = resolveAt(pkg, pos, 90000);
                                if (it == null) it = learned(pkg, pos);
                            }
                            if (it != null) {
                                CURRENT = it;
                                Log.i(TAG, "known " + it.title + " (pos " + pos + ")");
                            } else {
                                Log.i(TAG, "new session pkg=" + pkg + " pos=" + pos
                                        + " -> identifying");
                            }
                        }

                        long now = System.currentTimeMillis();
                        // Only record what the player itself says. A matched Watch Next row can be
                        // stale or belong to another device, and stamping it with our live position
                        // is how the list ended up full of titles nobody watched here.
                        if (now - lastLocalSave > 10000) {
                            lastLocalSave = now;
                            String t = CUR_SERIES != null ? CUR_SERIES : CUR_TITLE;
                            // Neither a Watch Next row nor a provider label names this
                            // show, but the stream gave us its id: ask the sitemap now,
                            // so the record is keyed by the show and not by the episode
                            // label it was watched under.
                            if (CUR_SERIES == null && CUR_SHOW_ID != null) {
                                String named = Sitemap.nameFor(Watcher.this, CUR_SHOW_ID);
                                if (named != null) t = named;
                            }
                            if (t != null && t.length() > 0 && pos > 0) {
                                saveLocal(Watcher.this, pkg, t, CUR_TITLE, pos, CUR_DUR,
                                        CUR_SHOW_ID, CUR_EP_ID);
                            }
                        }
                        if (sampleTime == 0) {
                            samplePos = pos;
                            sampleTime = now;
                            snapshot(pkg, prevRows);
                        } else if (now - sampleTime >= 4000) {
                            Item it = CUR_TITLE != null ? null
                                    : slopeMatch(pkg, pos, samplePos, prevRows);
                            if (it != null) {
                                if (CURRENT == null || !it.title.equals(CURRENT.title)) {
                                    CURRENT = it;
                                    save(pkg, it);
                                    Log.i(TAG, "tracked " + it.title
                                            + " (playback " + pos + ", row " + it.pos + ")");
                                }
                            }
                            samplePos = pos;
                            sampleTime = now;
                            if (CURRENT == null) snapshot(pkg, prevRows);
                        }

                        lastPkg = pkg;
                        lastPos = pos;
                        if (!isMusic(pkg) && pos > 0) {
                            seenPkg = pkg;
                            seenPos = pos;
                            lastSeen = now;
                            seenHadTitle = CUR_TITLE != null;
                        }
                    }
                } else {
                    // No session. Apps like Netflix and Apple TV publish their row only
                    // after they lose the player (Apple ~16 s, Netflix 6-10 min), so watch
                    // for a row that lands where we stopped and adopt it as watched-here.
                    long now = System.currentTimeMillis();
                    if (seenPkg != null && !seenHadTitle && lastSeen > 0
                            && now - lastSeen < LEARN_WINDOW_MS
                            && now - lastLearnCheck > 15000) {
                        lastLearnCheck = now;
                        Item row = rowNear(seenPkg, seenPos, 30000);
                        if (row != null) {
                            saveLocal(Watcher.this, seenPkg, row.title, null, row.pos, row.dur);
                            Log.i(TAG, "learned " + row.title + " after leaving " + seenPkg);
                            seenPkg = null;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "poll: " + e);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** How long after leaving a player we still look for its freshly written row. */
    static final long LEARN_WINDOW_MS = 16L * 60 * 1000;

    /** The row for {@code pkg} whose stored position is closest to where we stopped. */
    Item rowNear(String pkg, long pos, long tol) {
        Item best = null;
        long bestDiff = Long.MAX_VALUE;
        try (Cursor c = queryRows(pkg)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (!ofPkg(c, pkg)) continue;
                long rpos = c.getLong(3);
                if (rpos <= 0) continue;
                long diff = Math.abs(rpos - pos);
                if (diff <= tol && diff < bestDiff) {
                    bestDiff = diff;
                    best = item(pkg, c, c.getString(1), rpos);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "rowNear: " + e);
        }
        return best;
    }

    /** The row of this package whose stored position advanced like our playback. */
    Item slopeMatch(String pkg, long pos, long posBefore, Map<String, Long> prevRows) {
        long dUs = pos - posBefore;
        if (dUs < 3000) return null;
        long tol = Math.max(2500, dUs / 10);
        try (Cursor c = queryRows(pkg)) {
            if (c == null) return null;
            Item found = null;
            while (c.moveToNext()) {
                if (!ofPkg(c, pkg)) continue;
                String title = c.getString(1);
                long rpos = c.getLong(3);
                Long before = prevRows.get(title);
                if (before != null) {
                    long dRow = rpos - before;
                    if (dRow > 0 && Math.abs(dRow - dUs) <= tol) {
                        found = item(pkg, c, title, rpos);
                    }
                    prevRows.put(title, rpos);
                }
            }
            return found;
        } catch (Exception e) {
            Log.w(TAG, "slope: " + e);
            return null;
        }
    }

    void snapshot(String pkg, Map<String, Long> into) {
        try (Cursor c = queryRows(pkg)) {
            if (c == null) return;
            while (c.moveToNext()) {
                if (!ofPkg(c, pkg)) continue;
                into.put(c.getString(1), c.getLong(3));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Rows of one app only. Without the package filter, a Netflix session could be
     * matched to a JioHotstar row at a similar position — which is exactly how the
     * wrong titles appeared.
     *
     * The filter has to happen here, not in the query: this TV's provider throws
     * `SecurityException: Selection not allowed` for any selection, which silently
     * disabled every lookup that passed a package - no series by label, no row by
     * position. Callers skip other packages with {@link #ofPkg}.
     */
    Cursor queryRows(String pkg) {
        return getContentResolver().query(
                Uri.parse("content://android.media.tv/watch_next_program"),
                new String[]{"package_name", "title", "content_id",
                        "last_playback_position_millis", "duration_millis", "poster_art_uri",
                        "intent_uri", "short_description"},
                null, null, null);
    }

    /** True when this row belongs to {@code pkg}, or when no package was asked for. */
    static boolean ofPkg(Cursor c, String pkg) {
        if (pkg == null) return true;
        String p = c.getString(0);
        return p != null && p.equals(pkg);
    }

    Item item(String pkg, Cursor c, String title, long rpos) {
        Item it = new Item();
        it.pkg = pkg;
        it.title = title;
        it.cid = c.getString(2);
        it.pos = rpos;
        it.dur = c.getLong(4);
        try { it.poster = c.getString(5); } catch (Exception ignored) { }
        try { it.intent = c.getString(6); } catch (Exception ignored) { }
        return it;
    }

    /** Closest row of this package within {@code tol} of the resume position. */
    Item resolveAt(String pkg, long pos, long tol) {
        if (pos <= 3000) return null;
        try (Cursor c = queryRows(pkg)) {
            if (c == null) return null;
            Item best = null;
            long bestDiff = Long.MAX_VALUE;
            while (c.moveToNext()) {
                if (!ofPkg(c, pkg)) continue;
                long rpos = c.getLong(3);
                if (rpos <= 3000) continue;
                // A row further ahead than we are cannot be what we are playing:
                // resuming starts at (or behind) the stored position, never past it.
                if (rpos > pos + 5000) continue;
                long diff = Math.abs(rpos - pos);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = item(pkg, c, c.getString(1), rpos);
                }
            }
            return bestDiff <= tol ? best : null;
        } catch (Exception e) {
            Log.w(TAG, "resolve: " + e);
            return null;
        }
    }

    /** Remembered title for this package, if the position still fits. */
    Item learned(String pkg, long pos) {
        try {
            String v = prefs().getString(pkg, null);
            if (v == null) return null;
            String[] p = v.split("\\|");
            if (p.length < 4) return null;
            long lastPos = Long.parseLong(p[2]);
            if (lastPos > pos + 5000 || Math.abs(lastPos - pos) > 180000) return null;
            Item it = new Item();
            it.pkg = pkg;
            it.title = p[0];
            it.cid = "";
            it.pos = pos;
            it.dur = Long.parseLong(p[3]);
            return it;
        } catch (Exception e) {
            return null;
        }
    }

    void save(String pkg, Item it) {
        try {
            prefs().edit().putString(pkg, it.title + "|" + it.cid + "|" + it.pos + "|" + it.dur).apply();
        } catch (Exception ignored) {
        }
    }

    static void remember(Context c, String pkg, Item it) {        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(pkg, it.title + "|" + it.cid + "|" + it.pos + "|" + it.dur)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** Progress we saw ourselves, for apps that never publish to the TV Provider. */
    /** "S2 E06" -> 2006, so episodes can be ordered. */
    static int compareEp(String a, String b) {
        int[] x = parseEp(a), y = parseEp(b);
        if (x == null || y == null) return 0;
        return x[0] != y[0] ? Integer.compare(x[0], y[0]) : Integer.compare(x[1], y[1]);
    }

    static int[] parseEp(String ep) {
        if (ep == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)S(\\d+)\\s*E(\\d+)").matcher(ep);
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (Exception e) {
            return null;
        }
    }

    static void saveLocal(Context c, String pkg, String title, String episode, long pos, long dur) {
        saveLocal(c, pkg, title, episode, pos, dur, null, null);
    }

    static void saveLocal(Context c, String pkg, String title, String episode, long pos, long dur,
                          String showId) {
        saveLocal(c, pkg, title, episode, pos, dur, showId, null);
    }

    static void saveLocal(Context c, String pkg, String title, String episode, long pos, long dur,
                          String showId, String episodeId) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString("local2", "{}"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("pkg", pkg);
            o.put("pos", pos);
            o.put("dur", dur);
            o.put("ts", System.currentTimeMillis());
            String ep = epCode(episode);
            if (ep != null) o.put("ep", ep);
            String epname = episodeName(episode);
            if (epname != null) o.put("epname", epname);
            // Stored so a label-only record can be renamed once any row for this show
            // appears. The stream URL carries ids, not titles.
            if (showId != null && !showId.isEmpty()) o.put("sid", showId);
            // Both ids are needed to rebuild hotstar://in/shows/<slug>/<showId>/x/<epId>/watch
            // once a slug is known, so neither may be dropped.
            if (episodeId != null && !episodeId.isEmpty()) o.put("epid", episodeId);
            // Same episode, recorded earlier under its label ("S2 E4 …"): drop that key,
            // otherwise the list keeps a duplicate nobody can rate.
            java.util.List<String> drop = new java.util.ArrayList<>();
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (episode != null && !episode.equals(title) && k.equalsIgnoreCase(episode)) {
                    drop.add(k);
                }
            }
            for (String k : drop) all.remove(k);

            // One row per series, and it must not go backwards: re-watching an older
            // episode (or a stray tap on an older deeplink) used to overwrite the badge
            // and position of the newest one, which read as "my latest episode vanished".
            org.json.JSONObject prev = all.optJSONObject(title);
            if (prev != null && ep != null) {
                String prevEp = prev.optString("ep");
                if (!prevEp.isEmpty() && compareEp(ep, prevEp) < 0) {
                    prev.put("ts", System.currentTimeMillis());
                    all.put(title, prev);
                    p.edit().putString("local2", all.toString()).apply();
                    Log.i(TAG, "kept " + title + " at " + prevEp + " (played " + ep + ")");
                    return;
                }
            }

            all.put(title, o);
            p.edit().putString("local2", all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "saveLocal: " + e);
        }
    }

    /** Remember the YouTube video id so a row keeps its thumbnail and direct link. */
    static void saveVideo(Context c, String title, String vid) {
        if (title == null || vid == null) return;
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString("local2", "{}"));
            org.json.JSONObject o = all.optJSONObject(title);
            if (o == null) return;
            o.put("vid", vid);
            all.put(title, o);
            p.edit().putString("local2", all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "saveVideo: " + e);
        }
    }

    /** Every locally recorded row, newest first (used by the stats tab). */
    static java.util.List<Item> localAll(Context c) {
        return localRows(c, 0);
    }

    /** episode label ("S1 E5 Awakening") -> series title, from the provider rows. */
    static java.util.Map<String, String> episodeMap(Context c) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        try (Cursor cur = c.getContentResolver().query(
                Uri.parse("content://android.media.tv/watch_next_program"),
                new String[]{"title", "short_description"}, null, null, null)) {
            if (cur == null) return m;
            while (cur.moveToNext()) {
                String t = cur.getString(0);
                String sd = cur.getString(1);
                if (t != null && sd != null && sd.trim().matches("(?i)s\\d+\\s*e\\d+.*")) {
                    m.put(sd.trim().toLowerCase(), t);
                }
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    /** Series name for a show id, from any provider row for that show. */
    static String seriesForShow(Context c, String showId) {
        if (showId == null || showId.isEmpty()) return null;
        try (Cursor cur = c.getContentResolver().query(
                Uri.parse("content://android.media.tv/watch_next_program"),
                new String[]{"title", "intent_uri"}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String title = cur.getString(0);
                String intent = cur.getString(1);
                if (title == null || intent == null) continue;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/shows/[^/]+/(\\d+)/").matcher(intent);
                if (m.find() && showId.equals(m.group(1))) return title;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** "S9 E2 Vijay Deverakonda and Rashmika Mandanna" -> "Vijay Deverakonda…". */
    static String episodeName(String label) {
        if (label == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)^\\s*s\\d+\\s*e\\d+\\s*[:\\-\\u2013]?\\s*(.+)$")
                .matcher(label);
        if (!m.find()) return null;
        String t = m.group(1).trim();
        return t.isEmpty() ? null : t;
    }

    /** Locally recorded rows newer than {@code cutoff}, newest first. */
    static java.util.List<Item> localRows(Context c, long cutoff) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        java.util.Map<String, String> epMap = null;
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            org.json.JSONObject all = new org.json.JSONObject(p.getString("local2", "{}"));
            java.util.TreeMap<Long, Item> byTs = new java.util.TreeMap<>();
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                org.json.JSONObject o = all.getJSONObject(k);
                long ts = o.optLong("ts");
                if (ts < cutoff) continue;
                if (o.optLong("pos") <= 0) continue;
                if (isMusic(o.optString("pkg"))) continue;
                Item it = new Item();
                String title = k;
                String ep = o.optString("ep");
                // Records saved before the series lookup carry only "S1 E5 Awakening".
                // The provider's short_description holds exactly that string, so use it.
                // Either an old record without an episode code, or one saved under the
                // episode label because the series could not be resolved at the time.
                if (k.matches("(?i)^s\\d+\\s*e\\d+.*")) {
                    if (epMap == null) epMap = episodeMap(c);
                    String series = epMap.get(k.trim().toLowerCase());
                    if (series != null) {
                        title = series;
                        ep = epCode(k);
                    } else {
                        // No provider row names this episode's series, so the record
                        // keeps its label. Dropping it hid the title that was playing
                        // right now: the strip knew it, the list did not.
                        String sid = o.optString("sid");
                        String byShow = sid.isEmpty() ? null : seriesForShow(c, sid);
                        title = byShow != null ? byShow : k;
                        ep = epCode(k);
                    }
                }
                it.title = title;
                it.mine = true;
                it.pkg = o.optString("pkg");
                it.pos = o.optLong("pos");
                it.dur = o.optLong("dur");
                it.epname = o.optString("epname");
                it.sid = o.optString("sid");
                it.epid = o.optString("epid");
                // A user-supplied name wins; otherwise the sitemap names the show by
                // id, which is the only thing the stream URL carries.
                String alias = Sitemap.nameFor(c, it.sid);
                if (alias != null) it.title = alias;
                // Records written before the episode name was stored still carry it in
                // their key ("S9 E2 Vijay Deverakonda..."), so nothing needs replaying.
                if (it.epname == null || it.epname.isEmpty()) {
                    String fromKey = episodeName(k);
                    if (fromKey != null) it.epname = fromKey;
                }
                it.ts = ts;
                if (ep != null && !ep.isEmpty()) it.ep = ep;
                byTs.put(ts, it);
            }
            for (Item it : byTs.descendingMap().values()) {
                out.add(it);
                if (out.size() >= 10) break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    MediaController pick(List<MediaController> sessions) {
        MediaController paused = null;
        for (MediaController m : sessions) {
            // A music player keeps a paused session around forever; it is never "watching".
            if (isMusic(m.getPackageName())) continue;
            PlaybackState st = m.getPlaybackState();
            if (st == null) continue;
            if (st.getState() == PlaybackState.STATE_PLAYING) return m;
            if (paused == null && st.getState() == PlaybackState.STATE_PAUSED) paused = m;
        }
        return paused;
    }
}
