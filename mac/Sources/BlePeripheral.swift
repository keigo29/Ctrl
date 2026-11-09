import CoreBluetooth
import Foundation

/// MacをBLEペリフェラル（GATTサーバー）にして、スマホのPWAから書き込みを受け取る。
/// サービス・キャラクタリスティックはAndroid版と共通。
final class BlePeripheral: NSObject, CBPeripheralManagerDelegate {
    static let service = CBUUID(string: "7A1C0001-5D2B-4C6E-9F0A-3B8E2D4C6A10")
    static let command = CBUUID(string: "7A1C0002-5D2B-4C6E-9F0A-3B8E2D4C6A10")
    static let info = CBUUID(string: "7A1C0003-5D2B-4C6E-9F0A-3B8E2D4C6A10") // "mac" を返す

    private var manager: CBPeripheralManager!
    private let onPacket: (Data) -> Void
    private let onState: (String) -> Void
    private var idleTimer: Timer?

    init(onPacket: @escaping (Data) -> Void, onState: @escaping (String) -> Void) {
        self.onPacket = onPacket
        self.onState = onState
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn: setup()
        case .poweredOff: onState("Bluetoothがオフです")
        case .unauthorized: onState("Bluetoothが許可されていません")
        case .unsupported: onState("このMacはBLEに非対応です")
        default: onState("Bluetooth準備中…")
        }
    }

    private func setup() {
        manager.removeAllServices()
        let cmd = CBMutableCharacteristic(type: Self.command,
                                          properties: [.write, .writeWithoutResponse],
                                          value: nil, permissions: [.writeable])
        // value を nil にして読み取りを自前で返す（スマホが接続したことを状態表示に出すため）
        let info = CBMutableCharacteristic(type: Self.info, properties: [.read],
                                           value: nil, permissions: [.readable])
        let svc = CBMutableService(type: Self.service, primary: true)
        svc.characteristics = [cmd, info]
        manager.add(svc)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            onState("サービス登録に失敗：\(error.localizedDescription)")
            return
        }
        let name = "PadRemote"   // スマホの一覧で見分けやすい名前
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.service],
            CBAdvertisementDataLocalNameKey: name,
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            onState("アドバタイズに失敗：\(error.localizedDescription)")
        } else {
            onState("待機中（スマホから接続できます）")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == Self.info else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        let value = Data("mac".utf8)
        guard request.offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
        onState("スマホと接続しました")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests where r.characteristic.uuid == Self.command {
            if let v = r.value { onPacket(v) }
        }
        if let first = requests.first {
            peripheral.respond(to: first, withResult: .success)
        }
        onState("操作を受信中")
        idleTimer?.invalidate()
        idleTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: false) { [weak self] _ in
            self?.onState("待機中（スマホから接続できます）")
        }
    }
}
