package com.shizuku.ai;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Siri风格动态球体 - 纯Canvas绘制，移植自 BallApp SiriBall.kt
 */
public class SiriBallView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 动画值
    private float rotation = 0f;          // 0-360, 15s周期
    private float breathe = 0.975f;       // 0.95-1.0, 3s周期
    private float[] rippleScales = {1.0f, 1.0f, 1.0f};  // 1.0-1.2
    private float[] rippleAlphas = {0.5f, 0.5f, 0.5f};  // 0.5-0.0

    private final int mainColor = 0xFF0A84FF;
    private final int accent1 = 0xFFBF5AF2;
    private final int accent2 = 0xFFFF375F;
    private final int accent3 = 0xFF00D4FF;

    private final ValueAnimator rotAnim;
    private final ValueAnimator breatheAnim;
    private final ValueAnimator[] rippleAnims = new ValueAnimator[3];
    private final ValueAnimator[] rippleAlphaAnims = new ValueAnimator[3];

    public SiriBallView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        // 旋转动画 0->360, 15s
        rotAnim = ValueAnimator.ofFloat(0f, 360f);
        rotAnim.setDuration(15000);
        rotAnim.setRepeatCount(ValueAnimator.INFINITE);
        rotAnim.setInterpolator(new LinearInterpolator());
        rotAnim.addUpdateListener(a -> rotation = (float) a.getAnimatedValue());
        rotAnim.start();

        // 呼吸动画 0.95->1.0, 3s
        breatheAnim = ValueAnimator.ofFloat(0.95f, 1.0f);
        breatheAnim.setDuration(3000);
        breatheAnim.setRepeatCount(ValueAnimator.INFINITE);
        breatheAnim.setRepeatMode(ValueAnimator.REVERSE);
        breatheAnim.setInterpolator(new PathInterpolator(0.33f, 0.0f, 0.1f, 1.0f));
        breatheAnim.addUpdateListener(a -> breathe = (float) a.getAnimatedValue());
        breatheAnim.start();

        // 3个波纹动画：scale 1.0->1.2, alpha 0.5->0.0, 各2.5s, stagger 833ms
        long[] delays = {0, 833, 1666};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            // Scale
            rippleAnims[i] = ValueAnimator.ofFloat(1.0f, 1.2f);
            rippleAnims[i].setDuration(2500);
            rippleAnims[i].setRepeatCount(ValueAnimator.INFINITE);
            rippleAnims[i].setInterpolator(new PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f));
            rippleAnims[i].setStartDelay(delays[i]);
            rippleAnims[i].addUpdateListener(a -> rippleScales[idx] = (float) a.getAnimatedValue());
            rippleAnims[i].start();

            // Alpha
            rippleAlphaAnims[i] = ValueAnimator.ofFloat(0.5f, 0.0f);
            rippleAlphaAnims[i].setDuration(2500);
            rippleAlphaAnims[i].setRepeatCount(ValueAnimator.INFINITE);
            rippleAlphaAnims[i].setInterpolator(new LinearInterpolator());
            rippleAlphaAnims[i].setStartDelay(delays[i]);
            rippleAlphaAnims[i].addUpdateListener(a -> rippleAlphas[idx] = (float) a.getAnimatedValue());
            rippleAlphaAnims[i].start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;

        // 音波 - 3层脉冲波纹
        for (int i = 0; i < 3; i++) {
            float rippleR = r * rippleScales[i];
            int alphaColor = i == 0 ? accent3 : (i == 1 ? accent1 : mainColor);
            paint.setShader(new RadialGradient(cx, cy,
                    rippleR,
                    new int[]{Color.TRANSPARENT, (alphaColor & 0x00FFFFFF) | ((int)(rippleAlphas[i] * (i == 0 ? 64 : (i == 1 ? 51 : 38))) << 24), Color.TRANSPARENT},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            paint.setAlpha(255);
            canvas.drawCircle(cx, cy, rippleR, paint);
        }

        // 光晕
        float glowR = r * breathe * 0.7f;
        paint.setShader(new RadialGradient(cx, cy, glowR,
                new int[]{mainColor & 0x4CFFFFFF, accent1 & 0x33FFFFFF, Color.TRANSPARENT},
                new float[]{0f, 0.3f, 1f},
                Shader.TileMode.CLAMP));
        paint.setAlpha(255);
        canvas.drawCircle(cx, cy, glowR, paint);

        // 彩色光斑 (4个围绕中心旋转)
        float[] blobAngles = {rotation, rotation + 90, rotation + 180, rotation + 270};
        int[] blobColors = {mainColor, accent1, accent2, accent3};
        float[] blobDists = {0.2f, 0.25f, 0.22f, 0.23f};
        float[] blobSizes = {0.7f, 0.65f, 0.6f, 0.68f};

        for (int i = 0; i < 4; i++) {
            float rad = (float) Math.toRadians(blobAngles[i]);
            float dist = r * blobDists[i] * breathe;
            float bx = cx + (float) Math.cos(rad) * dist;
            float by = cy + (float) Math.sin(rad) * dist;
            float br = r * blobSizes[i];

            paint.setShader(new RadialGradient(bx, by, br,
                    new int[]{
                            (blobColors[i] & 0x00FFFFFF) | 0xCC000000,
                            (blobColors[i] & 0x00FFFFFF) | 0x80000000,
                            (blobColors[i] & 0x00FFFFFF) | 0x33000000,
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.3f, 0.6f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(bx, by, br, paint);
        }

        // 核心
        float coreR = r * 0.65f * breathe;
        paint.setShader(new RadialGradient(cx, cy, coreR,
                new int[]{
                        0xB3FFFFFF,
                        mainColor & 0x99FFFFFF,
                        accent1 & 0x80FFFFFF,
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.4f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR, paint);

        // 高光
        float hx = cx - r * 0.25f;
        float hy = cy - r * 0.25f;
        float hr = r * 0.35f;
        paint.setShader(new RadialGradient(hx, hy, hr,
                new int[]{0x80FFFFFF, 0x40FFFFFF, Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(hx, hy, hr, paint);

        // 边界
        paint.setShader(new RadialGradient(cx, cy, r * breathe,
                new int[]{Color.TRANSPARENT, 0x0DFFFFFF, 0x26FFFFFF, Color.TRANSPARENT},
                new float[]{0f, 0.5f, 0.8f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r * breathe, paint);

        // 清除shader
        paint.setShader(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        rotAnim.cancel();
        breatheAnim.cancel();
        for (int i = 0; i < 3; i++) {
            rippleAnims[i].cancel();
            rippleAlphaAnims[i].cancel();
        }
    }
}
