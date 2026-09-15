import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid

Rectangle {
    id: full

    // Height derives from the actual content (mainColumn's implicitHeight),
    // not a hand-guessed literal - so it's always exactly right regardless
    // of which lines are visible (credits row, resets row, etc.) and never
    // leaves stray empty space or clips a line.
    readonly property real contentHeight: mainColumn.implicitHeight + 32 // + top/bottom margins

    Layout.preferredWidth: 292
    Layout.preferredHeight: contentHeight
    Layout.minimumWidth: Layout.preferredWidth
    Layout.minimumHeight: Layout.preferredHeight
    Layout.maximumWidth: Layout.preferredWidth
    Layout.maximumHeight: Layout.preferredHeight
    width: Layout.preferredWidth
    height: Layout.preferredHeight
    color: "#0a0a0a"
    border.color: "#00D9FF"
    border.width: 2
    radius: 6

    readonly property bool cyber: Plasmoid.configuration.cyberMode

    // Thin gradient accent stripe along the top edge - a cheap way to get
    // a "dual neon" frame without a real gradient border (QtQuick Rectangle
    // borders can't gradient directly).
    Rectangle {
        anchors {
            top: parent.top
            left: parent.left
            right: parent.right
            topMargin: 2
        }
        height: 2
        radius: 1
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0.0; color: "#00D9FF" }
            GradientStop { position: 1.0; color: "#FF006E" }
        }
    }

    // ---- Cyber Mode: window-level HUD treatment ----

    // Breathing glow border, layered over the base border
    Rectangle {
        visible: full.cyber
        anchors.fill: parent
        radius: full.radius
        color: "transparent"
        border.color: "#00D9FF"
        border.width: 2

        SequentialAnimation on opacity {
            running: full.cyber
            loops: Animation.Infinite
            NumberAnimation { from: 0.25; to: 0.85; duration: 1400; easing.type: Easing.InOutSine }
            NumberAnimation { from: 0.85; to: 0.25; duration: 1400; easing.type: Easing.InOutSine }
        }
    }

    // Corner brackets - a HUD-reticle framing cue, diagonally color-paired
    // to match the cyan->pink flow used in the top stripe and divider
    component CornerBracket: Item {
        property color accent: "#00D9FF"
        property int hAlign: -1 // -1 = left, 1 = right
        property int vAlign: -1 // -1 = top, 1 = bottom
        width: 14
        height: 14

        Rectangle {
            width: 12
            height: 2
            color: parent.accent
            anchors {
                left: parent.hAlign < 0 ? parent.left : undefined
                right: parent.hAlign > 0 ? parent.right : undefined
                top: parent.vAlign < 0 ? parent.top : undefined
                bottom: parent.vAlign > 0 ? parent.bottom : undefined
            }
        }
        Rectangle {
            width: 2
            height: 12
            color: parent.accent
            anchors {
                left: parent.hAlign < 0 ? parent.left : undefined
                right: parent.hAlign > 0 ? parent.right : undefined
                top: parent.vAlign < 0 ? parent.top : undefined
                bottom: parent.vAlign > 0 ? parent.bottom : undefined
            }
        }
    }

    CornerBracket {
        visible: full.cyber
        accent: "#00D9FF"
        hAlign: -1; vAlign: -1
        anchors { left: parent.left; top: parent.top; leftMargin: 4; topMargin: 4 }
    }
    CornerBracket {
        visible: full.cyber
        accent: "#FF006E"
        hAlign: 1; vAlign: -1
        anchors { right: parent.right; top: parent.top; rightMargin: 4; topMargin: 4 }
    }
    CornerBracket {
        visible: full.cyber
        accent: "#FF006E"
        hAlign: -1; vAlign: 1
        anchors { left: parent.left; bottom: parent.bottom; leftMargin: 4; bottomMargin: 4 }
    }
    CornerBracket {
        visible: full.cyber
        accent: "#00D9FF"
        hAlign: 1; vAlign: 1
        anchors { right: parent.right; bottom: parent.bottom; rightMargin: 4; bottomMargin: 4 }
    }

    // Slow scanline sweep, clipped to the window bounds
    Item {
        anchors.fill: parent
        clip: true
        visible: full.cyber

        Rectangle {
            id: sweep
            width: parent.width
            height: 46
            y: -height
            gradient: Gradient {
                orientation: Gradient.Vertical
                GradientStop { position: 0.0; color: "#0000D9FF" }
                GradientStop { position: 0.5; color: "#2200D9FF" }
                GradientStop { position: 1.0; color: "#0000D9FF" }
            }

            NumberAnimation on y {
                running: full.cyber
                loops: Animation.Infinite
                from: -sweep.height
                to: full.height
                duration: 4200
            }
        }
    }

    readonly property var cache: {
        try {
            return JSON.parse(Plasmoid.configuration.cacheJson || "{}")
        } catch (e) {
            return {}
        }
    }
    readonly property var ant: cache.anthropic || {}
    readonly property var oai: cache.openai || {}

    // Relative "Xm ago" text, re-evaluated every tick
    property int tick: 0
    Timer {
        interval: 15000
        running: true
        repeat: true
        onTriggered: full.tick++
    }
    readonly property string relativeUpdated: {
        tick // dependency - forces re-evaluation on timer
        if (!full.cache.last_updated) return "never"
        const then = new Date(full.cache.last_updated)
        const diffSec = Math.floor((new Date() - then) / 1000)
        if (diffSec < 5) return "just now"
        if (diffSec < 60) return diffSec + "s ago"
        const diffMin = Math.floor(diffSec / 60)
        if (diffMin < 60) return diffMin + "m ago"
        return Math.floor(diffMin / 60) + "h ago"
    }

    // Strips CodexBar's verbose reset strings down to just the time/date,
    // e.g. "Resets 4:10pm (Pacific/Honolulu)" -> "4:10pm"
    function shortenReset(text) {
        if (!text) return ""
        return text.replace(/^Resets\s*/i, "").replace(/\s*\([^)]*\)\s*$/, "")
    }

    // Live "in Xh Ym" (or "in Xd Yh" past a day out - the weekly window
    // needs that reach) countdown from an ISO8601 timestamp, falling back
    // to the static human-readable string if no timestamp is available.
    function countdownText(isoString, fallbackText) {
        tick // dependency - re-evaluates on the same 15s timer as relativeUpdated
        if (!isoString) {
            return fallbackText ? shortenReset(fallbackText) : ""
        }
        const diffMs = new Date(isoString) - new Date()
        if (diffMs <= 0) return "resetting now"
        const diffMin = Math.floor(diffMs / 60000)
        const d = Math.floor(diffMin / 1440)
        const h = Math.floor((diffMin % 1440) / 60)
        const m = diffMin % 60
        if (d > 0) return `in ${d}d ${h}h`
        return h > 0 ? `in ${h}h ${m}m` : `in ${m}m`
    }

    component ProviderCard: Column {
        id: card
        property string label
        property color accent
        property string mode
        property real balance
        property real sessionPercent: -1
        property real weeklyPercent: -1
        property string sessionReset: ""
        property string sessionResetAt: ""
        property string weeklyReset: ""
        property string weeklyResetAt: ""
        property real creditsRemaining: -1

        // Show what's LEFT (fuel remaining), not what's been used - fits
        // the "NodeFuel" tank metaphor: a full ring means plenty left.
        readonly property real sessionLeft: sessionPercent >= 0 ? 100 - sessionPercent : -1
        readonly property real weeklyLeft: weeklyPercent >= 0 ? 100 - weeklyPercent : -1

        width: 124
        spacing: 5

        // Soft neon "aura" behind the ring - a single low-opacity circle,
        // no blur/shader needed, still reads as a glow.
        Item {
            width: 68
            height: 68
            anchors.horizontalCenter: parent.horizontalCenter

            Rectangle {
                anchors.centerIn: parent
                width: 62
                height: 62
                radius: 31
                color: card.accent
                opacity: card.mode === "none" ? 0 : 0.10
            }

            StatusRing {
                anchors.centerIn: parent
                ringSize: 52
                accent: card.accent
                mode: card.mode
                percent: card.sessionLeft
                cyberMode: Plasmoid.configuration.cyberMode
                centerText: {
                    if (mode === "api") return "$" + card.balance.toFixed(0)
                    if (mode === "usage" && card.sessionLeft >= 0) return card.sessionLeft.toFixed(0) + "%"
                    if (mode === "subscription") return "⚡"
                    return "–"
                }
            }
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: card.label
            color: card.accent
            font.family: "Monospace"
            font.pixelSize: 13
            font.bold: true
            font.letterSpacing: 1
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: {
                if (card.mode === "api") return "$" + card.balance.toFixed(2)
                if (card.mode === "usage") return "Daily " + card.sessionPercent.toFixed(0) + "% used"
                if (card.mode === "subscription") return "SUBSCRIPTION"
                return "NOT SET UP"
            }
            color: card.mode === "none" ? "#555555" : "#aaaaaa"
            font.family: "Monospace"
            font.pixelSize: 11
        }

        Text {
            visible: card.mode === "usage"
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Weekly " + card.weeklyPercent.toFixed(0) + "% used"
            color: "#888888"
            font.family: "Monospace"
            font.pixelSize: 11
        }

        Text {
            visible: card.mode === "usage" && (card.sessionResetAt.length > 0 || card.sessionReset.length > 0)
            anchors.horizontalCenter: parent.horizontalCenter
            text: "daily resets " + full.countdownText(card.sessionResetAt, card.sessionReset)
            color: "#555555"
            font.family: "Monospace"
            font.pixelSize: 9
        }

        Text {
            visible: card.mode === "usage" && (card.weeklyResetAt.length > 0 || card.weeklyReset.length > 0)
            anchors.horizontalCenter: parent.horizontalCenter
            text: "weekly resets " + full.countdownText(card.weeklyResetAt, card.weeklyReset)
            color: "#555555"
            font.family: "Monospace"
            font.pixelSize: 9
        }

        Text {
            visible: card.mode === "usage" && card.creditsRemaining >= 0
            anchors.horizontalCenter: parent.horizontalCenter
            text: card.creditsRemaining.toFixed(1) + " credits"
            color: "#555555"
            font.family: "Monospace"
            font.pixelSize: 9
        }
    }

    Column {
        id: mainColumn
        anchors {
            fill: parent
            margins: 16
        }
        spacing: 6

        // Header
        Item {
            width: parent.width
            height: 26

            Row {
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
                spacing: 4

                Image {
                    anchors.verticalCenter: parent.verticalCenter
                    source: Qt.resolvedUrl("../icons/nodefuel-logo.png")
                    height: 24
                    width: 24 * (651 / 832) // preserve the trimmed artwork's aspect ratio
                    fillMode: Image.PreserveAspectFit
                    smooth: true
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "NODEFUEL"
                    color: "#FF006E"
                    font.family: "Monospace"
                    font.pixelSize: 12
                    font.bold: true
                    font.letterSpacing: 1
                }
            }

            Row {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                spacing: 6

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.refreshing ? "refreshing..." : full.relativeUpdated
                    color: root.refreshing ? "#00D9FF" : "#555555"
                    font.family: "Monospace"
                    font.pixelSize: 8
                }

                Text {
                    id: refreshIcon
                    anchors.verticalCenter: parent.verticalCenter
                    text: "↻"
                    color: "#00D9FF"
                    font.pixelSize: 13
                    opacity: root.refreshing ? 0.6 : 1.0

                    // Spins continuously for as long as the fetch is
                    // actually in flight (can take ~15-20s on the Claude
                    // side) instead of a fixed one-shot spin that finishes
                    // long before the real data arrives.
                    RotationAnimation {
                        id: spin
                        target: refreshIcon
                        from: 0
                        to: 360
                        duration: 800
                        loops: Animation.Infinite
                        running: root.refreshing
                    }

                    MouseArea {
                        anchors.fill: parent
                        anchors.margins: -6
                        enabled: !root.refreshing
                        onClicked: {
                            root.forceRefresh()
                        }
                    }
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "⚙"
                    color: "#00D9FF"
                    font.pixelSize: 13
                    MouseArea {
                        anchors.fill: parent
                        anchors.margins: -6
                        onClicked: Plasmoid.internalAction("configure").trigger()
                    }
                }
            }
        }

        // Gradient divider instead of a flat line - matches the top stripe
        Rectangle {
            width: parent.width
            height: 1
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0.0; color: "#00D9FF" }
                GradientStop { position: 0.5; color: "#666666" }
                GradientStop { position: 1.0; color: "#FF006E" }
            }
            opacity: 0.4
        }

        // Provider cards side by side
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: 12

            ProviderCard {
                label: "ANTHROPIC"
                accent: "#00D9FF"
                mode: full.ant.mode || "none"
                balance: parseFloat(full.ant.balance) || 0.0
                sessionPercent: full.ant.session_percent !== undefined ? full.ant.session_percent : -1
                weeklyPercent: full.ant.weekly_percent !== undefined ? full.ant.weekly_percent : -1
                sessionReset: full.ant.session_reset || ""
                sessionResetAt: full.ant.session_reset_at || ""
                weeklyReset: full.ant.weekly_reset || ""
                weeklyResetAt: full.ant.weekly_reset_at || ""
                creditsRemaining: full.ant.credits_remaining !== undefined && full.ant.credits_remaining !== null
                    ? full.ant.credits_remaining : -1
            }

            ProviderCard {
                label: "OPENAI"
                accent: "#FF006E"
                mode: full.oai.mode || "none"
                balance: parseFloat(full.oai.balance) || 0.0
                sessionPercent: full.oai.session_percent !== undefined ? full.oai.session_percent : -1
                weeklyPercent: full.oai.weekly_percent !== undefined ? full.oai.weekly_percent : -1
                sessionReset: full.oai.session_reset || ""
                sessionResetAt: full.oai.session_reset_at || ""
                weeklyReset: full.oai.weekly_reset || ""
                weeklyResetAt: full.oai.weekly_reset_at || ""
                creditsRemaining: full.oai.credits_remaining !== undefined && full.oai.credits_remaining !== null
                    ? full.oai.credits_remaining : -1
            }
        }
    }
}
