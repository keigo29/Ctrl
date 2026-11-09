import AppKit

/// 操作中だけカーソルの周りに丸いハイライトを出す（クリックは素通り）
final class HighlightWindow {
    private let window: NSWindow
    private let ring: RingView
    private var hideWork: DispatchWorkItem?

    init() {
        let size: CGFloat = 56
        let frame = NSRect(x: 0, y: 0, width: size, height: size)
        window = NSWindow(contentRect: frame, styleMask: .borderless, backing: .buffered, defer: false)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = false
        window.ignoresMouseEvents = true
        window.level = .screenSaver
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        ring = RingView(frame: frame)
        window.contentView = ring
    }

    /// - Parameter point: CG座標（メインディスプレイ左上が原点）
    func show(at point: CGPoint, dragging: Bool) {
        guard UserDefaults.standard.bool(forKey: "highlight") else {
            window.orderOut(nil)
            return
        }
        // CG座標（左上原点）→ Cocoa座標（左下原点）
        let primaryHeight = NSScreen.screens.first?.frame.height ?? 0
        let s = window.frame.size
        window.setFrameOrigin(NSPoint(x: point.x - s.width / 2, y: primaryHeight - point.y - s.height / 2))
        ring.dragging = dragging
        window.alphaValue = 1
        window.orderFrontRegardless()

        hideWork?.cancel()
        guard !dragging else { return } // ドラッグ中は出しっぱなし
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            NSAnimationContext.runAnimationGroup({ ctx in
                ctx.duration = 0.3
                self.window.animator().alphaValue = 0
            }, completionHandler: {
                if self.window.alphaValue == 0 { self.window.orderOut(nil) }
            })
        }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }
}

final class RingView: NSView {
    var dragging = false { didSet { needsDisplay = true } }

    override func draw(_ dirtyRect: NSRect) {
        let rect = bounds.insetBy(dx: 4, dy: 4)
        let path = NSBezierPath(ovalIn: rect)
        (dragging ? NSColor.white.withAlphaComponent(0.45) : NSColor.white.withAlphaComponent(0.16)).setFill()
        path.fill()
        NSColor.black.withAlphaComponent(0.55).setStroke()
        path.lineWidth = 5
        path.stroke()
        NSColor.white.setStroke()
        path.lineWidth = 2.5
        path.stroke()
    }
}
