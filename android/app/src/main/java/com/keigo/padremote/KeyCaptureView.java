package com.keigo.padremote;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

/**
 * タップするとソフトキーボードが出て、打った文字をそのまま Mac へ送る入力欄。
 * 実物のキーボードと同じく「押したキー」を送るので、日本語は Mac 側の日本語入力（ローマ字）で変換する。
 */
public class KeyCaptureView extends TextView {

    public interface Listener {
        void onText(String s);
        void onKey(int usage);  // HID キーコード（Backspace・Enter・矢印など）
    }

    private Listener listener;

    public KeyCaptureView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setOnClickListener(v -> showKeyboard());
    }

    public void setListener(Listener l) { listener = l; }

    public void showKeyboard() {
        requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    public void hideKeyboard() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        clearFocus();
    }

    @Override
    public boolean onCheckIsTextEditor() { return true; }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        // 予測変換・まとめて入力をさせないため「パスワード表示」扱いにする（1文字ずつ届く）
        out.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            private CharSequence composing = "";

            @Override
            public boolean commitText(CharSequence t, int pos) {
                composing = "";
                if (listener != null && t != null && t.length() > 0) listener.onText(t.toString());
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence t, int pos) {
                composing = t == null ? "" : t;   // 変換中の文字は確定まで送らない
                return true;
            }

            @Override
            public boolean finishComposingText() {
                if (composing.length() > 0 && listener != null) listener.onText(composing.toString());
                composing = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                if (listener != null) {
                    for (int i = 0; i < before; i++) listener.onKey(Keymap.BACKSPACE);
                    for (int i = 0; i < after; i++) listener.onKey(Keymap.DELETE);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent e) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) handleKey(e.getKeyCode(), e);
                return true;
            }

            @Override
            public boolean performEditorAction(int action) {
                if (listener != null) listener.onKey(Keymap.ENTER);
                return true;
            }
        };
    }

    // 物理キーボードをつないだ場合もそのまま送る
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (handleKey(keyCode, e)) return true;
        return super.onKeyDown(keyCode, e);
    }

    private boolean handleKey(int keyCode, KeyEvent e) {
        if (listener == null) return false;
        int usage;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: usage = Keymap.BACKSPACE; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: usage = Keymap.DELETE; break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: usage = Keymap.ENTER; break;
            case KeyEvent.KEYCODE_TAB: usage = Keymap.TAB; break;
            case KeyEvent.KEYCODE_ESCAPE: usage = Keymap.ESC; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: usage = Keymap.LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: usage = Keymap.RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: usage = Keymap.UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: usage = Keymap.DOWN; break;
            case KeyEvent.KEYCODE_SPACE: usage = Keymap.SPACE; break;
            default: {
                int ch = e == null ? 0 : e.getUnicodeChar();
                if (ch > 0) {
                    listener.onText(String.valueOf((char) ch));
                    return true;
                }
                return false;
            }
        }
        listener.onKey(usage);
        return true;
    }
}
