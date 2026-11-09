#!/bin/bash
# Mac用 PadRemote.app をビルドする（Xcodeまたはコマンドラインツールが必要）
#   xcode-select --install   ← 未インストールなら先に実行
set -euo pipefail
cd "$(dirname "$0")"

APP=build/PadRemote.app
SDK=$(xcrun --sdk macosx --show-sdk-path)

rm -rf build
mkdir -p "$APP/Contents/MacOS"

for arch in arm64 x86_64; do
  xcrun swiftc -O -swift-version 5 -sdk "$SDK" \
    -target "$arch-apple-macos13.0" \
    -o "build/PadRemote-$arch" Sources/*.swift
done
lipo -create -output "$APP/Contents/MacOS/PadRemote" build/PadRemote-arm64 build/PadRemote-x86_64
rm build/PadRemote-arm64 build/PadRemote-x86_64

cp Info.plist "$APP/Contents/Info.plist"
codesign --force --sign - "$APP"   # ローカル用の署名（Apple Silicon で起動するのに必要）

echo "✅ できました: mac/$APP"
echo "   ダブルクリックで起動 → メニューバーにアイコンが出ます"
