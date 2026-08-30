#!/usr/bin/env python3
"""
NodeFuel Backend Service
Fetches AI credit balances (API mode), real subscription usage stats (usage
mode, via the locally-installed CodexBar CLI), or a plain subscription badge
(subscription mode) as a fallback - and stores the result in cache for the
Plasma widget.
"""

import json
import re
import shutil
import subprocess
import time
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from datetime import datetime
import logging

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Paths
CONFIG_DIR = Path.home() / ".config" / "nodefuel"
CACHE_FILE = CONFIG_DIR / "balance_cache.json"
CONFIG_FILE = CONFIG_DIR / "config.json"

# Ensure config dir exists
CONFIG_DIR.mkdir(parents=True, exist_ok=True)

# Placeholder values shipped in config.example.json - never treat these as real keys
PLACEHOLDER_KEYS = {"sk-ant-...", "sk-...", ""}

DEFAULT_CONFIG = {
    "anthropic_api_key": "",
    "openai_api_key": "",
    "anthropic_subscription": False,
    "openai_subscription": False,
    "anthropic_use_codexbar": True,
    "openai_use_codexbar": True,
    "anthropic_codexbar_provider": "claude",
    "openai_codexbar_provider": "codex",
    "refresh_interval": 60,
    "cyber_mode": False,  # Windows app only - Linux widget stores this in KDE's own kcfg instead
}


class NodeFuelService:
    def __init__(self):
        self.config = self.load_config()
        self.anthropic_key = self.config.get("anthropic_api_key", "")
        self.openai_key = self.config.get("openai_api_key", "")
        self.anthropic_subscription = self.config.get("anthropic_subscription", False)
        self.openai_subscription = self.config.get("openai_subscription", False)
        self.anthropic_use_codexbar = self.config.get("anthropic_use_codexbar", True)
        self.openai_use_codexbar = self.config.get("openai_use_codexbar", True)
        self.anthropic_codexbar_provider = self.config.get("anthropic_codexbar_provider", "claude")
        self.openai_codexbar_provider = self.config.get("openai_codexbar_provider", "codex")
        self.codexbar_path = self.find_codexbar()

    def find_codexbar(self):
        """
        Locate the codexbar CLI, cross-platform.
        - Linux: shutil.which() alone isn't enough - systemd user services run
          with a minimal PATH that doesn't include ~/.local/bin, where
          codexbar is typically installed, so check that explicitly too.
        - Windows: the CLI ships as codexbar-cli.exe (the tray app itself is
          codexbar.exe - not what we want to shell out to). Check PATH, then
          the common per-user install locations the Windows installer uses.
        """
        if sys.platform == "win32":
            found = shutil.which("codexbar-cli") or shutil.which("codexbar-cli.exe")
            if found:
                return found
            candidates = [
                Path.home() / "AppData" / "Local" / "CodexBar" / "codexbar-cli.exe",
                Path.home() / "AppData" / "Local" / "Programs" / "CodexBar" / "codexbar-cli.exe",
            ]
            for c in candidates:
                if c.exists():
                    return str(c)
            return None

        found = shutil.which("codexbar")
        if found:
            return found
        fallback = Path.home() / ".local" / "bin" / "codexbar"
        if fallback.exists():
            return str(fallback)
        return None

    def load_config(self):
        """Load configuration from file, filling in any missing keys with defaults"""
        data = dict(DEFAULT_CONFIG)
        if CONFIG_FILE.exists():
            with open(CONFIG_FILE, 'r') as f:
                data.update(json.load(f))
        return data

    def save_config(self, updates):
        """Merge updates into the config file on disk"""
        current = self.load_config()
        current.update(updates)
        with open(CONFIG_FILE, 'w') as f:
            json.dump(current, f, indent=2)
        logger.info(f"Config updated: {list(updates.keys())}")

    def has_key(self, key):
        """Check if a real (non-placeholder) API key is configured"""
        return key not in PLACEHOLDER_KEYS

    def normalize_reset_text(self, text):
        """
        Under systemd's environment, the codexbar CLI's Swift date formatter
        drops EVERY space in this string (a Swift-corelibs-Foundation locale
        quirk when run headless - confirmed via `systemd-run --user`, not
        something fixable on our end): "Resets4:10pm(Pacific/Honolulu)"
        instead of "Resets 4:10pm (Pacific/Honolulu)". Reconstruct spacing
        from the token boundaries rather than patching specific substrings.
        """
        if not text:
            return text
        text = re.sub(r'^(Resets)(?=\S)', r'\1 ', text)     # "Resets" prefix
        text = re.sub(r'(?<=[A-Za-z])(?=[0-9])', ' ', text)  # e.g. "Sep4" -> "Sep 4"
        text = re.sub(r'(?<=,)(?=[0-9])', ' ', text)         # e.g. "4,10am" -> "4, 10am"
        text = re.sub(r'(?<=[^\s(])\(', ' (', text)          # before "("
        return text

    def fetch_codexbar_usage(self, provider_id):
        """
        Fetch real session/weekly usage % via the locally-installed CodexBar
        CLI, which is already authenticated against claude.ai / ChatGPT/Codex
        by the user. Returns a dict on success, None if unavailable/failed.
        """
        if not self.codexbar_path:
            logger.warning(f"codexbar CLI not found - falling back to subscription badge for {provider_id}")
            return None
        try:
            result = subprocess.run(
                [self.codexbar_path, "usage", "--provider", provider_id, "--format", "json"],
                capture_output=True, text=True, timeout=30
            )
            if result.returncode != 0:
                logger.warning(f"codexbar[{provider_id}] exited {result.returncode}: {result.stderr.strip()[:200]}")
                return None
            entries = json.loads(result.stdout)
            if not entries:
                return None
            entry = entries[0]
            usage = entry.get("usage", {})
            primary = usage.get("primary") or {}
            secondary = usage.get("secondary") or {}
            credits = entry.get("credits", {})
            return {
                "session_percent": primary.get("usedPercent"),
                "session_reset": self.normalize_reset_text(primary.get("resetDescription")),
                "session_reset_at": primary.get("resetsAt"),  # ISO8601, for live countdowns
                "weekly_percent": secondary.get("usedPercent"),
                "weekly_reset": self.normalize_reset_text(secondary.get("resetDescription")),
                "weekly_reset_at": secondary.get("resetsAt"),
                "credits_remaining": credits.get("remaining"),
            }
        except Exception as e:
            logger.warning(f"codexbar[{provider_id}] error: {e}")
            return None

    def get_provider_status(self, name, key, has_subscription, use_codexbar,
                             codexbar_provider, fetch_fn):
        """
        Determine the display mode for a provider, in priority order:
        - "api": real API key present -> fetch and show $ balance
        - "usage": no key, subscribed, CodexBar available -> real session/weekly %
        - "subscription": no key, subscribed, CodexBar unavailable -> plain badge
        - "none": no key, not subscribed -> not configured
        """
        if self.has_key(key):
            balance, error = fetch_fn()
            if error:
                logger.warning(f"{name} error: {error}")
            return {"mode": "api", "balance": balance or 0.0, "error": error}

        if has_subscription:
            if use_codexbar:
                usage = self.fetch_codexbar_usage(codexbar_provider)
                if usage:
                    return {"mode": "usage", "balance": None, "error": None, **usage}
            return {"mode": "subscription", "balance": None, "error": None}

        return {"mode": "none", "balance": None, "error": None}

    def load_previous_cache(self):
        """Read the cache as it stood before this cycle's fetch, for reset detection"""
        if CACHE_FILE.exists():
            try:
                with open(CACHE_FILE, 'r') as f:
                    return json.load(f)
            except Exception:
                return None
        return None

    def send_notification(self, title, message):
        """Fire a desktop notification via notify-send, tagged with the NodeFuel logo"""
        try:
            icon = str(Path(__file__).resolve().parent.parent / "contents" / "icons" / "nodefuel-logo.png")
            args = ["notify-send", "-a", "NodeFuel", title, message]
            if Path(icon).exists():
                args[1:1] = ["-i", icon]
            subprocess.run(args, timeout=5)
        except Exception as e:
            logger.warning(f"notify-send failed: {e}")

    def check_for_resets(self, old_cache, new_statuses):
        """
        Compare this cycle's resetsAt timestamps against the previous cycle's.
        A changed resetsAt means that window just rolled over to a fresh
        period - fire a "refueled" notification instead of a low-fuel one,
        since a rollover is something worth celebrating, not warning about.
        """
        if not old_cache:
            return  # first run ever - nothing to compare against

        for name, new in new_statuses.items():
            if new.get("mode") != "usage":
                continue
            old = old_cache.get(name, {})
            if old.get("mode") != "usage":
                continue

            if old.get("session_reset_at") and new.get("session_reset_at") \
                    and old["session_reset_at"] != new["session_reset_at"]:
                self.send_notification(
                    f"⛽ {name.capitalize()} refueled!",
                    "Session limit just reset - fresh tank."
                )

            if old.get("weekly_reset_at") and new.get("weekly_reset_at") \
                    and old["weekly_reset_at"] != new["weekly_reset_at"]:
                self.send_notification(
                    f"⛽ {name.capitalize()} refueled!",
                    "Weekly limit just reset - fresh tank."
                )

    def save_cache(self, anthropic_status, openai_status):
        """Save balance/status data to cache"""
        cache_data = {
            "anthropic": anthropic_status,
            "openai": openai_status,
            "last_updated": datetime.now().isoformat(),
        }
        with open(CACHE_FILE, 'w') as f:
            json.dump(cache_data, f)
        logger.info(
            f"Cache saved: Anthropic[{anthropic_status['mode']}], "
            f"OpenAI[{openai_status['mode']}]"
        )

    def fetch_anthropic_balance(self):
        """Fetch balance from Anthropic API"""
        try:
            import requests
            headers = {"x-api-key": self.anthropic_key}
            response = requests.get(
                "https://api.anthropic.com/v1/account",
                headers=headers,
                timeout=10
            )
            if response.status_code == 200:
                data = response.json()
                balance = data.get("usage", {}).get("balance", 0.0)
                return balance, None
            else:
                return None, f"HTTP {response.status_code}"
        except Exception as e:
            return None, str(e)

    def fetch_openai_balance(self):
        """Fetch balance from OpenAI API"""
        try:
            import requests
            headers = {"Authorization": f"Bearer {self.openai_key}"}
            response = requests.get(
                "https://api.openai.com/dashboard/billing/credit_grants",
                headers=headers,
                timeout=10
            )
            if response.status_code == 200:
                data = response.json()
                balance = data.get("total_available", 0.0)
                return balance, None
            else:
                return None, f"HTTP {response.status_code}"
        except Exception as e:
            return None, str(e)

    def fetch_all_balances(self):
        """
        Determine status for all providers (API balance, real usage, or badge).
        Runs both providers' fetches concurrently (each is a network-bound
        codexbar/API call taking several seconds) instead of sequentially -
        cuts a refresh from the sum of both down to roughly the slower one.
        """
        logger.info("Checking provider status...")

        with ThreadPoolExecutor(max_workers=2) as executor:
            future_ant = executor.submit(
                self.get_provider_status,
                "Anthropic", self.anthropic_key, self.anthropic_subscription,
                self.anthropic_use_codexbar, self.anthropic_codexbar_provider,
                self.fetch_anthropic_balance
            )
            future_oai = executor.submit(
                self.get_provider_status,
                "OpenAI", self.openai_key, self.openai_subscription,
                self.openai_use_codexbar, self.openai_codexbar_provider,
                self.fetch_openai_balance
            )
            anthropic_status = future_ant.result()
            openai_status = future_oai.result()

        return anthropic_status, openai_status

    def run_cycle(self):
        """One fetch-save cycle, shared by run() and run_once()"""
        # Reset notifications turned out to be annoying in practice - the
        # detection logic (check_for_resets) is kept around unused in case
        # this gets revisited, but it's not called.
        anthropic_status, openai_status = self.fetch_all_balances()
        self.save_cache(anthropic_status, openai_status)

    def run_once(self):
        """Fetch status a single time and exit - used by the widget's manual refresh button"""
        self.run_cycle()

    def run(self, interval=60):
        """Run service indefinitely, refreshing status every interval seconds"""
        logger.info(f"NodeFuel service started. Refresh interval: {interval}s")

        try:
            while True:
                self.run_cycle()
                # Reload config each cycle so settings changes take effect without a restart
                self.__init__()
                time.sleep(interval)
        except KeyboardInterrupt:
            logger.info("Service stopped by user")
            sys.exit(0)
        except Exception as e:
            logger.error(f"Unexpected error: {e}")
            sys.exit(1)


if __name__ == "__main__":
    service = NodeFuelService()
    if "--set-config-b64" in sys.argv:
        # Base64-encoded JSON avoids shell-quoting issues with keys/values
        # that contain quotes - the caller (the settings QML) uses Qt.btoa()
        idx = sys.argv.index("--set-config-b64")
        import base64
        payload = json.loads(base64.b64decode(sys.argv[idx + 1]).decode("utf-8"))
        service.save_config(payload)
        # Re-init with the new config, then fetch immediately so the widget
        # reflects the change without waiting for the next interval
        service = NodeFuelService()
        service.run_once()
    elif "--set-config" in sys.argv:
        idx = sys.argv.index("--set-config")
        payload = json.loads(sys.argv[idx + 1])
        service.save_config(payload)
        service = NodeFuelService()
        service.run_once()
    elif "--once" in sys.argv:
        service.run_once()
    else:
        interval = int(sys.argv[1]) if len(sys.argv) > 1 else 60
        service.run(interval)
