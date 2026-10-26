package dev.ratemytv;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The card: tabs for watching / watch these / stats / settings, one rich row per title. */
public class OverlayController {
    static final String TAG = "RATEMYTV";

    static View root;
    static WindowManager wm;
    static Context vCtx;
    static Handler ui = new Handler(Looper.getMainLooper());

    static TextView vAppTag, vStatus;
    /** The rule between the wordmark and the app name; hidden when no app is named. */
    static View vHeadSep;
    static LinearLayout vList, vSorter, vNow, vUpdate;
    static TextView vUpdateBtn;
    static TextView vNotice;
    static boolean vUpdateFocused;
    static TextView vNowTime;
    static View vNowFill;
    static String vNowSig;
    static int vListW;
    static int vCardW;
    static ScrollView vScroll;
    static TextView vTab0, vTab1, vTab2, vTab3, vSort, vLayout;
    /** What is playing, in the tab row. Replaces the NOW WATCHING panel. */
    static TextView vTicker;
    static String vTickerText;
    /** Theme switch in the tab row. */
    static ThemeSwitch vTheme;
    /** Tab a rebuild should land on; 0 is TAB_WATCHING, which is the normal case. */
    static int bootTab;
    /** Tab whose content is on screen, so a switch can put the list back at the top. */
    static int lastBuiltTab = -1;
    /** True when the tab changed and the next render should start from the top. */
    static boolean scrollTopNext;
    /** Set while a theme rebuild runs, so focus goes back to the switch. */
    static boolean keepThemeFocus;
    /** chip that was just pressed, so a rebuild can hand focus back to it. */
    static View lastChip;
    /** Topmost work row of the current tab, so UP can leave the list for the tabs. */
    static View vTopRow;

    static final int TAB_WATCHING = 0;
    static final int TAB_THESE = 1;
    static final int TAB_STATS = 2;
    static final int TAB_SETTINGS = 3;
    /** The LEFT-key flyout: not a tab, a panel that borrows the list area. */
    static final int TAB_MENU = 4;

    static int tab;
    /** Tab the menu was opened from, so closing it puts the card back. */
    static int menuReturnTab = TAB_WATCHING;
    static boolean menuOpen;
    /** 0 most recent first, 1 best rated first. */
    static int sortMode;
    /** 0 list of rows (tall posters), 1 grid of cards (wide art). */
    static int layoutMode;

    static final Map<String, Ratings.Info> cache = new HashMap<>();

    static class Holder {
        boolean wideFrame;
        /** True while this row is still waiting for its rating, plot and cast. */
        boolean loading;
        Watcher.Item item;
        ImageView poster;
        TextView title, rating, meta, plot, cast, epName;
    }

    /** How long a row shows placeholders before giving up and closing up. */
    static final long SKELETON_MS = 7000;

    static final List<Holder> holders = new ArrayList<>();

    /** Every focusable row of the current tab, so the lit one has a single owner. */
    static final List<View> ROWS = new ArrayList<>();

    /**
     * Repaint every row from its own focus state.
     *
     * Setting the lit background inside each row's own onFocusChange left rows lit
     * behind: the callback that clears one can run after the one that lights the next,
     * and a rebuild detaches rows that were lit. Reading focus here is idempotent -
     * at most one row can be lit, no matter who moved the cursor or how.
     */
    static void paintFocus() {
        for (int i = 0; i < ROWS.size(); i++) {
            View v = ROWS.get(i);
            v.setBackground(focusBg(v.hasFocus()));
            Object tag = v.getTag();
            if (tag instanceof Acts) drawActs((Acts) tag);
        }
    }

    /**
     * The card's own root view. Every key for this window enters here before any child
     * sees it, which is the only place BACK can be guaranteed: a focused EditText
     * would otherwise take it to close the keyboard, and a focused view with no BACK
     * listener would let the press fall through to the player underneath.
     */
    static class Card extends LinearLayout {
        Card(Context c) { super(c); }

        /** Dismiss on press, not release: nothing else can swallow it that way. */
        static boolean back(KeyEvent e) {
            return e.getAction() == KeyEvent.ACTION_DOWN
                    && (e.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE);
        }

        /**
         * LEFT is the menu, everywhere.
         *
         * Handled here rather than per view: the tab row, the settings key field, the
         * stat boxes and the flyout itself each did something different with it, so the
         * gesture worked on three screens out of six. The tab row is the one exception -
         * LEFT still walks back through the tabs, because that is what the row is for.
         *
         * The cost is the text cursor in the key field: LEFT no longer moves it. A
         * seven-character key is not worth a gesture that only works in one place.
         */
        static boolean left(KeyEvent e) {
            return e.getAction() == KeyEvent.ACTION_DOWN
                    && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT;
        }

        static boolean onTabRow() {
            return (vTab0 != null && vTab0.isFocused())
                    || (vTab1 != null && vTab1.isFocused())
                    || (vTab2 != null && vTab2.isFocused())
                    || (vTab3 != null && vTab3.isFocused());
        }

        @Override public boolean dispatchKeyEventPreIme(KeyEvent e) {
            if (back(e)) {
                if (menuOpen) closeMenu();
                else dismiss(true);
                return true;
            }
            return super.dispatchKeyEventPreIme(e);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (back(e)) {
                // Back closes the menu before it closes the card: the menu is a layer,
                // and leaving the app from inside it would lose where you were.
                if (menuOpen) closeMenu();
                else dismiss(true);
                return true;
            }
            if (left(e)) {
                if (menuOpen) {
                    closeMenu();
                    return true;
                }
                if (!onTabRow()) {
                    openMenu();
                    return true;
                }
            }
            return super.dispatchKeyEvent(e);
        }
    }

    // ---------------------------------------------------------------- theme

    static boolean light() {
        try {
            return vCtx.getSharedPreferences("xray", Context.MODE_PRIVATE)
                    .getBoolean("light", false);
        } catch (Exception e) {
            return false;
        }
    }

    static int accent() { return light() ? 0xFF1B63C7 : 0xFF7EE0FF; }
    static int txt() { return light() ? 0xFF14192B : 0xFFFFFFFF; }
    static int dim() { return light() ? 0xFF3E4A66 : 0xFFB9C2DE; }
    static int dim2() { return light() ? 0xFF6A7590 : 0xFF9AA6C8; }
    // Keep the main overlay glassy, but give each content row a readable surface.
    // The old 6% light fill let the launcher artwork bleed through the synopsis and
    // bookmark controls, especially in list mode. A cool-white translucent surface
    // preserves the light theme while restoring the text hierarchy at 10 feet.
    static int rowTint() { return light() ? 0xF2FFFFFF : 0xF51A2334; }
    static int rowTintSel() { return light() ? 0xFFFFFFFF : 0xFF263756; }
    static int stroke() { return light() ? 0x551B2440 : 0x2EA8C8FF; }

    static GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                light() ? new int[]{0xF7F7F9FF, 0xF7E7EDFB}
                        : new int[]{0xF21B2440, 0xF22C1638});
        d.setCornerRadius(38);
        d.setStroke(2, light() ? 0x551B63C7 : 0x5AA8C8FF);
        return d;
    }

    // ----------------------------------------------------------------- show

    static void show(final Context ctx) {
        Log.i(TAG, "show() root=" + (root != null));
        if (root != null) return;
        vCtx = ctx.getApplicationContext();
        wm = (WindowManager) vCtx.getSystemService(Context.WINDOW_SERVICE);
        key(vCtx, KeyEvent.KEYCODE_MEDIA_PAUSE);

        android.util.DisplayMetrics dm = vCtx.getResources().getDisplayMetrics();
        int cardW = (int) (dm.widthPixels * 0.95);
        int listW = Math.max(240, cardW - 108);
        vListW = listW;
        vCardW = cardW;

        LinearLayout card = new Card(vCtx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(54, 40, 54, 30);
        card.setBackground(cardBg());
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setOnKeyListener(BACK);

        LinearLayout head = new LinearLayout(vCtx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        final int headH = 57;                 // was 52, +10%
        // The brand is the real lockup, not type. Light theme takes the light set.
        ImageView brand = new ImageView(vCtx);
        Bitmap logo = assetBitmap(light() ? "logo-light.png" : "logo-dark.png");
        if (logo != null) {
            brand.setImageBitmap(logo);
            brand.setAdjustViewBounds(true);
            brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int w = Math.round(headH * (logo.getWidth() / (float) logo.getHeight()));
            brand.setLayoutParams(new LinearLayout.LayoutParams(w, headH));
            head.addView(brand);
        } else {
            TextView fallback = new TextView(vCtx);
            fallback.setText("RATE MY TV");
            fallback.setTextSize(16);
            fallback.setTypeface(Typeface.DEFAULT_BOLD);
            fallback.setLetterSpacing(0.22f);
            fallback.setTextColor(accent());
            head.addView(fallback);
        }
        vAppTag = new TextView(vCtx);
        // 25, down from 31: the app name was competing with the wordmark instead of
        // sitting beside it. WRAP_CONTENT height so the head's CENTER_VERTICAL centres
        // the text itself against the mark, rather than a 57px box it sits inside.
        vAppTag.setTextSize(25);
        vAppTag.setTextColor(txt());
        vAppTag.setTypeface(Typeface.DEFAULT_BOLD);
        vAppTag.setIncludeFontPadding(false);
        vAppTag.setGravity(Gravity.CENTER_VERTICAL);
        vAppTag.setSingleLine(true);
        vAppTag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vAppTag.setPadding(0, 0, 0, 0);

        // A hairline between "which product" and "which app": without it the lockup
        // read as one long string.
        View sep = new View(vCtx);
        vHeadSep = sep;
        sep.setBackgroundColor(light() ? 0x551B2440 : 0x66B9C2DE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(1, 38);
        slp.leftMargin = 20;
        slp.rightMargin = 20;
        head.addView(sep, slp);

        head.addView(vAppTag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(head);

        LinearLayout tabs = new LinearLayout(vCtx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        vTab0 = chip("WATCHING");
        vTab1 = chip("WATCH THESE");
        vTab2 = chip("STATS");
        vTab3 = chip("SETTINGS");
        vTab0.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { lastChip = v; tab = TAB_WATCHING; chrome(); buildTab(); }
        });
        vTab1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { lastChip = v; tab = TAB_THESE; chrome(); buildTab(); }
        });
        vTab2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { lastChip = v; tab = TAB_STATS; chrome(); buildTab(); }
        });
        vTab3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { lastChip = v; tab = TAB_SETTINGS; chrome(); buildTab(); }
        });
        tabs.addView(vTab0);
        tabs.addView(vTab1);
        tabs.addView(vTab2);
        tabs.addView(vTab3);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        // The ticker takes the slack between the last tab and the switch: what is
        // playing, on one line, marquee when it does not fit. It replaces the NOW
        // WATCHING panel, which said the same thing and cost a whole row of height.
        vTicker = new TextView(vCtx);
        vTicker.setTextSize(13);
        vTicker.setTypeface(Typeface.DEFAULT_BOLD);
        vTicker.setTextColor(txt());
        vTicker.setSingleLine(true);
        vTicker.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        vTicker.setMarqueeRepeatLimit(-1);
        vTicker.setHorizontallyScrolling(true);
        vTicker.setSelected(true);      // marquee without needing focus
        vTicker.setPadding(dp(14), dp(7), dp(14), dp(7));
        vTicker.setBackground(tickerBg());
        vTicker.setVisibility(View.GONE);
        // A fixed lane, with the flexible spacer before it: a weighted ticker that is
        // GONE collapses its share and drags the theme switch left across the row.
        View slack = new View(vCtx);
        tabs.addView(slack, new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(330),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(12);
        tlp.rightMargin = dp(12);
        tabs.addView(vTicker, tlp);
        vTheme = new ThemeSwitch(vCtx);
        // 30% smaller than the first cut: at 104x38 it crowded the RATING control that
        // sits directly below it and read as the loudest thing in the row.
        LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(dp(73), dp(27));
        swp.leftMargin = dp(6);
        tabs.addView(vTheme, swp);
        // Focus search from a chip picks the sort icons, not the next chip, so the
        // tab row moves itself: LEFT and RIGHT change tabs and keep the cursor here.
        tabKeys(vTab0, TAB_WATCHING);
        tabKeys(vTab1, TAB_THESE);
        tabKeys(vTab2, TAB_STATS);
        tabKeys(vTab3, TAB_SETTINGS);
        card.addView(tabs);

        // Outside the ScrollView on purpose: what is playing must stay put while
        // the list scrolls, as the handoff draws it.
        vNotice = new TextView(vCtx);
        vNotice.setTextSize(13);
        vNotice.setPadding(20, 10, 16, 10);
        vNotice.setVisibility(View.GONE);
        card.addView(vNotice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        vUpdate = new LinearLayout(vCtx);
        vUpdate.setOrientation(LinearLayout.VERTICAL);
        vUpdate.setVisibility(View.GONE);
        card.addView(vUpdate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        vNow = new LinearLayout(vCtx);
        vNow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowLp.topMargin = 12;
        vNow.setVisibility(View.GONE);
        card.addView(vNow, nowLp);

        vSorter = new LinearLayout(vCtx);
        vSorter.setOrientation(LinearLayout.HORIZONTAL);
        vSorter.setGravity(Gravity.END);
        vSorter.setPadding(0, 6, 0, 2);
        vSorter.setLayoutParams(new LinearLayout.LayoutParams(
                listW, ViewGroup.LayoutParams.WRAP_CONTENT));
        vSort = icon("⇅");
        vLayout = icon("☰");
        vSort.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { lastChip = v; sortMode = sortMode == 0 ? 1 : 0; prefSet(); buildTab(); }
        });
        vLayout.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { lastChip = v; layoutMode = layoutMode == 0 ? 1 : 0; prefSet(); buildTab(); }
        });
        vSorter.addView(vSort);
        vSorter.addView(vLayout);
        // The icons live on the section header line, which is above the first row. With
        // UP from the top row going straight to the tabs there was no way to reach them
        // at all, so they carry their own UP (tabs) and DOWN (first row).
        View.OnKeyListener sortKeys = new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    focusTabs();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && vTopRow != null) {
                    vTopRow.requestFocus();
                    return true;
                }
                return false;
            }
        };
        vSort.setOnKeyListener(sortKeys);
        vLayout.setOnKeyListener(sortKeys);
        // vSorter is added next to the section header instead of here, so the
        // controls sit on the same line as "CONTINUE WATCHING" as drawn.

        vStatus = new TextView(vCtx);
        vStatus.setTextSize(14);
        vStatus.setTextColor(dim2());
        vStatus.setPadding(2, 6, 0, 0);
        vStatus.setVisibility(View.GONE);
        card.addView(vStatus);

        vScroll = new ScrollView(vCtx);
        vScroll.setVerticalScrollBarEnabled(false);
        vScroll.setVerticalFadingEdgeEnabled(true);
        vScroll.setFadingEdgeLength(40);
        vScroll.setLayoutParams(new LinearLayout.LayoutParams(listW, 0, 1f));
        vList = new LinearLayout(vCtx);
        vList.setOrientation(LinearLayout.VERTICAL);
        vScroll.addView(vList);
        card.addView(vScroll);

        // CREDIT: the @nikhiltalrejasocial footer must stay in every build.
        // Do not remove it, hide it, make it configurable, or move it off-screen.
        TextView handle = new TextView(vCtx);
        handle.setText("@nikhiltalrejasocial");
        handle.setTextSize(12);
        handle.setTextColor(dim2());
        handle.setGravity(Gravity.END);
        handle.setPadding(0, 10, 0, 0);
        card.addView(handle);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                cardW, (int) (dm.heightPixels * 0.90),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (dm.heightPixels * 0.04);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                // Blurs the TV UI behind the card. Only the canvas is translucent, so
                // this is where the glass impression comes from.
                lp.setBlurBehindRadius(90);
            } catch (Throwable ignored) {
                Log.i(TAG, "window blur unavailable");
            }
        }

        try {
            wm.addView(card, lp);
            root = card;
            card.setAlpha(0f);
            card.setTranslationY(-28f);
            Log.i(TAG, "overlay added (hidden)");
        } catch (Exception e) {
            Log.w(TAG, "addView: " + e);
            return;
        }
        card.requestFocus();

        // A rebuild for a theme change should land back on the tab the user was on;
        // a fresh open always starts on WATCHING.
        tab = bootTab;
        bootTab = TAB_WATCHING;
        sortMode = prefGet();
        layoutMode = vCtx.getSharedPreferences("xray", Context.MODE_PRIVATE)
                .getInt("layout", 0);
        chrome();
        buildTab();
        // Ask once per process; the answer drives the banner in renderUpdate().
        vUpdateFocused = false;
        Updater.check(vCtx);
        Sitemap.refresh(vCtx, false);

        // Fade in only now that the rows exist, so no empty frame is ever painted.
        card.post(new Runnable() {
            public void run() {
                if (root == null) return;
                // Adding a window at alpha 0 and animating in the same frame left the
                // first frame unpainted on this device until an input event forced a
                // redraw: the card looked blank. End the animation on an explicit
                // alpha and invalidate, so the surface presents without a key press.
                Log.i(TAG, "overlay built, waiting to reveal");
                // Do not fade in yet: the splash is still opaque above the launcher
                // and a simultaneous crossfade showed the home screen through both.
                Runnable r = ready;
                ready = null;
                if (r != null) r.run();
                // Nothing is covering the screen - a theme repaint, or the card opened
                // from the long-press rather than from the app icon. The card was added
                // at alpha 0, so without this it stayed invisible: the window existed
                // but drew nothing, which looked exactly like the app quitting.
                else reveal();
            }
        });

        new Thread(new Runnable() {
            public void run() {
                // While the card is open, not six times and then never again. That cap
                // meant the NOW WATCHING strip froze on whatever had been playing when
                // the card opened, however long you kept it up.
                while (root != null) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    ui.post(new Runnable() {
                        public void run() {
                            if (root != null) status();
                        }
                    });
                }
                Log.i(TAG, "status loop stopped");
            }
        }).start();
    }

    /** tab highlight, sorter visibility and the playing status line. */
    static void chrome() {
        if (vTab0 == null) return;
        int on = accent(), off = dim2();
        // With the menu open the tab row keeps showing where you were, so closing the
        // menu is not a surprise about which page you will land on.
        int active = menuOpen ? menuReturnTab : tab;
        vTab0.setTextColor(active == TAB_WATCHING ? on : off);
        vTab1.setTextColor(active == TAB_THESE ? on : off);
        vTab2.setTextColor(active == TAB_STATS ? on : off);
        vTab3.setTextColor(active == TAB_SETTINGS ? on : off);
        // Colour alone was not enough at 10 feet: the active tab also carries a rule,
        // drawn by its own background so no extra view is needed.
        TextView[] chips = {vTab0, vTab1, vTab2, vTab3};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            chips[i].setBackground(active == i ? new TabRule(on) : rounded(0, 12, 0));
        }
        if (vSorter != null) vSorter.setVisibility(active == TAB_WATCHING ? View.VISIBLE : View.GONE);
        // A glyph pair was ambiguous: "★ sort is on" was easy to miss, and it looks
        // like a bug ("why is a 15-minute-old title below an 11-hour-old one?").
        vSort.setText(livePlaying() || sortMode == 0 ? "\u21C5  RECENT" : "\u2605  RATING");
        vLayout.setText(layoutMode == 0 ? "\u2630" : "\u25A6");
        vSort.setTextColor(on);
        vLayout.setTextColor(layoutMode == 0 ? off : on);
        status();
    }

    static String lastSessionTitle;
    /** Sort actually used for this render: recency whenever something is playing. */
    static int sortModeForNow;

    static boolean livePlaying() {
        return Watcher.CUR_TITLE != null && Watcher.CUR_TITLE.length() > 0;
    }

    /** Rebuild the watching list when what is playing changed, but not every second. */
    static void syncListWithSession() {
        if (tab != TAB_WATCHING || root == null) return;
        String nowTitle = Watcher.CUR_SERIES != null ? Watcher.CUR_SERIES : Watcher.CUR_TITLE;
        if (nowTitle == null || nowTitle.equals(lastSessionTitle)) return;
        lastSessionTitle = nowTitle;
        Log.i(TAG, "session changed -> " + nowTitle);
        buildTab();
    }

    static void status() {
        if (root == null || vStatus == null) return;
        if (vAppTag != null) {
            String tag = shortName(Watcher.CUR_PKG);
            vAppTag.setText(tag);
            if (vHeadSep != null) {
                vHeadSep.setVisibility(tag == null || tag.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
        renderNow();
        renderUpdate();
        if (Sitemap.consumeUpdated()) {
            // Rename in place. A buildTab() here tore the list down a second or two
            // after launch, which read as the list loading twice: the rows were
            // already on screen, only their names had changed.
            Log.i(TAG, "sitemap updated; renaming in place");
            retitleRows();
        }
        // Deliberately not rebuilding the list on session changes here. It fired a
        // second after the card opened, resetting row focus and the action cursor
        // mid-interaction, so a press could land on RESUME instead of the pill the
        // user had moved to. Order refreshes on open and on sort changes instead.
        // syncListWithSession();
        if (tab != TAB_WATCHING) { vStatus.setVisibility(View.GONE); return; }

        Watcher.Item cur = Watcher.CURRENT;
        String title = Watcher.CUR_TITLE;
        String pkg = Watcher.CUR_PKG;
        if (cur != null) { vStatus.setVisibility(View.GONE); return; }
        if (title != null && title.length() > 0) {
            // The strip above the list already says this, in more detail.
            vStatus.setVisibility(View.GONE);
            return;
        }
        if (title != null && title.length() > 0) {
            String series = Watcher.CUR_SERIES;
            vStatus.setVisibility(View.VISIBLE);
            vStatus.setText(series != null ? series + "  \u00B7  " + title : title);
            return;
        }
        if (pkg == null || Watcher.isMusic(pkg)) {
            vStatus.setVisibility(View.GONE);
            return;
        }
        vStatus.setVisibility(View.VISIBLE);
        vStatus.setText(pkg.contains("netflix")
                ? "Netflix publishes it a few minutes after you leave the player \u2014 try again then."
                : "Looking for what's playing\u2026");
    }

    /** The card is focusable, so focus search hands UP to the card, not the chips.
     *  Moving to the tab row explicitly is the only reliable way back to the tabs. */
    static void focusTabs() {
        // Leaving the list goes back to the top of it. Up from a scrolled row used to
        // hand the cursor to the tabs and leave the list where it was, so coming back
        // down started below the CONTINUE WATCHING heading - which cannot take focus,
        // so nothing could scroll up to it.
        if (vScroll != null) {
            vScroll.scrollTo(0, 0);
            vScroll.post(new Runnable() {
                public void run() {
                    if (vScroll != null) vScroll.scrollTo(0, 0);
                }
            });
        }
        TextView[] chips = {vTab0, vTab1, vTab2, vTab3};
        if (tab >= 0 && tab < chips.length && chips[tab] != null) {
            chips[tab].requestFocus();
        } else if (vTab0 != null) {
            vTab0.requestFocus();
        }
    }

    /**
     * UP from the top row lands on the sort / layout icons first, then on the tabs.
     * Those icons sit above the first row and cannot be reached any other way.
     */
    static void focusSort() {
        // Attached as well as visible: on a tab whose list has no section header the
        // icon strip is not in the tree, and focusing it would silently do nothing.
        if (vSorter != null && vSorter.getParent() != null
                && vSorter.getVisibility() == View.VISIBLE && vSort != null) {
            vSort.requestFocus();
            return;
        }
        focusTabs();
    }

    static void tabKeys(final TextView chip, final int index) {
        chip.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && index == TAB_WATCHING) {
                    // A second door to the flyout: with an empty history there is no row
                    // to press LEFT on, and the menu was otherwise unreachable.
                    openMenu();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && index > TAB_WATCHING) {
                    selectTab(index - 1);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && index < TAB_SETTINGS) {
                    selectTab(index + 1);
                    return true;
                }
                return false;
            }
        });
    }

    /** Change tab without leaving the tab row, so LEFT/RIGHT stays a tab switch. */
    static void selectTab(int which) {
        tab = which;
        chrome();
        buildTab();
        // buildTab focuses the content it just made; put the cursor back on the chip.
        ui.post(new Runnable() {
            public void run() {
                if (root != null) focusTabs();
            }
        });
    }

    static void buildTab() {
        if (root == null) return;
        // Both modes use the full content width. List mode gets its reading/action
        // rail; grid mode adapts its column count to keep cards comfortably sized.
        if (vCardW > 0) {
            int contentW = Math.max(240, vCardW - 108);
            int w = contentW;
            vListW = w;
            android.view.ViewGroup.LayoutParams lp = vScroll.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams && lp.width != w) {
                lp.width = w;
                vScroll.setLayoutParams(lp);
            }
            if (vSorter != null) {
                android.view.ViewGroup.LayoutParams slp = vSorter.getLayoutParams();
                if (slp instanceof LinearLayout.LayoutParams && slp.width != w) {
                    slp.width = w;
                    vSorter.setLayoutParams(slp);
                }
            }
        }
        // Whichever tab is being built clears the list when it has something to show.
        // Clearing here meant the pane sat empty for the whole worker round trip, which
        // is what made moving between tabs feel slow: the old content stayed valid
        // until the new content existed.
        // A different tab starts at the top. Without this the ScrollView kept the
        // previous tab's offset, so the new list opened already scrolled: CONTINUE
        // WATCHING was clipped and, because the heading cannot take focus, nothing
        // could scroll back up to it.
        scrollTopNext = tab != lastBuiltTab;
        lastBuiltTab = tab;
        if (tab == TAB_WATCHING) {
            // Coming back to a list we built seconds ago: paint it in this frame, then
            // let the worker confirm it. Without this the tab sat on the previous
            // tab's content for the whole provider round trip.
            if (lastCont != null && System.currentTimeMillis() - lastRowsAt < 30000) {
                Log.i(TAG, "watching: painting cached rows");
                renderList(lastCont, lastFresh);
            }
            buildWatching();
        } else if (tab == TAB_THESE) buildThese();
        else if (tab == TAB_STATS) buildStats();
        else if (tab == TAB_MENU) buildMenu();
        else buildSettings();
        paintFocus();
    }

    /** Empty the scroll pane and forget the rows that lived in it. */
    static void clearList() {
        vList.removeAllViews();
        holders.clear();
        ROWS.clear();
        vTopRow = null;
    }

    /**
     * Start a freshly built tab at the top of the list.
     *
     * Once, per tab change, and again after layout: the first call lands before the new
     * rows exist (so the view clamps against the old height), the posted one lands once
     * there is something to be at the top of.
     */
    static void maybeScrollTop() {
        if (!scrollTopNext) return;
        scrollTopNext = false;
        vScroll.scrollTo(0, 0);
        vScroll.post(new Runnable() {
            public void run() {
                if (vScroll == null || vList == null) return;
                vScroll.scrollTo(0, 0);
                vList.scrollTo(0, 0);
            }
        });
    }

    // ------------------------------------------------------------- watching

    /** The last rows built, so returning to WATCHING paints before it queries. */
    static List<Watcher.Item> lastCont, lastFresh;
    /** What is on screen right now, so a rebuild that changes nothing can be skipped. */
    static List<Watcher.Item> shownCont, shownFresh;
    static long lastRowsAt;

    /** True when both lists would render the same rows in the same order. */
    static boolean sameShape(List<Watcher.Item> cont, List<Watcher.Item> fresh) {
        if (shownCont == null || shownFresh == null) return false;
        if (shownCont.size() != cont.size() || shownFresh.size() != fresh.size()) return false;
        for (int i = 0; i < cont.size(); i++) {
            if (!sameRow(shownCont.get(i), cont.get(i))) return false;
        }
        for (int i = 0; i < fresh.size(); i++) {
            if (!sameRow(shownFresh.get(i), fresh.get(i))) return false;
        }
        return true;
    }

    static boolean sameRow(Watcher.Item a, Watcher.Item b) {
        if (a == null || b == null) return a == b;
        return eq(a.title, b.title) && a.pos == b.pos && a.dur == b.dur;
    }

    static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static void buildWatching() {
        new Thread(new Runnable() {
            public void run() {
                final List<Watcher.Item> cont = allRows(true);
                final List<Watcher.Item> fresh = allRows(false);
                lastCont = cont;
                lastFresh = fresh;
                lastRowsAt = System.currentTimeMillis();

                // While something is playing, its row is the one being looked for.
                // Rating order would bury it, so live playback always means recency -
                // and only for that render: the user's own choice comes back after.
                sortModeForNow = livePlaying() ? 0 : sortMode;

                if (sortModeForNow == 1 && haveScores(cont, fresh)) {
                    sortByRating(cont);
                    sortByRating(fresh);
                }
                // Draw first: waiting on OMDb would leave the pane empty for seconds.
                // If the rows on screen are already these rows, only the scores are
                // missing, and repainting them would be a flicker for nothing.
                final boolean same = sameShape(cont, fresh);
                ui.post(new Runnable() {
                    public void run() {
                        if (same) {
                            Log.i(TAG, "rows unchanged, filling scores only");
                            fill(cont, fresh);
                        } else {
                            renderList(cont, fresh);
                        }
                    }
                });

                if (sortModeForNow == 1 && !haveScores(cont, fresh)) {
                    // Sort by rating, but only rebuild the list if the order actually
                    // moved. Rebuilding anyway is what looked like the list loading
                    // twice on every cold start: the first pass drew by recency, and
                    // the second tore it down to shuffle rows that were already right.
                    java.util.List<String> before = titles(cont);
                    sortByRating(cont);
                    sortByRating(fresh);
                    final boolean moved = !before.equals(titles(cont));
                    ui.post(new Runnable() {
                        public void run() {
                            if (moved) {
                                Log.i(TAG, "second pass: order moved, re-render");
                                renderList(cont, fresh);
                            } else {
                                Log.i(TAG, "second pass: order unchanged, fill only");
                                fill(cont, fresh);   // ratings land in place
                            }
                        }
                    });
                }
            }
        }).start();
    }

    static boolean haveScores(List<Watcher.Item> a, List<Watcher.Item> b) {
        for (Watcher.Item it : a) if (!cache.containsKey(it.title)) return false;
        for (Watcher.Item it : b) if (!cache.containsKey(it.title)) return false;
        return true;
    }

    /** Just the titles, in order, so two sorts can be compared without rebuilding. */
    static java.util.List<String> titles(List<Watcher.Item> rows) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Watcher.Item it : rows) out.add(it.title == null ? "" : it.title);
        return out;
    }

    /**
     * Apply freshly resolved show names to the rows already on screen.
     *
     * The sitemap lands a second or two after launch, and every row it renamed used to
     * cost a full rebuild. Only the text changed, so only the text is touched.
     */
    static void retitleRows() {
        int changed = 0;
        for (int i = 0; i < holders.size(); i++) {
            Holder h = holders.get(i);
            Watcher.Item it = h.item;
            if (it == null || it.sid == null || it.sid.isEmpty()) continue;
            String name = Sitemap.nameFor(vCtx, it.sid);
            if (name == null || name.equals(it.title)) continue;
            Log.i(TAG, "renamed " + it.title + " -> " + name);
            it.title = name;
            if (h.title != null) {
                boolean labelOnly = name != null && name.matches("(?i)^s\\d+\\s*e\\d+.*");
                h.title.setText(it.ep != null && !labelOnly && !it.ep.isEmpty()
                        ? name + "  " + it.ep : name);
            }
            changed++;
        }
        if (changed > 0) paintFocus();
    }

    static void renderList(List<Watcher.Item> contIn, List<Watcher.Item> freshIn) {
        if (root == null || tab != TAB_WATCHING) return;
        Log.i(TAG, "renderList cont=" + contIn.size() + " fresh=" + freshIn.size());
        // The worker that posted this render can start sorting the same lists right
        // after the post (rating mode with scores still arriving), so iterate copies.
        List<Watcher.Item> cont = new ArrayList<>(contIn);
        List<Watcher.Item> fresh = new ArrayList<>(freshIn);
        shownCont = cont;
        shownFresh = fresh;
        clearList();
        renderNow();
        // A title shown in continue does not belong again under new/saved.
        // The live title goes first even if its timestamp has not caught up yet.
        String live = Watcher.CUR_SERIES != null ? Watcher.CUR_SERIES : Watcher.CUR_TITLE;
        if (live != null) {
            for (int i = 0; i < cont.size(); i++) {
                Watcher.Item it = cont.get(i);
                if (it.title != null && it.title.equalsIgnoreCase(live) && i > 0) {
                    cont.remove(i);
                    cont.add(0, it);
                    break;
                }
            }
        }

        java.util.Set<String> shown = new java.util.HashSet<>();
        for (Watcher.Item it : cont) {
            if (it.title != null) shown.add(it.title.toLowerCase());
        }
        java.util.List<Watcher.Item> extra = new ArrayList<>();
        for (Watcher.Item it : fresh) {
            if (it.title == null || !shown.contains(it.title.toLowerCase())) extra.add(it);
        }
        fresh = extra;

        if (cont.isEmpty() && fresh.isEmpty()) {
            // First run. An empty pane is indistinguishable from a broken one, so say
            // what to do about it.
            vList.addView(sectionHeader("CONTINUE WATCHING"));
            vList.addView(emptyState());
            // Nothing in this pane can take focus, so without this the card has no
            // focused control and UP never reaches the tabs: an empty history locked
            // the user out of every other screen.
            focusTabs();
        } else if (layoutMode == 0) {
            if (!cont.isEmpty()) {
                vList.addView(sectionHeader("CONTINUE WATCHING"));
                for (Watcher.Item it : cont) addRow(it);
            }
            if (!fresh.isEmpty()) {
                vList.addView(sectionHeader("NEW AND SAVED"));
                for (Watcher.Item it : fresh) addRow(it);
            }
        } else {
            if (!cont.isEmpty()) {
                vList.addView(sectionHeader("CONTINUE WATCHING"));
                grid(cont);
            }
            if (!fresh.isEmpty()) {
                vList.addView(sectionHeader("NEW AND SAVED"));
                grid(fresh);
            }
        }
        restoreFocus();
        paintFocus();
        maybeScrollTop();
        fill(cont, fresh);
    }

    /** A rebuild must not steal focus from the chip the user just pressed. */
    static void restoreFocus() {
        // A theme toggle rebuilds the whole card; the switch is where the hand was, so
        // put the cursor back on it instead of dropping it onto the first RESUME.
        if (keepThemeFocus) {
            keepThemeFocus = false;
            if (vTheme != null) {
                vTheme.requestFocus();
                return;
            }
        }
        View f = lastChip;
        lastChip = null;
        if (f != null) {
            f.requestFocus();
            return;
        }
        if (vList.getChildCount() > 1) vList.getChildAt(1).requestFocus();
    }

    /** Section title with the sort / rating / layout controls on the same line. */
    static LinearLayout sectionHeader(String text) {
        LinearLayout row = new LinearLayout(vCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // MATCH_PARENT or the weighted title collapses to nothing: a wrap-content
        // row has no width to hand out.
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(header(text), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (vSorter != null) {
            android.view.ViewParent parent = vSorter.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(vSorter);
            row.addView(vSorter, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    static TextView header(String text) {
        TextView h = new TextView(vCtx);
        h.setText(text);
        h.setTextSize(13);
        h.setLetterSpacing(0.10f);
        h.setTextColor(dim());
        h.setPadding(4, 14, 4, 8);
        return h;
    }

    /**
     * What the watching tab shows before anything has been played.
     *
     * Deliberately instructional: the first ten seconds decide whether the app looks
     * broken, and an empty list looks broken.
     */
    static View emptyState() {
        LinearLayout box = new LinearLayout(vCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(44, 44, 44, 46);
        box.setBackground(rounded(light() ? 0x40FFFFFF : 0x330D1424, 18, stroke()));

        TextView head = new TextView(vCtx);
        head.setText("Nothing watched yet");
        head.setTextSize(22);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(txt());
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(head);

        TextView body = new TextView(vCtx);
        body.setText("Play any title for a few seconds, then come back to this card. "
                + "It will be listed here with its score, cast and where you stopped.");
        body.setTextSize(14);
        body.setTextColor(dim());
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, 12, 0, 0);
        box.addView(body);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 6;
        box.setLayoutParams(lp);
        return box;
    }

    /** One slow pulse shared by every waiting row, rather than an animator per row. */
    static android.animation.ValueAnimator pulse;

    static void startSkeleton(Holder h) {
        h.loading = true;
        bar(h.rating, dp(88), dp(16));
        bar(h.plot, ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        bar(h.cast, dp(210), dp(11));

        if (pulse == null) {
            pulse = android.animation.ValueAnimator.ofFloat(0.4f, 0.95f);
            pulse.setDuration(700);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    float v = (Float) a.getAnimatedValue();
                    for (int i = 0; i < holders.size(); i++) {
                        Holder hh = holders.get(i);
                        if (!hh.loading) continue;
                        if (hh.rating != null) hh.rating.setAlpha(v);
                        if (hh.plot != null) hh.plot.setAlpha(v);
                        if (hh.cast != null) hh.cast.setAlpha(v);
                    }
                }
            });
        }
        if (!pulse.isRunning()) pulse.start();
    }

    /** Turn a text view into a placeholder bar of a given size. */
    /**
     * Turn a text view into a placeholder bar - without touching its LayoutParams.
     *
     * Sizing it by parameters was the wrong tool: the view is later filled with real
     * text, and a fixed height clipped it and spilled the overflow into the row below.
     * A minimum size only sets a floor, so the text can still grow into its box.
     */
    static void bar(TextView t, int width, int height) {
        if (t == null) return;
        t.setText("");
        t.setBackground(rounded(light() ? 0x1A1B2440 : 0x2EA8C8FF, 5, 0));
        t.setMinWidth(width);
        t.setMinHeight(height);
        t.setVisibility(View.VISIBLE);
        t.setAlpha(0.55f);
    }

    /** Back to a normal text view, whether the answer arrived or the wait timed out. */
    static void stopSkeleton(Holder h) {
        h.loading = false;
        clearBar(h.rating, 0);
        clearBar(h.plot, 50);   // the two-line floor the row was designed around
        clearBar(h.cast, 18);
    }

    static void clearBar(TextView t, int minHeight) {
        if (t == null) return;
        t.setAlpha(1f);
        t.setBackground(null);
        t.setMinWidth(0);
        t.setMinHeight(minHeight);
    }

    static void addRow(final Watcher.Item it) {
        final Holder h = new Holder();
        h.item = it;

        LinearLayout row = new LinearLayout(vCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Top, not centre: the row grows when the bookmark strip appears, and a
        // centred poster slid down the card every time it did.
        row.setGravity(Gravity.TOP);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setOnKeyListener(BACK);
        row.setPadding(34, 24, 34, 24);
        row.setBackground(rounded(rowTint(), 18, stroke()));

        h.poster = new ImageView(vCtx);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(126, 173);
        plp.topMargin = 2;
        h.poster.setLayoutParams(plp);
        h.poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.poster.setBackground(rounded(0x33000000, 10, 0));
        row.addView(h.poster);

        LinearLayout body = new LinearLayout(vCtx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(22, 0, 0, 0);
        row.addView(body, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout line1 = new LinearLayout(vCtx);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        h.title = new TextView(vCtx);
        // A record kept under its episode label already reads "S1 E1 ...", so appending
        // the code again produced "S1 E1 The Immortal Smile  S1 E01".
        boolean labelOnly = it.title != null
                && it.title.matches("(?i)^s\\d+\\s*e\\d+.*");
        h.title.setText(it.ep != null && !labelOnly ? it.title + "  " + it.ep : it.title);
        h.title.setTextSize(24);
        h.title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        h.title.setTextColor(txt());
        h.title.setSingleLine(true);
        h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line1.addView(h.title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (it.alsoOn != null && !it.alsoOn.isEmpty()) {
            // A licensed title that also lives on another service: say so, because the
            // resume may belong to that service and not to this app.
            TextView src = new TextView(vCtx);
            src.setText(shortName(it.alsoOn).toUpperCase());
            src.setTextSize(9);
            src.setLetterSpacing(0.12f);
            src.setTypeface(Typeface.DEFAULT_BOLD);
            src.setTextColor(dim2());
            src.setPadding(12, 5, 12, 5);
            src.setBackground(rounded(0x00000000, 10, stroke()));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.leftMargin = 12;
            line1.addView(src, slp);
        }
        h.rating = new TextView(vCtx);
        h.rating.setTextSize(15);
        h.rating.setTypeface(Typeface.DEFAULT_BOLD);
        h.rating.setTextColor(txt());
        h.rating.setGravity(Gravity.END);
        h.rating.setText("");   // the percent lives beside the bar now
        line1.addView(h.rating);
        body.addView(line1);

        h.epName = new TextView(vCtx);
        h.epName.setTextSize(13);
        h.epName.setTextColor(dim2());
        h.epName.setSingleLine(true);
        h.epName.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        h.epName.setMarqueeRepeatLimit(-1);
        h.epName.setHorizontallyScrolling(true);
        h.epName.setPadding(0, 1, 0, 0);
        h.epName.setVisibility(View.GONE);
        if (it.epname != null && !it.epname.isEmpty()) {
            h.epName.setText(it.epname);
            body.addView(h.epName);
        } else {
            h.epName = null;
        }

        h.meta = new TextView(vCtx);
        h.meta.setTextSize(14);
        h.meta.setTextColor(dim());
        h.meta.setSingleLine(true);
        h.meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.meta.setPadding(0, 3, 0, 0);
        h.meta.setText(channelLine(it));
        body.addView(h.meta);

        if (it.pos > 0 && it.dur > 0) {
            LinearLayout prow = new LinearLayout(vCtx);
            prow.setOrientation(LinearLayout.HORIZONTAL);
            prow.setGravity(Gravity.CENTER_VERTICAL);
            prow.addView(progress(it.pos, it.dur), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pct = new TextView(vCtx);
            pct.setText(watched(it));
            pct.setTextSize(11);
            pct.setTextColor(dim2());
            pct.setIncludeFontPadding(false);
            pct.setPadding(10, 0, 0, 0);
            prow.addView(pct);

            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp2.topMargin = 8;
            plp2.rightMargin = 16;
            body.addView(prow, plp2);
        }

        h.plot = new TextView(vCtx);
        h.plot.setTextSize(14);
        h.plot.setTextColor(dim2());
        h.plot.setMaxLines(2);
        h.plot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.plot.setLineSpacing(0, 1.04f);
        h.plot.setPadding(0, 9, 0, 0);
        // Reserve the two lines before OMDb answers. A row that grows from 90 to
        // 150 px when the network replies is the list "shaking on load".
        h.plot.setLines(2);
        h.plot.setMinHeight(50);
        body.addView(h.plot);

        h.cast = new TextView(vCtx);
        h.cast.setTextSize(13);
        h.cast.setTextColor(dim2());
        h.cast.setSingleLine(true);
        h.cast.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.cast.setPadding(0, 4, 0, 0);
        h.cast.setMinHeight(18);
        body.addView(h.cast);

        // Placeholders for exactly the three fields the fetcher fills: score, synopsis
        // and cast. They are thinner than the text they stand in for, so a row waiting
        // on the network is shorter than a finished one - and if nothing arrives, this
        // is what is removed.
        startSkeleton(h);
        final Holder pending = h;
        vList.postDelayed(new Runnable() {
            public void run() {
                if (!pending.loading) return;
                stopSkeleton(pending);
                if (pending.plot != null) pending.plot.setVisibility(View.GONE);
                if (pending.cast != null) pending.cast.setVisibility(View.GONE);
                if (pending.rating != null) {
                    pending.rating.setText("not rated");
                    pending.rating.setTextColor(dim2());
                }
                Log.i(TAG, "skeleton timeout: " + pending.item.title);
            }
        }, SKELETON_MS);
        final LinearLayout bookmarks = bookmarkStrip(it, row);
        final TextView[] resumeRef = new TextView[1];
        row.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                // On DOWN, not UP: Android's default focus search runs on the DOWN
                // first, so the top row would already have lost focus by the UP.
                if (code == KeyEvent.KEYCODE_DPAD_UP && v == vTopRow
                        && e.getAction() == KeyEvent.ACTION_DOWN) {
                    // Sort icons first: they sit above this row and have no other route.
                    focusSort();
                    return true;
                }
                if (e.getAction() != KeyEvent.ACTION_UP) return false;
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    dismiss(true);
                    return true;
                }
                Object tag = v.getTag();
                if (tag instanceof Acts) {
                    Acts a = (Acts) tag;
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        stepActs(a, 1);
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        // LEFT is the menu, not a step backwards through the actions.
                        // RIGHT wraps through RESUME, bookmarks and saved scenes, so
                        // nothing is lost - and the menu is now reachable from a row
                        // twenty deep, instead of only from the top of the list.
                        openMenu();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                        View target = actView(a);
                        if (target != null) target.performClick();
                        return true;
                    }
                }
                return false;
            }
        });

        final LinearLayout rightCol = new LinearLayout(vCtx);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.TOP);
        // 18 on the right, not 4: the pill fills this rail's width, so its right edge
        // sat 20 px from the row's visible border - tighter than any other gap on the
        // card, and close enough to read as touching the rounded corner.
        rightCol.setPadding(22, 0, 18, 0);
        final TextView resume = resumePill(false);
        resumeRef[0] = resume;
        if (row.getId() == View.NO_ID) row.setId(View.generateViewId());
        resume.setId(View.generateViewId());
        Acts acts = new Acts();
        acts.row = row;
        acts.resume = resume;
        acts.row = row;
        acts.bar = bookmarks;
        row.setTag(acts);
        fillBookmarks(bookmarks, it, row, resume);
        drawActs(acts);
        resume.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i(TAG, "resume " + it.title);
                if (launch(it)) dismiss(false);
            }
        });
        resume.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                paintFocus();
            }
        });
        rightCol.addView(resume);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 4;
        rightCol.addView(bookmarks, blp);
        LinearLayout.LayoutParams rclp = new LinearLayout.LayoutParams(372,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rclp.rightMargin = 2;
        row.addView(rightCol, rclp);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i(TAG, "play " + it.title);
                if (launch(it)) dismiss(false);
            }
        });
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (h.epName != null) {
                    h.epName.setVisibility(has ? View.VISIBLE : View.GONE);
                    h.epName.setSelected(has);
                }
                Object tag = v.getTag();
                if (tag instanceof Acts && has) ((Acts) tag).idx = 0;
                paintFocus();
                if (has) {
                    // Scrolling on every focus change fought the D-pad and shook the
                    // list. Only move when the row is outside the visible band.
                    int top = v.getTop(), bottom = v.getBottom();
                    int viewTop = vScroll.getScrollY();
                    // getTop() is in the list's coordinates, scrollY in the viewport's.
                    // Comparing them directly meant a row could be judged visible when
                    // it was off-screen, and the first row never pulled the list back up
                    // to reveal the heading above it.
                    int visibleTop = top - viewTop;
                    int visibleBottom = bottom - viewTop;
                    if (visibleTop < 8 || visibleBottom > vScroll.getHeight() - 8) {
                        int target = Math.max(0, top - vScroll.getHeight() / 2 + v.getHeight() / 2);
                        // Instant, not smooth: an animated scroll re-composites this
                        // translucent card ~14 times per press, and each frame costs
                        // ~24 ms on this SoC. One jump reads as snappier than that.
                        vScroll.scrollTo(0, target);
                    }
                }
            }
        });

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = 28;
        vList.addView(row, rlp);
        ROWS.add(row);
        if (vTopRow == null) vTopRow = row;
        holders.add(h);
        if (it.poster != null && !it.poster.isEmpty()) loadPoster(h, it.poster);
        else h.poster.setImageBitmap(placeholder(108, 162, it.title, it.pkg));
    }

    /**
     * A solid button that keeps its fill whether or not it has focus. chip() reverts to
     * a transparent background on blur, which left white text on white - unreadable in
     * exactly the state a user sees while moving towards the button.
     */
    static TextView solidButton(String label, int fillIn, int textColorIn) {
        final int fill = fillIn;
        final int text = textColorIn;
        TextView t = new TextView(vCtx);
        t.setText(label);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(text);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(150);
        t.setPadding(22, 12, 22, 12);
        t.setFocusable(true);
        t.setBackground(rounded(fill, 12, 0));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                // A thin outline was too faint to spot on a TV from across a room, so
                // focus inverts the button the way rows already do.
                TextView b = (TextView) v;
                if (has) {
                    // Same amber as the row focus ring, so "where am I" is one colour.
                    b.setBackground(rounded(0xFFFFC857, 12, 0xFFFFFFFF));
                    b.setTextColor(0xFF1B2440);
                } else {
                    b.setBackground(rounded(fill, 12, 0));
                    b.setTextColor(text);
                }
            }
        });
        return t;
    }

    /** A small focusable pill, used for the bookmark controls. */
    /**
     * The rule under the active tab, painted by the tab's own background: a second
     * view under the label needed its width back from the layout, and a MATCH_PARENT
     * child measured the whole row instead.
     */
    static class TabRule extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint();

        TabRule(int color) {
            paint.setColor(color);
            paint.setAntiAlias(true);
        }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float inset = b.width() * 0.06f;
            c.drawRoundRect(b.left + inset, b.bottom - 3, b.right - inset, b.bottom,
                    3, 3, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * Dark/light switch for the tab row: a glass pill with a lit knob.
     *
     * Drawn rather than assembled from views and assets - no image to ship, and the
     * state reads from the knob's position and colour alone at 10 feet. The halo
     * behind the track is the "3.0" part: depth from light rather than a drop shadow.
     */
    static class ThemeSwitch extends View {
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ThemeSwitch(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setContentDescription("Theme");
            setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // Come back to this switch, on this tab, when the card is rebuilt.
                    keepThemeFocus = true;
                    bootTab = tab;
                    setLight(!light());
                }
            });
            setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) { v.invalidate(); }
            });
        }
        /**
         * Segmented control, not a knob: two halves, and the selected half sits on a
         * slightly raised capsule. Icons are stroked, not filled, and there is no
         * gloss anywhere - the first version read as a 2010 widget.
         */
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            boolean lit = light();
            float pad = h * 0.10f;
            float segW = (w - pad * 2f) / 2f;
            float segH = h - pad * 2f;

            // track
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(lit ? 0xFFE4EAF4 : 0xFF0B1020);
            cv.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, p);

            // the selected half: a capsule a shade off the track, no shadow. Sun means
            // light and moon means dark, so the capsule follows the icon it belongs to.
            float sx = lit ? pad : pad + segW;
            p.setColor(lit ? 0xFFFBFDFF : 0xFF1C2740);
            cv.drawRoundRect(sx, pad, sx + segW, pad + segH, segH / 2f, segH / 2f, p);

            // icons: sun left, moon right; the active one carries the ink
            float cy = h / 2f;
            float ir = segH * 0.21f;
            int inkOff = lit ? 0xFF97A1B6 : 0xFF5A6784;
            int inkOn = lit ? 0xFF16233F : 0xFFF2F6FF;
            sun(cv, pad + segW / 2f, cy, ir, lit ? inkOn : inkOff);
            moon(cv, pad + segW + segW / 2f, cy, ir, lit ? inkOff : inkOn);

            // focus: the same rim the chips use, so it belongs to the card
            if (isFocused()) {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, h * 0.05f));
                p.setColor(lit ? 0xFF1B63C7 : 0xFFFFD75E);
                float i = p.getStrokeWidth() / 2f;
                cv.drawRoundRect(i, i, w - i, h - i, h / 2f, h / 2f, p);
                p.setStyle(android.graphics.Paint.Style.FILL);
            }
        }

        /** Sun: a ring with eight rays. Round caps - square ones read as blocks. */
        private void sun(android.graphics.Canvas cv, float x, float y, float r, int colour) {
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(1.6f, r * 0.22f));
            p.setColor(colour);
            cv.drawCircle(x, y, r * 0.44f, p);
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4.0;
                cv.drawLine(x + (float) Math.cos(a) * r * 0.74f,
                        y + (float) Math.sin(a) * r * 0.74f,
                        x + (float) Math.cos(a) * r * 1.02f,
                        y + (float) Math.sin(a) * r * 1.02f, p);
            }
            p.setStrokeCap(android.graphics.Paint.Cap.BUTT);
            p.setStyle(android.graphics.Paint.Style.FILL);
        }

        /** Moon: a crescent, stroked so it matches the sun's weight. */
        private void moon(android.graphics.Canvas cv, float x, float y, float r, int colour) {
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(1.6f, r * 0.22f));
            p.setColor(colour);
            android.graphics.Path crescent = new android.graphics.Path();
            crescent.addCircle(x, y, r * 0.92f, android.graphics.Path.Direction.CW);
            android.graphics.Path bite = new android.graphics.Path();
            bite.addCircle(x + r * 0.52f, y - r * 0.10f, r * 0.84f,
                    android.graphics.Path.Direction.CW);
            crescent.op(bite, android.graphics.Path.Op.DIFFERENCE);
            cv.drawPath(crescent, p);
            p.setStrokeCap(android.graphics.Paint.Cap.BUTT);
            p.setStyle(android.graphics.Paint.Style.FILL);
        }
    }

    /** Density helper: the switch draws in pixels, the layout is specified in dp. */
    static int dp(float v) {
        return Math.round(v * vCtx.getResources().getDisplayMetrics().density);
    }

    static TextView chip(String label, int textColor) {
        return chip(label, textColor, 11, 16, 9);
    }

    static TextView chip(String label, final int textColor, int size, int padX, int padY) {
        return chip(label, textColor, size, padX, padY, null);
    }

    static TextView chip(String label, final int textColor, int size, int padX, int padY,
                         final View owner) {
        final TextView t = new TextView(vCtx);
        t.setText(label);
        t.setTextSize(size);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(textColor);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMinWidth(150);
        t.setPadding(padX, Math.max(6, padY - 2), padX, Math.max(6, padY - 2));
        // Decoration only: the row owns the cursor, so a chip can never hold or lose
        // focus, and rebuilding the strip cannot strand it.
        t.setFocusable(false);
        t.setFocusableInTouchMode(false);
        t.setBackground(rounded(0, 12, 0));
        CHIP_COLOR.put(t, textColor);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                // A tinted fill was invisible once light rows became opaque white;
                // inverting the pill works on every surface and every theme.
                if (has) {
                    v.setBackground(rounded(accent(), 12, 0));
                    t.setTextColor(0xFFFFFFFF);
                } else {
                    v.setBackground(rounded(0, 12, 0));
                    t.setTextColor(textColor);
                }
                // Keep the parent row lit while one of its pills has focus, so the
                // eye still knows which title these controls belong to.
                if (owner != null) owner.setBackground(focusBg(has || owner.hasFocus()));
            }
        });
        return t;
    }

    /**
     * "+ Bookmark scene" plus one pill per saved scene. The pills rebuild in place
     * so adding or deleting does not kick the list focus back to the top.
     */
    static LinearLayout bookmarkStrip(final Watcher.Item it, final View row) {
        final LinearLayout bar = new LinearLayout(vCtx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 8, 0, 0);
        fillBookmarks(bar, it, row);
        return bar;
    }

    static void fillBookmarks(final LinearLayout bar, final Watcher.Item it, final View row) {
        fillBookmarks(bar, it, row, null);
    }

    static void fillBookmarks(final LinearLayout bar, final Watcher.Item it, final View row,
                              final View resume) {
        bar.removeAllViews();
        final java.util.List<View> order = new java.util.ArrayList<View>();

        final int have = Bookmarks.count(vCtx, it.title);
        final boolean full = have >= 2;
        final TextView add = chip(full ? "2 saved" : "+  Bookmark",
                full ? dim2() : accent(), 12, 14, 8, row);
        if (full) CHIP_COLOR.put(add, dim2());
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (full) {
                    toast("Two scenes is the limit \u2014 delete one first");
                    return;
                }
                Log.i(TAG, "add bookmark tapped: title=" + it.title + " pos=" + it.pos
                        + " dur=" + it.dur + " have=" + Bookmarks.count(vCtx, it.title));
                // The live player position beats the record, which lags behind a seek
                // and made a fresh bookmark repeat the previous one.
                long at = it.pos > 0 ? it.pos : 0;
                // Compare episode ids, not titles: a Jio row is titled by its show
                // ("Mobland") while the session is titled by its episode
                // ("S2 E5 Birdland"), so a title match never fired for Jio and the
                // bookmark kept repeating the stale record position.
                boolean playingRow = (it.epid != null && !it.epid.isEmpty()
                        && it.epid.equals(Watcher.CUR_EP_ID))
                        || (it.sid != null && !it.sid.isEmpty()
                        && it.sid.equals(Watcher.CUR_SHOW_ID))
                        || (it.title != null && Watcher.CUR_TITLE != null
                        && it.title.equalsIgnoreCase(Watcher.CUR_TITLE));
                Log.i(TAG, "bookmark compare: sid=" + it.sid + "/" + Watcher.CUR_SHOW_ID
                        + " epid=" + it.epid + "/" + Watcher.CUR_EP_ID
                        + " playing=" + playingRow + " live=" + Watcher.CUR_POS);
                if (Watcher.CUR_POS > 0 && playingRow) {
                    at = Watcher.CUR_POS;
                    Log.i(TAG, "bookmark uses live position " + at);
                }
                if (at <= 0) {
                    toast("Press play first \u2014 no position recorded yet");
                    return;
                }
                if (!Bookmarks.add(vCtx, it.title, at, it.dur)) {
                    notice("Already bookmarked here \u2014 move a little and try again");
                    return;
                }
                fillBookmarks(bar, it, row, resume);
                notice("Bookmarked " + fmt(at) + " in " + it.title);
            }
        });
        bar.addView(add);
        order.add(add);

        // One scene per line. Side by side they overflowed the column, so the extra
        // stops in the cycle existed but were clipped off-screen.
        JSONArray arr = Bookmarks.forTitle(vCtx, it.title);
        for (int i = 0; i < arr.length(); i++) {
            final long pos = arr.optJSONObject(i) != null
                    ? arr.optJSONObject(i).optLong("pos", 0) : 0;
            if (pos <= 0) continue;

            LinearLayout line = new LinearLayout(vCtx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = 4;
            bar.addView(line, llp);

            TextView go = chip("\u25B6 " + fmt(pos), txt(), 12, 13, 8, row);
            go.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Log.i(TAG, "bookmark -> " + it.title + " @ " + pos);
                    launch(it);
                    Seek.after(vCtx, it.pkg, pos, 3500);
                    dismiss(false);
                }
            });
            line.addView(go);
            order.add(go);

            TextView del = chip("\u2715", dim(), 12, 11, 8, row);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Bookmarks.remove(vCtx, it.title, pos);
                    fillBookmarks(bar, it, row, resume);
                }
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.leftMargin = 6;
            line.addView(del, dl);
            order.add(del);
        }

        // Only when the show is still nameless: the sitemap names almost everything, so
        // this control is a fallback for the gaps, not a permanent row of clutter.
        if (resume != null) {
            Object t = row.getTag();
            if (t instanceof Acts) {
                Acts a = (Acts) t;
                a.bar = bar;
                a.resume = (TextView) resume;
                a.order = order;
                if (a.idx > order.size()) a.idx = order.size();
                drawActs(a);
            }
        }
    }


    /**
     * Toast is unreliable from an overlay window on Android 12+ (the platform drops
     * text toasts from background contexts), so refusals are shown in the card itself.
     */
    static void notice(String msg) {
        if (vNotice == null) {
            toast(msg);
            return;
        }
        vNotice.setText(msg);
        vNotice.setTextColor(accent());
        vNotice.setBackground(rounded(light() ? 0xE6FFFFFF : 0xE6111929, 14, accent()));
        vNotice.setVisibility(View.VISIBLE);
        vNotice.removeCallbacks(hideNotice);
        vNotice.postDelayed(hideNotice, 4500);
    }

    static final Runnable hideNotice = new Runnable() {
        public void run() {
            if (vNotice != null) vNotice.setVisibility(View.GONE);
        }
    };

    static void toast(String msg) {
        try {
            android.widget.Toast.makeText(vCtx, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    static final android.graphics.drawable.Drawable[] FOCUS_CACHE =
            new android.graphics.drawable.Drawable[2];
    static boolean focusCacheLight = true;

    /**
     * Focused = cobalt edge plus a soft yellow halo. Cached per theme: this used to
     * allocate a LayerDrawable and two GradientDrawables on every focus change, which
     * measured 18-30 ms a frame - the jerking.
     */
    static android.graphics.drawable.Drawable focusBg(boolean on) {
        if (focusCacheLight != light()) {
            FOCUS_CACHE[0] = null;
            FOCUS_CACHE[1] = null;
            focusCacheLight = light();
        }
        int slot = on ? 1 : 0;
        if (FOCUS_CACHE[slot] == null) FOCUS_CACHE[slot] = buildFocusBg(on);
        return FOCUS_CACHE[slot];
    }

    static android.graphics.drawable.Drawable buildFocusBg(boolean on) {
        if (!on) return rounded(rowTint(), 18, stroke());

        // A single flat ring reads like a CSS outline. Three stacked rings with
        // falling alpha and growing inset give a soft glow that fades outward, which
        // is what makes a focused row look lifted rather than boxed.
        int halo = light() ? 0x33B8860B : 0x40FFC857;
        int mid = light() ? 0x55C79A00 : 0x66FFC857;
        int edge = light() ? 0xFFC79A00 : 0xFFFFD75E;

        GradientDrawable outer = new GradientDrawable();
        outer.setColor(0x00000000);
        outer.setCornerRadius(24);
        outer.setStroke(7, halo);

        GradientDrawable inner = new GradientDrawable();
        inner.setColor(0x00000000);
        inner.setCornerRadius(21);
        inner.setStroke(4, mid);

        GradientDrawable body = new GradientDrawable();
        body.setColor(light() ? 0xF7FFFFFF : 0xF7151F33);
        body.setCornerRadius(17);
        body.setStroke(2, edge);

        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{outer, inner, body});
        layers.setLayerInset(1, 3, 3, 3, 3);
        layers.setLayerInset(2, 7, 7, 7, 7);
        return layers;
    }

    /** Thin progress line: filled by position, the remainder is the track. */
    static LinearLayout progress(long pos, long dur) {
        int h = 6;
        LinearLayout line = new LinearLayout(vCtx);
        line.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout track = new LinearLayout(vCtx);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(light() ? 0x221B2440 : 0x33A8C8FF, 3, 0));

        float f = dur > 0 ? Math.min(1f, Math.max(0f, pos / (float) dur)) : 0f;
        View fill = new View(vCtx);
        fill.setBackground(rounded(accent(), 3, 0));
        track.addView(fill, new LinearLayout.LayoutParams(0, h, Math.max(0.001f, f)));
        track.addView(new View(vCtx), new LinearLayout.LayoutParams(0, h, Math.max(0.001f, 1f - f)));

        line.addView(track, new LinearLayout.LayoutParams(0, h, 1f));
        return line;
    }

    /** The row's dominant action. Primary when the row has focus. */
    static TextView resumePill(boolean primary) {
        TextView t = new TextView(vCtx);
        t.setText("\u25B6 RESUME");
        t.setSingleLine(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        styleResume(t, primary);
        return t;
    }

    /** Restyle in place. Rebuilding the column detached the pill that had focus. */
    static final java.util.Map<View, Integer> CHIP_COLOR =
            new java.util.WeakHashMap<View, Integer>();

    static final GradientDrawable[] RESUME_CACHE = new GradientDrawable[2];
    static boolean resumeCacheLight = true;

    /** Restyle in place. Cached drawables, and a no-op when the state already matches. */
    static void styleResume(TextView t, boolean primary) {
        if (t == null) return;
        if (t.getTag() instanceof Boolean && ((Boolean) t.getTag()) == primary
                && resumeCacheLight == light()) {
            return;
        }
        t.setTag(primary);

        // Same metrics in both states: changing size or padding makes the row
        // re-measure its text, which is the gap between 20 ms and a smooth move.
        // 14, not 18: at 56 px tall the pill was half again the height of the line it
        // sits next to and pushed the row's own text down the page.
        t.setTextSize(15);
        t.setTextColor(primary ? 0xFFFFFFFF : accent());
        t.setPadding(16, 14, 16, 14);

        if (resumeCacheLight != light()) {
            RESUME_CACHE[0] = null;
            RESUME_CACHE[1] = null;
            resumeCacheLight = light();
        }
        int slot = primary ? 1 : 0;
        if (RESUME_CACHE[slot] == null) {
            GradientDrawable d;
            if (primary) {
                d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        light() ? new int[]{0xFF4B91F2, 0xFF0D5BC7}
                                : new int[]{0xFF5DE4FF, 0xFF087CA4});
                d.setCornerRadius(16);
                d.setStroke(2, light() ? 0xFFD2E4FF : 0xFFB7F5FF);
            } else {
                d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        light() ? new int[]{0xFFEAF3FF, 0xFFC9DEFA}
                                : new int[]{0xFF18263A, 0xFF121B2B});
                d.setCornerRadius(16);
                d.setStroke(2, accent());
            }
            RESUME_CACHE[slot] = d;
        }
        t.setBackground(RESUME_CACHE[slot]);
        // No elevation: a shadow on a translucent overlay costs an extra draw pass
        // every frame it is on screen, and on a TV it buys almost nothing.
        t.setElevation(0f);
    }

    /** Fill (or hide) the pinned strip. Called whenever the list or the session changes. */
    /**
     * Update prompt. Sits above the list when a newer release exists: one focused
     * button, and the install is the system's own dialog, so the user sees the
     * signature change before anything happens.
     */
    /** Invoked once the card is built and painted. Used by the splash. */
    static Runnable ready;

    /** Fade the card in. Called once whatever covered the screen has finished. */
    static void reveal() {
        ui.post(new Runnable() {
            public void run() {
                if (root == null) return;
                root.animate().alpha(1f).translationY(0f).setDuration(180)
                        .withEndAction(new Runnable() {
                            public void run() {
                                if (root == null) return;
                                root.setAlpha(1f);
                                root.invalidate();
                            }
                        }).start();
                root.postInvalidateOnAnimation();
                Log.i(TAG, "overlay shown");
            }
        });
    }

    static void whenReady(Runnable r) {
        ready = r;
    }

    static void renderUpdate() {
        if (vUpdate == null) return;
        vUpdate.removeAllViews();
        if (!Updater.available(vCtx)) {
            vUpdate.setVisibility(View.GONE);
            return;
        }
        LinearLayout box = new LinearLayout(vCtx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(20, 12, 16, 12);
        box.setBackground(rounded(light() ? 0xF2FFFFFF : 0xF2182236, 16, accent()));

        TextView label = new TextView(vCtx);
        label.setText("Update available \u2014 " + Updater.latestTag
                + "  (you have " + Updater.installedVersion(vCtx) + ")");
        label.setTextSize(14);
        label.setTextColor(txt());
        box.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView go = solidButton("Install", accent(), 0xFFFFFFFF);
        vUpdateBtn = go;
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toast("Downloading update\u2026");
                dismiss(false);
                Updater.install(vCtx, null);
            }
        });
        box.addView(go);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 12;
        vUpdate.addView(box, lp);
        vUpdate.setVisibility(View.VISIBLE);

        // Take focus: the banner sits between the tabs and the list, where D-pad
        // traversal does not reliably land, and an update nobody can reach is a bug.
        if (!vUpdateFocused) {
            go.postDelayed(new Runnable() {
                public void run() {
                    if (go.isShown() && root != null) {
                        go.requestFocus();
                        vUpdateFocused = true;
                    }
                }
            }, 400);
        }
    }

    static void renderNow() {
        if (vNow == null) return;
        // The NOW WATCHING panel is gone: the ticker in the tab row says the same
        // thing, and the panel cost a full row of height on every screen.
        vNow.removeAllViews();
        vNow.setVisibility(View.GONE);
        updateTicker();
    }

    /**
     * What is playing, in the tab row: name, episode, position, one line.
     *
     * Marquee rather than ellipsis, because a long show name truncated to "The Madi…"
     * is useless at the one place you look to confirm what you are watching.
     */
    static void updateTicker() {
        if (vTicker == null) return;
        String show = Sitemap.nameFor(vCtx, Watcher.CUR_SHOW_ID);
        if (show == null) show = Watcher.CUR_SERIES;
        String ep = Watcher.CUR_TITLE;
        if ((show == null || show.isEmpty()) && (ep == null || ep.isEmpty())) {
            vTicker.setVisibility(View.GONE);
            return;
        }
        StringBuilder b = new StringBuilder("\u25B6  ");
        b.append(show != null && !show.isEmpty() ? show : ep);
        if (show != null && ep != null && !ep.isEmpty() && !ep.equalsIgnoreCase(show)) {
            b.append("   \u00B7   ").append(ep);
        }
        if (Watcher.CUR_DUR > 0 && Watcher.CUR_POS > 0) {
            b.append("   \u00B7   ").append(fmt(Watcher.CUR_POS))
                    .append(" / ").append(fmt(Watcher.CUR_DUR));
        }
        String text = b.toString();
        // Only touch the view when the text really changed, or the marquee restarts
        // every second and the line never travels.
        if (!text.equals(vTickerText)) {
            vTickerText = text;
            vTicker.setText(text);
        }
        vTicker.setVisibility(View.VISIBLE);
        vTicker.setSelected(true);
    }

    /** The ticker's pill: a dark body with a shadow peeking out below it. */
    static android.graphics.drawable.Drawable tickerBg() {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(light() ? 0x14000000 : 0x73000000);
        shadow.setCornerRadius(dp(14));

        GradientDrawable body = new GradientDrawable();
        body.setColor(light() ? 0xF2FFFFFF : 0xE6121C30);
        body.setCornerRadius(dp(14));
        body.setStroke(1, light() ? 0x331B2440 : 0x33A8C8FF);

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{shadow, body});
        ld.setLayerInset(1, 0, 0, 0, dp(3));
        return ld;
    }

    static void renderNowPanelUnused() {
        if (vNow == null) return;
        try {
            String sig = tab + "|" + Watcher.CUR_SHOW_ID + "|" + Watcher.CUR_SERIES
                    + "|" + Watcher.CUR_TITLE + "|" + Watcher.CUR_DUR;
            if (tab == TAB_WATCHING && sig.equals(vNowSig) && vNow.getChildCount() > 0) {
                updateNowProgress();
                return;
            }
            vNowSig = sig;

            vNow.removeAllViews();
            LinearLayout content = tab == TAB_WATCHING ? nowWatching() : null;
            if (content == null) {
                vNow.setVisibility(View.GONE);
                return;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 14;
            vNow.addView(content, lp);
            vNow.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Log.w(TAG, "renderNow: " + e);
            vNow.setVisibility(View.GONE);
        }
    }

    /** Cheap tick: move the bar and the clock, touch nothing else. */
    static void updateNowProgress() {
        try {
            if (vNowTime != null && Watcher.CUR_DUR > 0) {
                vNowTime.setText(fmt(Watcher.CUR_POS) + " / " + fmt(Watcher.CUR_DUR));
            }
            if (vNowFill != null && Watcher.CUR_DUR > 0) {
                float f = Math.min(1f, Math.max(0f, Watcher.CUR_POS / (float) Watcher.CUR_DUR));
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) vNowFill.getLayoutParams();
                lp.weight = Math.max(0.001f, f);
                vNowFill.setLayoutParams(lp);
                View rest = vNowFill.getParent() instanceof LinearLayout
                        ? ((LinearLayout) vNowFill.getParent()).getChildAt(1) : null;
                if (rest != null) {
                    LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) rest.getLayoutParams();
                    rp.weight = Math.max(0.001f, 1f - f);
                    rest.setLayoutParams(rp);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Live strip above the list: what is playing, how far in, and how good it is. */
    static LinearLayout nowWatching() {
        if (Watcher.CUR_TITLE == null) return null;
        LinearLayout box = new LinearLayout(vCtx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        // A panel, not a caption: this strip tells you the block is live and the list
        // below it is history, which the flat version left ambiguous.
        box.setPadding(26, 20, 26, 22);
        box.setBackground(rounded(light() ? 0xE6FFFFFF : 0xCC0D1424, 18,
                light() ? 0x441B2440 : 0x5536D6FF));

        LinearLayout left = new LinearLayout(vCtx);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(vCtx);
        label.setText("NOW WATCHING");
        label.setTextSize(10);
        label.setLetterSpacing(0.2f);
        label.setTextColor(accent());
        left.addView(label);

        // The stream's show id is authoritative; a provider row matched by episode
        // label is a guess. JioHotstar was serving The Madison while its session still
        // advertised the previous episode ("S1 E1 Pilot"), that label matched an
        // X-Files row, and the panel showed X-Files for a Madison stream.
        String show = Sitemap.nameFor(vCtx, Watcher.CUR_SHOW_ID);
        if (show == null) show = Watcher.CUR_SERIES;
        String epLabel = Watcher.CUR_TITLE;

        TextView title = new TextView(vCtx);
        title.setText(show != null ? show : epLabel);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(txt());
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(title);

        if (show != null && epLabel != null && !epLabel.equalsIgnoreCase(show)) {
            TextView sub = new TextView(vCtx);
            sub.setText(epLabel);
            sub.setTextSize(12);
            sub.setTextColor(dim2());
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            sub.setMarqueeRepeatLimit(-1);
            sub.setHorizontallyScrolling(true);
            sub.setSelected(true);
            left.addView(sub);
        }

        if (Watcher.CUR_DUR > 0) {
            LinearLayout line = new LinearLayout(vCtx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, 6, 0, 0);
            LinearLayout bar = progress(Watcher.CUR_POS, Watcher.CUR_DUR);
            if (bar.getChildCount() > 0 && bar.getChildAt(0) instanceof LinearLayout) {
                LinearLayout track = (LinearLayout) bar.getChildAt(0);
                if (track.getChildCount() > 0) vNowFill = track.getChildAt(0);
            }
            line.addView(bar,
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t = new TextView(vCtx);
            t.setText(fmt(Watcher.CUR_POS) + " / " + fmt(Watcher.CUR_DUR));
            t.setTextSize(11);
            t.setTextColor(dim());
            t.setPadding(12, 0, 0, 0);
            vNowTime = t;
            line.addView(t);
            left.addView(line);
        }
        box.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Ratings.Info info = cache.get(Watcher.CUR_SERIES != null ? Watcher.CUR_SERIES
                : Watcher.CUR_TITLE);
        if (info != null && info.imdb != null) {
            TextView r = new TextView(vCtx);
            r.setText("\u2605 " + info.imdb);
            r.setTextSize(20);
            r.setTypeface(Typeface.DEFAULT_BOLD);
            r.setTextColor(accent());
            r.setPadding(18, 0, 0, 0);
            box.addView(r);
        }
        return box;
    }

    /**
     * What this row is, as far as OMDb is concerned. A row with an episode code is a
     * series; without one it is a film. Passing nothing lets a same-named film answer
     * for a series - which is exactly what happened to "Captive".
     */
    static String kindOf(Watcher.Item it) {
        if (it == null) return null;
        return it.ep != null && !it.ep.isEmpty() ? "series" : "movie";
    }

    /** A row's action cursor: 0 RESUME, then + Bookmark, each scene, each delete. */
    static class Acts {
        View row;
        TextView resume;
        LinearLayout bar;
        java.util.List<View> order;
        int idx;
    }

    static void drawActs(Acts a) {
        if (a == null || a.resume == null || a.bar == null) return;
        java.util.List<View> all = new java.util.ArrayList<View>();
        all.add(a.resume);
        if (a.order != null) all.addAll(a.order);
        if (all.size() == 0) return;
        if (a.idx >= all.size()) a.idx = all.size() - 1;
        if (a.idx < 0) a.idx = 0;

        for (int i = 0; i < all.size(); i++) {
            View v = all.get(i);
            // Primary only when the row itself has focus. Without this every row drew
            // its RESUME as if hovered, because every row's cursor starts at 0.
            boolean rowFocused = a.row != null && a.row.hasFocus();
            boolean on = rowFocused && i == a.idx;
            if (v == a.resume) {
                styleResume(a.resume, on);
                continue;
            }
            Integer stored = CHIP_COLOR.get(v);
            int base = stored != null ? stored : 0xFF000000;
            if (on) {
                v.setBackground(rounded(accent(), 12, 0));
                ((TextView) v).setTextColor(0xFFFFFFFF);
            } else {
                v.setBackground(rounded(0, 12, 0));
                ((TextView) v).setTextColor(base);
            }
        }
    }

    static View actView(Acts a) {
        if (a == null || a.resume == null || a.bar == null) return null;
        if (a.idx == 0) return a.resume;
        int i = a.idx - 1;
        return a.order != null && i < a.order.size() ? a.order.get(i) : null;
    }

    static void stepActs(Acts a, int delta) {
        if (a == null || a.bar == null) return;
        int n = 1 + (a.order == null ? 0 : a.order.size());
        if (n <= 1) a.idx = 0;
        else a.idx = ((a.idx + delta) % n + n) % n;
        drawActs(a);
    }

    static String channelLine(Watcher.Item it) {
        StringBuilder b = new StringBuilder();
        if (it.pos > 0) b.append(fmt(it.pos));
        if (it.type == 1) b.append(b.length() > 0 ? "  \u00B7  " : "").append("NEXT");
        if (it.type == 2) b.append(b.length() > 0 ? "  \u00B7  " : "").append("NEW");
        if (it.type == 3) b.append(b.length() > 0 ? "  \u00B7  " : "").append("SAVED");
        b.append(b.length() > 0 ? "  \u00B7  " : "").append(shortName(it.pkg));
        return b.toString();
    }

    static void fill(final List<Watcher.Item> cont, final List<Watcher.Item> fresh) {
        new Thread(new Runnable() {
            public void run() {
                List<Watcher.Item> all = new ArrayList<>(cont);
                all.addAll(fresh);

                // Anything cached for a title that is no longer on the card is dead
                // weight: its OMDb text and its poster are dropped here.
                java.util.Set<String> live = new java.util.HashSet<String>();
                for (Watcher.Item it : all) live.add(Cache.key(it.title));
                // Everything the TV still remembers, not merely what fits on screen.
                for (Watcher.Item it : Watcher.localAll(vCtx)) live.add(Cache.key(it.title));
                for (String b : Bookmarks.titles(vCtx)) live.add(Cache.key(b));
                Cache.prune(vCtx, live);

                int n = 0;
                for (final Watcher.Item it : all) {
                    if (n++ >= 6) break;
                    Ratings.Info info = cache.get(it.title);
                    if (info == null) {
                        info = Ratings.lookup(vCtx, it.title, kindOf(it));
                        if (info != null) cache.put(it.title, info);
                    }
                    final Ratings.Info fi = info;
                    if (fi == null) continue;
                    ui.post(new Runnable() {
                        public void run() { apply(it, fi); }
                    });
                }
            }
        }).start();
    }

    static void apply(Watcher.Item it, Ratings.Info i) {
        for (Holder h : holders) {
            if (h.item != it) continue;
            // The answer arrived: drop the placeholders and let the text size itself.
            stopSkeleton(h);

            StringBuilder r = new StringBuilder();
            if (i.imdb != null) {
                r.append("\u2605 ").append(i.imdb);
                if (i.imdbVotes != null) r.append(" (").append(i.imdbVotes).append(")");
            }
            if (i.rt != null) r.append(r.length() > 0 ? "   " : "")
                    .append("\uD83C\uDF45 ").append(i.rt);
            if (r.length() > 0) {
                h.rating.setText(r.toString());
            } else {
                // An empty corner reads as a failure; say what happened instead. OMDb
                // has no entry for a lot of regional Indian titles.
                h.rating.setText("not rated");
                h.rating.setTextColor(dim2());
            }

            StringBuilder m = new StringBuilder();
            if (i.year != null) m.append(i.year);
            if (i.type != null) m.append(m.length() > 0 ? "  \u00B7  " : "")
                    .append("series".equalsIgnoreCase(i.type) ? "TV series"
                            : "movie".equalsIgnoreCase(i.type) ? "Movie" : i.type);
            if (it.pos > 0) m.append(m.length() > 0 ? "  \u00B7  " : "").append(fmt(it.pos));
            if (it.dur > 0) m.append(" / ").append(fmt(it.dur));
            if (it.type == 1) m.append(m.length() > 0 ? "  \u00B7  " : "").append("NEXT");
            if (it.type == 2) m.append(m.length() > 0 ? "  \u00B7  " : "").append("NEW");
            if (it.type == 3) m.append(m.length() > 0 ? "  \u00B7  " : "").append("SAVED");
            m.append(m.length() > 0 ? "  \u00B7  " : "").append(shortName(it.pkg));
            // Only our own records are viewing history; a provider row's timestamp is
            // the account's last engagement and says nothing about this TV.
            if (it.mine && it.ts > 0) m.append("  \u00B7  watched ").append(age(it.ts));
            h.meta.setText(m.toString());

            if (h.plot != null) {
                if (i.plot == null) {
                    // No synopsis: close the gap instead of leaving a blank band where
                    // the skeleton used to be.
                    h.plot.setVisibility(View.GONE);
                } else {
                // The plot OMDb returns describes the show, not the row's title, so
                // name the show in front of it. Skipped when the text already says it.
                String show = it.title == null ? null : it.title.trim();
                String body = i.plot;
                if (show != null && !show.isEmpty()
                        && i.plot.toLowerCase().indexOf(show.toLowerCase()) < 0) {
                    h.plot.setText(show + " \u2014 " + i.plot);
                } else {
                    h.plot.setText(body);
                }
                h.plot.setVisibility(View.VISIBLE);
                }
            }
            if (h.cast != null && i.actors != null) {
                h.cast.setText("Stars  " + i.actors.replace(", ", "  \u00B7  "));
                h.cast.setVisibility(View.VISIBLE);
            }
            boolean noart = it.poster == null || it.poster.isEmpty();
            if (i.poster != null && !i.poster.isEmpty() && (!h.wideFrame || noart)) {
                loadPoster(h, i.poster);
            }
            return;
        }
    }

    // ---------------------------------------------------------- watch these

    /**
     * 2026 series above 7.0 on IMDb, each one on JioHotstar.
     *
     * The list is joined live by {@link WatchThese} from the JioHotstar sitemap and the
     * public ratings catalogue, then cached for half a day. Drawing never waits
     * on the network: the cached (or bundled) list is painted first and the panel
     * rebuilds itself if a refresh lands while it is open. Text only - no poster, no
     * plot, no cast - so a refresh costs about 45 KB, not one lookup per title.
     */
    static void buildThese() {
        clearList();
        vTopRow = null;

        boolean refreshing = WatchThese.stale(vCtx);
        List<WatchThese.Pick> picks = WatchThese.cached(vCtx);
        boolean live = !picks.isEmpty();
        if (!live) picks = WatchThese.seed();
        if (refreshing) WatchThese.refresh(vCtx, false);

        // Where the list came from, on the same line as the title: five rows plus a
        // footer note do not fit the card, and this says more than the note did.
        LinearLayout headRow = new LinearLayout(vCtx);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headRow.addView(header("WATCH THESE"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView source = new TextView(vCtx);
        source.setText(picks.size() + (picks.size() == 1 ? " title" : " titles")
                + (live ? " \u00B7 updated " + age(WatchThese.cachedAt(vCtx))
                        : " \u00B7 saved " + WatchThese.SEED_CHECKED));
        source.setTextSize(11);
        source.setTextColor(dim2());
        source.setPadding(0, 14, 4, 8);
        headRow.addView(source);
        vList.addView(headRow);

        TextView intro = new TextView(vCtx);
        intro.setText("Series that started in 2026, above "
                + String.format(java.util.Locale.US, "%.1f", WatchThese.MIN_RATING)
                + " on IMDb, streaming on JioHotstar. OK opens one.");
        intro.setTextSize(13);
        intro.setTextColor(dim());
        intro.setLineSpacing(0, 1.12f);
        intro.setPadding(6, 0, 6, 8);
        vList.addView(intro);

        if (refreshing) {
            TextView wait = new TextView(vCtx);
            wait.setText(live ? "Refreshing titles and scores\u2026"
                    : "Fetching JioHotstar's index and IMDb scores\u2026");
            wait.setTextSize(12);
            wait.setTextColor(dim2());
            wait.setPadding(6, 0, 6, 6);
            vList.addView(wait);
        }

        int firstLiveRow = vList.getChildCount();
        for (int i = 0; i < picks.size(); i++) {
            addPick(i + 1, picks.get(i));
        }

        // Returning shows, hand-checked: the live join above cannot see them, because
        // it selects on the series' original year and a 2026 season does not change
        // that. See WatchThese.returning().
        List<WatchThese.Pick> again = WatchThese.returning();
        if (!again.isEmpty()) {
            vList.addView(header("NEW SEASONS IN 2026"));
            TextView why = new TextView(vCtx);
            why.setText("Returning series with a season that premiered this year. "
                    + "Checked by hand: the list above only knows shows that started in 2026.");
            why.setTextSize(12);
            why.setTextColor(dim2());
            why.setLineSpacing(0, 1.1f);
            why.setPadding(6, 0, 6, 8);
            vList.addView(why);
            for (WatchThese.Pick p : again) {
                addPickSeason(p);
            }
        }

        maybeScrollTop();
        if (firstLiveRow > 0 && firstLiveRow < vList.getChildCount()) {
            vList.getChildAt(firstLiveRow).requestFocus();
        }
    }

    static void addPick(final int rank, final WatchThese.Pick p) {
        addPickRow(String.format(java.util.Locale.US, "%02d", rank),
                rank <= 3 ? (light() ? 0xFFB8860B : 0xFFFFC857) : dim2(), 22, p);
    }

    static void addPickSeason(final WatchThese.Pick p) {
        // "S3" in the numeral slot: same row, different claim — this show did not
        // start this year, its new season did.
        addPickRow("S" + p.season, accent(), 16, p);
    }

    static void addPickRow(final String lead, final int leadColor, final int leadSize,
                           final WatchThese.Pick p) {
        LinearLayout row = new LinearLayout(vCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                // On DOWN, not UP: Android's default focus search runs on the DOWN
                // first, so the top row would already have lost focus by the UP.
                if (code == KeyEvent.KEYCODE_DPAD_UP && v == vTopRow
                        && e.getAction() == KeyEvent.ACTION_DOWN) {
                    // Sort icons first: they sit above this row and have no other route.
                    focusSort();
                    return true;
                }
                if (e.getAction() != KeyEvent.ACTION_UP) return false;
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    dismiss(true);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    openMenu();
                    return true;
                }
                return false;
            }
        });
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(28, 16, 28, 16);
        row.setBackground(rounded(rowTint(), 18, stroke()));

        // The numeral is the only "art" left. Dropping the poster is the point: a
        // refresh now costs one small list, not five image downloads per open.
        TextView num = new TextView(vCtx);
        num.setText(lead);
        num.setTextSize(leadSize);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        // The top three carry the accent: with every row otherwise equal, rank is
        // the one thing worth emphasising. A season tag carries the accent instead.
        num.setTextColor(leadColor);
        num.setGravity(Gravity.CENTER);
        num.setIncludeFontPadding(false);
        num.setLetterSpacing(0.02f);
        num.setSingleLine(true);
        row.addView(num, new LinearLayout.LayoutParams(80,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(vCtx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(16, 0, 0, 0);
        row.addView(body, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(vCtx);
        title.setText(p.title);
        title.setTextSize(23);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(txt());
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(title);

        TextView meta = new TextView(vCtx);
        String when = p.season > 0 ? "Season " + p.season + ", 2026" : String.valueOf(p.year);
        String line = when + (p.genres.isEmpty() ? "" : "  \u00B7  " + p.genres);
        meta.setText(line + "  \u00B7  " + p.votesText() + " votes");
        meta.setTextSize(12);
        meta.setTextColor(dim());
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setPadding(0, 3, 0, 0);
        body.addView(meta);

        // One right rail holds the score badge and the action. The badge is filled
        // for 8.5+, tinted for 8.0+, outlined below: quality is scannable without
        // reading the number, and the title keeps the full width.
        double rating = 0;
        try {
            rating = Double.parseDouble(p.imdb);
        } catch (Exception ignored) {
        }
        int gold = light() ? 0xFFB8860B : 0xFFFFC857;

        LinearLayout rail = new LinearLayout(vCtx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.END);
        rail.setPadding(18, 0, 2, 0);

        TextView score = new TextView(vCtx);
        score.setText("\u2605 " + p.imdb);
        score.setTextSize(15);
        score.setTypeface(Typeface.DEFAULT_BOLD);
        score.setGravity(Gravity.CENTER);
        score.setPadding(14, 5, 14, 5);
        if (rating >= 8.5) {
            score.setBackground(rounded(gold, 10, 0));
            score.setTextColor(0xFF14192B);
        } else if (rating >= 8.0) {
            score.setBackground(rounded(light() ? 0x22B8860B : 0x33FFC857, 10, gold));
            score.setTextColor(gold);
        } else {
            score.setBackground(rounded(0, 10, light() ? 0x3314192B : 0x33FFFFFF));
            score.setTextColor(dim());
        }
        rail.addView(score);

        final TextView watch = new TextView(vCtx);
        watch.setText("\u25B6  JIOHOTSTAR");
        watch.setTextSize(11);
        watch.setLetterSpacing(0.10f);
        watch.setTypeface(Typeface.DEFAULT_BOLD);
        watch.setTextColor(accent());
        watch.setGravity(Gravity.END);
        watch.setPadding(0, 9, 0, 0);
        rail.addView(watch);

        row.addView(rail);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i(TAG, "watch these: " + p.title);
                if (openShow(p.url)) dismiss(false);
            }
        });
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                paintFocus();
                // The action is what OK will do: light it with the row.
                watch.setTextColor(has ? txt() : accent());
                if (!has) return;
                // Same rule as the watching rows: jump only when the row is not
                // already fully on screen, so focus never shakes the list.
                int top = v.getTop(), bottom = v.getBottom();
                int viewTop = vScroll.getScrollY();
                int visibleTop = top - viewTop;
                int visibleBottom = bottom - viewTop;
                if (visibleTop < 8 || visibleBottom > vScroll.getHeight() - 8) {
                    int target = Math.max(0, top - vScroll.getHeight() / 2 + v.getHeight() / 2);
                    vScroll.scrollTo(0, target);
                }
            }
        });

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = 18;
        vList.addView(row, rlp);
        ROWS.add(row);
        if (vTopRow == null) vTopRow = row;
    }

    /** A live refresh landed; repaint the panel if it is the one on screen. */
    static void theseUpdated() {
        ui.post(new Runnable() {
            public void run() {
                if (root != null && tab == TAB_THESE) buildTab();
            }
        });
    }

    /** Open a JioHotstar show page; the system routes hotstar.com to the app. */
    static boolean openShow(String url) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            vCtx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open show: " + e);
            notice("Could not open JioHotstar for this title.");
            return false;
        }
    }

    // ---------------------------------------------------------------- stats

    static void buildStats() {
        new Thread(new Runnable() {
            public void run() {
                long monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
                List<Watcher.Item> all = Watcher.localAll(vCtx);
                java.util.Set<String> month = new java.util.HashSet<>();
                for (Watcher.Item it : all) {
                    if (it.ts >= monthAgo) month.add(it.title.toLowerCase());
                }
                for (Watcher.Item it : allRows(true)) {
                    if (it.ts >= monthAgo) month.add(it.title.toLowerCase());
                }
                double sum = 0;
                int rated = 0;
                int strong = 0;
                int awful = 0;
                int n = 0;
                for (Watcher.Item it : all) {
                    if (n++ >= 20) break;
                    Ratings.Info i = cache.get(it.title);
                    if (i == null) {
                        i = Ratings.lookup(vCtx, it.title, kindOf(it));
                        if (i != null) cache.put(it.title, i);
                    }
                    if (i == null || i.imdb == null) continue;
                    try {
                        double v = Double.parseDouble(i.imdb);
                        sum += v;
                        rated++;
                        if (v > 6.0) strong++;
                        else if (v < 6.0) awful++;
                    } catch (Exception ignored) {
                    }
                }
                final String avg = rated > 0 ? String.format("%.1f", sum / rated) : "--";
                final int ratedCount = rated;
                final int strongCount = strong;
                final int awfulCount = awful;
                final int monthCount = month.size();
                final int total = all.size();

                ui.post(new Runnable() {
                    public void run() {
                        if (root == null || tab != TAB_STATS) return;
                        clearList();
                        vList.addView(header("YOUR MONTH"));
                        // All four in one line. Three stacked card rows made this the
                        // only screen that scrolled, for numbers you read in a glance.
                        vList.addView(statRow4(
                                stat("WATCHED THIS MONTH", String.valueOf(monthCount),
                                        monthCount == 1 ? "title" : "titles"),
                                stat("AVERAGE RATING", avg,
                                        ratedCount > 0 ? ratedCount + " rated"
                                                : "no ratings yet"),
                                stat("STRONG TITLES", String.valueOf(strongCount),
                                        "IMDb above 6.0"),
                                stat("AWFUL TITLES", String.valueOf(awfulCount),
                                        "IMDb below 6.0")));

                        TextView inHistory = new TextView(vCtx);
                        inHistory.setText(total + (total == 1 ? " title" : " titles")
                                + " recorded on this TV");
                        inHistory.setTextSize(13);
                        inHistory.setTextColor(dim());
                        inHistory.setPadding(6, 2, 6, 0);
                        vList.addView(inHistory);

                        TextView note = new TextView(vCtx);
                        note.setText("Counted from what this TV actually played, plus the apps' own lists.");
                        note.setTextSize(12);
                        note.setTextColor(dim2());
                        note.setPadding(6, 16, 6, 0);
                        vList.addView(note);
                        maybeScrollTop();
                        if (vList.getChildCount() > 1) vList.getChildAt(1).requestFocus();
                    }
                });
            }
        }).start();
    }

    /** Four cards on one line, so the stats fit without scrolling. */
    static LinearLayout statRow4(LinearLayout a, LinearLayout b, LinearLayout c,
                                 LinearLayout d) {
        LinearLayout row = new LinearLayout(vCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout[] cells = {a, b, c, d};
        for (int i = 0; i < cells.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.rightMargin = i == cells.length - 1 ? 0 : 12;
            lp.bottomMargin = 6;
            row.addView(cells[i], lp);
        }
        return row;
    }

    /** Two cards per line, equal width. */
    static LinearLayout statRow(LinearLayout left, LinearLayout right) {
        LinearLayout row = new LinearLayout(vCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.rightMargin = 12;
        lp.bottomMargin = 12;
        row.addView(left, lp);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rp.bottomMargin = 12;
        if (right != null) row.addView(right, rp);
        else row.addView(new View(vCtx), rp);
        return row;
    }

    /** A single card that spans the width. */
    static LinearLayout wide(LinearLayout card) {
        LinearLayout row = new LinearLayout(vCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 12;
        row.addView(card, lp);
        return row;
    }

    static LinearLayout stat(String label, String value, String sub) {
        LinearLayout box = new LinearLayout(vCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(22, 18, 22, 18);
        box.setMinimumHeight(124);
        box.setBackground(rounded(rowTint(), 18, stroke()));

        TextView l = new TextView(vCtx);
        l.setText(label);
        l.setTextSize(11);
        l.setLetterSpacing(0.18f);
        l.setTextColor(dim2());
        box.addView(l);

        TextView v = new TextView(vCtx);
        v.setText(value);
        v.setTextSize(46);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(txt());
        v.setPadding(0, -2, 0, 0);
        v.setIncludeFontPadding(false);
        box.addView(v);

        TextView s = new TextView(vCtx);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(dim());
        box.addView(s);
        return box;
    }

    // ----------------------------------------------------------------- menu

    /**
     * The LEFT-key flyout.
     *
     * LEFT used to step the row's action cursor backwards, which duplicated RIGHT:
     * that already wraps through RESUME, bookmarks and saved scenes. Spending LEFT on
     * a menu instead means the card's own navigation is one press away from a row
     * twenty deep, instead of a scroll back to the top.
     */
    static void openMenu() {
        if (menuOpen || root == null) return;
        menuOpen = true;
        clearArmed = false;
        menuFocus = MENU_WATCHING;
        menuSwipe = true;
        menuReturnTab = tab;
        tab = TAB_MENU;
        chrome();
        buildTab();
        Log.i(TAG, "menu open from tab " + menuReturnTab);
    }

    static void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        tab = menuReturnTab;
        chrome();
        buildTab();
        Log.i(TAG, "menu closed -> tab " + tab);
    }

    static final int MENU_WATCHING = 0, MENU_THESE = 1, MENU_STATS = 2, MENU_SETTINGS = 3,
            MENU_SORT = 4, MENU_LAYOUT = 5, MENU_THEME = 6, MENU_CLEAR = 7;

    /**
     * The LEFT flyout: a full-height magenta slab that swipes in from the edge.
     *
     * Narrow on purpose - it is a keypad strip, not a page - and tall, so it reads as
     * something that slid over the card rather than a box that replaced its content.
     * No scrim behind it: the card stays legible, which is the point of a flyout.
     */
    static void buildMenu() {
        clearList();
        MENU_ROWS.clear();

        LinearLayout panel = new LinearLayout(vCtx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(14, 18, 14, 16);

        // Glass with a magenta body: a rim, a gradient, and a gloss band across the top
        // third. Three layers, drawn by one drawable, so no extra views.
        GradientDrawable rim = new GradientDrawable();
        rim.setColor(0x00000000);
        rim.setCornerRadius(30);
        rim.setStroke(2, 0x66FF9BE8);

        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{light() ? 0xFF7A1A72 : 0xFF5C1155,
                        light() ? 0xFF4E0F4C : 0xFF37093A});
        body.setCornerRadius(30);

        GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22FFFFFF, 0x00FFFFFF});
        gloss.setCornerRadius(30);

        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{rim, body, gloss});
        layers.setLayerInset(1, 3, 3, 3, 3);
        layers.setLayerInset(2, 3, 3, 3, 3);
        panel.setBackground(layers);

        // Shorter than the pane and pushed down off the tab row: at full height it butted
        // against the tabs, which read as a layout mistake rather than a flyout. Height
        // comes from the content, so the last two rows are never clipped.
        int pane = vScroll != null && vScroll.getHeight() > 0 ? vScroll.getHeight() : dp(560);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                dp(186), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = (int) (pane * 0.15f);
        vList.addView(panel, plp);

        TextView title = new TextView(vCtx);
        title.setText("MENU");
        title.setTextSize(10);
        title.setLetterSpacing(0.22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0x99FFFFFF);
        title.setPadding(10, 0, 10, 10);
        panel.addView(title);

        // State lives in the glyph, not in a value column: at this width there is no
        // room for both, and a glyph that changes is quicker to read anyway.
        addMenuItem(panel, "\u25A4", "WATCHING", MENU_WATCHING);
        addMenuItem(panel, "\u2605", "WATCH THESE", MENU_THESE);
        addMenuItem(panel, "\u2261", "STATS", MENU_STATS);
        addMenuItem(panel, "\u25C8", "SETTINGS", MENU_SETTINGS);
        addMenuItem(panel, sortMode == 0 ? "\u21C5" : "\u2605", "SORT", MENU_SORT);
        addMenuItem(panel, layoutMode == 0 ? "\u2630" : "\u25A6", "LAYOUT", MENU_LAYOUT);
        addMenuItem(panel, light() ? "\u2600" : "\u263E", "THEME", MENU_THEME);
        addMenuItem(panel, "\u2715", clearArmed ? "SURE?" : "CLEAR HISTORY", MENU_CLEAR);

        // Swipe in only when the flyout is being opened. SORT, LAYOUT and THEME rebuild
        // this panel in place to show their new state - animating there made a small
        // toggle look like the menu reopening.
        final boolean swipe = menuSwipe;
        menuSwipe = false;
        if (swipe) {
            panel.setTranslationX(-dp(186));
            panel.setAlpha(0.55f);
            // Posted: a translation set before the first layout is overwritten by it,
            // which is why the swipe never actually played.
            panel.post(new Runnable() {
                public void run() {
                    panel.animate().translationX(0f).alpha(1f)
                            .setDuration(200)
                            .setInterpolator(
                                    new android.view.animation.DecelerateInterpolator())
                            .start();
                }
            });
        } else {
            panel.setTranslationX(0f);
            panel.setAlpha(1f);
        }

        maybeScrollTop();
        View want = MENU_ROWS.get(menuFocus);
        if (want != null) want.requestFocus();
        else if (panel.getChildCount() > 1) panel.getChildAt(1).requestFocus();
    }

    /** Set by the first press on CLEAR HISTORY; the second press does it. */
    static boolean clearArmed;

    /** True only for the build that follows an open, so toggles never re-swipe. */
    static boolean menuSwipe;

    /** Which menu row the cursor was on, across a rebuild of the menu. */
    static int menuFocus = MENU_WATCHING;
    /** The rows of the open menu, so one can be focused again after a toggle. */
    static final java.util.Map<Integer, View> MENU_ROWS = new HashMap<Integer, View>();

    static void addMenuItem(LinearLayout panel, String glyph, String label, final int what) {
        LinearLayout row = new LinearLayout(vCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setPadding(8, 11, 8, 11);
        row.setBackground(rounded(0, 16, 0));

        TextView mark = new TextView(vCtx);
        mark.setText(glyph);
        mark.setTextSize(14);
        mark.setTextColor(0xCCFFFFFF);
        mark.setMinWidth(dp(24));
        row.addView(mark);

        TextView name = new TextView(vCtx);
        name.setText(label);
        name.setTextSize(12);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(0xFFFFFFFF);
        name.setLetterSpacing(0.06f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                // Lighter magenta on focus, plus a rim: on a slab this saturated, a
                // neutral highlight disappears.
                v.setBackground(has ? rounded(0x33FFFFFF, 16, 0xAAFFD6F5)
                        : rounded(0, 16, 0));
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { performMenu(what); }
        });
        // No LEFT handler here on purpose. The window root owns LEFT, and it only sees
        // the key-down; a row that also acted on key-up closed the menu that the same
        // press had just opened.

        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MENU_ROWS.put(what, row);
    }

    static void performMenu(int what) {
        menuFocus = what;
        switch (what) {
            case MENU_WATCHING: menuOpen = false; tab = TAB_WATCHING; break;
            case MENU_THESE: menuOpen = false; tab = TAB_THESE; break;
            case MENU_STATS: menuOpen = false; tab = TAB_STATS; break;
            case MENU_SETTINGS: menuOpen = false; tab = TAB_SETTINGS; break;
            case MENU_SORT: sortMode = sortMode == 0 ? 1 : 0; prefSet(); break;
            case MENU_LAYOUT: layoutMode = layoutMode == 0 ? 1 : 0; prefSet(); break;
            case MENU_THEME:
                // Come back into the menu, not to WATCHING: the card is rebuilt for the
                // theme, and the menu is where the press happened.
                bootTab = TAB_MENU;
                setLight(!light());
                return;
            case MENU_CLEAR:
                // Two presses. This cannot be undone, and the menu row sits next to
                // the theme toggle: one stray OK should never cost your history.
                if (!clearArmed) {
                    clearArmed = true;
                    notice("Press OK again to clear your history. LEFT cancels.");
                    buildTab();
                    return;
                }
                clearArmed = false;
                vCtx.getSharedPreferences("xray", Context.MODE_PRIVATE).edit()
                        .remove("local2").apply();
                vCtx.getSharedPreferences("ratings_cache", Context.MODE_PRIVATE).edit()
                        .clear().apply();
                cache.clear();
                lastCont = null;
                lastFresh = null;
                notice("History and cached ratings cleared.");
                Log.i(TAG, "menu: cleared history");
                buildTab();
                return;
            default: return;
        }
        chrome();
        buildTab();
    }

    // ------------------------------------------------------------- settings

    /**
     * Settings, in two columns and one screen.
     *
     * Everything here fits without scrolling: the key is a key, and the rest is what
     * you read once. A single column scrolled, which on a TV means content you never
     * discover because nothing hints that it exists below the fold.
     */
    static void buildSettings() {
        clearList();

        LinearLayout cols = new LinearLayout(vCtx);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout left = new LinearLayout(vCtx);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout right = new LinearLayout(vCtx);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = 22;
        cols.addView(left, colLp);
        LinearLayout.LayoutParams colLp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cols.addView(right, colLp2);
        vList.addView(cols);

        // ---- left: the key, then the facts ----
        left.addView(header("OMDB API KEY"));
        final EditText input = new EditText(vCtx);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(txt());
        input.setHintTextColor(dim2());
        input.setHint("paste your key here");
        String cur = Ratings.key(vCtx);
        if (cur != null) input.setText(cur);
        input.setPadding(16, 9, 16, 9);
        input.setBackground(rounded(rowTint(), 14, stroke()));
        left.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(vCtx);
        help.setText("Free key at omdbapi.com/apikey.aspx. Without one the card still "
                + "works, it just shows fewer ratings.");
        help.setTextSize(12);
        help.setTextColor(dim2());
        help.setLineSpacing(0, 1.25f);
        help.setPadding(6, 8, 6, 10);
        left.addView(help);

        final TextView saved = new TextView(vCtx);
        saved.setTextSize(12);
        saved.setTextColor(accent());
        saved.setPadding(6, 8, 6, 0);

        TextView save = solidButton("SAVE KEY", accent(), 0xFFFFFFFF);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String k = input.getText().toString().trim();
                vCtx.getSharedPreferences("xray", Context.MODE_PRIVATE).edit()
                        .putString("omdb", k).apply();
                cache.clear();
                saved.setText(k.isEmpty() ? "Cleared." : "Saved.");
                Log.i(TAG, "key saved len=" + k.length());
            }
        });
        left.addView(save);
        left.addView(saved);

        left.addView(header("ABOUT"));
        TextView ver = new TextView(vCtx);
        ver.setText("Rate My TV " + Updater.installedVersion(vCtx));
        ver.setTextSize(19);
        ver.setTypeface(Typeface.DEFAULT_BOLD);
        ver.setTextColor(txt());
        ver.setPadding(6, 2, 6, 0);
        left.addView(ver);

        TextView state = new TextView(vCtx);
        state.setTextSize(12);
        state.setPadding(6, 3, 6, 8);
        if (Updater.available(vCtx)) {
            state.setText("Update available: " + Updater.latestTag);
            state.setTextColor(accent());
            left.addView(state);
            final TextView install = solidButton("Install " + Updater.latestTag,
                    accent(), 0xFFFFFFFF);
            install.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    toast("Downloading update\u2026");
                    dismiss(false);
                    Updater.install(vCtx, null);
                }
            });
            left.addView(install);
        } else {
            state.setText(Updater.latestTag == null ? "Checking for updates\u2026" : "Up to date");
            state.setTextColor(dim2());
            left.addView(state);
        }

        TextView author = new TextView(vCtx);
        author.setText("Nikhil Talreja");
        author.setTextSize(16);
        author.setTypeface(Typeface.DEFAULT_BOLD);
        author.setTextColor(txt());
        author.setPadding(6, 8, 6, 0);
        left.addView(author);

        TextView creditLine = new TextView(vCtx);
        creditLine.setText("@nikhiltalrejasocial");
        creditLine.setTextSize(13);
        creditLine.setTextColor(accent());
        creditLine.setPadding(6, 1, 6, 0);
        left.addView(creditLine);

        // ---- right: why, share, disclaimer ----
        right.addView(header("WHY THIS EXISTS"));
        TextView note = new TextView(vCtx);
        note.setText("My wife would ask me what to watch next, every single evening, and "
                + "it was a loop that never ended.\n\n"
                + "So I built this. Now she sees the rating and chooses for herself, and "
                + "the badly rated things simply are not there for her to fall into.");
        note.setTextSize(13);
        note.setTextColor(dim());
        note.setLineSpacing(3f, 1.12f);
        note.setPadding(6, 4, 6, 8);
        right.addView(note);

        right.addView(header("SHARE"));
        final TextView share = new TextView(vCtx);
        share.setText("Send the repo to someone who keeps asking:\n"
                + "github.com/nikhiltalreja/ratemytv");
        share.setTextSize(12);
        share.setTextColor(accent());
        share.setLineSpacing(2f, 1.1f);
        share.setPadding(16, 10, 16, 10);
        share.setFocusable(true);
        share.setFocusableInTouchMode(true);
        share.setBackground(rounded(rowTint(), 14, stroke()));
        share.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(REPO); }
        });
        share.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? focusBg(true) : rounded(rowTint(), 14, stroke()));
            }
        });
        right.addView(share);

        right.addView(header("DISCLAIMER"));
        TextView disc = new TextView(vCtx);
        disc.setText("Not an official app, and not affiliated with, endorsed by or connected "
                + "to JioHotstar, Netflix, Prime Video, YouTube, Apple TV, SonyLIV, AIRTEL "
                + "Xstream, IMDb or Rotten Tomatoes. Every brand and name belongs to its "
                + "owner and appears only to say what is playing.\n\n"
                + "Provided as is: you install and use it at your own risk, and the author "
                + "accepts no liability for misconfiguration, misuse or software issues.");
        disc.setTextSize(11);
        disc.setTextColor(dim2());
        disc.setLineSpacing(2f, 1.1f);
        disc.setPadding(6, 4, 6, 0);
        right.addView(disc);

        if (vList.getChildCount() > 0) vList.getChildAt(0).requestFocus();
    }
    /** The repo, for the share row. */
    static final String REPO = "https://github.com/nikhiltalreja/ratemytv";

    static void open(String url) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            vCtx.startActivity(i);
        } catch (Exception e) {
            notice("No browser for that link.");
        }
    }

    /** Repaint the whole card in the other theme. */
    static void setLight(final boolean on) {
        final boolean switchHadFocus = vTheme != null && vTheme.hasFocus();
        try {
            vCtx.getSharedPreferences("xray", Context.MODE_PRIVATE).edit()
                    .putBoolean("light", on).apply();
        } catch (Exception e) {
            return;
        }
        // Tear down and rebuild inside one main-thread turn. The old version dismissed
        // with a fade and re-showed 240 ms later: the window was gone for that gap, so
        // the card vanished and the player underneath was visible - which reads as the
        // app quitting, and a second press in the gap did nothing at all.
        View old = root;
        root = null;
        if (old != null) {
            try {
                wm.removeView(old);
            } catch (Exception e) {
                Log.w(TAG, "theme: removeView " + e);
            }
        }
        show(vCtx);
        // Tabs that focus their own first child (settings, watch these) run inside
        // show(), so the switch takes the cursor back only after that returns - and
        // only if it had it: a theme change from the LEFT menu must stay in the menu.
        if (switchHadFocus && vTheme != null) vTheme.requestFocus();
        if (root == null) {
            // Nothing was drawn; leave the window in a defined state rather than dead.
            Log.w(TAG, "theme: rebuild produced no window");
        }
    }

    // --------------------------------------------------------------- common

    static TextView chip(String text) {
        TextView t = new TextView(vCtx);
        t.setText(text);
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(14, 8, 14, 8);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setBackground(rounded(0, 12, 0));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                v.setBackground(rounded(has ? rowTintSel() : 0, 12, 0));
            }
        });
        return t;
    }

    static TextView icon(String text) {
        TextView t = chip(text);
        // 15, the section header's size: at 19 "⇅ RECENT" outweighed the heading it
        // sits beside, so the control read as a button.
        t.setTextSize(15);
        t.setLetterSpacing(0f);
        t.setPadding(9, 5, 9, 5);
        return t;
    }

    static int gridColumns() {
        return vListW >= 1200 ? 4 : 3;
    }

    /** Wide cards sized to the full TV canvas. */
    static void grid(List<Watcher.Item> items) {
        LinearLayout line = null;
        int cols = gridColumns();
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                line = new LinearLayout(vCtx);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setWeightSum(cols);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = 26;
                vList.addView(line, lp);
            } else {
                View gap = new View(vCtx);
                line.addView(gap, new LinearLayout.LayoutParams(16, 1));
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            line.addView(card(items.get(i)), clp);
        }
        while (line != null && line.getChildCount() < cols * 2 - 1) {
            if (line.getChildCount() > 0) {
                line.addView(new View(vCtx), new LinearLayout.LayoutParams(16, 1));
            }
            line.addView(new View(vCtx), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    static View card(final Watcher.Item it) {
        final Holder h = new Holder();
        h.item = it;

        LinearLayout box = new LinearLayout(vCtx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setOnKeyListener(BACK);
        box.setPadding(10, 10, 10, 12);
        box.setBackground(rounded(rowTint(), 18, stroke()));

        h.wideFrame = true;
        int cardW = Math.max(200, (vListW - (gridColumns() - 1) * 16) / gridColumns());

        // The artwork is 16:9 but the card used to carry four lines underneath, which
        // squared it off. The progress now sits on the image, and the text is one line:
        // the tile follows its own artwork instead of fighting it.
        android.widget.FrameLayout art = new android.widget.FrameLayout(vCtx);
        h.poster = new ImageView(vCtx);
        h.poster.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardW * 9 / 16));
        h.poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.poster.setBackground(rounded(0x33000000, 10, 0));
        art.addView(h.poster);
        if (it.pos > 0 && it.dur > 0) {
            LinearLayout pl = progress(it.pos, it.dur);
            android.widget.FrameLayout.LayoutParams flp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.gravity = Gravity.BOTTOM;
            flp.leftMargin = 10;
            flp.rightMargin = 10;
            flp.bottomMargin = 8;
            art.addView(pl, flp);
        }
        box.addView(art);

        h.title = new TextView(vCtx);
        // A record kept under its episode label already reads "S1 E1 ...", so appending
        // the code again produced "S1 E1 The Immortal Smile  S1 E01".
        boolean labelOnly = it.title != null
                && it.title.matches("(?i)^s\\d+\\s*e\\d+.*");
        h.title.setText(it.ep != null && !labelOnly ? it.title + "  " + it.ep : it.title);
        h.title.setTextSize(16);
        h.title.setTypeface(Typeface.DEFAULT_BOLD);
        h.title.setTextColor(txt());
        h.title.setSingleLine(true);
        h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.title.setPadding(2, 8, 2, 0);
        box.addView(h.title);

        // The score shares the second line with the channel: on the title's line it
        // stole the width, and "The X-Files S…" is a worse tile than a smaller number.
        LinearLayout caption = new LinearLayout(vCtx);
        caption.setOrientation(LinearLayout.HORIZONTAL);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(2, 3, 2, 0);

        h.meta = new TextView(vCtx);
        h.meta.setTextSize(11);
        h.meta.setTextColor(dim2());
        h.meta.setSingleLine(true);
        h.meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.meta.setText(channelLine(it));
        caption.addView(h.meta, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        h.rating = new TextView(vCtx);
        h.rating.setTextSize(13);
        h.rating.setTextColor(txt());
        h.rating.setSingleLine(true);
        h.rating.setPadding(10, 0, 0, 0);
        caption.addView(h.rating);
        box.addView(caption);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i(TAG, "play " + it.title);
                launch(it);
                dismiss(false);
            }
        });
        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                paintFocus();
            }
        });
        box.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                // Same escape hatch as the list rows: the card root owns the focus
                // search upwards, so the top card hands the cursor to the tabs.
                if (code == KeyEvent.KEYCODE_DPAD_UP && v == vTopRow
                        && e.getAction() == KeyEvent.ACTION_DOWN) {
                    // Sort icons first: they sit above this row and have no other route.
                    focusSort();
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_UP
                        && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
                    dismiss(true);
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_UP
                        && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    openMenu();
                    return true;
                }
                return false;
            }
        });
        if (vTopRow == null) vTopRow = box;

        ROWS.add(box);
        holders.add(h);
        if (it.poster != null && !it.poster.isEmpty()) loadPoster(h, it.poster);
        else {
            int cw = Math.max(200, (vListW - (gridColumns() - 1) * 16) / gridColumns());
            h.poster.setImageBitmap(placeholder(cw - 20, (cw - 20) * 9 / 16, it.title, it.pkg));
        }
        return box;
    }

    static int prefGet() {
        return vCtx.getSharedPreferences("xray", Context.MODE_PRIVATE).getInt("sort", 0);
    }

    static void prefSet() {
        vCtx.getSharedPreferences("xray", Context.MODE_PRIVATE).edit()
                .putInt("sort", sortMode).putInt("layout", layoutMode).apply();
    }

    static void sortByRating(List<Watcher.Item> rows) {
        {
            for (Watcher.Item it : rows) {
                if (cache.containsKey(it.title)) continue;
                Ratings.Info i = Ratings.lookup(vCtx, it.title, kindOf(it));
                if (i != null) cache.put(it.title, i);
            }
        }
        java.util.Collections.sort(rows, new java.util.Comparator<Watcher.Item>() {
            public int compare(Watcher.Item a, Watcher.Item b) {
                return Double.compare(score(b), score(a));
            }
        });
    }

    static double score(Watcher.Item it) {
        Ratings.Info i = cache.get(it.title);
        if (i == null || i.imdb == null) return -1;
        try {
            return Double.parseDouble(i.imdb);
        } catch (Exception e) {
            return -1;
        }
    }

    static List<Watcher.Item> allRows(boolean started) {
        List<Watcher.Item> out = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;

        if (started) {
            for (Watcher.Item it : Watcher.localRows(vCtx, cutoff)) {
                if (it.pos > 0) out.add(it);
                if (out.size() >= 10) break;
            }
        }

        // JioHotstar fills Watch Next with recommendation-tray rows it never played
        // (utm_medium=reco_tray_play_next), so for Jio we trust only our own records.
        java.util.Map<String, String> artByTitle = new java.util.HashMap<>();
        java.util.Map<String, String> intentByTitle = new java.util.HashMap<>();
        java.util.Map<String, String> pkgByTitle = new java.util.HashMap<>();
        java.util.Set<String> seenHere = new java.util.HashSet<>();
        for (Watcher.Item it : Watcher.localAll(vCtx)) {
            if (it.title != null) seenHere.add(it.title.toLowerCase());
        }

        try (Cursor c = vCtx.getContentResolver().query(
                Uri.parse("content://android.media.tv/watch_next_program"),
                new String[]{"package_name", "title", "content_id",
                        "last_playback_position_millis", "duration_millis", "poster_art_uri",
                        "last_engagement_time_utc_millis", "watch_next_type", "intent_uri"},
                null, null, "last_engagement_time_utc_millis DESC")) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < 10) {
                long pos = c.getLong(3);
                long eng = c.getLong(6);
                int type = c.getInt(7);
                if (started) {
                    if (type != 0 || pos <= 0) continue;
                } else {
                    if (type == 0) continue;
                }
                if (eng < cutoff) continue;
                // A provider row is only listed when this TV has its own viewing record
                // for that title. Netflix/Prime rows cannot be verified, so they drop out.
                String rowPkg = c.getString(0);
                String rowTitle = c.getString(1);
                if (rowTitle == null || !seenHere.contains(rowTitle.toLowerCase())) continue;
                Watcher.Item it = new Watcher.Item();
                it.pkg = rowPkg;
                it.title = c.getString(1);
                it.cid = c.getString(2);
                it.pos = pos;
                it.dur = c.getLong(4);
                it.poster = c.getString(5);
                it.type = type;
                it.ts = eng;
                if (it.poster != null && !it.poster.isEmpty()) {
                    artByTitle.put(it.title.toLowerCase(), it.poster);
                }
                try { it.intent = c.getString(8); } catch (Exception ignored) { }
                Watcher.Item existing = null;
                for (Watcher.Item have : out) {
                    if (have.title != null && have.title.equalsIgnoreCase(it.title)) {
                        existing = have;
                        break;
                    }
                }
                if (existing == null) {
                    out.add(it);
                } else if (it.pkg != null && existing.pkg != null
                        && it.pkg.equals(existing.pkg)) {
                    // Borrow the launch information, but only from the same app. A
                    // same-titled row from another service used to overwrite the
                    // package, so a JioHotstar record launched Amazon Prime.
                    if (existing.intent == null || existing.intent.isEmpty()) {
                        existing.intent = it.intent;
                    }
                } else if (it.pkg != null && !it.pkg.equals(existing.pkg)) {
                    Log.i(TAG, "same title on " + it.pkg + ", not borrowing (row is "
                            + existing.pkg + ")");
                    existing.alsoOn = it.pkg;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "rows: " + e);
        }

        // A locally recorded row carries no artwork or deeplink of its own. Borrow
        // both from the provider, from every row regardless of watch_next_type: the
        // filtered pass above only sees type 0, and a saved title (type 3) would
        // otherwise fall back to "open the app", which is not a switch.
        try (Cursor c = vCtx.getContentResolver().query(
                Uri.parse("content://android.media.tv/watch_next_program"),
                new String[]{"package_name", "title", "intent_uri", "poster_art_uri"},
                null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String t = c.getString(1);
                    if (t == null) continue;
                    String key2 = t.toLowerCase();
                    if (!intentByTitle.containsKey(key2)) intentByTitle.put(key2, c.getString(2));
                    if (!pkgByTitle.containsKey(key2)) pkgByTitle.put(key2, c.getString(0));
                    if (!artByTitle.containsKey(key2)) artByTitle.put(key2, c.getString(3));
                }
            }
        } catch (Exception ignored) {
        }

        for (Watcher.Item it : out) {
            if (it.title == null) continue;
            String key2 = it.title.toLowerCase();
            if (it.poster == null || it.poster.isEmpty()) {
                String art = artByTitle.get(key2);
                if (art != null) it.poster = art;
            }
            if (it.intent == null || it.intent.isEmpty()) {
                String deep = intentByTitle.get(key2);
                if (deep != null && !deep.isEmpty()) {
                    it.intent = deep;
                    String p = pkgByTitle.get(key2);
                    if (p != null && !p.isEmpty()) it.pkg = p;
                }
            }
        }

        // Locals are newest-first and provider rows are appended after them, so the
        // merged list was not in time order at all: a title watched 13 minutes ago sat
        // under one watched 11 hours ago. Sort the whole thing by when it was watched.
        java.util.Collections.sort(out, new java.util.Comparator<Watcher.Item>() {
            public int compare(Watcher.Item a, Watcher.Item b) {
                return Long.compare(b.ts, a.ts);
            }
        });
        return out;
    }

    /**
     * No artwork anywhere: show the brand's 3D mark (shipped in the APK assets).
     * Deliberately no flat fills — the only thing drawn is the real 3D object, so the
     * row still looks like part of the brand. Falls back to initials only if the
     * asset cannot be read.
     */
    static Bitmap placeholder(int w, int h, String title, String pkg) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.FILTER_BITMAP_FLAG);

        // YouTube ships a proper 2:3 cover built from its own icon, so use that
        // rather than the generic brand mark.
        Bitmap cover = (pkg != null && pkg.contains("youtube"))
                ? assetBitmap("youtube-cover.png") : null;
        if (cover != null) {
            float scale = Math.max(w / (float) cover.getWidth(), h / (float) cover.getHeight());
            float dw = cover.getWidth() * scale, dh = cover.getHeight() * scale;
            android.graphics.RectF dst = new android.graphics.RectF(
                    (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            c.drawBitmap(cover, null, dst, p);
            return bm;
        }

        Bitmap mark = assetBitmap("mark.png");
        if (mark != null) {
            // fit the 3D TV into the frame, centred, leaving a little air
            float scale = Math.min(w * 0.98f / mark.getWidth(), h * 0.86f / mark.getHeight());
            float dw = mark.getWidth() * scale, dh = mark.getHeight() * scale;
            android.graphics.RectF dst = new android.graphics.RectF(
                    (w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            c.drawBitmap(mark, null, dst, p);
            return bm;
        }

        String initials = initials(title);
        p.setColor(0xCCFFFFFF);
        p.setTextSize(Math.min(w, h) * 0.38f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText(initials, w / 2f, h / 2f + p.getTextSize() / 3f, p);
        return bm;
    }

    static final Map<String, Bitmap> assetCache = new HashMap<>();

    static Bitmap assetBitmap(String name) {
        if (vCtx == null) return null;
        if (assetCache.containsKey(name)) return assetCache.get(name);
        Bitmap bm = null;
        try {
            InputStream in = vCtx.getAssets().open(name);
            bm = BitmapFactory.decodeStream(in);
            in.close();
        } catch (Exception e) {
            Log.w(TAG, "asset " + name + ": " + e);
        }
        assetCache.put(name, bm);
        return bm;
    }

    static String initials(String title) {
        if (title == null) return "?";
        String[] words = title.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            char ch = Character.toUpperCase(w.charAt(0));
            if (Character.isLetterOrDigit(ch)) b.append(ch);
            if (b.length() == 2) break;
        }
        return b.length() == 0 ? "?" : b.toString();
    }

    /**
     * Decoded posters keyed by URL, most-recently-used last. The card is rebuilt on
     * every tab, sort and layout change, and without this each rebuild re-downloaded
     * every image in the list.
     */
    static final java.util.LinkedHashMap<String, Bitmap> IMAGES =
            new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Bitmap> eldest) {
                    return size() > 24;
                }
            };

    static void loadPoster(final Holder h, final String url) {
        if (url == null || url.isEmpty()) return;

        Bitmap cached;
        synchronized (IMAGES) {
            cached = IMAGES.get(url);
        }
        if (cached != null) {
            applyPoster(h, cached);
            return;
        }

        final String title = h.item != null ? h.item.title : url;
        Bitmap onDisk = Cache.load(vCtx, title, url);
        if (onDisk != null) {
            synchronized (IMAGES) {
                IMAGES.put(url, onDisk);
            }
            applyPoster(h, onDisk);
            return;
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = (InputStream) new URL(url).openStream();
                    final Bitmap bm = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bm == null) return;
                    synchronized (IMAGES) {
                        IMAGES.put(url, bm);
                    }
                    Cache.save(vCtx, title, url, bm);
                    ui.post(new Runnable() {
                        public void run() {
                            if (root == null) return;
                            applyPoster(h, bm);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "poster: " + e);
                }
            }
        }).start();
    }

    /** Grid frames are wide, so the art always fills them — crop, never letterbox. */
    static void applyPoster(Holder h, Bitmap bm) {
        h.poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.poster.setImageBitmap(bm);
    }

    static boolean launch(Watcher.Item it) {
        android.content.Intent i = null;
        // No provider deeplink, but the record knows its show and episode ids and the
        // user may have named the show: build the route ourselves.
        if ((it.intent == null || it.intent.isEmpty()) && it.epid != null
                && !it.epid.isEmpty() && it.pkg != null && it.pkg.contains("hotstar")) {
            // Verified on device: this opens the exact episode in JioHotstar.
            it.intent = "https://www.hotstar.com/in/watch/" + it.epid;
            Log.i(TAG, "watch url from episode id: " + it.epid);
        }
        if ((it.intent == null || it.intent.isEmpty()) && it.sid != null
                && it.pkg != null && it.pkg.contains("hotstar")) {
        }
        try {
            if (it.intent != null && it.intent.startsWith("http")) {
                // A web link, not an intent:// string. Left unaddressed so the system
                // routes it by app links - Jio claims hotstar.com.
                i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        Uri.parse(it.intent));
            } else if (it.intent != null && it.intent.length() > 0) {
                i = android.content.Intent.parseUri(it.intent,
                        android.content.Intent.URI_INTENT_SCHEME);
                if (i != null && i.getPackage() == null) i.setPackage(it.pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "parse intent: " + e);
            i = null;
        }
        if (i != null) {
            try {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                vCtx.startActivity(i);
                Log.i(TAG, "launched " + it.pkg + " via deeplink");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "deeplink failed (" + e + "), opening app instead");
            }
        }
        // Jio with no route is not "open the app": Jio resumes its own last title, so
        // pressing Vijay played MobLand. Refusing and saying so is the honest result.
        if (it.pkg != null && it.pkg.contains("hotstar")) {
            String hint = (it.sid != null && !it.sid.isEmpty())
                    ? "No direct link for this title. Open it in JioHotstar."
                    : "No direct link for this title. Open it in JioHotstar.";
            notice(hint);
            Log.i(TAG, "no route for " + it.title + " sid=" + it.sid);
            return false;
        }
        // YouTube exposes no media id, so we cannot deep-link the exact video;
        // a search URL at least lands on it (its session title is channel + video).
        if (it.pkg != null && it.pkg.contains("youtube") && it.title != null) {
            try {
                android.content.Intent q = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query="
                                + Uri.encode(it.title)));
                q.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                vCtx.startActivity(q);
                Log.i(TAG, "launched " + it.pkg + " via search");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "search failed: " + e);
            }
        }

        try {
            android.content.Intent app = vCtx.getPackageManager()
                    .getLeanbackLaunchIntentForPackage(it.pkg);
            if (app == null) {
                app = vCtx.getPackageManager().getLaunchIntentForPackage(it.pkg);
            }
            if (app == null) return false;
            app.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            vCtx.startActivity(app);
            Log.i(TAG, "launched " + it.pkg + " via app");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "launch: " + e);
            return false;
        }
    }

    static final View.OnKeyListener BACK = new View.OnKeyListener() {
        public boolean onKey(View v, int code, KeyEvent e) {
            if (e.getAction() == KeyEvent.ACTION_UP
                    && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
                dismiss(true);
                return true;
            }
            return false;
        }
    };

    static void key(Context ctx, int code) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long now = System.currentTimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    static String watched(Watcher.Item it) {
        if (it.dur <= 0 || it.pos <= 0) return "";
        int pct = (int) (it.pos * 100 / it.dur);
        if (pct > 100) pct = 100;
        return pct + "%";
    }

    static String age(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < 0) d = 0;
        long m = d / 60000;
        if (m < 5) return "just now";
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        long days = h / 24;
        return days == 1 ? "yesterday" : days + "d ago";
    }

    static String fmt(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        if (s >= 3600) return (s / 3600) + ":" + String.format("%02d", (s % 3600) / 60)
                + ":" + String.format("%02d", s % 60);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    static String shortName(String pkg) {
        if (pkg == null) return "";
        if (pkg.contains("netflix")) return "Netflix";
        if (pkg.contains("amazon")) return "Prime Video";
        if (pkg.contains("hotstar")) return "JioHotstar";
        if (pkg.contains("youtube")) return "YouTube";
        if (pkg.contains("sonyliv")) return "SonyLIV";
        if (pkg.contains("airtel")) return "Airtel Xstream";
        if (pkg.contains("apple")) return "Apple TV";
        if (pkg.contains("kodi")) return "Kodi";
        if (pkg.contains("spotify")) return "Spotify";
        return pkg;
    }

    static GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(2, stroke);
        return d;
    }

    static void dismiss(boolean resume) {
        if (root != null) {
            final View v = root;
            root = null;
            v.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
                public void run() {
                    try { wm.removeView(v); } catch (Exception ignored) { }
                }
            }).start();
        }
        if (resume) key(vCtx, KeyEvent.KEYCODE_MEDIA_PLAY);
    }
}
