package com.keigo.padremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * トラックパッド。
 * 1本指：移動 / タップ：クリック / 長押し・2本指タップ：右クリック / 2本指・右端の帯：スクロール
 * 指が触れてから少し動くまではカーソルを動かさない（タップ時にクリック位置がずれないように）。
 */
public class TrackpadView extends View {

    public interface Listener {
        void onMove(float dx, float dy);     // dp 単位
        void onScroll(float dx, float dy);   // dp 単位（指の動き）
        void onTap();
        void onRightClick();
    }

    private final float d;                 // density
    private final float slop;              // これ以上動いたら「移動」とみなす
    private final float stripW;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF strip = new RectF();
    private Drawable mark, stripIcon;
    private boolean dragging;

    // タッチ状態
    private long downTime;
    private int maxPointers;
    private float lastX, lastY, pendX, pendY, dist;
    private boolean moving, longFired, inStrip;
    private final float[] px = new float[5], py = new float[5];
    private int pcount;

    public TrackpadView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        slop = 6 * d;
        stripW = 44 * d;
        bg.setColor(Color.rgb(17, 17, 19));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1 * d);
        border.setColor(Color.rgb(42, 42, 46));
        stripPaint.setColor(Color.argb(10, 255, 255, 255));
        stripBorder.setStyle(Paint.Style.STROKE);
        stripBorder.setStrokeWidth(1 * d);
        stripBorder.setColor(Color.rgb(44, 44, 49));
        stripBorder.setPathEffect(new DashPathEffect(new float[]{4 * d, 4 * d}, 0));
        text.setColor(Color.rgb(85, 85, 92));
        text.setTextSize(12 * d);
        text.setTextAlign(Paint.Align.CENTER);
        dotRing.setStyle(Paint.Style.STROKE);
        dotRing.setStrokeWidth(1 * d);
        dotRing.setColor(Color.argb(46, 255, 255, 255));
        mark = c.getDrawable(R.drawable.lc_mouse_pointer_2);
        if (mark != null) mark.mutate().setTint(Color.rgb(38, 38, 43));
        stripIcon = c.getDrawable(R.drawable.lc_chevrons_up_down);
        if (stripIcon != null) stripIcon.mutate().setTint(Color.rgb(74, 74, 82));
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setDragging(boolean v) {
        dragging = v;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bg.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(29, 29, 33), Color.rgb(17, 17, 19), Shader.TileMode.CLAMP));
    }

    // ================= 描画 =================

    @Override
    protected void onDraw(Canvas c) {
        float r = 24 * d;
        rect.set(d, d, getWidth() - d, getHeight() - d);
        c.drawRoundRect(rect, r, r, bg);
        if (dragging) {
            border.setColor(Color.rgb(242, 242, 243));
            border.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
        } else {
            border.setColor(maxPointers > 0 ? Color.rgb(74, 74, 82) : Color.rgb(42, 42, 46));
            border.setPathEffect(null);
        }
        c.drawRoundRect(rect, r, r, border);

        // 中央のマーク
        if (mark != null) {
            int s = (int) (64 * d);
            int cx = (int) ((getWidth() - stripW) / 2), cy = getHeight() / 2;
            mark.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            mark.draw(c);
        }

        // 右端のスクロール帯
        strip.set(getWidth() - stripW - 8 * d, 12 * d, getWidth() - 8 * d, getHeight() - 12 * d);
        stripPaint.setColor(inStrip && maxPointers > 0 ? Color.argb(24, 255, 255, 255) : Color.argb(10, 255, 255, 255));
        c.drawRoundRect(strip, stripW / 2, stripW / 2, stripPaint);
        c.drawRoundRect(strip, stripW / 2, stripW / 2, stripBorder);
        if (stripIcon != null) {
            int s = (int) (18 * d);
            int cx = (int) strip.centerX(), cy = (int) strip.centerY();
            stripIcon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            stripIcon.draw(c);
        }

        // ガイド
        float gy = getHeight() - 16 * d;
        String g1 = "1本指：移動　タップ：クリック　長押し・2本指タップ：右クリック　2本指／右端：スクロール";
        String g2 = "タップ：クリック　長押し：右クリック　2本指：スクロール";
        float avail = getWidth() - stripW - 40 * d;
        String guide = text.measureText(g1) <= avail ? g1 : text.measureText(g2) <= avail ? g2 : null;
        if (guide != null) c.drawText(guide, (getWidth() - stripW) / 2f, gy, text);

        // 指の位置
        for (int i = 0; i < pcount; i++) {
            float rr = 22 * d;
            dot.setShader(new RadialGradient(px[i], py[i], rr,
                    Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
            c.drawCircle(px[i], py[i], rr, dot);
            c.drawCircle(px[i], py[i], rr, dotRing);
        }
    }

    // ================= タッチ =================

    private final Runnable longPress = () -> {
        if (maxPointers == 1 && !moving && !inStrip && listener != null) {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onRightClick();
        }
    };

    private void captureTouches(MotionEvent e) {
        pcount = Math.min(e.getPointerCount(), px.length);
        for (int i = 0; i < pcount; i++) { px[i] = e.getX(i); py[i] = e.getY(i); }
    }

    private float avgX(MotionEvent e) {
        float s = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) s += e.getX(i);
        return s / n;
    }

    private float avgY(MotionEvent e) {
        float s = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) s += e.getY(i);
        return s / n;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return true;
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downTime = SystemClock.uptimeMillis();
                maxPointers = 1;
                lastX = e.getX();
                lastY = e.getY();
                pendX = pendY = dist = 0;
                moving = longFired = false;
                inStrip = e.getX() > getWidth() - stripW - 16 * d;
                handler.postDelayed(longPress, 500);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP: {
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    maxPointers = Math.max(maxPointers, e.getPointerCount());
                    handler.removeCallbacks(longPress);
                }
                // 指の本数が変わったら基準点を取り直す（離れる指を除いた平均）
                int skip = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
                float sx = 0, sy = 0;
                int n = 0;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (i == skip) continue;
                    sx += e.getX(i);
                    sy += e.getY(i);
                    n++;
                }
                if (n > 0) { lastX = sx / n; lastY = sy / n; }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                float x = avgX(e), y = avgY(e);
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                dist += (float) Math.hypot(dx, dy);
                if (inStrip && maxPointers == 1) {
                    listener.onScroll(0, dy / d);
                } else if (maxPointers >= 2) {
                    if (e.getPointerCount() >= 2) listener.onScroll(dx / d, dy / d);
                } else if (!longFired) {
                    if (!moving) {
                        pendX += dx;
                        pendY += dy;
                        if (dist > slop) {
                            moving = true;
                            handler.removeCallbacks(longPress);
                            listener.onMove(pendX / d, pendY / d);
                        }
                    } else {
                        listener.onMove(dx / d, dy / d);
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPress);
                long dur = SystemClock.uptimeMillis() - downTime;
                if (action == MotionEvent.ACTION_UP && !inStrip) {
                    if (maxPointers >= 2) {
                        if (dist < 14 * d && dur < 300) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            listener.onRightClick();
                        }
                    } else if (!moving && !longFired && dur < 350) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        listener.onTap();
                    }
                }
                maxPointers = 0;
                pcount = 0;
                invalidate();
                return true;
            }
        }
        captureTouches(e);
        if (action == MotionEvent.ACTION_POINTER_UP) {
            // 離れた指の点は消す
            int idx = e.getActionIndex();
            for (int i = idx; i < pcount - 1; i++) { px[i] = px[i + 1]; py[i] = py[i + 1]; }
            pcount = Math.max(0, pcount - 1);
        }
        invalidate();
        return true;
    }
}
