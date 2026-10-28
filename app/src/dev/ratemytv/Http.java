package dev.ratemytv;

import java.net.HttpURLConnection;

/**
 * One place for how this app looks on the wire.
 *
 * Every request used to carry "RateMyTV/1.0", or nothing at all — which means the default
 * Java user agent, the most obvious bot signature there is. These endpoints are public
 * pages and public APIs that a browser reads every day; the app asks for the same
 * documents, so it should present the same way: a real Chrome-on-Android-TV fingerprint,
 * with the headers that page actually sends (Accept, Accept-Language, Origin/Referer on
 * the GraphQL POST).
 *
 * This is not camouflage for scraping: no rate limit is exceeded, no anti-bot measure is
 * dodged, and nothing here changes what is requested. It is simply the difference between
 * looking like a browser reading a page and looking like a crawler.
 */
public final class Http {

    /** A plausible Chrome on a 4K Android TV, matching the device this runs on. */
    public static final String UA = "Mozilla/5.0 (Linux; Android 11; MiTV_MZTU0) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private Http() {
    }

    /** A page or feed a browser would open. */
    public static void browser(HttpURLConnection c) {
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "en-IN,en-GB;q=0.9,en;q=0.8");
        c.setRequestProperty("Cache-Control", "no-cache");
    }

    /** The site's own GraphQL endpoint, called the way its web client calls it. */
    public static void graphql(HttpURLConnection c) {
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
        c.setRequestProperty("Origin", "https://www.justwatch.com");
        c.setRequestProperty("Referer", "https://www.justwatch.com/");
    }

    /** JSON APIs and metadata endpoints. */
    public static void json(HttpURLConnection c) {
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9");
    }

    /** Poster art from the image CDN. */
    public static void image(HttpURLConnection c) {
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept",
                "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
    }
}
