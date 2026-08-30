# NodeFuel

A cyberpunk-themed KDE Plasma widget that tracks your Anthropic and OpenAI usage like a fuel gauge — full tank means plenty left, empty means you're about to run dry.

![platform](https://img.shields.io/badge/platform-Linux%20%2F%20KDE%20Plasma%206-blue) ![license](https://img.shields.io/badge/license-MIT-green)

> **Platform status:** Linux (KDE Plasma 6) is the finished version. A [Windows build](platforms/windows/) exists and works in headless testing, but hasn't been run on a real Windows machine yet. Android is planned but not started — see [Roadmap](#roadmap).

## What it does

- **Have API keys?** Shows your real `$` balance for Anthropic and/or OpenAI.
- **Only have a subscription** (Claude Pro/Max, ChatGPT/Codex Plus)? Shows real session/weekly usage % via the [CodexBar](https://github.com/EvilFreelancer/CodexBar-KDE) CLI, if it's installed and logged in — falls back to a plain "subscription active" badge otherwise.
- Either way, the ring shows **fuel remaining**, not fuel burned — a full ring means you're in good shape.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Python Backend Service (systemd --user, runs in background) │
│  • API key present  -> fetches real $ balance                │
│  • Subscription only -> shells out to `codexbar` for real     │
│    session/weekly usage %, or falls back to a plain badge    │
│  • Fetches both providers concurrently                       │
│  • Writes ~/.config/nodefuel/balance_cache.json               │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│  KDE Plasma Widget (QML)                                      │
│  • Panel icon: just the NodeFuel logo, click to open          │
│  • Dashboard: a fuel-gauge ring + stats per provider           │
│  • Settings page: API keys, subscription toggles, Cyber Mode  │
└──────────────────────────────────────────────────────────────┘
```

## Installation

### 1. Install the Python backend
```bash
cd NodeFuel/backend
bash setup.sh
```

### 2. Configure
Edit `~/.config/nodefuel/config.json` (or use the widget's own Settings page once installed):
```json
{
  "anthropic_api_key": "",
  "openai_api_key": "",
  "anthropic_subscription": true,
  "openai_subscription": true,
  "anthropic_use_codexbar": true,
  "openai_use_codexbar": true,
  "refresh_interval": 60
}
```
Leave the API key fields blank if you only have a subscription — the subscription toggles handle that case.

### 3. Start the service
```bash
systemctl --user enable --now nodefuel
```

### 4. Install the Plasma widget
```bash
cp -r NodeFuel ~/.local/share/plasma/plasmoids/org.nodefuel.credit
kquitapp6 plasmashell && kstart6 plasmashell &
```
Then add it via right-click on a panel → *Add Widgets* → search "NodeFuel".

## Features

- **Panel icon** — just the logo; auto-sizes to your panel's thickness
- **Dashboard** — dropped onto the desktop directly, it shows the full view inline instead of the small icon
- **Fuel gauge rings** — show what's *left*, with real session/weekly % underneath (what's *used*) when CodexBar is available
- **Live countdown** — "resets in 2h 15m", ticking down from the real reset timestamp
- **Manual refresh** — forces an immediate re-fetch (bypassing the poll interval), with an honest "refreshing..." state since a real fetch can take 15-20s
- **Settings page** — cyberpunk-styled, actually persists to the backend config, no restart needed
- **Cyber Mode** (optional, Settings → Display) — rings render as glossy 3D orbs (true radial-gradient shading, slowly rotating light source) with jagged electric surges crawling across the surface

## Commands

```bash
systemctl --user status nodefuel      # check the backend
journalctl --user -u nodefuel -f      # tail its logs
systemctl --user restart nodefuel     # restart it
cat ~/.config/nodefuel/balance_cache.json   # see the current cache
```

## Project structure

```
NodeFuel/
├── README.md
├── metadata.json                    (Plasma widget metadata)
├── backend/
│   ├── main.py                      (Python service - fetch, cache, config)
│   ├── requirements.txt
│   ├── config.example.json
│   ├── nodefuel.service             (systemd unit)
│   └── setup.sh
└── contents/
    ├── ui/
    │   ├── main.qml                 (root: cache polling, panel/desktop switching)
    │   ├── CompactRepresentation.qml (panel icon)
    │   ├── FullRepresentation.qml   (dashboard)
    │   ├── StatusRing.qml           (the fuel-gauge ring, incl. Cyber Mode)
    │   └── ConfigGeneral.qml        (settings page)
    ├── config/
    │   ├── config.qml
    │   └── main.xml                 (kcfg schema)
    └── icons/
        └── nodefuel-logo.png
```

## Roadmap

- [x] Windows version — see [`platforms/windows/`](platforms/windows/) (needs real-machine testing)
- [ ] Android version
- [ ] Usage history charts
- [ ] Budget alerts
- [ ] Multiple account support per provider

## Troubleshooting

**Widget not updating?**
- `systemctl --user status nodefuel`
- `journalctl --user -u nodefuel -f`

**Only seeing a "subscription active" badge, not real %?**
- Real usage % requires the [`codexbar`](https://github.com/EvilFreelancer/CodexBar-KDE) CLI to be installed and logged in. Without it, subscriptions fall back to the plain badge.

**Settings not saving?**
- The Settings page writes to `~/.config/nodefuel/config.json` via the backend directly (not KDE's config system), so `nodefuel-service` must be installed at `~/.local/bin/nodefuel-service` (done by `setup.sh`).

## License
MIT
