#!/usr/bin/env python3
"""
NodeFuel - Windows tray application

Single-process design (unlike the Linux version's systemd-service + Plasma-
widget split): this app runs the background fetch cycle in its own thread
and hosts the QML dashboard/settings directly. Reuses NodeFuelService from
the shared ../../backend/main.py unchanged - only its CodexBar path lookup
needed a cross-platform branch (already added there).

NOTE: written without a Windows machine to test on. Expect an iteration
pass once this actually runs - report back anything that errors or looks
wrong and it'll get fixed, the same way the Linux widget was tuned.
"""

import sys
import json
import threading
from pathlib import Path

# Make backend/main.py importable as `from main import NodeFuelService`
_BACKEND_DIR = str(Path(__file__).resolve().parent.parent.parent / "backend")
sys.path.insert(0, _BACKEND_DIR)
from main import NodeFuelService, CACHE_FILE, CONFIG_FILE  # noqa: E402

from PySide6.QtCore import QObject, Property, Signal, Slot, QUrl, QPoint, Qt  # noqa: E402
from PySide6.QtGui import QIcon  # noqa: E402
from PySide6.QtWidgets import QApplication, QSystemTrayIcon, QMenu  # noqa: E402
from PySide6.QtQuick import QQuickView  # noqa: E402

QML_DIR = Path(__file__).resolve().parent / "qml"
ICON_PATH = Path(__file__).resolve().parent / "icons" / "nodefuel-logo.png"


class Bridge(QObject):
    """
    Exposed to QML as `bridge` - the one thing Dashboard.qml and
    Settings.qml talk to, replacing Plasmoid.configuration and the
    P5Support executable engine the Linux widget used.
    """

    cacheJsonChanged = Signal()
    cyberModeChanged = Signal()
    refreshingChanged = Signal()
    settingsRequested = Signal()

    def __init__(self):
        super().__init__()
        self._cache_json = "{}"
        self._cyber_mode = False
        self._refreshing = False
        self._load_cyber_mode()
        self.reload_cache()

    def _load_cyber_mode(self):
        try:
            if CONFIG_FILE.exists():
                cfg = json.loads(CONFIG_FILE.read_text())
                self._cyber_mode = bool(cfg.get("cyber_mode", False))
        except Exception as e:
            print(f"Bridge: failed to load cyber_mode: {e}")

    def reload_cache(self):
        try:
            if CACHE_FILE.exists():
                self._cache_json = CACHE_FILE.read_text()
                self.cacheJsonChanged.emit()
        except Exception as e:
            print(f"Bridge: failed to reload cache: {e}")

    @Property(str, notify=cacheJsonChanged)
    def cacheJson(self):
        return self._cache_json

    def _get_cyber_mode(self):
        return self._cyber_mode

    def _set_cyber_mode(self, value):
        if value == self._cyber_mode:
            return
        self._cyber_mode = value
        self.cyberModeChanged.emit()
        # Purely cosmetic - persist immediately, no need to route through
        # the full save/refresh flow the API-key/subscription fields use.
        try:
            cfg = json.loads(CONFIG_FILE.read_text()) if CONFIG_FILE.exists() else {}
            cfg["cyber_mode"] = value
            CONFIG_FILE.write_text(json.dumps(cfg, indent=2))
        except Exception as e:
            print(f"Bridge: failed to persist cyber_mode: {e}")

    cyberMode = Property(bool, _get_cyber_mode, _set_cyber_mode, notify=cyberModeChanged)

    def _get_refreshing(self):
        return self._refreshing

    def _set_refreshing(self, value):
        if value == self._refreshing:
            return
        self._refreshing = value
        self.refreshingChanged.emit()

    refreshing = Property(bool, _get_refreshing, _set_refreshing, notify=refreshingChanged)

    @Slot()
    def requestRefresh(self):
        if self._refreshing:
            return
        self.refreshing = True
        threading.Thread(target=self._refresh_worker, daemon=True).start()

    def _refresh_worker(self):
        try:
            NodeFuelService().run_cycle()
        except Exception as e:
            print(f"Bridge: refresh failed: {e}")
        finally:
            self.reload_cache()
            self.refreshing = False

    @Slot()
    def openSettings(self):
        self.settingsRequested.emit()

    @Slot(result=str)
    def loadConfigJson(self):
        try:
            if CONFIG_FILE.exists():
                return CONFIG_FILE.read_text()
        except Exception as e:
            print(f"Bridge: failed to load config: {e}")
        return "{}"

    @Slot(str)
    def saveConfigJson(self, json_str):
        try:
            payload = json.loads(json_str)
        except Exception as e:
            print(f"Bridge: saveConfigJson got bad JSON: {e}")
            return
        NodeFuelService().save_config(payload)
        self.requestRefresh()


class PopupWindow(QQuickView):
    """A frameless QQuickView hosting one QML file, positioned near the tray icon."""

    def __init__(self, qml_file, bridge):
        super().__init__()
        self.setFlags(Qt.FramelessWindowHint | Qt.Tool | Qt.WindowStaysOnTopHint)
        self.setColor(Qt.transparent)
        self.rootContext().setContextProperty("bridge", bridge)
        self.setResizeMode(QQuickView.SizeRootObjectToView)
        self.setSource(QUrl.fromLocalFile(str(QML_DIR / qml_file)))
        for err in self.errors():
            print(f"QML error in {qml_file}: {err.toString()}")

    def show_near_tray(self, tray_icon):
        root = self.rootObject()
        w = int(root.width()) if root else 300
        h = int(root.height()) if root else 250
        self.resize(w, h)

        geo = tray_icon.geometry()
        screen = self.screen() or QApplication.primaryScreen()
        avail = screen.availableGeometry()

        if not geo.isNull():
            x = geo.center().x() - w // 2
            y = geo.top() - h - 8
            if y < avail.top():
                y = geo.bottom() + 8
        else:
            # geometry() can return an empty rect on some setups - fall
            # back to the bottom-right corner (typical taskbar position)
            x = avail.right() - w - 16
            y = avail.bottom() - h - 16

        x = max(avail.left(), min(x, avail.right() - w))
        y = max(avail.top(), min(y, avail.bottom() - h))
        self.setPosition(QPoint(x, y))
        self.show()
        self.requestActivate()


def main():
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)

    bridge = Bridge()

    if not ICON_PATH.exists():
        print(f"Warning: icon not found at {ICON_PATH}")
    tray = QSystemTrayIcon(QIcon(str(ICON_PATH)))
    tray.setToolTip("NodeFuel")

    dashboard = PopupWindow("Dashboard.qml", bridge)
    settings = PopupWindow("Settings.qml", bridge)
    # Re-run Settings.qml's load() every time it's opened, so it reflects
    # whatever's actually on disk (e.g. if it was edited by hand).
    settings_root_ref = {"obj": settings.rootObject()}

    def open_settings():
        root = settings.rootObject()
        if root:
            root.load()
        settings.show_near_tray(tray)

    bridge.settingsRequested.connect(open_settings)

    def toggle_dashboard():
        if dashboard.isVisible():
            dashboard.hide()
        else:
            dashboard.show_near_tray(tray)

    def on_tray_activated(reason):
        if reason == QSystemTrayIcon.Trigger:  # left click
            toggle_dashboard()

    tray.activated.connect(on_tray_activated)

    menu = QMenu()
    menu.addAction("Open Dashboard", toggle_dashboard)
    menu.addAction("Settings", open_settings)
    menu.addAction("Refresh Now", bridge.requestRefresh)
    menu.addSeparator()
    menu.addAction("Quit", app.quit)
    tray.setContextMenu(menu)

    tray.show()

    # Kick off an initial fetch shortly after startup, then let
    # NodeFuelService's own configured interval (default 60s) take over -
    # run() blocks, so it needs its own thread for the life of the app.
    def run_backend_loop():
        service = NodeFuelService()
        interval = service.config.get("refresh_interval", 60)
        service.run(interval)  # blocks forever, refreshing on its own schedule

    backend_thread = threading.Thread(target=run_backend_loop, daemon=True)
    backend_thread.start()

    # Reflect the backend thread's writes to balance_cache.json in the UI -
    # poll rather than a filesystem watcher, matching the Linux widget's
    # same polling philosophy for consistency.
    from PySide6.QtCore import QTimer
    poll_timer = QTimer()
    poll_timer.timeout.connect(bridge.reload_cache)
    poll_timer.start(5000)

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
