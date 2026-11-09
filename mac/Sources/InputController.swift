import AppKit
import CoreGraphics

/// PWAから届いたコマンドを、本物のマウス・キーボード操作としてMacに送る。
final class InputController {
    // コマンド（PWA・Android版と共通）
    private enum Op: UInt8 {
        case move = 0x01, tap = 0x02, longPress = 0x03, scroll = 0x04, global = 0x05
        case textChunk = 0x06, textCommit = 0x07, backspace = 0x08, enter = 0x09
        case dragStart = 0x0A, dragEnd = 0x0B, center = 0x0C
    }

    /// カーソル位置が変わったとき（ハイライト表示用）。座標はCG座標（左上原点）
    var onCursor: ((CGPoint, Bool) -> Void)?

    private let moveScale: CGFloat = 1.0

    // BLEは数十ms間隔でまとめて届くので、届いた移動量を画面の更新ごと（120Hz）に少しずつ反映して滑らかにする
    private var pendX: CGFloat = 0, pendY: CGFloat = 0
    private var smoothTimer: Timer?
    private let smoothRate: CGFloat = 0.6    // 1コマで残りの何割を動かすか（大きいほど追従が速く、ラグが少ない）

    private let source = CGEventSource(stateID: .hidSystemState)
    private var dragging = false
    private var textBuf = Data()
    private var lastClickTime: TimeInterval = 0
    private var lastClickPos = CGPoint.zero
    private var clickCount = 0

    func handle(_ data: Data) {
        let b = [UInt8](data)
        guard let first = b.first, let op = Op(rawValue: first) else { return }
        if op != .move { flushMove() }
        switch op {
        case .move:
            if b.count >= 5 { move(dx: CGFloat(s16(b, 1)) * moveScale, dy: CGFloat(s16(b, 3)) * moveScale) }
        case .tap:
            click(.left)
        case .longPress:
            click(.right) // Macでは長押し＝右クリック
        case .scroll:
            if b.count >= 5 { scroll(dx: Int32(s16(b, 1)), dy: Int32(s16(b, 3))) }
        case .global:
            if b.count >= 2 { global(Int(b[1])) }
        case .textChunk:
            textBuf.append(contentsOf: b.dropFirst())
        case .textCommit:
            let s = String(decoding: textBuf, as: UTF8.self)
            textBuf.removeAll()
            if !s.isEmpty { insertText(s) }
        case .backspace:
            key(51)
        case .enter:
            key(36)
        case .dragStart:
            dragStart()
        case .dragEnd:
            dragEnd()
        case .center:
            center()
        }
    }

    private func s16(_ b: [UInt8], _ i: Int) -> Int16 {
        Int16(bitPattern: UInt16(b[i]) | (UInt16(b[i + 1]) << 8))
    }

    // MARK: - カーソル

    // 送ったイベントがすぐ反映されるとは限らないので、直前に自分で動かした位置を覚えておく。
    // 少し時間が空いたら（本物のマウスで動かした可能性があるので）実際の位置を読み直す。
    private var lastPosted: CGPoint?
    private var lastPostTime: TimeInterval = 0
    private var location: CGPoint {
        if let p = lastPosted, ProcessInfo.processInfo.systemUptime - lastPostTime < 0.15 { return p }
        return CGEvent(source: nil)?.location ?? .zero
    }

    private func displayBounds() -> [CGRect] {
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetActiveDisplayList(count, &ids, &count)
        return ids.map { CGDisplayBounds($0) }
    }

    /// 画面の外やディスプレイの隙間に出ないようにする
    private func clamp(_ p: CGPoint, from old: CGPoint) -> CGPoint {
        let rects = displayBounds()
        if rects.contains(where: { $0.contains(p) }) { return p }
        let r = rects.first(where: { $0.contains(old) }) ?? CGDisplayBounds(CGMainDisplayID())
        return CGPoint(x: min(max(p.x, r.minX), r.maxX - 1), y: min(max(p.y, r.minY), r.maxY - 1))
    }

    private func postMouse(_ type: CGEventType, _ p: CGPoint, _ button: CGMouseButton, clickState: Int64 = 1) {
        let e = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: p, mouseButton: button)
        e?.setIntegerValueField(.mouseEventClickState, value: clickState)
        e?.post(tap: .cghidEventTap)
        lastPosted = p
        lastPostTime = ProcessInfo.processInfo.systemUptime
    }

    private func move(dx: CGFloat, dy: CGFloat) {
        pendX += dx; pendY += dy
        guard smoothTimer == nil else { return }
        let t = Timer(timeInterval: 1.0 / 120, repeats: true) { [weak self] _ in self?.smoothTick() }
        RunLoop.main.add(t, forMode: .common)
        smoothTimer = t
        smoothTick()
    }

    private func smoothTick() {
        var sx = pendX * smoothRate, sy = pendY * smoothRate
        if abs(pendX) + abs(pendY) < 1.5 { sx = pendX; sy = pendY }   // 残りわずかなら一度に
        pendX -= sx; pendY -= sy
        if sx != 0 || sy != 0 { applyMove(dx: sx, dy: sy) }
        if pendX == 0 && pendY == 0 { smoothTimer?.invalidate(); smoothTimer = nil }
    }

    /// クリックなどの前に、残っている移動を全部反映する
    private func flushMove() {
        smoothTimer?.invalidate(); smoothTimer = nil
        let dx = pendX, dy = pendY
        pendX = 0; pendY = 0
        if dx != 0 || dy != 0 { applyMove(dx: dx, dy: dy) }
    }

    private func applyMove(dx: CGFloat, dy: CGFloat) {
        let old = location
        let p = clamp(CGPoint(x: old.x + dx, y: old.y + dy), from: old)
        postMouse(dragging ? .leftMouseDragged : .mouseMoved, p, .left)
        onCursor?(p, dragging)
    }

    private func click(_ button: CGMouseButton) {
        let p = location
        let now = ProcessInfo.processInfo.systemUptime
        // 素早く2回タップ → ダブルクリックとして扱う
        if button == .left, now - lastClickTime < NSEvent.doubleClickInterval,
           hypot(p.x - lastClickPos.x, p.y - lastClickPos.y) < 8 {
            clickCount += 1
        } else {
            clickCount = 1
        }
        lastClickTime = now
        lastClickPos = p
        let (down, up): (CGEventType, CGEventType) =
            button == .left ? (.leftMouseDown, .leftMouseUp) : (.rightMouseDown, .rightMouseUp)
        postMouse(down, p, button, clickState: Int64(clickCount))
        postMouse(up, p, button, clickState: Int64(clickCount))
        onCursor?(p, dragging)
    }

    private func scroll(dx: Int32, dy: Int32) {
        // 指を下に動かす → 内容が下に動く（ナチュラルスクロール）
        let e = CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
                        wheel1: dy, wheel2: dx, wheel3: 0)
        e?.post(tap: .cghidEventTap)
        onCursor?(location, dragging)
    }

    private func dragStart() {
        guard !dragging else { return }
        dragging = true
        let p = location
        postMouse(.leftMouseDown, p, .left)
        onCursor?(p, true)
    }

    private func dragEnd() {
        guard dragging else { return }
        dragging = false
        let p = location
        postMouse(.leftMouseUp, p, .left)
        onCursor?(p, false)
    }

    private func center() {
        let r = CGDisplayBounds(CGMainDisplayID())
        let p = CGPoint(x: r.midX, y: r.midY)
        pendX = 0; pendY = 0
        _ = CGWarpMouseCursorPosition(p)
        postMouse(dragging ? .leftMouseDragged : .mouseMoved, p, .left)
        onCursor?(p, dragging)
    }

    // MARK: - キーボード

    private func key(_ code: CGKeyCode, _ flags: CGEventFlags = []) {
        let down = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: true)
        let up = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: false)
        down?.flags = flags
        up?.flags = flags
        down?.post(tap: .cghidEventTap)
        up?.post(tap: .cghidEventTap)
    }

    /// ⌘Tab：⌘を押したままTabを押して離す → 直前のアプリに切り替え
    private func appSwitch() {
        let cmdDown = CGEvent(keyboardEventSource: source, virtualKey: 55, keyDown: true)
        cmdDown?.flags = .maskCommand
        cmdDown?.post(tap: .cghidEventTap)
        key(48, .maskCommand)
        let cmdUp = CGEvent(keyboardEventSource: source, virtualKey: 55, keyDown: false)
        cmdUp?.post(tap: .cghidEventTap)
    }

    /// PWAのボタン番号 → Macの操作
    private func global(_ id: Int) {
        switch id {
        case 1: key(33, .maskCommand)                       // 戻る ⌘[
        case 2: runMissionControl(["1"])                    // デスクトップを表示
        case 3: runMissionControl([])                       // Mission Control
        case 4: key(49, .maskCommand)                       // Spotlight ⌘Space
        case 5: appSwitch()                                 // アプリ切替 ⌘Tab
        case 8: key(12, [.maskCommand, .maskControl])       // 画面ロック ⌃⌘Q
        case 9: key(20, [.maskCommand, .maskShift])         // スクリーンショット ⌘⇧3
        default: break
        }
    }

    private func runMissionControl(_ args: [String]) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/System/Applications/Mission Control.app/Contents/MacOS/Mission Control")
        p.arguments = args
        try? p.run()
    }

    // MARK: - 文字入力

    private func insertText(_ s: String) {
        if UserDefaults.standard.bool(forKey: "pasteText") {
            paste(s)
        } else {
            typeText(s)
        }
    }

    /// クリップボード経由で貼り付け（日本語入力がONでも確実）。元のクリップボードは戻す。
    private func paste(_ s: String) {
        let pb = NSPasteboard.general
        let saved: [NSPasteboardItem] = (pb.pasteboardItems ?? []).map { item in
            let copy = NSPasteboardItem()
            for t in item.types {
                if let d = item.data(forType: t) { copy.setData(d, forType: t) }
            }
            return copy
        }
        pb.clearContents()
        pb.setString(s, forType: .string)
        key(9, .maskCommand) // ⌘V
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            pb.clearContents()
            if !saved.isEmpty { pb.writeObjects(saved) }
        }
    }

    /// キー入力として直接打ち込む（クリップボードを使わない）
    private func typeText(_ s: String) {
        let lines = s.split(separator: "\n", omittingEmptySubsequences: false)
        for (i, line) in lines.enumerated() {
            if i > 0 { key(36) }
            let units = Array(String(line).utf16)
            var start = 0
            while start < units.count {
                let chunk = Array(units[start..<min(start + 20, units.count)])
                for isDown in [true, false] {
                    let e = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: isDown)
                    chunk.withUnsafeBufferPointer { buf in
                        e?.keyboardSetUnicodeString(stringLength: buf.count, unicodeString: buf.baseAddress)
                    }
                    e?.post(tap: .cghidEventTap)
                }
                start += 20
            }
        }
    }
}
