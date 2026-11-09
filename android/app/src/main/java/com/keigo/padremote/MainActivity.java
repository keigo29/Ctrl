package com.keigo.padremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 初期設定画面：権限の許可・ユーザー補助の有効化・状態の確認 */
public class MainActivity extends Activity {

    private TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (24 * d), (int) (32 * d), (int) (24 * d), (int) (24 * d));
        root.setBackgroundColor(Color.rgb(11, 11, 12));

        TextView title = new TextView(this);
        title.setText("PadRemote");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("スマホのPWAからBluetooth(BLE)でこのタブレットを操作します。\n下のボタンを上から順に設定してください。");
        desc.setTextColor(Color.rgb(170, 170, 170));
        desc.setPadding(0, (int) (8 * d), 0, (int) (20 * d));
        root.addView(desc);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16);
        statusView.setPadding(0, 0, 0, (int) (20 * d));
        root.addView(statusView);

        root.addView(button("① Bluetoothの権限を許可", v -> requestBlePermissions()));
        root.addView(button("② ユーザー補助で「PadRemote」をオン", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("オンにできない時：アプリ情報（制限付き設定を許可）", v ->
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)))));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(11, 11, 12));
        sv.addView(root);
        setContentView(sv);
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(p);
        return b;
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31 && !hasBlePermissions()) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            onPermissionsReady();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (hasBlePermissions()) onPermissionsReady();
        refresh();
    }

    private void onPermissionsReady() {
        RemoteService s = RemoteService.instance;
        if (s != null) s.startBleIfPossible();
    }

    private void refresh() {
        boolean perm = hasBlePermissions();
        boolean svc = RemoteService.instance != null;
        statusView.setText(
                "Bluetooth権限： " + (perm ? "OK" : "未許可") + "\n"
                        + "ユーザー補助： " + (svc ? "オン" : "オフ") + "\n"
                        + "状態： " + RemoteService.status);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBlePermissions()) onPermissionsReady();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }
}
