package com.keigo.padremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;

import java.util.UUID;

/**
 * タブレットをBLEペリフェラル（GATTサーバー）にして、スマホのPWAから書き込みを受け取る。
 */
@SuppressLint("MissingPermission")
public class BlePeripheral {

    public static final UUID SERVICE_UUID = UUID.fromString("7a1c0001-5d2b-4c6e-9f0a-3b8e2d4c6a10");
    public static final UUID COMMAND_UUID = UUID.fromString("7a1c0002-5d2b-4c6e-9f0a-3b8e2d4c6a10");
    /** 接続先の種類を返す（PWAがボタン表示を切り替えるため）。Mac版は "mac" を返す */
    public static final UUID INFO_UUID = UUID.fromString("7a1c0003-5d2b-4c6e-9f0a-3b8e2d4c6a10");
    private static final byte[] INFO_VALUE = "android".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public interface Listener {
        void onPacket(byte[] data);
        void onState(String state);
    }

    private final Context ctx;
    private final Listener listener;
    private BluetoothManager manager;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private boolean withName = true;
    private int connected = 0;

    public BlePeripheral(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void start() {
        manager = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onState("Bluetoothがオフです");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.onState("この端末はBLEアドバタイズに非対応です");
            return;
        }
        server = manager.openGattServer(ctx, gattCallback);
        if (server == null) {
            listener.onState("GATTサーバーを開けませんでした");
            return;
        }
        BluetoothGattService svc = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic ch = new BluetoothGattCharacteristic(
                COMMAND_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        svc.addCharacteristic(ch);
        svc.addCharacteristic(new BluetoothGattCharacteristic(
                INFO_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ));
        server.addService(svc); // 追加完了(onServiceAdded)後にアドバタイズ開始
    }

    public void stop() {
        try { if (advertiser != null) advertiser.stopAdvertising(advCallback); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        advertiser = null;
        server = null;
    }

    private void startAdvertising() {
        if (advertiser == null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .setIncludeDeviceName(false)
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(withName)
                .build();
        advertiser.startAdvertising(settings, data, scanResponse, advCallback);
    }

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            listener.onState(connected > 0 ? "接続中" : "待機中（スマホから接続できます）");
        }

        @Override
        public void onStartFailure(int errorCode) {
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && withName) {
                withName = false; // 端末名が長すぎる場合は名前なしで再挑戦
                startAdvertising();
                return;
            }
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return;
            listener.onState("アドバタイズ失敗 (code " + errorCode + ")");
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startAdvertising();
            } else {
                listener.onState("サービス登録失敗 (" + status + ")");
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected++;
                listener.onState("接続中");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = Math.max(0, connected - 1);
                listener.onState(connected > 0 ? "接続中" : "待機中（スマホから接続できます）");
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (server == null) return;
            if (INFO_UUID.equals(characteristic.getUuid())) {
                int off = Math.min(Math.max(offset, 0), INFO_VALUE.length);
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, off,
                        java.util.Arrays.copyOfRange(INFO_VALUE, off, INFO_VALUE.length));
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (responseNeeded && server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            if (COMMAND_UUID.equals(characteristic.getUuid()) && value != null && !preparedWrite) {
                listener.onPacket(value);
            }
        }
    };
}
