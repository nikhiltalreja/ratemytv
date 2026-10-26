package dev.ratemytv;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Take the player to a bookmarked position once whatever we launched is actually
 * playing.
 *
 * A single seekTo was not enough: the session exists while the title is still
 * buffering, so the seek landed on a player that then reset its playhead to zero when
 * the content finished loading - the bookmark appeared to start from the beginning.
 * So: wait for a playing state, seek, then verify the position and seek again.
 */
public class Seek {
    static final String TAG = "RATEMYTV";

    static final int MAX_WAITS = 80;
    static final int MAX_SEEKS = 4;
    static final long TOLERANCE_MS = 6000;

    static void after(final Context c, final String pkg, final long pos, int delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                waitForPlayback(c, pkg, pos, 0, 0);
            }
        }, delayMs);
    }

    static void waitForPlayback(final Context c, final String pkg, final long pos,
                                final int waited, final int seeks) {
        if (waited > MAX_WAITS || seeks >= MAX_SEEKS) {
            Log.i(TAG, "seek: gave up on " + pkg + " at " + pos + " (seeks=" + seeks + ")");
            return;
        }
        try {
            MediaController mc = find(c, pkg);
            if (mc != null && mc.getPlaybackState() != null) {
                PlaybackState st = mc.getPlaybackState();
                int state = st.getState();
                boolean playing = state == PlaybackState.STATE_PLAYING
                        || state == PlaybackState.STATE_PAUSED;
                if (playing) {
                    mc.getTransportControls().seekTo(pos);
                    Log.i(TAG, "seek: " + pkg + " -> " + pos + " (seek " + (seeks + 1)
                            + ", state=" + state + ")");
                    verify(c, pkg, pos, seeks + 1);
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "seek: " + e);
        }
        retry(c, pkg, pos, waited + 1, seeks, 1500);
    }

    /** Confirm the player actually moved; re-seek if the app reset it. */
    static void verify(final Context c, final String pkg, final long pos, final int seeks) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try {
                    MediaController mc = find(c, pkg);
                    if (mc == null || mc.getPlaybackState() == null) {
                        retry(c, pkg, pos, 0, seeks, 700);
                        return;
                    }
                    long at = mc.getPlaybackState().getPosition();
                    long delta = Math.abs(at - pos);
                    Log.i(TAG, "seek: at " + at + " target " + pos + " delta " + delta);
                    if (delta > TOLERANCE_MS) {
                        // The app reset us: seek again while we still have attempts.
                        retry(c, pkg, pos, 0, seeks, 300);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "seek verify: " + e);
                }
            }
        }, 1600);
    }

    static void retry(final Context c, final String pkg, final long pos,
                      final int waited, final int seeks, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                waitForPlayback(c, pkg, pos, waited, seeks);
            }
        }, delayMs);
    }

    static MediaController find(Context c, String pkg) {
        try {
            MediaSessionManager m = (MediaSessionManager)
                    c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(c, Watcher.class);
            for (MediaController mc : m.getActiveSessions(cn)) {
                if (pkg.equals(mc.getPackageName())) return mc;
            }
        } catch (Exception e) {
            Log.w(TAG, "seek find: " + e);
        }
        return null;
    }
}
