import QtQuick
import org.kde.plasma.plasmoid
import org.kde.plasma.core as PlasmaCore
import org.kde.plasma.plasma5support as P5Support

PlasmoidItem {
    id: root

    preferredRepresentation: compactRepresentation
    Plasmoid.status: PlasmaCore.Types.ActiveStatus
    // Suppress the panel's default background chip behind the tray icon -
    // just the two rings should show, no boxed/windowed backdrop.
    Plasmoid.backgroundHints: PlasmaCore.Types.NoBackground

    // When there's more room than this (dropped directly onto the desktop,
    // not squeezed into a thin panel), show the full dashboard inline
    // instead of the small dual-ring icon + click-to-popup.
    switchWidth: 160
    switchHeight: 90

    // True while a manual forceRefresh() is actually in flight - the
    // underlying codexbar fetch (Claude side especially) can take ~15-20s,
    // so the UI needs an honest "refreshing" state instead of looking frozen.
    property bool refreshing: false

    // Executable data engine - runs `cat` on the cache file.
    // Result is stored in Plasmoid.configuration.cacheJson, which (unlike
    // custom properties on this root item) is genuinely visible from both
    // CompactRepresentation.qml and FullRepresentation.qml.
    P5Support.DataSource {
        id: executable
        engine: "executable"
        connectedSources: []

        function runCommand(cmd) {
            connectSource(cmd)
        }

        onNewData: (sourceName, data) => {
            const stdout = (data["stdout"] || "").trim()
            if (stdout.length > 0) {
                try {
                    JSON.parse(stdout) // validate before storing
                    Plasmoid.configuration.cacheJson = stdout
                } catch (e) {
                    // Cache file not valid JSON yet - keep previous value
                }
            }
            if (sourceName.indexOf("--once") >= 0) {
                root.refreshing = false
            }
            disconnectSource(sourceName)
        }
    }

    Timer {
        id: refreshTimer
        interval: Math.max(5, Plasmoid.configuration.refreshInterval) * 1000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: readCache()
    }

    function readCache() {
        // ~ is expanded by the shell the executable engine runs commands through
        executable.runCommand("cat ~/.config/nodefuel/balance_cache.json")
    }

    function forceRefresh() {
        // Runs the backend once (bypassing its own interval wait) then reads
        // the freshly-written cache in the same shell command, so the
        // manual refresh button always reflects a genuinely new fetch.
        // This can take ~15-20s (the Claude-side codexbar fetch is slow) -
        // root.refreshing lets the UI show that honestly instead of
        // silently sitting on the old timestamp.
        root.refreshing = true
        executable.runCommand(
            "~/.local/bin/nodefuel-service --once >/dev/null 2>&1; cat ~/.config/nodefuel/balance_cache.json"
        )
    }

    compactRepresentation: CompactRepresentation {}
    fullRepresentation: FullRepresentation {}
}
