# UI rules

The card is a ten-foot, remote-driven overlay, not a web page. Two themes ship, both
first-class: dark (default) and light, toggled from the header. These rules exist because
each was broken once and cost a round of fixing.

## 1. A colour is defined for both themes, in one place

Every text and surface colour resolves through `light()` at the point of use — `txt()`,
`dim()`, `dim2()`, `accent()`, `brand()`. A constant tuned for one background and reused
on the other is a bug, not a shortcut: Apple TV's near-white wordmark (`#E9E9F0`) is
right for the dark card and invisible on the light one.

## 2. Hierarchy comes from size and weight; contrast is not a hierarchy

"Secondary" means smaller, **not fainter**. The grey-on-white failure was a 11sp label at
4.4:1 with 0.10em tracking: the three faults compound. Floors:

| Role | Minimum | Target |
|---|---|---|
| Titles | 4.5:1 | 7:1 |
| Meta / counts | 4.5:1 | 7:1 |
| Labels, stat captions | 4.5:1 | 5:1 |

Measured on the current palette (card `#F7F9FF` light, `#0E1526` dark):

| Token | Light | Ratio | Dark | Ratio |
|---|---|---|---|---|
| `txt` | `#14192B` | 16.6:1 | `#FFFFFF` | 18.2:1 |
| `dim` | `#2C3549` | 11.6:1 | `#B9C2DE` | 10.3:1 |
| `dim2` | `#4A5468` | 7.2:1 | `#9AA6C8` | 7.5:1 |
| `accent` | `#1B63C7` | 5.5:1 | `#7EE0FF` | 12.1:1 |
| gold ink | `#7A5200` | 6.9:1 | `#FFC857` | 11.4:1 |

The values these replaced — `#3E4A66` (8.4:1), `#6A7590` (**4.4:1**), gold `#B8860B`
(**3.3:1**) — passed or nearly passed on a monitor and failed at three metres, because
size and tracking cut the effective legibility well below the raw ratio.

## 3. No letterspacing under 14sp

Tracking belongs to short labels in caps, and even then it is small:

- Titles: 0
- Meta, counts, row tags: 0–0.04em
- Section headers, the service line: 0.03em
- Chips and menu labels: 0.06–0.08em

The panel line was `NEW SEASONS · 5 PICKS` at 0.10em, 13sp, grey. On white it read as
separated hairlines rather than words.

## 4. One type scale, referenced by constant

Row titles are `TITLE_SP` (20sp) in **both** tabs. They were 21 and 20 — two numbers that
happened to round to the same cap height on this panel, so they looked equal on some rows
and not others and drifted whenever either was touched.

## 5. Brand marks are drawn, and tuned per theme

`brand(key)` returns two ink values: lifted for the dark card, deepened for the light one.
Saturated marks sit on a **dark scrim plate** so a pure red does not bloom against the
purple, and a service that is not installed keeps its own colour at reduced alpha rather
than going grey — grey reads as "broken", which is how Apple TV looked.

| Service | Light | Dark |
|---|---|---|
| JioHotstar | `#0B63C9` | `#4FB3FF` |
| Netflix | `#C40812` | `#FF6B6B` |
| Prime Video | `#00709E` | `#3FC0EE` |
| ZEE5 | `#6B2FB3` | `#B07CFF` |
| Apple TV | `#2F3238` | `#E9E9F0` |
| Hoichoi | `#C1002F` | `#FF5C7A` |
| Crunchyroll | `#B04E00` | `#FF9A4D` |

## 6. Focus is one colour and one owner

Focused rows, tiles and buttons share the amber ring; the lit background is painted from
one place (`paintFocus()`), never per-view, so a rebuild cannot leave two rows lit.

## 7. A flyout overlays; it does not replace

The LEFT menu draws on top of the current pane inside the card's frame. It must never
switch tab or clear the list — doing so made the pane vanish and read as a broken tab.
LEFT and RIGHT both dismiss it, because it slides in from the left edge.
