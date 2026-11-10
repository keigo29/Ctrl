package com.keigo.padremote;

/** 文字 → HID キーコード（US 配列）。Mac では「ANSI（US）」キーボードとして設定すること */
public final class Keymap {
    private Keymap() { }

    public static final int ENTER = 0x28, ESC = 0x29, BACKSPACE = 0x2A, TAB = 0x2B, SPACE = 0x2C;
    public static final int RIGHT = 0x4F, LEFT = 0x50, DOWN = 0x51, UP = 0x52;
    public static final int DELETE = 0x4C, F11 = 0x44;
    public static final int LANG1_KANA = 0x90, LANG2_EISU = 0x91;   // JIS の「かな」「英数」
    public static final int KEY_Q = 0x14, KEY_L = 0x0F, KEY_T = 0x17, KEY_OPEN_BRACKET = 0x2F;

    // Consumer Page（メディアキー）
    public static final int MEDIA_PLAY_PAUSE = 0xCD, MEDIA_NEXT = 0xB5, MEDIA_PREV = 0xB6;
    public static final int MEDIA_VOL_UP = 0xE9, MEDIA_VOL_DOWN = 0xEA, MEDIA_MUTE = 0xE2;

    private static final String SHIFT_DIGITS = ")!@#$%^&*(";
    private static final String PLAIN_SYM = "-=[]\\;'`,./";
    private static final String SHIFT_SYM = "_+{}|:\"~<>?";
    private static final int[] SYM_CODES = {0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};

    /** @return {修飾キー, キーコード}。打てない文字は null */
    public static int[] forChar(char c) {
        if (c >= 'a' && c <= 'z') return new int[]{0, 0x04 + (c - 'a')};
        if (c >= 'A' && c <= 'Z') return new int[]{HidMac.MOD_SHIFT, 0x04 + (c - 'A')};
        if (c >= '1' && c <= '9') return new int[]{0, 0x1E + (c - '1')};
        if (c == '0') return new int[]{0, 0x27};
        int i = SHIFT_DIGITS.indexOf(c);
        if (i >= 0) return new int[]{HidMac.MOD_SHIFT, i == 0 ? 0x27 : 0x1E + (i - 1)};
        i = PLAIN_SYM.indexOf(c);
        if (i >= 0) return new int[]{0, SYM_CODES[i]};
        i = SHIFT_SYM.indexOf(c);
        if (i >= 0) return new int[]{HidMac.MOD_SHIFT, SYM_CODES[i]};
        switch (c) {
            case ' ': return new int[]{0, SPACE};
            case '\n': return new int[]{0, ENTER};
            case '\t': return new int[]{0, TAB};
            default: return null;
        }
    }
}
