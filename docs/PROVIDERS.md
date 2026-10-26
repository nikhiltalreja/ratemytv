# Measured behaviour of each streaming app

Every number here was measured on a Xiaomi MiTV-MZTU0 (Android TV, 2026 firmware)
by polling the TV Provider every 15 seconds while playing. Nothing here is from
documentation — the documentation is wrong or missing for all of it.

## The TV Provider

Authority `android.media.tv`, two tables matter:

- `watch_next_program` — "Continue watching". Readable with `READ_TV_LISTINGS`.
  57 columns, including `title`, `last_playback_position_millis`,
  `duration_millis`, `poster_art_uri`, `intent_uri`, `watch_next_type`,
  `last_engagement_time_utc_millis`, `short_description`.
- `preview_program` — catalogue rows. 11,468 rows on this box, almost all Apple TV.
  Has **no** `last_engagement_time_utc_millis` column, so sorting by it throws.

`watch_next_type` values: `0` continue, `1` next episode, `2` new episode,
`3` watchlist. A `0` row with `position = 0` is finished-or-reset, not "unwatched".

Without `READ_TV_LISTINGS` the provider returns an **empty cursor and no error**.
That is why `adb shell content query --uri content://android.media.tv/watch_next_program`
says "No result found" on a stock TV: the `shell` user has no grant.

## Latency: when does a row appear?

| app | writes during playback? | when the row lands | timestamp written |
|---|---|---|---|
| **Netflix** | no — 8 minutes of playback, zero writes | 6–10 min after the app loses foreground | **backdated** to playback start |
| **Apple TV** | no | **~16 s** after the player stops | honest (now) |
| **JioHotstar** | never for the current title | — | — |
| **Prime Video** | unmeasured | rows exist and are fresh (hours) | unknown |
| **YouTube** | never writes a row | — | — |

Verified for Apple TV: session stopped at position `294000`, and 16 s later a new
row appeared with exactly `294000 / 2473000` and a `last_engagement_time` of *now*.

Verified for Netflix: session at 17 s → 497 s over 8 minutes, row set identical
throughout; the row for that title appeared only after leaving the app, and carried
a `last_engagement_time` backdated to the moment playback had *started*. So an
old-looking row can be freshly written — do not read the timestamp as "watched
then" for Netflix.

## MediaSession metadata: who titles their session?

| app | `METADATA_KEY_TITLE` | other keys | verdict |
|---|---|---|---|
| **JioHotstar** | episode label, e.g. `S2 E4 10th Quarter` | `DURATION`, `MEDIA_URI`; all display/album/artist fields `null` | usable |
| **YouTube** | video title | `ARTIST` = channel, `DURATION`; `MEDIA_URI`/`MEDIA_ID` `null` | usable |
| **Netflix** | `null` | nothing | useless |
| **Apple TV** | `null` | nothing | useless |
| **Prime Video** | unmeasured | — | — |

Netflix returns `metadata: null` even while playing, on both browse and player
screens. Apple TV likewise.

JioHotstar's `MEDIA_URI` is the CDN manifest, and its path embeds two ids:
`…/v3/<showId>/<episodeId>/…`. The `episodeId` equals the `content_id` of the
provider row for that episode, whose `title` is the **series** name. That is how
Rate My TV turns `S2 E4 10th Quarter` into `Chad Powers S2 E04`.

## Screenshots and accessibility: all blocked

| app | `screencap` | view tree |
|---|---|---|
| Netflix | 0 bytes (fails outright) | canvas (`com.netflix.ninja:id/gibbon`), no text nodes |
| Prime Video | captures, but fully black | — |
| Apple TV | splash renders; **0 bytes** once the UI loads | 3.7 KB shell, no text nodes |
| YouTube | **works** (2.4 MB, full UI) | — |

FLAG_SECURE is set per window, so the same app can be capturable on one screen and
not another: Apple's splash came through, its browse screen did not. OCR is only
viable for YouTube, where Rate My TV already reads the title from the session.

## Other traps

- **Account-wide rows.** `watch_next_program` is shared across every device on the
  account. Another TV watching *The Fall of the House of Usher* put that row on
  this one. There is no device column among the 57 — not even in `intent_uri`,
  which is stamped with the device id of whichever app *wrote* the row.
- **JioHotstar pushes recommendations.** Rows it never played appear with
  `watch_next_type = 0`, a position and an engagement time, indistinguishable from
  real history except by the link: `utm_medium=reco_tray_play_next`.
  `The X-Files@98000` was one of these.
- **Netflix evicts.** Only ~4 Netflix continue rows exist at a time; adding one
  drops another. Apple TV keeps ~35.
- **Android 11+ package visibility.** A `hotstar://` link is unresolvable from
  another app without a `<queries>` declaration — the intent silently falls back
  to "open the app". Cost us a debugging session; see the manifest.
- **`org.json` keys() cannot remove.** Iterating `JSONObject.keys()` and calling
  `remove()` throws `UnsupportedOperationException`, which an enclosing `catch`
  will happily swallow *before* the write — records vanish and nothing is logged.
  Collect keys first, remove after.
- **javac and UTF-8.** Non-ASCII literals in a `.java` file are read with the
  platform charset unless `-encoding UTF-8` is passed; `·` becomes `Â·`.
