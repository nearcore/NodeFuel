import QtQuick
import org.kde.plasma.plasmoid

Item {
    id: compact

    // A panel gives this item its actual thickness at runtime - size the
    // logo to fit that instead of a fixed pixel value.
    readonly property real availableSize: compact.height > 0 ? compact.height : 32
    readonly property real logoSize: Math.max(16, Math.min(48, availableSize - 4))

    // Fixed fallback hint - must NOT derive from compact.height itself,
    // or Plasma's own height-from-implicitHeight resolution creates a
    // binding loop. logoSize below still reads the real runtime height
    // once the panel assigns one.
    implicitWidth: 32
    implicitHeight: 32

    Image {
        anchors.centerIn: parent
        source: Qt.resolvedUrl("../icons/nodefuel-logo.png")
        height: compact.logoSize
        width: compact.logoSize * (651 / 832) // preserve the trimmed artwork's aspect ratio
        fillMode: Image.PreserveAspectFit
        smooth: true
    }

    MouseArea {
        anchors.fill: parent
        onClicked: root.expanded = !root.expanded
    }
}
