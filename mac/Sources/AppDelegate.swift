import AppKit
import ApplicationServices

/// メニューバーのアイコンと設定メニュー
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    private let input = InputController()
    private var ble: BlePeripheral!
    private var highlight: HighlightWindow!
    private var statusText = "起動中…"

    func applicationDidFinishLaunching(_ notification: Notification) {
        UserDefaults.standard.register(defaults: ["highlight": true, "pasteText": true])

        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let button = statusItem.button {
            if let img = NSImage(systemSymbolName: "cursorarrow.motionlines", accessibilityDescription: "PadRemote") {
                img.isTemplate = true
                button.image = img
            } else {
                button.title = "PR"
            }
        }
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu

        highlight = HighlightWindow()
        input.onCursor = { [weak self] point, dragging in
            self?.highlight.show(at: point, dragging: dragging)
        }
        ble = BlePeripheral(
            onPacket: { [weak self] data in self?.input.handle(data) },
            onState: { [weak self] text in self?.statusText = text }
        )

        // マウス・キーボード操作にはアクセシビリティの許可が必要
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(opts)
    }

    // メニューを開くたびに最新の状態で作り直す
    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        menu.addItem(info("PadRemote for Mac"))
        menu.addItem(info("状態：" + statusText))
        menu.addItem(info("アクセシビリティ：" + (AXIsProcessTrusted() ? "許可済み" : "未許可（下から設定）")))
        menu.addItem(.separator())

        let hl = NSMenuItem(title: "カーソルのハイライトを表示", action: #selector(toggleHighlight), keyEquivalent: "")
        hl.target = self
        hl.state = UserDefaults.standard.bool(forKey: "highlight") ? .on : .off
        menu.addItem(hl)

        let paste = NSMenuItem(title: "文字はペーストで入力（日本語入力ONでも確実）", action: #selector(togglePaste), keyEquivalent: "")
        paste.target = self
        paste.state = UserDefaults.standard.bool(forKey: "pasteText") ? .on : .off
        menu.addItem(paste)

        menu.addItem(.separator())
        let ax = NSMenuItem(title: "アクセシビリティ設定を開く…", action: #selector(openAccessibility), keyEquivalent: "")
        ax.target = self
        menu.addItem(ax)
        let bt = NSMenuItem(title: "Bluetooth設定を開く…", action: #selector(openBluetoothPrivacy), keyEquivalent: "")
        bt.target = self
        menu.addItem(bt)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "終了", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
    }

    private func info(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    @objc private func toggleHighlight() {
        let d = UserDefaults.standard
        d.set(!d.bool(forKey: "highlight"), forKey: "highlight")
    }

    @objc private func togglePaste() {
        let d = UserDefaults.standard
        d.set(!d.bool(forKey: "pasteText"), forKey: "pasteText")
    }

    @objc private func openAccessibility() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
    }

    @objc private func openBluetoothPrivacy() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth")
    }

    private func open(_ url: String) {
        if let u = URL(string: url) { NSWorkspace.shared.open(u) }
    }
}
