package io.github.ice.flymelyric;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.animation.LinearInterpolator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;




import com.google.android.material.materialswitch.MaterialSwitch;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvSong, tvLine, tvStatus;
    private ImageView bgImage;
    private MaterialSwitch swIcon;
    private AuroraView aurora;
    private ValueAnimator auroraAnim;
    private ObjectAnimator kenBurns;
    private int shownArtVersion = -1;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String song = State.playing ? (State.title + " - " + State.artist) : "未在播放";
            tvSong.setText(song);
            tvLine.setText(State.line == null || State.line.isEmpty() ? "—" : State.line);
            tvStatus.setText(State.connected ? State.status : "未授权 · 请开启通知使用权");

            if (State.art != null && State.artVersion != shownArtVersion) {
                shownArtVersion = State.artVersion;
                bgImage.setImageBitmap(State.art);
                bgImage.setVisibility(View.VISIBLE);
                applyBlur();
                updateAuroraColors();
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        tvSong = findViewById(R.id.tvSong);
        tvLine = findViewById(R.id.tvLine);
        tvStatus = findViewById(R.id.tvStatus);
        bgImage = findViewById(R.id.bgImage);
        findViewById(R.id.cardLyric).setOnLongClickListener(v -> {
            State.reloadRequested = true;
            Toast.makeText(this, "正在重新获取歌词…", Toast.LENGTH_SHORT).show();
            return true;
        });
        aurora = findViewById(R.id.aurora);
        swIcon = findViewById(R.id.swIcon);
        swIcon.setChecked(getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("show_icon", true));
        swIcon.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putBoolean("show_icon", checked).apply());
        Button btn = findViewById(R.id.btnAccess);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                } catch (Exception ignored) { }
            }
        });
    }

    private void updateAuroraColors() {
        if (State.art == null) return;
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createScaledBitmap(State.art, 4, 4, true);
            aurora.setColors(boost(b.getPixel(0, 0), 0x60), boost(b.getPixel(3, 1), 0x58),
                    boost(b.getPixel(1, 3), 0x50));
        } catch (Throwable ignored) { }
    }

    private int boost(int c, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.3f + 0.15f);
        hsv[2] = Math.max(0.45f, Math.min(1f, hsv[2] * 1.1f));
        int rgb = Color.HSVToColor(hsv);
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    private void startMotion() {
        if (auroraAnim == null) {
            auroraAnim = ValueAnimator.ofFloat(0f, 1f);
            auroraAnim.setDuration(8000);
            auroraAnim.setRepeatCount(ValueAnimator.INFINITE);
            auroraAnim.setInterpolator(new LinearInterpolator());
            auroraAnim.addUpdateListener(a -> aurora.setPhase((float) a.getAnimatedValue()));
            auroraAnim.start();
        } else if (auroraAnim.isPaused()) {
            auroraAnim.resume();
        } else if (!auroraAnim.isStarted()) {
            auroraAnim.start();
        }

        if (State.art != null) {
            if (kenBurns == null) {
                bgImage.setScaleX(1.22f);
                bgImage.setScaleY(1.22f);
                PropertyValuesHolder sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.22f, 1.42f);
                PropertyValuesHolder sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.22f, 1.42f);
                PropertyValuesHolder tx = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -18f, 18f);
                PropertyValuesHolder ty = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 12f, -12f);
                kenBurns = ObjectAnimator.ofPropertyValuesHolder(bgImage, sx, sy, tx, ty);
                kenBurns.setDuration(9000);
                kenBurns.setRepeatCount(ObjectAnimator.INFINITE);
                kenBurns.setRepeatMode(ObjectAnimator.REVERSE);
                kenBurns.setInterpolator(new LinearInterpolator());
                kenBurns.start();
            } else if (kenBurns.isPaused()) {
                kenBurns.resume();
            } else if (!kenBurns.isStarted()) {
                kenBurns.start();
            }
        }
    }

    /** 退到后台时只暂停动画，模糊效果保留，回来时从原来的位置继续。 */
    private void pauseMotion() {
        if (auroraAnim != null && auroraAnim.isRunning()) auroraAnim.pause();
        if (kenBurns != null && kenBurns.isRunning()) kenBurns.pause();
    }

    private void applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bgImage.setRenderEffect(
                    RenderEffect.createBlurEffect(110f, 110f, Shader.TileMode.CLAMP));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (State.art != null) {
            applyBlur();
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        startMotion();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        pauseMotion();
        super.onPause();
    }
}
