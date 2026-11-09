package com.keigo.padremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** 画面に重ねて表示するマウスカーソル（中心がクリック位置） */
public class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean dragging;

    public CursorView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        fill.setColor(Color.argb(150, 255, 255, 255));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * d);
        stroke.setColor(Color.argb(230, 20, 20, 20));
        dot.setColor(Color.argb(255, 20, 20, 20));
    }

    public void setDragging(boolean v) {
        dragging = v;
        fill.setColor(v ? Color.argb(190, 255, 152, 0) : Color.argb(150, 255, 255, 255));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f - stroke.getStrokeWidth();
        c.drawCircle(w / 2f, h / 2f, r, fill);
        c.drawCircle(w / 2f, h / 2f, r, stroke);
        c.drawCircle(w / 2f, h / 2f, r * (dragging ? 0.35f : 0.18f), dot);
    }
}
