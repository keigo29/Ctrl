// アプリアイコンを書き出す（PWA用PNG・Mac用icns素材）
//   swift tools/make-icons.swift
// 図形は 108x108 の座標で定義（Androidのアダプティブアイコンと同じ。中央66の円内が安全領域）
import AppKit

let bgTop = NSColor(srgbRed: 0.145, green: 0.145, blue: 0.165, alpha: 1)    // #25252a
let bgBottom = NSColor(srgbRed: 0.043, green: 0.043, blue: 0.047, alpha: 1) // #0b0b0c
let panelFill = NSColor(srgbRed: 0.18, green: 0.18, blue: 0.20, alpha: 1)   // #2e2e33
let panelLine = NSColor(white: 1, alpha: 0.10)
let accent = NSColor(srgbRed: 1.0, green: 0.596, blue: 0.0, alpha: 1)       // #ff9800

// 108座標（y下向き）→ CoreGraphics（y上向き）
func flip(_ ctx: CGContext) { ctx.translateBy(x: 0, y: 108); ctx.scaleBy(x: 1, y: -1) }

func drawBackground(_ ctx: CGContext) {
    let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                       colors: [bgTop.cgColor, bgBottom.cgColor] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(g, start: CGPoint(x: 54, y: 0), end: CGPoint(x: 54, y: 108), options: [])
}

func cursorPath() -> CGPath {
    // 矢印カーソル（先端 = 47,43）
    let p = CGMutablePath()
    let pts: [(CGFloat, CGFloat)] = [(0, 0), (0, 24), (6, 18.6), (10.2, 27.6), (14, 25.9), (9.8, 17), (17.4, 17)]
    p.move(to: CGPoint(x: 47 + pts[0].0, y: 43 + pts[0].1))
    for q in pts.dropFirst() { p.addLine(to: CGPoint(x: 47 + q.0, y: 43 + q.1)) }
    p.closeSubpath()
    return p
}

func drawForeground(_ ctx: CGContext) {
    // トラックパッド
    let panel = CGPath(roundedRect: CGRect(x: 25, y: 33, width: 58, height: 42), cornerWidth: 9, cornerHeight: 9, transform: nil)
    ctx.addPath(panel); ctx.setFillColor(panelFill.cgColor); ctx.fillPath()
    ctx.addPath(panel); ctx.setStrokeColor(panelLine.cgColor); ctx.setLineWidth(1); ctx.strokePath()

    // タッチの波紋
    ctx.setStrokeColor(accent.withAlphaComponent(0.35).cgColor); ctx.setLineWidth(2)
    ctx.strokeEllipse(in: CGRect(x: 47 - 13, y: 43 - 13, width: 26, height: 26))
    ctx.setStrokeColor(accent.cgColor); ctx.setLineWidth(2.4)
    ctx.strokeEllipse(in: CGRect(x: 47 - 7, y: 43 - 7, width: 14, height: 14))

    // カーソル
    let c = cursorPath()
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: 1.2), blur: 3, color: NSColor(white: 0, alpha: 0.5).cgColor)
    ctx.addPath(c); ctx.setFillColor(NSColor.white.cgColor); ctx.fillPath()
    ctx.restoreGState()
    ctx.addPath(c); ctx.setStrokeColor(bgBottom.cgColor); ctx.setLineWidth(1.8); ctx.setLineJoin(.round); ctx.strokePath()
}

func render(size: Int, mac: Bool) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    let ctx = NSGraphicsContext(bitmapImageRep: rep)!.cgContext
    let s = CGFloat(size)
    if mac {
        // macOSのアイコングリッド：1024中824の角丸、影つき
        let inset = s * 100 / 1024, body = s - inset * 2
        let rect = CGRect(x: inset, y: inset + s * 10 / 1024, width: body, height: body)
        let shape = CGPath(roundedRect: rect, cornerWidth: body * 0.225, cornerHeight: body * 0.225, transform: nil)
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -s * 10 / 1024), blur: s * 28 / 1024, color: NSColor(white: 0, alpha: 0.35).cgColor)
        ctx.addPath(shape); ctx.setFillColor(bgBottom.cgColor); ctx.fillPath()
        ctx.restoreGState()
        ctx.addPath(shape); ctx.clip()
        // 108座標のうち 14..94 を角丸の中に収める
        ctx.translateBy(x: rect.minX, y: rect.minY)
        ctx.scaleBy(x: body / 80, y: body / 80)
        ctx.translateBy(x: -14, y: -14)
    } else {
        ctx.scaleBy(x: s / 108, y: s / 108)
    }
    flip(ctx)
    drawBackground(ctx)
    drawForeground(ctx)
    return rep.representation(using: .png, properties: [:])!
}

let root = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
func write(_ d: Data, _ path: String) {
    let u = root.appendingPathComponent(path)
    try! FileManager.default.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! d.write(to: u)
}

// PWA（全面塗り＝maskable対応）
write(render(size: 192, mac: false), "pwa/icon-192.png")
write(render(size: 512, mac: false), "pwa/icon-512.png")
write(render(size: 180, mac: false), "pwa/apple-touch-icon.png")
write(render(size: 32, mac: false), "pwa/favicon-32.png")

// Mac（iconset → build.sh で icns に変換）
for (n, px) in [(16, 16), (32, 32), (128, 128), (256, 256), (512, 512)] {
    write(render(size: px, mac: true), "mac/AppIcon.iconset/icon_\(n)x\(n).png")
    write(render(size: px * 2, mac: true), "mac/AppIcon.iconset/icon_\(n)x\(n)@2x.png")
}
print("done")
