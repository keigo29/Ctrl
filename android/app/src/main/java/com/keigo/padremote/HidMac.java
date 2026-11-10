package com.keigo.padremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redmi Pad を「Bluetoothマウス＋キーボード」として Mac につなぐ（Android の HID デバイス機能）。
 * Mac 側にアプリは不要。普通の Bluetooth キーボード・マウスとして認識される。
 */
@SuppressLint("MissingPermission")
public class HidMac {

    public interface Listener {
        void onState(int state, String text);
    }

    public static final int UNSUPPORTED = -1;
    public static final int STARTING = 0;
    public static final int READY = 1;        // 登録済み・Mac と未接続
    public static final int CONNECTING = 2;
    public static final int CONNECTED = 3;

    static final byte ID_KEYBOARD = 1;
    static final byte ID_MOUSE = 2;
    static final byte ID_CONSUMER = 3;

    // 修飾キー
    public static final int MOD_CTRL = 0x01, MOD_SHIFT = 0x02, MOD_ALT = 0x04, MOD_CMD = 0x08;

    // マウスボタン
    public static final int BTN_LEFT = 0x01, BTN_RIGHT = 0x02;

    /** キーボード(ID1) ＋ マウス(ID2・横スクロール付き) ＋ メディアキー(ID3) */
    private static final byte[] DESCRIPTOR = bytes(
            // ---- Keyboard ----
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x81, 0x00,
            0xC0,
            // ---- Mouse ----
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
            0xC0, 0xC0,
            // ---- Consumer (メディアキー) ----
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0
    );

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(); // 送信順を守る
    private final SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private volatile BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private boolean registered;
    private int buttons;
    private int state = STARTING;
    private String stateText = "準備中…";

    public HidMac(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.prefs = ctx.getSharedPreferences("hid", Context.MODE_PRIVATE);
    }

    // ================= 状態 =================

    private void setState(int s, String text) {
        state = s;
        stateText = text;
        main.post(() -> listener.onState(s, text));
    }

    public int getState() { return state; }
    public String getStateText() { return stateText; }
    public boolean isConnected() { return host != null && state == CONNECTED; }

    // ================= 開始・終了 =================

    public void start() {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            setState(UNSUPPORTED, "Bluetoothが使えません");
            return;
        }
        if (!adapter.isEnabled()) {
            setState(UNSUPPORTED, "Bluetoothがオフです");
            return;
        }
        setState(STARTING, "HIDを準備中…");
        boolean ok = adapter.getProfileProxy(ctx, proxyListener, BluetoothProfile.HID_DEVICE);
        if (!ok) {
            setState(UNSUPPORTED, "この端末はBluetoothキーボード・マウス機能（HIDデバイス）に非対応です");
        }
    }

    public void stop() {
        BluetoothHidDevice h = hid;
        if (h != null) {
            try {
                if (host != null) h.disconnect(host);
                if (registered) h.unregisterApp();
            } catch (Exception ignored) { }
            try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, h); } catch (Exception ignored) { }
        }
        hid = null;
        host = null;
        registered = false;
        io.shutdown();
    }

    private final BluetoothProfile.ServiceListener proxyListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            register();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = null;
            host = null;
            registered = false;
            setState(UNSUPPORTED, "HIDサービスが停止しました（アプリを開き直してください）");
        }
    };

    private void register() {
        BluetoothHidDevice h = hid;
        if (h == null) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "PadRemote", "Redmi Pad Mouse & Keyboard", "PadRemote",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
                BluetoothHidDeviceAppQosSettings.MAX);
        boolean ok;
        try {
            ok = h.registerApp(sdp, null, qos, Executors.newSingleThreadExecutor(), callback);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            setState(UNSUPPORTED, "HIDの登録に失敗しました（他のキーボード系アプリが使用中かも）");
            return;
        }
        // 一定時間たっても登録完了の通知が来なければ非対応とみなす
        main.postDelayed(() -> {
            if (!registered && state == STARTING) {
                setState(UNSUPPORTED, "HIDの登録が完了しません（この端末では使えない可能性があります）");
            }
        }, 5000);
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean reg) {
            registered = reg;
            if (reg) {
                setState(READY, "Macと未接続");
                if (pluggedDevice != null) {
                    connect(pluggedDevice);
                } else {
                    connectLast();
                }
            } else {
                host = null;
                setState(UNSUPPORTED, "HIDの登録が解除されました");
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int st) {
            if (st == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                prefs.edit().putString("last", device.getAddress()).apply();
                setState(CONNECTED, name(device) + " に接続中");
            } else if (st == BluetoothProfile.STATE_CONNECTING) {
                setState(CONNECTING, name(device) + " に接続しています…");
            } else if (st == BluetoothProfile.STATE_DISCONNECTED) {
                if (host == null || host.equals(device)) {
                    host = null;
                    buttons = 0;
                    if (registered) setState(READY, "Macと未接続");
                }
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            BluetoothHidDevice h = hid;
            if (h == null) return;
            int len = id == ID_KEYBOARD ? 8 : id == ID_MOUSE ? 5 : id == ID_CONSUMER ? 2 : 0;
            if (len == 0) {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            } else {
                h.replyReport(device, type, id, new byte[len]);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            BluetoothHidDevice h = hid;
            if (h != null) h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS); // キーボードLEDなどは無視
        }
    };

    private static String name(BluetoothDevice d) {
        String n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    // ================= 接続 =================

    public Set<BluetoothDevice> bondedDevices() {
        try {
            return adapter == null ? Collections.emptySet() : adapter.getBondedDevices();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    public void connect(BluetoothDevice d) {
        BluetoothHidDevice h = hid;
        if (h == null || !registered || d == null) return;
        if (host != null && !host.equals(d)) {
            try { h.disconnect(host); } catch (Exception ignored) { }
        }
        setState(CONNECTING, name(d) + " に接続しています…");
        boolean ok;
        try { ok = h.connect(d); } catch (Exception e) { ok = false; }
        if (!ok) setState(READY, "接続できませんでした（Macとペアリング済みか確認）");
    }

    private void connectLast() {
        String addr = prefs.getString("last", null);
        if (addr == null || adapter == null) return;
        for (BluetoothDevice d : bondedDevices()) {
            if (addr.equals(d.getAddress())) {
                connect(d);
                return;
            }
        }
    }

    public void disconnect() {
        BluetoothHidDevice h = hid;
        if (h != null && host != null) {
            try { h.disconnect(host); } catch (Exception ignored) { }
        }
    }

    // ================= 送信 =================

    private void send(byte id, byte[] data) {
        final BluetoothHidDevice h = hid;
        final BluetoothDevice d = host;
        if (h == null || d == null || io.isShutdown()) return;
        io.execute(() -> {
            try { h.sendReport(d, id, data); } catch (Exception ignored) { }
        });
    }

    private static int clamp(int v) { return Math.max(-127, Math.min(127, v)); }

    /** 相対移動・スクロール。127を超える分は分割して送る */
    public void mouse(int dx, int dy, int wheel, int pan) {
        do {
            int x = clamp(dx), y = clamp(dy), w = clamp(wheel), p = clamp(pan);
            send(ID_MOUSE, new byte[]{(byte) buttons, (byte) x, (byte) y, (byte) w, (byte) p});
            dx -= x; dy -= y; wheel -= w; pan -= p;
        } while (dx != 0 || dy != 0 || wheel != 0 || pan != 0);
    }

    public void setButtons(int b) {
        buttons = b;
        send(ID_MOUSE, new byte[]{(byte) b, 0, 0, 0, 0});
    }

    public int getButtons() { return buttons; }

    public void click(int button) {
        int keep = buttons & ~button;
        setButtons(keep | button);
        setButtons(keep);
    }

    /** キーを1回押して離す */
    public void key(int mods, int usage) {
        send(ID_KEYBOARD, new byte[]{(byte) mods, 0, (byte) usage, 0, 0, 0, 0, 0});
        send(ID_KEYBOARD, new byte[8]);
    }

    /** メディアキー（Consumer Page の usage） */
    public void consumer(int usage) {
        send(ID_CONSUMER, new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)});
        send(ID_CONSUMER, new byte[2]);
    }

    /** 英字・数字・記号を US 配列のキー入力として打つ。打てなかった文字数を返す */
    public int type(String s) {
        int skipped = 0;
        for (int i = 0; i < s.length(); i++) {
            int[] k = Keymap.forChar(s.charAt(i));
            if (k == null) { skipped++; continue; }
            key(k[0], k[1]);
        }
        return skipped;
    }
}
