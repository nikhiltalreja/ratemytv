# Screenshots

Six use cases, one image each, captured on **1.0.6**, light theme.

| # | Use case | File | Status |
|---|---|---|---|
| 1 | Watching, list | `docs/shot-watching.png` | captured |
| 2 | Watching, grid | `docs/shot-grid.png` | captured |
| 3 | Stats | `docs/shot-stats.png` | captured |
| 4 | Settings | `docs/shot-settings.png` | captured |
| 5 | Menu | `docs/shot-menu.png` | captured |
| 6 | Watch These | `docs/shot-watch-these-1.0.6.png` | captured |

`docs/shot-watch-these.png` and `docs/shot-watch-these-light.png` are the README's
Watch These images; the six-screen set above has its own capture.

## Before you capture

- TV at **1920×1080** (`adb shell wm size`), light theme, the build to be shipped.
- At least two titles in `WATCHING` (for example The Bear and Mobland), so the list and
  grid are not empty.
- JioHotstar signed in on the TV. Its splash is the clean background; do not start
  playback, because the DRM surface makes `screencap` return a 0-byte file.
- The card open, and `adb` connected.

Capture:

```bash
adb shell screencap -p /sdcard/shot.png
adb pull /sdcard/shot.png docs/shot-<case>.png
```

For the background: force-stop and relaunch JioHotstar, then capture within about
1.5 seconds, while the splash is still up. Once the profile picker or a trailer appears,
the clean window is gone. The launcher is a fallback, but its top bar and app tiles show
through the glass card.

Post-process to 1920×1080: black outside the card's rounded frame, and mask the OMDb
field before saving.

## 1. Watching, list

Open the card on `WATCHING` (the default tab). Two rows with poster, score, plot, cast
and progress.

## 2. Watching, grid

With `WATCHING` selected, press the layout button at the top right (the list/grid icon),
or open the menu and choose `LAYOUT`. Capture the grid.

## 3. Stats

Select the `STATS` tab. Capture the four cards.

## 4. Settings

Select the `SETTINGS` tab. **The OMDb field contains a live key.** Clear it before
capturing, or mask the field with bullets. Never commit a screenshot with the key
visible.

## 5. Menu

From a row, press `LEFT` to open the menu: `WATCHING`, `WATCH THESE`, `STATS`,
`SETTINGS`, `SORT`, `LAYOUT`, `THEME`, `CLEAR HISTORY`. Capture with the first item
focused. `BACK` closes the menu; a second `BACK` closes the card.

## 6. Watch These

Select `WATCH THESE`. The rail on the left lists the services installed on this TV,
with a count; the collection on the right is the selected service's 2026 picks, headed
`NETFLIX · 6 TITLES`. Services without an app stay in the rail, dimmed and labelled
"Not installed · install". Capture with a service that has picks selected.

## Rules

- 1920×1080 PNG, light theme.
- No OMDb key, Wi-Fi name, profile name or account e-mail.
- No playback behind the card: DRM blocks the capture.
- If the app updates while capturing, start again; data and layout may reset.
