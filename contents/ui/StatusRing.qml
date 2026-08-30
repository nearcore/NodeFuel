import QtQuick
import QtQuick.Shapes

// Circular status indicator.
// - mode "usage": draws a REAL arc from `percent` (0-100), from CodexBar CLI
// - mode "api" / "subscription": decorative ~88% sweep (no % data available)
// - mode "none": dim track only
//
// Optional "Cyber Mode" (cyberMode: true) renders the orb with a true
// RadialGradient (QtQuick.Shapes - a native Qt6 module, not a heavy compat
// layer) with an off-center highlight and dark limb, so it reads as a lit
// sphere rather than a flat tinted disc. Jagged lightning (Canvas-drawn
// zigzag paths, glow + bright core) crawls across it, dimmed near the rim
// and brightened near the light source to follow that same curvature.
Item {
    id: ring

    property color accent: "#00D9FF"
    property string mode: "none" // "api" | "subscription" | "usage" | "none"
    property real percent: -1 // 0-100, only meaningful when mode === "usage"
    property string centerText: ""
    property real ringSize: 48
    property bool cyberMode: false

    readonly property bool active: cyberMode && mode !== "none"
    // Light source direction used consistently by the sphere shading, the
    // specular highlight, and the lightning's brightness bias below.
    // Slowly sweeping instead of fixed - since the "sphere" is a 2D gradient
    // illusion (no real mesh), orbiting the light source around it reads as
    // the sphere itself slowly turning. Everything shading-related (the
    // gradient's focal point, the specular highlight, the lightning's
    // brightness bias) already reads from this one property, so animating
    // it alone spins the whole illusion in sync.
    property real lightAngle: 225 * Math.PI / 180 // upper-left
    NumberAnimation on lightAngle {
        running: ring.active
        loops: Animation.Infinite
        from: 225 * Math.PI / 180
        to: 225 * Math.PI / 180 + 2 * Math.PI
        duration: 13000 // one full turn every 13s - "very slowly"
    }

    width: ringSize
    height: ringSize

    // True radial-gradient sphere fill: bright off-center highlight fading
    // through the accent color to a near-black limb at the rim.
    Shape {
        id: sphere
        visible: ring.active
        anchors.centerIn: parent
        width: ring.ringSize - 8
        height: width
        readonly property real r: width / 2

        ShapePath {
            strokeColor: "transparent"
            fillGradient: RadialGradient {
                centerX: sphere.r
                centerY: sphere.r
                centerRadius: sphere.r
                focalX: sphere.r + Math.cos(ring.lightAngle) * sphere.r * 0.45
                focalY: sphere.r + Math.sin(ring.lightAngle) * sphere.r * 0.45
                focalRadius: 0
                GradientStop { position: 0.0; color: Qt.lighter(ring.accent, 2.1) }
                GradientStop { position: 0.35; color: ring.accent }
                GradientStop { position: 0.75; color: Qt.darker(ring.accent, 3.5) }
                GradientStop { position: 1.0; color: "#020204" }
            }
            startX: sphere.r * 2; startY: sphere.r
            PathAngleArc {
                centerX: sphere.r; centerY: sphere.r
                radiusX: sphere.r; radiusY: sphere.r
                startAngle: 0; sweepAngle: 360
            }
        }
    }

    // Specular glint - the classic "glossy ball" highlight, offset toward
    // the light source. A linear gradient on a circular patch reads as a
    // flat bright smear, so this fakes a soft radial falloff cheaply with
    // 3 nested, shrinking, increasingly-opaque circles instead.
    Item {
        visible: ring.active
        readonly property real gx: parent.width / 2 + Math.cos(ring.lightAngle) * (ring.ringSize * 0.22)
        readonly property real gy: parent.height / 2 + Math.sin(ring.lightAngle) * (ring.ringSize * 0.22)

        Repeater {
            model: [
                { size: 0.20, alpha: 0.10 },
                { size: 0.13, alpha: 0.18 },
                { size: 0.07, alpha: 0.30 }
            ]
            delegate: Rectangle {
                required property var modelData
                width: ring.ringSize * modelData.size
                height: width
                radius: width / 2
                color: "#ffffff"
                opacity: modelData.alpha
                x: parent.gx - width / 2
                y: parent.gy - height / 2
            }
        }
    }

    // Jagged lightning that actually travels: each surge picks a start and
    // end point, then progressively reveals its zigzag path from one toward
    // the other over ~150-400ms (not an instant flicker), holds briefly at
    // full length, then fades. Up to 4 independent surge "slots" run this
    // lifecycle on their own random timing, so anywhere from 1 to 4 can be
    // live at once. Brightness follows the same curvature the sphere's
    // shading uses, so it dims near the rim and brightens toward the light.
    Canvas {
        id: lightning
        anchors.fill: parent
        visible: ring.active
        renderTarget: Canvas.FramebufferObject

        property real cx: width / 2
        property real cy: height / 2
        property real r: Math.min(width, height) / 2 - 4
        property var surges: []

        // 0 (dim rim / away from light) - 1 (bright crown, facing the light)
        function surfaceBrightness(px, py) {
            const dx = px - cx, dy = py - cy
            const d = Math.sqrt(dx * dx + dy * dy) / r // 0 center -> 1 edge
            const limb = Math.sqrt(Math.max(0, 1 - d * d * 0.85))
            const angleToPoint = Math.atan2(dy, dx)
            const lightDot = Math.cos(angleToPoint - ring.lightAngle) // 1 = facing light, -1 = opposite
            const lightBias = 0.55 + 0.45 * Math.max(0, lightDot)
            return limb * lightBias
        }

        // Keeps every point within an outer band (minR-maxR from center) -
        // the percentage text needs the middle of the sphere clear, and
        // real static/plasma discharge visibly hugs the surface anyway
        // rather than arcing through the core.
        function clampAnnulus(x, y, minR, maxR) {
            const dx = x - cx, dy = y - cy
            const d = Math.sqrt(dx * dx + dy * dy) || 1
            const clamped = Math.max(minR, Math.min(maxR, d))
            return { x: cx + dx / d * clamped, y: cy + dy / d * clamped }
        }

        function makePath() {
            const minR = r * 0.5
            const maxR = r * 0.92
            const a1 = Math.random() * Math.PI * 2
            const a2 = a1 + Math.PI * (0.4 + Math.random() * 0.9) // shorter hops, not always cross-sphere
            const x0 = cx + r * Math.cos(a1) * 0.85
            const y0 = cy + r * Math.sin(a1) * 0.85
            const x1 = cx + r * Math.cos(a2) * 0.85
            const y1 = cy + r * Math.sin(a2) * 0.85

            const segments = 3 + Math.floor(Math.random() * 2)
            const points = [{ x: x0, y: y0 }]
            const dx = x1 - x0, dy = y1 - y0
            const len = Math.sqrt(dx * dx + dy * dy) || 1
            const nx = -dy / len, ny = dx / len

            for (let i = 1; i < segments; i++) {
                const t = i / segments
                const mx = x0 + dx * t
                const my = y0 + dy * t
                const offset = (Math.random() - 0.5) * r * 0.3
                const p = clampAnnulus(mx + nx * offset, my + ny * offset, minR, maxR)
                points.push(p)
            }
            points.push({ x: x1, y: y1 })
            return points
        }

        function spawn(slot, now) {
            slot.active = true
            slot.points = makePath()
            slot.startTime = now
            slot.growDuration = 150 + Math.random() * 250
            slot.fadeDuration = 150 + Math.random() * 200
        }

        function tick() {
            const now = Date.now()
            for (const slot of surges) {
                if (!slot.active) {
                    if (now >= slot.nextSpawnAt) spawn(slot, now)
                } else if (now - slot.startTime > slot.growDuration + slot.fadeDuration) {
                    slot.active = false
                    slot.nextSpawnAt = now + 120 + Math.random() * 500
                }
            }
            // never let it go fully dark - force one on if all slots lapsed
            if (surges.length > 0 && surges.every(s => !s.active)) spawn(surges[0], now)
            lightning.requestPaint()
        }

        function drawSurge(slot, now) {
            const elapsed = now - slot.startTime
            const growT = Math.min(1, elapsed / slot.growDuration)
            const totalSegs = slot.points.length - 1
            const revealSegs = growT * totalSegs
            const fullIdx = Math.floor(revealSegs)
            const frac = revealSegs - fullIdx

            const drawPoints = slot.points.slice(0, fullIdx + 1)
            if (fullIdx < totalSegs) {
                const p0 = slot.points[fullIdx], p1 = slot.points[fullIdx + 1]
                drawPoints.push({ x: p0.x + (p1.x - p0.x) * frac, y: p0.y + (p1.y - p0.y) * frac })
            }
            if (drawPoints.length < 2) return

            let fadeAlpha = 1
            if (elapsed > slot.growDuration) {
                fadeAlpha = 1 - Math.min(1, (elapsed - slot.growDuration) / slot.fadeDuration)
            }
            if (fadeAlpha <= 0) return

            const ctx = getContext("2d")
            const midIdx = Math.floor(drawPoints.length / 2)
            const brightness = surfaceBrightness(drawPoints[midIdx].x, drawPoints[midIdx].y) * fadeAlpha

            // Fresh random jitter every redraw (this function runs each
            // ~40ms tick) - the path itself doesn't change, but re-rolling
            // a small offset each frame makes it visibly tremble/jump
            // instead of gliding perfectly smoothly, like real static does.
            const jitter = r * 0.05
            const jittered = drawPoints.map(p => ({
                x: p.x + (Math.random() - 0.5) * jitter,
                y: p.y + (Math.random() - 0.5) * jitter
            }))

            ctx.beginPath()
            ctx.moveTo(jittered[0].x, jittered[0].y)
            for (let i = 1; i < jittered.length; i++) ctx.lineTo(jittered[i].x, jittered[i].y)
            ctx.lineJoin = "round"

            // Line widths scale with ring size - a fixed pixel width looks
            // like a thick scribble on a ~50px ring even though it reads
            // fine on a larger one.
            const glowWidth = Math.max(1.1, r * 0.09)
            const coreWidth = Math.max(0.5, r * 0.032)

            // Glow pass (wide, translucent, accent color)
            ctx.strokeStyle = ring.accent
            ctx.lineWidth = glowWidth
            ctx.globalAlpha = 0.45 * brightness
            ctx.stroke()

            // Bright core pass (thin, near-white) - traces just ahead of the
            // glow so the "head" of the surge reads brighter as it travels
            ctx.strokeStyle = "#ffffff"
            ctx.lineWidth = coreWidth
            ctx.globalAlpha = 0.3 + 0.65 * brightness
            ctx.stroke()
            ctx.globalAlpha = 1.0
        }

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            if (!ring.active) return

            ctx.save()
            ctx.beginPath()
            ctx.arc(cx, cy, r, 0, 2 * Math.PI)
            ctx.clip()

            const now = Date.now()
            for (const slot of surges) if (slot.active) drawSurge(slot, now)

            ctx.restore()
        }

        Timer {
            running: ring.active
            repeat: true
            interval: 40 // ~25fps - smooth travel without per-frame overhead
            onTriggered: lightning.tick()
        }

        Component.onCompleted: {
            // 4 independent slots, staggered so they don't all fire at once -
            // however many happen to overlap at a given moment (1-4) is
            // whatever the random timing produces.
            const now = Date.now()
            const slots = []
            for (let i = 0; i < 4; i++) {
                slots.push({ active: false, points: [], startTime: 0, growDuration: 0, fadeDuration: 0, nextSpawnAt: now + i * 120 + Math.random() * 200 })
            }
            surges = slots
            if (ring.active) requestPaint()
        }
    }

    Canvas {
        id: canvas
        anchors.fill: parent

        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()

            const cx = width / 2
            const cy = height / 2
            const r = Math.min(width, height) / 2 - 3
            const start = -Math.PI / 2

            // Track (always visible, dim)
            ctx.beginPath()
            ctx.arc(cx, cy, r, 0, 2 * Math.PI)
            ctx.lineWidth = 3
            ctx.strokeStyle = "#2a2a2a"
            ctx.stroke()

            if (ring.mode === "none") return

            let sweep
            if (ring.mode === "usage" && ring.percent >= 0) {
                // Real percentage - minimum visible sliver at 0% so the ring
                // still reads as "connected" rather than empty
                sweep = Math.max(0.02, Math.min(1, ring.percent / 100)) * 2 * Math.PI
            } else {
                // No % data (api/subscription) - decorative sweep
                sweep = 2 * Math.PI * 0.88
            }

            ctx.beginPath()
            ctx.arc(cx, cy, r, start, start + sweep)
            ctx.lineWidth = 3
            ctx.strokeStyle = ring.accent
            ctx.lineCap = "round"
            ctx.stroke()
        }
    }

    onModeChanged: canvas.requestPaint()
    onAccentChanged: canvas.requestPaint()
    onPercentChanged: canvas.requestPaint()
    Component.onCompleted: canvas.requestPaint()

    // Cheap 4-direction outline so the percentage stays legible over the
    // lightning in Cyber Mode, without pulling in a real drop-shadow effect
    Text {
        visible: ring.active
        anchors.centerIn: parent
        anchors.horizontalCenterOffset: 1
        anchors.verticalCenterOffset: 1
        text: ring.centerText
        color: "#000000"
        opacity: 0.7
        font.family: "Monospace"
        font.pixelSize: ring.ringSize * 0.24
        font.bold: true
    }

    Text {
        anchors.centerIn: parent
        text: ring.centerText
        color: ring.mode === "none" ? "#555555" : ring.accent
        font.family: "Monospace"
        font.pixelSize: ring.ringSize * 0.24
        font.bold: true
    }
}
