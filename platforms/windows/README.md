# NodeFuel for Windows

A single-process tray app, unlike Linux's systemd-service + Plasma-widget
split — this one Python process runs the background fetch loop itself and
hosts the QML dashboard directly.

> **Status:** written and validated (headless, on Linux) but **not yet run
> on an actual Windows machine or display**. QML loads cleanly, the
> Python↔QML bridge works, and a real backend fetch cycle completed
> successfully in testing — but real-Windows behavior (window positioning,
> tray click handling, DPI, the `codexbar-cli.exe` path lookup) is
> unverified. Expect a short iteration pass once you run it for real.

## Setup

```powershell
cd platforms\windows
pip install -r requirements.txt
python app.py
```

Config and cache live at `~/.config/nodefuel/` (same convention as Linux -
works fine on Windows too, just not the most idiomatic location; may move
to `%APPDATA%\NodeFuel\` later).

### Real usage % (optional)

If you have a Claude Pro/Max or ChatGPT/Codex Plus subscription (no API
key), NodeFuel can show real session/weekly usage % the same way the Linux
version does - by shelling out to the [CodexBar](https://github.com/steipete/CodexBar)
CLI. Install its Windows build (`codexbar-cli.exe`) and make sure it's on
your `PATH`, or in one of:
- `%LOCALAPPDATA%\CodexBar\codexbar-cli.exe`
- `%LOCALAPPDATA%\Programs\CodexBar\codexbar-cli.exe`

Without it, subscription accounts fall back to a plain "active" badge -
same fallback behavior as Linux.

## Architecture

```
app.py
├── Bridge(QObject)        - exposes cacheJson/cyberMode/refreshing to QML,
│                             plus requestRefresh()/openSettings()/
│                             loadConfigJson()/saveConfigJson() slots
├── background thread       - runs the same NodeFuelService.run() loop
│                             from ../../backend/main.py, unmodified
├── PopupWindow (Dashboard) - frameless QQuickView, positioned near the
│                             tray icon on click
└── PopupWindow (Settings)  - same pattern, opened from the ⚙ icon or
                              tray context menu

qml/
├── StatusRing.qml   - COPIED UNCHANGED from ../../contents/ui/ - it was
│                       always pure Qt Quick, never Plasma-specific
├── Dashboard.qml     - ported from FullRepresentation.qml (Plasmoid.* -> bridge.*)
└── Settings.qml      - ported from ConfigGeneral.qml (P5Support shell
                        round-trips -> direct bridge.loadConfigJson()/
                        saveConfigJson() calls - actually simpler than Linux)
```

## Known gaps / next steps

- [ ] Verify on a real Windows machine (window positioning, tray click, DPI)
- [ ] Auto-start on login (Startup folder shortcut or Task Scheduler)
- [ ] Package as a standalone .exe (PyInstaller) so it doesn't need a Python install
- [ ] Icon in the Windows tray may need a dedicated small-size export - test at actual tray resolution
