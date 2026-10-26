package dev.ratemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Opening this app means building a card: reading the TV provider, checking for a
 * release, maybe fetching the sitemap. That takes about a second, and the screen used
 * to sit on the launcher while it happened. A splash fills that second with the mark.
 *
 * The splash is the card's own footprint, not the whole screen: it is an opaque
 * rectangle in the same place, with the same corner radius, so the player stays
 * visible around it and the card appears to fill the panel in rather than replace the
 * television. Filling the screen made a one-second wait look like a channel change.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        boolean light = getSharedPreferences("xray", Context.MODE_PRIVATE)
                .getBoolean("light", false);

        DisplayMetrics dm = getResources().getDisplayMetrics();

        // The window itself is translucent (Theme.Translucent.NoTitleBar), so only the
        // panel below is painted; everything outside it is the player.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                light ? new int[]{0xF7F7F9FF, 0xF7E7EDFB}
                        : new int[]{0xF21B2440, 0xF22C1638});
        bg.setCornerRadius(38);
        bg.setStroke(2, light ? 0x551B63C7 : 0x5AA8C8FF);
        panel.setBackground(bg);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                (int) (dm.widthPixels * 0.95), (int) (dm.heightPixels * 0.90));
        plp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        plp.topMargin = (int) (dm.heightPixels * 0.04);
        root.addView(panel, plp);

        ImageView mark = new ImageView(this);
        try {
            // logo-dark is the full lockup (icon plus wordmark); the splash adds its
            // own name, so use the mark alone.
            Bitmap bm = BitmapFactory.decodeStream(getAssets().open("mark.png"));
            if (bm != null) mark.setImageBitmap(bm);
        } catch (Exception ignored) {
        }
        mark.setAdjustViewBounds(true);
        int w = (int) (dm.widthPixels * 0.16);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.addView(mark, mlp);

        TextView name = new TextView(this);
        name.setText("RATE MY TV");
        name.setTextSize(20);
        name.setLetterSpacing(0.18f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(light ? 0xFF1B2440 : 0xFFEAF2FF);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = 26;
        panel.addView(name, nlp);

        // CREDIT: the @nikhiltalrejasocial footer must stay in every build, splash
        // included. Do not remove it, hide it, or make it configurable.
        TextView credit = new TextView(this);
        credit.setText("@nikhiltalrejasocial");
        credit.setTextSize(11);
        credit.setTextColor(light ? 0xFF5A6480 : 0xFF8FA2C4);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = 10;
        panel.addView(credit, clp);

        setContentView(root);

        // Build the card immediately, behind the splash, and leave the splash as soon
        // as the card is painted. The old fixed 850 ms delay covered none of the work:
        // it just sat there and then started loading. Bounds keep both extremes sane -
        // a minimum so a fast build does not flash, a maximum so nothing can hang.
        final long start = System.currentTimeMillis();
        final boolean[] left = {false};

        final Runnable leave = new Runnable() {
            public void run() {
                if (left[0] || isFinishing()) return;
                left[0] = true;
                long age = System.currentTimeMillis() - start;
                // 600 ms was on screen for less time than it takes the eye to register
                // the mark: the card is ready in ~400 ms and the fade starts at once.
                long wait = Math.max(0, 1500 - age);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        // Bring the card up over the splash, then drop the splash. The
                        // other order left a gap where the launcher was visible.
                        OverlayController.reveal();
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            public void run() {
                                finish();
                                overridePendingTransition(0, 0);
                            }
                        }, 240);
                    }
                }, wait);

            }
        };

        OverlayController.whenReady(leave);
        OverlayController.show(this);

        // Safety net: if the card never reports ready, do not trap the user.
        new Handler(Looper.getMainLooper()).postDelayed(leave, 4000);
    }
}
