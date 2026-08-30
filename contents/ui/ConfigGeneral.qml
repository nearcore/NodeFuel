import QtQuick
import org.kde.plasma.plasmoid
import org.kde.plasma.plasma5support as P5Support

Item {
    id: page

    implicitWidth: 460
    implicitHeight: 460

    readonly property color neonCyan: "#00D9FF"
    readonly property color neonPink: "#FF006E"
    readonly property color bg: "#0a0a0a"
    readonly property color panel: "#151515"
    readonly property color dim: "#666666"

    // Local form state - loaded from ~/.config/nodefuel/config.json on open,
    // written back (and the backend re-fetched) when SAVE is clicked.
    property string anthropicKey: ""
    property string openaiKey: ""
    property bool anthropicSub: false
    property bool openaiSub: false
    property bool anthropicUseCodexbar: true
    property bool openaiUseCodexbar: true
    property int refreshInterval: 60
    property string statusMessage: "Loading current settings..."
    property color statusColor: dim

    P5Support.DataSource {
        id: exec
        engine: "executable"
        connectedSources: []
        function run(cmd) { connectSource(cmd) }
        onNewData: (sourceName, data) => {
            const stdout = (data["stdout"] || "").trim()
            if (sourceName.indexOf("cat ") === 0 && stdout.length > 0) {
                try {
                    const cfg = JSON.parse(stdout)
                    page.anthropicKey = cfg.anthropic_api_key || ""
                    page.openaiKey = cfg.openai_api_key || ""
                    page.anthropicSub = !!cfg.anthropic_subscription
                    page.openaiSub = !!cfg.openai_subscription
                    page.anthropicUseCodexbar = cfg.anthropic_use_codexbar !== false
                    page.openaiUseCodexbar = cfg.openai_use_codexbar !== false
                    page.refreshInterval = cfg.refresh_interval || 60
                    page.statusMessage = "Loaded current settings"
                    page.statusColor = page.dim
                } catch (e) {
                    page.statusMessage = "No existing config found - using defaults"
                    page.statusColor = page.dim
                }
            } else if (sourceName.indexOf("--set-config-b64") >= 0) {
                page.statusMessage = "✓ Saved and refreshed"
                page.statusColor = "#00ff88"
            }
            disconnectSource(sourceName)
        }
    }

    Component.onCompleted: exec.run("cat ~/.config/nodefuel/config.json")

    function save() {
        const payload = {
            anthropic_api_key: page.anthropicKey,
            openai_api_key: page.openaiKey,
            anthropic_subscription: page.anthropicSub,
            openai_subscription: page.openaiSub,
            anthropic_use_codexbar: page.anthropicUseCodexbar,
            openai_use_codexbar: page.openaiUseCodexbar,
            refresh_interval: page.refreshInterval
        }
        const b64 = Qt.btoa(JSON.stringify(payload))
        page.statusMessage = "Saving..."
        page.statusColor = page.dim
        exec.run("~/.local/bin/nodefuel-service --set-config-b64 " + b64)
    }

    // ---- reusable pieces ----

    component FieldLabel: Text {
        color: page.dim
        font.family: "Monospace"
        font.pixelSize: 11
        font.bold: true
    }

    component NeonTextField: Rectangle {
        id: fieldRoot
        property alias text: input.text
        property string placeholder: ""
        property color accent: page.neonCyan
        property bool masked: true

        height: 30
        color: page.panel
        border.color: accent
        border.width: 1
        radius: 3

        TextInput {
            id: input
            anchors {
                fill: parent
                leftMargin: 8
                rightMargin: 8
            }
            verticalAlignment: TextInput.AlignVCenter
            color: "#dddddd"
            font.family: "Monospace"
            font.pixelSize: 11
            clip: true
            echoMode: fieldRoot.masked ? TextInput.Password : TextInput.Normal

            Text {
                visible: input.text.length === 0
                text: fieldRoot.placeholder
                color: "#444444"
                font: input.font
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }

    component NeonToggle: Row {
        property bool checked: false
        property string label: ""
        property color accent: page.neonCyan
        signal toggled(bool value)

        spacing: 8

        Rectangle {
            width: 34
            height: 18
            radius: 9
            anchors.verticalCenter: parent.verticalCenter
            color: parent.checked ? parent.accent : "#333333"

            Rectangle {
                width: 14
                height: 14
                radius: 7
                color: "#0a0a0a"
                anchors.verticalCenter: parent.verticalCenter
                x: parent.parent.checked ? parent.width - width - 2 : 2
                Behavior on x { NumberAnimation { duration: 120 } }
            }

            MouseArea {
                anchors.fill: parent
                onClicked: parent.parent.toggled(!parent.parent.checked)
            }
        }

        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: parent.label
            color: "#aaaaaa"
            font.family: "Monospace"
            font.pixelSize: 10
        }
    }

    // ---- layout ----

    Rectangle {
        anchors.fill: parent
        color: page.bg

        Column {
            anchors {
                fill: parent
                margins: 18
            }
            spacing: 14

            Text {
                text: "⚡ NODEFUEL SETTINGS"
                color: page.neonPink
                font.family: "Monospace"
                font.pixelSize: 14
                font.bold: true
                font.letterSpacing: 1
            }

            Rectangle { width: parent.width; height: 1; color: page.neonCyan; opacity: 0.25 }

            // Display section - purely cosmetic, so it writes directly to
            // Plasmoid.configuration and applies instantly (no SAVE needed,
            // no backend involvement).
            Column {
                width: parent.width
                spacing: 6

                Text {
                    text: "DISPLAY"
                    color: "#999999"
                    font.family: "Monospace"
                    font.pixelSize: 11
                    font.bold: true
                }

                NeonToggle {
                    checked: Plasmoid.configuration.cyberMode
                    accent: page.neonPink
                    label: "⚡ Cyber Mode - glossy orbs with orbiting sparks"
                    onToggled: (v) => Plasmoid.configuration.cyberMode = v
                }
            }

            Rectangle { width: parent.width; height: 1; color: "#222222" }

            // Anthropic section
            Column {
                width: parent.width
                spacing: 6

                Text {
                    text: "ANTHROPIC"
                    color: page.neonCyan
                    font.family: "Monospace"
                    font.pixelSize: 11
                    font.bold: true
                }

                FieldLabel { text: "API key (leave blank if you only have a subscription)" }
                NeonTextField {
                    width: parent.width
                    accent: page.neonCyan
                    placeholder: "sk-ant-..."
                    text: page.anthropicKey
                    onTextChanged: page.anthropicKey = text
                }

                NeonToggle {
                    checked: page.anthropicSub
                    accent: page.neonCyan
                    label: "I have a Claude subscription (Pro/Max)"
                    onToggled: (v) => page.anthropicSub = v
                }

                NeonToggle {
                    checked: page.anthropicUseCodexbar
                    accent: page.neonCyan
                    label: "Show real usage % via CodexBar CLI"
                    onToggled: (v) => page.anthropicUseCodexbar = v
                }
            }

            Rectangle { width: parent.width; height: 1; color: "#222222" }

            // OpenAI section
            Column {
                width: parent.width
                spacing: 6

                Text {
                    text: "OPENAI / CODEX"
                    color: page.neonPink
                    font.family: "Monospace"
                    font.pixelSize: 11
                    font.bold: true
                }

                FieldLabel { text: "API key (leave blank if you only have a subscription)" }
                NeonTextField {
                    width: parent.width
                    accent: page.neonPink
                    placeholder: "sk-..."
                    text: page.openaiKey
                    onTextChanged: page.openaiKey = text
                }

                NeonToggle {
                    checked: page.openaiSub
                    accent: page.neonPink
                    label: "I have a ChatGPT/Codex subscription (Plus)"
                    onToggled: (v) => page.openaiSub = v
                }

                NeonToggle {
                    checked: page.openaiUseCodexbar
                    accent: page.neonPink
                    label: "Show real usage % via CodexBar CLI"
                    onToggled: (v) => page.openaiUseCodexbar = v
                }
            }

            Rectangle { width: parent.width; height: 1; color: "#222222" }

            Row {
                spacing: 10
                FieldLabel {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "Refresh interval (seconds):"
                }
                NeonTextField {
                    width: 70
                    height: 26
                    masked: false
                    text: String(page.refreshInterval)
                    onTextChanged: {
                        const n = parseInt(text)
                        if (!isNaN(n)) page.refreshInterval = n
                    }
                }
            }

            Item { height: 4; width: 1 }

            Row {
                spacing: 12

                Rectangle {
                    width: 120
                    height: 32
                    color: "#1a1a1a"
                    border.color: "#00ff88"
                    border.width: 1
                    radius: 3

                    Text {
                        anchors.centerIn: parent
                        text: "💾 SAVE"
                        color: "#00ff88"
                        font.family: "Monospace"
                        font.pixelSize: 11
                        font.bold: true
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: page.save()
                    }
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: page.statusMessage
                    color: page.statusColor
                    font.family: "Monospace"
                    font.pixelSize: 9
                }
            }

            FieldLabel {
                text: "Note: \"Real usage %\" requires the codexbar CLI to be installed and logged in.\nWithout it, subscriptions fall back to a simple \"active\" badge."
                wrapMode: Text.WordWrap
                width: parent.width
            }
        }
    }
}
