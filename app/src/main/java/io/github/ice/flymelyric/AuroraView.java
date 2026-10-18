package io.github.ice.flymelyric;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Color;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 类似 Apple Music 歌词页的动态光晕：几团大范围径向渐变缓慢漂移。
 */
public class AuroraView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] baseColors = {0x66FF7A59, 0x664D6BFE, 0x5AB84DFF};
    private final int[] shifted = new int[3];
    private float phase;

    public AuroraView(Context context) {
        super(context);
    }

    public AuroraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AuroraView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setColors(int c1, int c2, int c3) {
        baseColors[0] = c1;
        baseColors[1] = c2;
        baseColors[2] = c3;
        setPhase(phase);
    }

    public void setPhase(float p) {
        phase = p;
        // 颜色缓慢偏移，像 Apple Music 那样整体色调在流动
        float shift = (float) Math.sin(p * Math.PI * 2) * 26f;
        for (int i = 0; i < 3; i++) {
            int c = baseColors[i];
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[0] = (hsv[0] + shift + 360f) % 360f;
            shifted[i] = Color.HSVToColor(Color.alpha(c), hsv);
        }
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setPhase(phase);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.max(w, h) * 0.62f;
        for (int i = 0; i < 3; i++) {
            double a = phase * Math.PI * 2 + i * 2.1;
            float x = cx + (float) Math.sin(a) * w * 0.28f;
            float y = cy + (float) Math.cos(a * 0.8) * h * 0.22f;
            paint.setShader(new RadialGradient(x, y, radius, shifted[i], 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
    }
}
