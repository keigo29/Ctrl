package com.keigo.padremote;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * ユーザー補助サービス本体。
 * BLEで受け取ったコマンドを、カーソル表示・ジェスチャー・文字入力・ボタン操作に変換する。
 */
public class RemoteService extends AccessibilityService implements BlePeripheral.Listener {

    // ---- コマンド（PWA側と共通） ----
    static final int OP_MOVE = 0x01;        // int16 dx, int16 dy
    static final int OP_TAP = 0x02;
    static final int OP_LONG_PRESS = 0x03;
    static final int OP_SCROLL = 0x04;      // int16 dx, int16 dy
    static final int OP_GLOBAL = 0x05;      // uint8 GLOBAL_ACTION_*
    static final int OP_TEXT_CHUNK = 0x06;  // UTF-8 bytes
    static final int OP_TEXT_COMMIT = 0x07;
    static final int OP_BACKSPACE = 0x08;
    static final int OP_ENTER = 0x09;
    static final int OP_DRAG_START = 0x0A;
    static final int OP_DRAG_END = 0x0B;
    static final int OP_CENTER = 0x0C;
    static final int OP_LAUNCH = 0x0D;     // 0x06で送った文字列を "app:名前" / "url:URL" として起動

    static volatile RemoteService instance;
    static volatile String status = "停止中";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams lp;
    private int cursorSize;
    private float cx, cy;
    private int sw, sh;
    private BlePeripheral ble;
    private final ByteArrayOutputStream textBuf = new ByteArrayOutputStream();
    private boolean dragging;
    private float dragX, dragY;
    private boolean receiverRegistered;

    // BLEは数十ms間隔でまとめて届くので、届いた移動量を画面の描画ごとに少しずつ反映して滑らかにする（Mac版と同じ）
    private static final float SMOOTH_RATE = 0.6f;   // 1コマで残りの何割を動かすか
    private float pendX, pendY;
    private boolean frameScheduled;
    private final Choreographer.FrameCallback smoothFrame = t -> {
        frameScheduled = false;
        smoothTick();
    };

    private final Runnable hideCursor = () -> {
        if (cursor != null && !dragging) cursor.setVisibility(View.INVISIBLE);
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (st == BluetoothAdapter.STATE_ON) {
                startBleIfPossible();
            } else if (st == BluetoothAdapter.STATE_OFF) {
                stopBle();
                status = "Bluetoothがオフです";
            }
        }
    };

    // ================= ライフサイクル =================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = getSystemService(WindowManager.class);
        updateScreenSize();
        cx = sw / 2f;
        cy = sh / 2f;
        addCursor();

        IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(btReceiver, f);
        }
        receiverRegistered = true;

        startBleIfPossible();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        stopBle();
        if (receiverRegistered) {
            try { unregisterReceiver(btReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (Exception ignored) { }
            cursor = null;
        }
        instance = null;
        status = "停止中";
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateScreenSize();
        cx = clamp(cx, 0, sw - 1);
        cy = clamp(cy, 0, sh - 1);
        moveCursorView();
    }

    // ================= BLE =================

    void startBleIfPossible() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                status = "Bluetoothの権限が未許可です（アプリを開いて許可）";
                return;
            }
        }
        if (ble != null) return;
        ble = new BlePeripheral(this, this);
        ble.start();
    }

    private void stopBle() {
        if (ble != null) {
            ble.stop();
            ble = null;
        }
    }

    @Override
    public void onPacket(byte[] data) {
        final byte[] d = data.clone();
        main.post(() -> handle(d));
    }

    @Override
    public void onState(String s) {
        status = s;
    }

    // ================= コマンド処理 =================

    private void handle(byte[] d) {
        if (d.length == 0) return;
        int op = d[0] & 0xff;
        if (op != OP_MOVE) flushMove();   // タップなどは残りの移動を反映してから
        switch (op) {
            case OP_MOVE:
                if (d.length >= 5) {
                    pendX += s16(d, 1);
                    pendY += s16(d, 3);
                    showCursor();
                    if (!frameScheduled) {
                        frameScheduled = true;
                        Choreographer.getInstance().postFrameCallback(smoothFrame);
                    }
                }
                break;
            case OP_TAP:
                showCursor();
                press(cx, cy, 40);
                break;
            case OP_LONG_PRESS:
                showCursor();
                press(cx, cy, 700);
                break;
            case OP_SCROLL:
                if (d.length >= 5) scroll(s16(d, 1), s16(d, 3));
                break;
            case OP_GLOBAL:
                if (d.length >= 2) performGlobalAction(d[1] & 0xff);
                break;
            case OP_TEXT_CHUNK:
                textBuf.write(d, 1, d.length - 1);
                break;
            case OP_TEXT_COMMIT: {
                String t = new String(textBuf.toByteArray(), StandardCharsets.UTF_8);
                textBuf.reset();
                if (!t.isEmpty()) insertText(t);
                break;
            }
            case OP_BACKSPACE:
                backspace();
                break;
            case OP_ENTER:
                enter();
                break;
            case OP_DRAG_START:
                dragStart();
                break;
            case OP_DRAG_END:
                dragEnd();
                break;
            case OP_LAUNCH: {
                String t = new String(textBuf.toByteArray(), StandardCharsets.UTF_8);
                textBuf.reset();
                if (!t.isEmpty()) launch(t);
                break;
            }
            case OP_CENTER:
                cx = sw / 2f;
                cy = sh / 2f;
                moveCursorView();
                showCursor();
                break;
            default:
                break;
        }
    }

    private static int s16(byte[] d, int i) {
        return (short) ((d[i] & 0xff) | (d[i + 1] << 8));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ================= カーソル =================

    private void updateScreenSize() {
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        sw = b.width();
        sh = b.height();
    }

    private void addCursor() {
        cursorSize = (int) (30 * getResources().getDisplayMetrics().density);
        cursor = new CursorView(this);
        lp = new WindowManager.LayoutParams(
                cursorSize, cursorSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        cursor.setVisibility(View.INVISIBLE);
        wm.addView(cursor, lp);
        moveCursorView();
    }

    private void smoothTick() {
        float mx = pendX * SMOOTH_RATE, my = pendY * SMOOTH_RATE;
        if (Math.abs(pendX) + Math.abs(pendY) < 1.5f) { mx = pendX; my = pendY; }   // 残りわずかなら一度に
        pendX -= mx;
        pendY -= my;
        applyMove(mx, my);
        if (pendX != 0 || pendY != 0) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(smoothFrame);
        }
    }

    private void flushMove() {
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(smoothFrame);
            frameScheduled = false;
        }
        float mx = pendX, my = pendY;
        pendX = pendY = 0;
        if (mx != 0 || my != 0) applyMove(mx, my);
    }

    private void applyMove(float dx, float dy) {
        cx = clamp(cx + dx, 0, sw - 1);
        cy = clamp(cy + dy, 0, sh - 1);
        moveCursorView();
    }

    private void moveCursorView() {
        if (cursor == null) return;
        lp.x = (int) cx - cursorSize / 2;
        lp.y = (int) cy - cursorSize / 2;
        try { wm.updateViewLayout(cursor, lp); } catch (Exception ignored) { }
    }

    private void showCursor() {
        if (cursor == null) return;
        cursor.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideCursor);
        main.postDelayed(hideCursor, 8000);
    }

    // ================= ジェスチャー =================

    private void dispatch(GestureDescription.StrokeDescription s, GestureResultCallback cb) {
        GestureDescription g = new GestureDescription.Builder().addStroke(s).build();
        dispatchGesture(g, cb, null);
    }

    private void press(float x, float y, long duration) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(new GestureDescription.StrokeDescription(p, 0, duration), null);
    }

    private void scroll(int dx, int dy) {
        // 画面端から始めるとシステムのジェスチャー（戻る等）になるので内側に寄せる
        float margin = 80;
        float sx = clamp(cx, margin, sw - margin);
        float sy = clamp(cy, margin, sh - margin);
        float ex = clamp(sx + dx, 1, sw - 2);
        float ey = clamp(sy + dy, 1, sh - 2);
        if (Math.hypot(ex - sx, ey - sy) < 4) return;
        Path p = new Path();
        p.moveTo(sx, sy);
        p.lineTo(ex, ey);
        dispatch(new GestureDescription.StrokeDescription(p, 0, 180), null);
    }

    private void dragStart() {
        dragging = true;
        dragX = cx;
        dragY = cy;
        if (cursor != null) cursor.setDragging(true);
        showCursor();
    }

    /** 開始位置で長押し → 現在のカーソル位置までなぞる（アイコン移動・範囲選択など） */
    private void dragEnd() {
        if (!dragging) return;
        dragging = false;
        if (cursor != null) cursor.setDragging(false);
        showCursor();
        final float sx = dragX, sy = dragY, ex = cx, ey = cy;
        Path hold = new Path();
        hold.moveTo(sx, sy);
        final GestureDescription.StrokeDescription first =
                new GestureDescription.StrokeDescription(hold, 0, 600, true);
        dispatch(first, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Path mv = new Path();
                mv.moveTo(sx, sy);
                mv.lineTo(ex, ey);
                dispatch(first.continueStroke(mv, 0, 500, false), null);
            }
        });
    }

    // ================= 文字入力 =================

    private AccessibilityNodeInfo focusedInput() {
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) {
            toast("入力欄が選択されていません（先に入力欄をタップ）");
            return null;
        }
        return node;
    }

    private static String currentText(AccessibilityNodeInfo node) {
        if (node.isShowingHintText()) return "";
        CharSequence t = node.getText();
        return t == null ? "" : t.toString();
    }

    private static int[] selection(AccessibilityNodeInfo node, String cur) {
        int s = node.getTextSelectionStart();
        int e = node.getTextSelectionEnd();
        if (s < 0 || e < 0) { s = cur.length(); e = cur.length(); }
        if (s > e) { int t = s; s = e; e = t; }
        s = Math.min(s, cur.length());
        e = Math.min(e, cur.length());
        return new int[]{s, e};
    }

    private static void setText(AccessibilityNodeInfo node, String text, int caret) {
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
        Bundle b = new Bundle();
        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, b);
    }

    private void insertText(String ins) {
        AccessibilityNodeInfo node = focusedInput();
        if (node == null) return;
        String cur = currentText(node);
        int[] sel = selection(node, cur);
        String next = cur.substring(0, sel[0]) + ins + cur.substring(sel[1]);
        setText(node, next, sel[0] + ins.length());
    }

    private void backspace() {
        AccessibilityNodeInfo node = focusedInput();
        if (node == null) return;
        String cur = currentText(node);
        int[] sel = selection(node, cur);
        int s = sel[0], e = sel[1];
        if (s == e) {
            if (s == 0) return;
            s = Character.offsetByCodePoints(cur, s, -1);
        }
        setText(node, cur.substring(0, s) + cur.substring(e), s);
    }

    private void enter() {
        AccessibilityNodeInfo node = focusedInput();
        if (node == null) return;
        boolean ok = node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        if (!ok && node.isMultiLine()) insertText("\n");
    }

    // ================= ランチャー =================

    private void launch(String target) {
        try {
            Intent intent;
            if (target.startsWith("url:")) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target.substring(4)));
            } else {
                String name = target.startsWith("app:") ? target.substring(4) : target;
                intent = findApp(name.trim());
                if (intent == null) {
                    toast("アプリが見つかりません：" + name);
                    return;
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Exception e) {
            toast("起動できませんでした：" + e.getMessage());
        }
    }

    /** パッケージ名（com.example.app）でも、ホーム画面の表示名（YouTube・マップ など）でも探す */
    private Intent findApp(String name) {
        PackageManager pm = getPackageManager();
        if (name.contains(".") && !name.contains(" ")) {
            Intent byPkg = pm.getLaunchIntentForPackage(name);
            if (byPkg != null) return byPkg;
        }
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        ResolveInfo partial = null;
        String key = name.toLowerCase(Locale.ROOT);
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.equals(key)) return componentIntent(ri);
            if (partial == null && label.contains(key)) partial = ri;
        }
        return partial == null ? null : componentIntent(partial);
    }

    private static Intent componentIntent(ResolveInfo ri) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        return i;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
