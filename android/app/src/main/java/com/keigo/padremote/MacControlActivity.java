package com.keigo.padremote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Redmi Pad を Mac のトラックパッド＋キーボード＋ショートカットボードにする画面。
 * Mac には普通の Bluetooth マウス・キーボードとして見える（Mac 側のアプリ不要）。
 */
@SuppressLint("MissingPermission")
public class MacControlActivity extends Activity {

    // ---- 色（単色グラデーション：明るい色 → 暗い色） ----
    static final int[] GRAY = {0xFF26262A, 0xFF18181B};
    static final int[] BLUE = {0xFF5B8DEF, 0xFF2C4AA8};
    static final int[] GREEN = {0xFF21B37E, 0xFF0B6B48};
    static final int[] VIOLET = {0xFF9A6CF0, 0xFF5A2FA8};
    static final int[] CYAN = {0xFF1FB5D6, 0xFF0A6880};
    static final int[] ROSE = {0xFFEE5A6F, 0xFFA3243C};
    static final int[] AMBER = {0xFFE5A530, 0xFF8A4C0A};
    static final int[] WHITE = {0xFFFFFFFF, 0xFFD9D9DE};
    static final int[][] PALETTE = {GRAY, BLUE, CYAN, GREEN, AMBER, ROSE, VIOLET};

    static final int BG = 0xFF0B0B0C, PANEL = 0xFF141416, LINE = 0xFF2A2A2E, TEXT = 0xFFF2F2F3, SUB = 0xFF8B8B92;

    private float d;
    private HidMac hid;
    private SharedPreferences prefs;

    private LinearLayout root;
    private TrackpadView pad;
    private ScrollView panel;
    private TextView statusText;
    private View statusDot;
    private TextView dragBtn;
    private LinearLayout deckGrid;
    private TextView deckEditBtn;
    private boolean dragging, deckEdit;

    private float sens, accel, scrollSpeed;
    private boolean invertScroll;
    private float fx, fy, sx, sy;
    private long lastWarn;

    // ================= ライフサイクル =================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("ctl", MODE_PRIVATE);
        sens = prefs.getFloat("sens", 1.4f);
        accel = prefs.getFloat("accel", 1.0f);
        scrollSpeed = prefs.getFloat("scroll", 1.0f);
        invertScroll = prefs.getBoolean("invert", false);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        hid = new HidMac(this, this::onHidState);

        root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        int pd = (int) (10 * d);
        // Android 15 の全画面表示に合わせ、ステータスバー・ナビバー・キーボードの分だけ内側に寄せる
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                    | WindowInsets.Type.displayCutout());
            v.setPadding(i.left + pd, i.top + pd, i.right + pd, i.bottom + pd);
            return WindowInsets.CONSUMED;
        });

        pad = new TrackpadView(this);
        pad.setListener(padListener);
        panel = new ScrollView(this);
        panel.setVerticalScrollBarEnabled(false);
        panel.addView(buildPanel());

        applyOrientation(getResources().getConfiguration().orientation);
        setContentView(root);

        if (hasPermissions()) {
            hid.start();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (hasPermissions()) hid.start();
        else onHidState(HidMac.UNSUPPORTED, "Bluetoothの権限が必要です（「付近のデバイス」を許可）");
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        if (dragging) hid.setButtons(0);
        hid.stop();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation(c.orientation);
    }

    /** 横画面：左にトラックパッド・右に操作パネル ／ 縦画面：上に操作パネル・下にトラックパッド */
    private void applyOrientation(int orientation) {
        root.removeAllViews();
        int gap = (int) (10 * d);
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            root.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f);
            lp1.rightMargin = gap;
            root.addView(pad, lp1);
            root.addView(panel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f);
            lp1.bottomMargin = gap;
            root.addView(panel, lp1);
            root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    // ================= HID の状態 =================

    private void onHidState(int state, String text) {
        if (statusText == null) return;
        statusText.setText(text);
        int c = state == HidMac.CONNECTED ? 0xFF4ADE80
                : state == HidMac.CONNECTING || state == HidMac.STARTING ? 0xFFFBBF24 : 0xFFF87171;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(c);
        statusDot.setBackground(dot);
        if (state == HidMac.CONNECTED) haptic(statusText);
    }

    private boolean ready() {
        if (hid.isConnected()) return true;
        long now = System.currentTimeMillis();
        if (now - lastWarn > 1500) {
            lastWarn = now;
            toast(hid.getState() == HidMac.UNSUPPORTED ? hid.getStateText() : "先に「接続」でMacにつないでください");
        }
        return false;
    }

    // ================= トラックパッド =================

    private final TrackpadView.Listener padListener = new TrackpadView.Listener() {
        @Override
        public void onMove(float dx, float dy) {
            if (!ready()) return;
            float speed = (float) Math.hypot(dx, dy);
            float f = sens * (1 + Math.min(speed / 10f, 1.5f) * accel);
            fx += dx * f;
            fy += dy * f;
            int ix = (int) fx, iy = (int) fy;
            fx -= ix;
            fy -= iy;
            if (ix != 0 || iy != 0) hid.mouse(ix, iy, 0, 0);
        }

        @Override
        public void onScroll(float dx, float dy) {
            if (!ready()) return;
            float lineDp = 22f / scrollSpeed;   // 何dp動かしたら1行スクロールするか
            sx += dx / lineDp;
            sy += dy / lineDp;
            int lx = (int) sx, ly = (int) sy;
            sx -= lx;
            sy -= ly;
            if (lx == 0 && ly == 0) return;
            // Mac の「ナチュラルなスクロール」オン（初期設定）で、指と同じ向きに内容が動く向き
            int wheel = -ly, pan = lx;
            if (invertScroll) { wheel = -wheel; pan = -pan; }
            hid.mouse(0, 0, wheel, pan);
        }

        @Override
        public void onTap() {
            if (ready()) hid.click(HidMac.BTN_LEFT);
        }

        @Override
        public void onRightClick() {
            if (ready()) hid.click(HidMac.BTN_RIGHT);
        }
    };

    // ================= 操作パネル =================

    private View buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        // ---- ヘッダー ----
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Mac操作", 19, TEXT, true);
        head.addView(title);

        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding((int) (10 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        chip.setBackground(round(new int[]{PANEL, PANEL}, 999, LINE));
        statusDot = new View(this);
        chip.addView(statusDot, new LinearLayout.LayoutParams((int) (8 * d), (int) (8 * d)));
        statusText = label("準備中…", 12.5f, SUB, false);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.leftMargin = (int) (7 * d);
        chip.addView(statusText, stp);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chipLp.leftMargin = chipLp.rightMargin = (int) (10 * d);
        head.addView(chip, chipLp);

        TextView pair = pill("ペアリング", R.drawable.lc_link, GRAY, false);
        pair.setOnClickListener(v -> startPairing());
        head.addView(pair);
        TextView conn = pill("接続", R.drawable.lc_bluetooth, WHITE, false);
        conn.setOnClickListener(v -> chooseDevice());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = (int) (8 * d);
        head.addView(conn, cl);
        p.addView(head);
        onHidState(HidMac.STARTING, "準備中…");

        // ---- ショートカット ----
        p.addView(section("ショートカット", R.drawable.lc_layout_grid));
        p.addView(row(
                key("戻る", R.drawable.lc_undo_2, BLUE, () -> hid.key(HidMac.MOD_CMD, Keymap.KEY_OPEN_BRACKET)),
                key("デスクトップ", R.drawable.lc_monitor, GREEN, () -> hid.key(0, Keymap.F11)),
                key("一覧", R.drawable.lc_layout_grid, VIOLET, () -> hid.key(HidMac.MOD_CTRL, Keymap.UP))));
        p.addView(row(
                key("検索", R.drawable.lc_search, GRAY, () -> hid.key(HidMac.MOD_CMD, Keymap.SPACE)),
                key("アプリ切替", R.drawable.lc_arrow_right_left, GRAY, () -> hid.key(HidMac.MOD_CMD, Keymap.TAB)),
                key("ロック", R.drawable.lc_lock, GRAY, () -> hid.key(HidMac.MOD_CMD | HidMac.MOD_CTRL, Keymap.KEY_Q))));
        dragBtn = key("ドラッグ", R.drawable.lc_hand_grab, GRAY, this::toggleDrag);
        p.addView(row(
                dragBtn,
                key("右クリック", R.drawable.lc_mouse_pointer_click, GRAY, () -> hid.click(HidMac.BTN_RIGHT)),
                key("ダブル", R.drawable.lc_mouse_pointer_click, GRAY, () -> {
                    hid.click(HidMac.BTN_LEFT);
                    hid.click(HidMac.BTN_LEFT);
                })));

        // ---- メディア ----
        p.addView(section("メディア", R.drawable.lc_play));
        p.addView(row(
                icon(R.drawable.lc_skip_back, () -> hid.consumer(Keymap.MEDIA_PREV)),
                icon(R.drawable.lc_play, () -> hid.consumer(Keymap.MEDIA_PLAY_PAUSE)),
                icon(R.drawable.lc_skip_forward, () -> hid.consumer(Keymap.MEDIA_NEXT)),
                icon(R.drawable.lc_volume_1, () -> hid.consumer(Keymap.MEDIA_VOL_DOWN)),
                icon(R.drawable.lc_volume_2, () -> hid.consumer(Keymap.MEDIA_VOL_UP)),
                icon(R.drawable.lc_volume_x, () -> hid.consumer(Keymap.MEDIA_MUTE))));

        // ---- キーボード ----
        p.addView(section("キーボード（英字で入力 → Macの日本語入力で変換）", R.drawable.lc_keyboard));
        KeyCaptureView kb = new KeyCaptureView(this);
        kb.setText("ここをタップしてキーボードを出す");
        kb.setTextColor(0xFF55555C);
        kb.setTextSize(15);
        kb.setGravity(Gravity.CENTER_VERTICAL);
        kb.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
        kb.setBackground(round(new int[]{PANEL, PANEL}, 16, LINE));
        kb.setListener(new KeyCaptureView.Listener() {
            @Override
            public void onText(String s) {
                if (!ready()) return;
                if (hid.type(s) > 0) warnNonAscii();
            }

            @Override
            public void onKey(int usage) {
                if (ready()) hid.key(0, usage);
            }
        });
        kb.setOnFocusChangeListener((v, has) -> kb.setTextColor(has ? TEXT : 0xFF55555C));
        p.addView(kb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (50 * d)));
        p.addView(row(
                small("英数", GRAY, () -> hid.key(0, Keymap.LANG2_EISU)),
                small("かな", GRAY, () -> hid.key(0, Keymap.LANG1_KANA)),
                small("Esc", GRAY, () -> hid.key(0, Keymap.ESC)),
                small("Tab", GRAY, () -> hid.key(0, Keymap.TAB)),
                small("⌫", ROSE, () -> hid.key(0, Keymap.BACKSPACE)),
                small("⏎", AMBER, () -> hid.key(0, Keymap.ENTER))));
        p.addView(row(
                small("←", GRAY, () -> hid.key(0, Keymap.LEFT)),
                small("↑", GRAY, () -> hid.key(0, Keymap.UP)),
                small("↓", GRAY, () -> hid.key(0, Keymap.DOWN)),
                small("→", GRAY, () -> hid.key(0, Keymap.RIGHT)),
                small("⌘A", GRAY, () -> hid.key(HidMac.MOD_CMD, 0x04)),
                small("⌘C", GRAY, () -> hid.key(HidMac.MOD_CMD, 0x06)),
                small("⌘V", GRAY, () -> hid.key(HidMac.MOD_CMD, 0x19))));

        // ---- ランチャー ----
        LinearLayout deckHead = new LinearLayout(this);
        deckHead.setGravity(Gravity.CENTER_VERTICAL);
        View sec = section("ランチャー", R.drawable.lc_layout_grid);
        deckHead.addView(sec, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        deckEditBtn = pill("編集", R.drawable.lc_pencil, GRAY, false);
        deckEditBtn.setTextSize(12);
        deckEditBtn.setOnClickListener(v -> {
            deckEdit = !deckEdit;
            styleToggle(deckEditBtn, deckEdit, GRAY);
            haptic(v);
        });
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) (12 * d);
        deckHead.addView(deckEditBtn, dl);
        p.addView(deckHead);
        deckGrid = new LinearLayout(this);
        deckGrid.setOrientation(LinearLayout.VERTICAL);
        p.addView(deckGrid);
        renderDeck();

        // ---- 設定 ----
        p.addView(section("感度の設定", R.drawable.lc_sliders_horizontal));
        p.addView(slider("カーソル", sens, 0.4f, 4f, v -> { sens = v; prefs.edit().putFloat("sens", v).apply(); }));
        p.addView(slider("加速", accel, 0f, 3f, v -> { accel = v; prefs.edit().putFloat("accel", v).apply(); }));
        p.addView(slider("スクロール", scrollSpeed, 0.3f, 3f, v -> { scrollSpeed = v; prefs.edit().putFloat("scroll", v).apply(); }));
        Switch inv = new Switch(this);
        inv.setText("スクロールの向きを逆にする");
        inv.setTextColor(SUB);
        inv.setChecked(invertScroll);
        inv.setOnCheckedChangeListener((v, on) -> { invertScroll = on; prefs.edit().putBoolean("invert", on).apply(); });
        p.addView(inv);

        TextView note = label("初回はMacの「キーボード設定アシスタント」で ANSI（US）を選んでください。"
                + "Spotlightのアプリ名・URLは英字で登録してください。", 11.5f, 0xFF55555C, false);
        note.setPadding(0, (int) (10 * d), 0, (int) (24 * d));
        p.addView(note);
        return p;
    }

    private void toggleDrag() {
        if (!ready()) return;
        dragging = !dragging;
        hid.setButtons(dragging ? HidMac.BTN_LEFT : 0);
        pad.setDragging(dragging);
        dragBtn.setText(dragging ? "ここで離す" : "ドラッグ");
        styleToggle(dragBtn, dragging, GRAY);
    }

    // ================= 接続・ペアリング =================

    private void chooseDevice() {
        if (hid.getState() == HidMac.UNSUPPORTED) {
            toast(hid.getStateText());
            return;
        }
        final List<BluetoothDevice> list = new ArrayList<>(hid.bondedDevices());
        if (list.isEmpty()) {
            dialog().setTitle("ペアリング済みの端末がありません")
                    .setMessage("先に「ペアリング」でMacとペアリングしてください。")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] names = new String[list.size() + (hid.isConnected() ? 1 : 0)];
        for (int i = 0; i < list.size(); i++) {
            String n = list.get(i).getName();
            names[i] = n == null || n.isEmpty() ? list.get(i).getAddress() : n;
        }
        if (hid.isConnected()) names[list.size()] = "切断する";
        dialog().setTitle("接続するMacを選択")
                .setItems(names, (dlg, which) -> {
                    if (which >= list.size()) hid.disconnect();
                    else hid.connect(list.get(which));
                })
                .show();
    }

    private void startPairing() {
        if (hid.getState() == HidMac.UNSUPPORTED) {
            toast(hid.getStateText());
            return;
        }
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180);
            startActivity(i);
        } catch (Exception ignored) { }
        String name;
        try {
            name = BluetoothAdapter.getDefaultAdapter().getName();
        } catch (Exception e) {
            name = "このタブレット";
        }
        dialog().setTitle("Macとペアリング")
                .setMessage("1. Macで「システム設定 → Bluetooth」を開く\n"
                        + "2. 「近くのデバイス」に出る「" + name + "」の「接続」を押す\n"
                        + "3. 両方に表示されるコードが同じなら許可する\n"
                        + "4. 「キーボード設定アシスタント」が出たら ANSI（US）を選ぶ\n\n"
                        + "次回からはこの画面の「接続」でMacを選ぶだけでつながります。")
                .setPositiveButton("OK", null).show();
    }

    // ================= ランチャー =================

    private JSONArray loadDeck() {
        String s = prefs.getString("deck", null);
        if (s != null) {
            try { return new JSONArray(s); } catch (Exception ignored) { }
        }
        JSONArray a = new JSONArray();
        a.put(item("Safari", "app", "Safari", 2));
        a.put(item("Finder", "app", "Finder", 1));
        a.put(item("YouTube", "url", "https://www.youtube.com", 5));
        a.put(item("メモ", "app", "Notes", 4));
        a.put(item("ターミナル", "app", "Terminal", 0));
        a.put(item("システム設定", "app", "System Settings", 0));
        return a;
    }

    private static JSONObject item(String name, String type, String value, int color) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("type", type);
            o.put("value", value);
            o.put("color", color);
        } catch (Exception ignored) { }
        return o;
    }

    private void saveDeck(JSONArray a) {
        prefs.edit().putString("deck", a.toString()).apply();
    }

    private void renderDeck() {
        deckGrid.removeAllViews();
        JSONArray deck = loadDeck();
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < deck.length(); i++) {
            final int idx = i;
            JSONObject o = deck.optJSONObject(i);
            if (o == null) continue;
            int[] col = PALETTE[Math.max(0, Math.min(PALETTE.length - 1, o.optInt("color", 0)))];
            TextView t = pill(o.optString("name"), 0, col, true);
            t.setTextSize(14);
            t.setMinHeight((int) (64 * d));
            t.setOnClickListener(v -> {
                haptic(v);
                if (deckEdit) editItem(idx);
                else launch(o);
            });
            t.setOnLongClickListener(v -> { editItem(idx); return true; });
            tiles.add(t);
        }
        TextView add = pill("＋ 追加", 0, GRAY, true);
        add.setMinHeight((int) (64 * d));
        add.setTextColor(SUB);
        GradientDrawable dash = new GradientDrawable();
        dash.setCornerRadius(18 * d);
        dash.setStroke((int) (1.5f * d), 0xFF3A3A40, 6 * d, 4 * d);
        add.setBackground(dash);
        add.setOnClickListener(v -> editItem(-1));
        tiles.add(add);
        for (int i = 0; i < tiles.size(); i += 3) {
            View a = tiles.get(i);
            View b = i + 1 < tiles.size() ? tiles.get(i + 1) : spacer();
            View c = i + 2 < tiles.size() ? tiles.get(i + 2) : spacer();
            deckGrid.addView(row(a, b, c));
        }
    }

    /** Spotlight（⌘Space）でアプリ名を打って起動する。URLはブラウザを開いて新しいタブで入力 */
    private void launch(JSONObject o) {
        if (!ready()) return;
        final String type = o.optString("type", "app");
        final String value = o.optString("value", "").trim();
        final String browser = prefs.getString("browser", "Safari");
        if (value.isEmpty()) return;
        toast(o.optString("name") + " を起動");
        new Thread(() -> {
            try {
                hid.key(0, Keymap.LANG2_EISU);      // 日本語入力をオフ（英数）
                Thread.sleep(80);
                if ("url".equals(type)) {
                    spotlight(browser);
                    Thread.sleep(1300);
                    hid.key(HidMac.MOD_CMD, Keymap.KEY_T);
                    Thread.sleep(350);
                    String url = value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") ? value : "https://" + value;
                    hid.type(url);
                    Thread.sleep(120);
                    hid.key(0, Keymap.ENTER);
                } else {
                    spotlight(value);
                }
            } catch (InterruptedException ignored) { }
        }).start();
    }

    private void spotlight(String name) throws InterruptedException {
        hid.key(HidMac.MOD_CMD, Keymap.SPACE);
        Thread.sleep(450);
        hid.type(name);
        Thread.sleep(700);
        hid.key(0, Keymap.ENTER);
    }

    private void editItem(int idx) {
        final JSONArray deck = loadDeck();
        final JSONObject o = idx >= 0 ? deck.optJSONObject(idx) : item("", "app", "", 1);
        final String[] type = {o.optString("type", "app")};
        final int[] color = {o.optInt("color", 1)};

        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        int pd = (int) (20 * d);
        f.setPadding(pd, (int) (8 * d), pd, 0);

        EditText name = input("名前（例：YouTube）", o.optString("name"));
        f.addView(fieldLabel("名前"));
        f.addView(name);

        f.addView(fieldLabel("種類"));
        final TextView appBtn = pill("アプリ", 0, GRAY, false);
        final TextView urlBtn = pill("サイト", 0, GRAY, false);
        final EditText value = input("", o.optString("value"));
        final TextView hint = label("", 11.5f, 0xFF55555C, false);
        Runnable refresh = () -> {
            boolean app = "app".equals(type[0]);
            styleToggle(appBtn, app, GRAY);
            styleToggle(urlBtn, !app, GRAY);
            value.setHint(app ? "例：Safari" : "例：https://www.youtube.com");
            value.setInputType(app ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            hint.setText(app
                    ? "Spotlightで探す名前を英字で（Safari、Notes、Terminal、System Settings など）"
                    : "ブラウザ（" + prefs.getString("browser", "Safari") + "）の新しいタブで開きます");
        };
        appBtn.setOnClickListener(v -> { type[0] = "app"; refresh.run(); });
        urlBtn.setOnClickListener(v -> { type[0] = "url"; refresh.run(); });
        f.addView(row(appBtn, urlBtn));

        f.addView(fieldLabel("アプリ名 / URL"));
        f.addView(value);
        f.addView(hint);

        f.addView(fieldLabel("色"));
        LinearLayout swatches = new LinearLayout(this);
        final List<View> sw = new ArrayList<>();
        for (int i = 0; i < PALETTE.length; i++) {
            final int ci = i;
            View s = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (34 * d), (int) (34 * d));
            lp.rightMargin = (int) (8 * d);
            s.setOnClickListener(v -> {
                color[0] = ci;
                for (int k = 0; k < sw.size(); k++) {
                    sw.get(k).setBackground(round(PALETTE[k], 999, k == ci ? 0xFFFFFFFF : LINE));
                }
            });
            s.setBackground(round(PALETTE[i], 999, i == color[0] ? 0xFFFFFFFF : LINE));
            sw.add(s);
            swatches.addView(s, lp);
        }
        f.addView(swatches);

        TextView br = fieldLabel("サイトを開くブラウザ（全ボタン共通）");
        EditText browser = input("Safari", prefs.getString("browser", "Safari"));
        f.addView(br);
        f.addView(browser);
        refresh.run();

        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        AlertDialog.Builder bld = dialog()
                .setTitle(idx >= 0 ? "ボタンを編集" : "ボタンを追加")
                .setView(sv)
                .setPositiveButton("保存", (dlg, w) -> {
                    String v = value.getText().toString().trim();
                    String n = name.getText().toString().trim();
                    if (v.isEmpty()) return;
                    if (!v.matches("\\p{ASCII}*")) toast("アプリ名・URLは英字で入力してください（Macへはキー入力で送るため）");
                    try {
                        o.put("name", n.isEmpty() ? v : n);
                        o.put("type", type[0]);
                        o.put("value", v);
                        o.put("color", color[0]);
                        if (idx < 0) deck.put(o);
                    } catch (Exception ignored) { }
                    String b = browser.getText().toString().trim();
                    prefs.edit().putString("browser", b.isEmpty() ? "Safari" : b).apply();
                    saveDeck(deck);
                    renderDeck();
                })
                .setNegativeButton("キャンセル", null);
        if (idx >= 0) {
            bld.setNeutralButton("削除", (dlg, w) -> {
                deck.remove(idx);
                saveDeck(deck);
                renderDeck();
            });
        }
        bld.show();
    }

    // ================= 部品 =================

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void warnNonAscii() {
        long now = System.currentTimeMillis();
        if (now - lastWarn < 3000) return;
        lastWarn = now;
        toast("英字以外は送れません。日本語はMacを「かな」にしてローマ字で打ってください");
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView fieldLabel(String s) {
        TextView t = label(s, 12, SUB, true);
        t.setPadding(0, (int) (12 * d), 0, (int) (6 * d));
        return t;
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF55555C);
        e.setSingleLine(true);
        e.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        e.setBackground(round(new int[]{0xFF0E0E10, 0xFF0E0E10}, 12, LINE));
        return e;
    }

    private View section(String s, int iconRes) {
        TextView t = label(s, 12, SUB, true);
        Drawable ic = getDrawable(iconRes);
        if (ic != null) {
            ic = ic.mutate();
            ic.setTint(SUB);
            int sz = (int) (15 * d);
            ic.setBounds(0, 0, sz, sz);
            t.setCompoundDrawables(ic, null, null, null);
            t.setCompoundDrawablePadding((int) (6 * d));
        }
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding((int) (2 * d), (int) (16 * d), 0, (int) (8 * d));
        return t;
    }

    /** 角丸＋単色グラデーション（左上が明るく右下が暗い）＋押した時の波紋 */
    private Drawable round(int[] colors, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(radiusDp * d);
        if (stroke != 0) g.setStroke((int) Math.max(1, d), stroke);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }

    private TextView pill(String text, int iconRes, int[] colors, boolean vertical) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13.5f);
        boolean colored = colors != GRAY;
        boolean white = colors == WHITE;
        int fg = white ? BG : TEXT;
        t.setTextColor(fg);
        t.setTypeface(colored ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setBackground(round(colors, 18, colored ? 0 : LINE));
        t.setPadding((int) (12 * d), (int) (9 * d), (int) (12 * d), (int) (9 * d));
        t.setClickable(true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (iconRes != 0) {
            Drawable ic = getDrawable(iconRes);
            if (ic != null) {
                ic = ic.mutate();
                ic.setTint(fg);
                int sz = (int) ((vertical ? 22 : 17) * d);
                ic.setBounds(0, 0, sz, sz);
                if (vertical) t.setCompoundDrawables(null, ic, null, null);
                else t.setCompoundDrawables(ic, null, null, null);
                t.setCompoundDrawablePadding((int) ((vertical ? 4 : 6) * d));
            }
        }
        return t;
    }

    private void styleToggle(TextView t, boolean on, int[] offColors) {
        int fg = on ? BG : TEXT;
        t.setBackground(round(on ? WHITE : offColors, 18, on ? 0 : LINE));
        t.setTextColor(fg);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        for (Drawable dr : t.getCompoundDrawables()) if (dr != null) dr.setTint(fg);
    }

    /** アイコン＋文字の大きめボタン */
    private TextView key(String text, int iconRes, int[] colors, Runnable action) {
        TextView t = pill(text, iconRes, colors, true);
        t.setTextSize(12.5f);
        t.setMinHeight((int) (62 * d));
        t.setOnClickListener(v -> {
            haptic(v);
            if (ready()) action.run();
        });
        return t;
    }

    private TextView small(String text, int[] colors, Runnable action) {
        TextView t = pill(text, 0, colors, false);
        t.setTextSize(14);
        t.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        t.setOnClickListener(v -> {
            haptic(v);
            if (ready()) action.run();
        });
        return t;
    }

    private TextView icon(int iconRes, Runnable action) {
        TextView t = pill("", iconRes, GRAY, false);
        t.setCompoundDrawablePadding(0);
        t.setPadding(0, (int) (11 * d), 0, (int) (11 * d));
        t.setOnClickListener(v -> {
            haptic(v);
            if (ready()) action.run();
        });
        return t;
    }

    private View spacer() {
        return new View(this);
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        int gap = (int) (8 * d);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = gap;
            r.addView(views[i], lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = gap;
        r.setLayoutParams(rp);
        return r;
    }

    private interface FloatSetter { void set(float v); }

    private View slider(String name, float value, float min, float max, FloatSetter setter) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = label(name, 13, SUB, false);
        r.addView(n, new LinearLayout.LayoutParams((int) (80 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round((value - min) / (max - min) * 100));
        sb.setProgressTintList(ColorStateList.valueOf(TEXT));
        sb.setThumbTintList(ColorStateList.valueOf(TEXT));
        TextView v = label(String.format(java.util.Locale.ROOT, "%.1f", value), 13, TEXT, false);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float val = min + (max - min) * p / 100f;
                v.setText(String.format(java.util.Locale.ROOT, "%.1f", val));
                if (fromUser) setter.set(val);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        r.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams((int) (36 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }
}
