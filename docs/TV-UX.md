# TV UX policy

A ten-foot UI on a remote. Every rule here exists because breaking it produced a specific
defect on this TV, and each one is testable — if a screen cannot be checked against a rule,
the rule is written badly.

The card is 90% of the screen, dark by default, with a light theme that is a first-class
citizen. Rules marked **MUST** are not style preferences; they are the difference between a
screen that works from three metres and one that does not.

---

## 1. Canvas, safe area, scale

| Rule | Value |
|---|---|
| Card size | 90% of the screen, centred, 38 dp corner radius |
| Inner padding | 34 dp horizontal, 22 dp vertical |
| Safe margin | Nothing within 5% of any screen edge — TVs overscan |
| Spacing unit | 8 dp. All gaps, paddings and margins are multiples of 8 (6 dp is the only exception, for vertical rhythm inside a row) |
| Row height | 96–132 dp depending on metadata lines; never below 88 dp |
| Touch/hit target | Minimum 48 dp, even though the remote is the input |
| Two-pane split | Rail 212 dp fixed; collection takes the rest. The detail panel is 62% of the body width |

**MUST:** the layout survives a 5% overscan crop on every edge without losing a control or
clipping a word.

---

## 2. Type

### Size range

| Role | Min | Default | Max |
|---|---|---|---|
| Row title | 18 sp | 20 sp (`TITLE_SP`) | 22 sp |
| Detail-panel title | 22 sp | 26 sp | 30 sp |
| Section header | 12 sp | 14 sp | 16 sp |
| Body / synopsis | 13 sp | 14 sp | 16 sp |
| Metadata, counts | 11 sp | 12 sp | 13 sp |
| Chip / button label | 12 sp | 15 sp | 16 sp |
| Never | below 11 sp | | above 30 sp |

- **MUST:** nothing is smaller than 11 sp. Below that a TV scaler turns text into texture.
- **MUST:** titles are the only 20 sp+ element on a row. Two competing large elements make
  the eye bounce.
- **MUST:** one constant per role (`TITLE_SP`), referenced by code. Two numbers that happen
  to round to the same pixel height are a bug waiting to diverge.
- **MUST NOT:** letterspacing above 0.06 em under 14 sp. Tracking belongs to short caps
  labels; on small text it turns words into separated hairlines.
- Weight carries hierarchy before size does: bold title, regular body, no third weight.

### Line length

- Synopses wrap at 60–70 characters. The panel clips at ~420 characters, on a word boundary.

---

## 3. Colour

### Theme targets — measured in luminance, not taste

| Theme | Target | Current | Why |
|---|---|---|---|
| Dark canvas | ~5% brightness | `#0E1526` (≈7%) | A dark room needs the panel to be darker than the wall it lights |
| Light canvas | ~90% brightness | `#EAECF4` (≈91%) | Near-white is glare on a 4K panel at full backlight; it also erases the row edge |
| Rows on light | ~97% | `#FFFFFF` at 95% alpha | Rows must lift *off* the canvas, not merge with it |

### Contrast floors (WCAG ratio, measured, not eyeballed)

| Role | Minimum | Target |
|---|---|---|
| Titles | 4.5:1 | 7:1 |
| Body, metadata | 4.5:1 | 7:1 |
| Labels, captions | 4.5:1 | 5:1 |
| Disabled / "not installed" | 3:1 | — |
| Focus ring vs its surface | 3:1 | 4.5:1 |

Current palette achieves 7.2–18.2:1 for text; see [UI.md](UI.md) for the token table.

**MUST NOT:** place foreground and background within ~20% of each other in luminance.
Similar tones are the single most common TV failure: they look correct on a monitor and
disappear across a room. If two surfaces are adjacent, one of them gets a hairline or a
luminance step of at least 8%.

**MUST NOT:** use colour alone to carry state. The score badge pairs its colour with
`4K`/`HD` text; "not installed" pairs its dimming with the words *install*.

### Brand colour

- Each service gets an ink per theme (light ink is darker, dark ink is lifted).
- Saturated marks sit on a **dark scrim plate** so pure red does not bloom against the
  purple canvas.
- Brand marks never carry information by themselves — the service name is on the row.

---

## 4. Depth — bevels, not outlines

A 2 dp stroke of even weight on every edge is a web outline. On a TV it reads as a
selection box, not a control.

**MUST:** interactive surfaces rise from the canvas using a bevel:

1. **Lip** — the body of the key, drawn 3–4 dp below the face, in a darker shade.
2. **Face** — vertical gradient, lighter at the top.
3. **Sheen** — a white gradient clipped to the upper 50%, 20–25% alpha.
4. **Hairline** — 1.4 dp inner stroke at ~25% white, inside the edge.

Implemented in `Bevel.java`. Applies to RESUME, INSTALL and any future primary action.

**MUST:** focus is a property of the whole row, painted by one owner (`paintFocus`), and it
must be visible from three metres with the row's own content still legible underneath.
**MUST NOT:** focus is not a thin ring drawn *around* content — it is a lit surface plus a
ring, and the ring never replaces the bevel on a control.

### Layers need a lightbox

**MUST:** any panel that overlays content dims what it covers — a scrim at 38% on the light
card, 55% on the dark one, fading in with the panel and out with it. Without it a sliding
panel reads as a *replacement* of the page, and the eye cannot tell which surface is
forward. The scrim is never focusable and never takes the cursor; it is a lightbox, not a
target.

The LEFT flyout is the exception and deliberately has none: it is a narrow strip pinned to
the edge, and dimming the whole card for it would make a 186 dp menu feel like a modal.

---

## 5. Loading — a skeleton is mandatory

**MUST:** any wait longer than ~150 ms with reserved space shows a shimmer skeleton in that
space. Never a blank panel, never a spinner alone, never a jump when data lands.

- Skeleton geometry matches the final geometry: same width fractions, same line heights.
- The shimmer is a sweeping highlight, 1.0–1.2 s per pass, low contrast (≤ 10% luminance
  step) so it does not compete with real content.
- One shimmer per region; a page of nine skeleton lines is noise.
- **MUST:** the skeleton stops when the data arrives, and stops itself after ~8 s if it
  never does, with an error state replacing it.
- **MUST NOT:** shimmer over content that is already available (warm cache). The loader
  tracks the wait, not the widget.

Where it applies today: detail-panel poster, detail-panel synopsis/cast when a curated pick
has to be looked up, and the panel body while a refresh is in flight.

---

## 6. Motion

| Action | Duration | Easing |
|---|---|---|
| Card open / close | 180–220 ms | decelerate |
| Flyout in / out | 200 ms | decelerate |
| Detail panel in | 220 ms | decelerate |
| Detail panel out | 160 ms | accelerate |
| Focus movement | instant, or ≤ 80 ms | linear |
| Colour/state change | 120 ms | linear |

- **MUST:** focus feedback is never animated longer than 80 ms — the cursor must feel like
  it teleported.
- **MUST:** nothing loops except a genuine indeterminate load, and even that at low
  contrast.
- **MUST NOT:** animate layout size when a fade or a translate will do; re-measuring during
  a key press is what makes a remote feel laggy.

---

## 7. Navigation and affordance

| Key | Meaning | Never |
|---|---|---|
| Up / Down | Move within the current list | Change section |
| Left | Back one level: collection → rail → flyout | Skip a level |
| Right | Deeper: rail → collection; row → detail panel | Do nothing silently |
| OK | Open / activate the focused thing | Open something else |
| Back | Close the outermost layer | Close the card while a layer is open |

**MUST:** every direction that does something visible is signposted — a chevron on the row
edge for "there is a panel to the right", a count on a rail tile, `INSTALL ▸` on a row that
opens the store.

**MUST:** a direction with no destination is consumed, not passed to the platform's default
focus search. Default focus search is what made LEFT from the last title jump to the last
service tile.

**MUST:** closing a layer returns focus to the exact view that opened it.

---

## 8. States

Every list has five, and each is designed rather than defaulted:

| State | Requirement |
|---|---|
| Loading | Skeleton (§5) |
| Empty | A sentence that says what would appear here and why it does not, plus one action |
| Error | What failed, in plain words; a retry affordance; never a raw exception or a blank pane |
| Partial | Show what arrived, mark what did not ("Refreshing…") |
| Stale | Show the age ("Updated 6m ago") when the data is cached |

**MUST NOT:** an empty pane with no text. Silent emptiness reads as a crash.

---

## 9. Words

- Sentence case for sentences; caps only for labels of 1–2 words.
- No jargon, no API names, no error codes on screen.
- Numbers get units and separators: `291,756 votes`, `8.6`, `94%`.
- **MUST:** a list of people or things is presented as chips, not as a punctuated
  sentence. `Stars  A · B · C` reads as prose; a label plus separate plates reads as a
  cast. Chips carry no bevel — they are not interactive — but they keep the 11 sp floor,
  a 4.5:1 ink, and the 8 dp rhythm between them.
- **MUST:** a `0` that means "no data" is shown as nothing, not as zero (a 0% Tomatometer
  badge was reporting "no score" as a review score of zero).

---

## 10. Performance budget (visible behaviour)

| Operation | Budget |
|---|---|
| Card appears | ≤ 400 ms after the key |
| Tab switch with warm cache | ≤ 100 ms, no flash of empty |
| Row paint from cache | one frame |
| Detail panel opens | ≤ 220 ms to full content, poster may follow |
| Scroll step | no dropped frame while holding Down |

**MUST:** no network call on the UI thread, ever. A fetch blocks a worker, and the screen
shows its skeleton state until it answers.

---

## Checklist before shipping a screen

1. Readable at three metres? Check the smallest text against §2.
2. Every surface pair ≥ 8% luminance apart, or separated by a hairline.
3. Contrast measured, not guessed (§3 table).
4. Every wait has a skeleton in its final geometry (§5).
5. Every interactive surface has a bevel; focus is painted by one owner (§4).
6. Every direction is either signposted or consumed (§7).
7. Five states designed (§8).
8. Both themes checked — the light canvas is ~90%, never `#FFFFFF` at full page.
9. No number without a unit, no zero meaning "unknown" (§9).
10. No dropped frames while holding Down (§10).
