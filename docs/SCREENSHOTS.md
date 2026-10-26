# Screenshots

Six use cases, one image each. Five are to be recaptured on the current UI; **Watch
These** is on hold while the cross-app browser is rebuilt.

| # | Use case | File | Status |
|---|---|---|---|
| 1 | Watching, list | `docs/shot-watching.png` | recapture |
| 2 | Watching, grid | `docs/shot-grid.png` | recapture |
| 3 | Stats | `docs/shot-stats.png` | recapture |
| 4 | Settings | `docs/shot-settings.png` | recapture |
| 5 | Menu | `docs/shot-menu.png` | recapture |
| 6 | Watch These | `docs/shot-watch-these.png` | on hold |

## Before you capture

- TV at **1920×1080** (`adb shell wm size`), dark theme, the build to be shipped.
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
capturing, or draw a black bar over the field. Never commit a screenshot with the key
visible.

## 5. Menu

From a row, press `LEFT` to open the menu: `WATCHING`, `WATCH THESE`, `STATS`,
`SETTINGS`, `SORT`, `LAYOUT`, `THEME`, `CLEAR HISTORY`. Capture with the first item
focused. `BACK` closes the menu; a second `BACK` closes the card.

## 6. Watch These — on hold

The tab is being rebuilt as a cross-app browser. Do not capture it until that lands.

## Rules

- 1920×1080 PNG, dark theme.
- No OMDb key, Wi-Fi name, profile name or account e-mail.
- No playback behind the card: DRM blocks the capture.
- If the app updates while capturing, start again; data and layout may reset.
