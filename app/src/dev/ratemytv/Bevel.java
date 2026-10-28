package dev.ratemytv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A button face that reads as a physical key, not a CSS outline.
 *
 * The old pill was a flat gradient with a 2dp stroke all the way round, which is what a
 * stylesheet produces: the same weight on every edge, so nothing says which way the light
 * falls. Real controls are lit from above — a brighter top edge, the face falling away to
 * a darker bottom, and a dark lip where the surface meets the body.
 *
 * Four layers, in order: the lip (offset down), the face gradient, a sheen clipped to the
 * top half, and a 1px inner hairline.
 */
public class Bevel extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int topColor, bottomColor, lipColor;
    private final float radius, lift;

    public Bevel(int topColor, int bottomColor, int lipColor, float radius, float lift) {
        this.topColor = topColor;
        this.bottomColor = bottomColor;
        this.lipColor = lipColor;
        this.radius = radius;
        this.lift = lift;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float l = b.left, t = b.top, r = b.right, bo = b.bottom;
        float faceBottom = bo - lift;
        if (r <= l || faceBottom <= t) return;

        // The lip: the body of the key showing under the face.
        paint.setShader(null);
        paint.setColor(lipColor);
        c.drawRoundRect(l, t + lift * 0.55f, r, bo, radius, radius, paint);

        // The face, lit from the top.
        paint.setShader(new LinearGradient(0, t, 0, faceBottom,
                topColor, bottomColor, Shader.TileMode.CLAMP));
        c.drawRoundRect(l, t, r, faceBottom, radius, radius, paint);

        // Sheen across the upper half only — the giveaway that there is a bevel.
        c.save();
        c.clipRect(l, t, r, t + (faceBottom - t) * 0.5f);
        paint.setShader(new LinearGradient(0, t, 0, t + (faceBottom - t) * 0.55f,
                0x3DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(l, t, r, faceBottom, radius, radius, paint);
        c.restore();

        // Inner hairline: catches the edge without drawing a ring around the whole shape.
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(0x40FFFFFF);
        c.drawRoundRect(l + 1, t + 1, r - 1, faceBottom - 1,
                Math.max(1, radius - 1), Math.max(1, radius - 1), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override public void setColorFilter(android.graphics.ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
