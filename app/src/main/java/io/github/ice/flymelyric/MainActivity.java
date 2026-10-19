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
import android.net.Uri;
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
    private TextView tvSong, tvLine, tvStatus, tvVersion;
    private static final String RELEASES_URL = "https://github.com/ArciCE0721/flyme-lyric/releases/latest";
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
        tvVersion = findViewById(R.id.tvVersion);
        final String curVer = versionName();
        tvVersion.setText("v" + curVer + " · 检查更新");
        tvVersion.setOnClickListener(v -> checkUpdate(false));
        checkUpdate(true);
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


    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** 静默检查更新：拉 GitHub 最新 Release，比较版本号。 */
    private void checkUpdate(final boolean silent) {
        new Thread(new Runnable() {
            @Override public void run() {
                // 主：GitHub API（实时）；失败（常见原因是共享 IP 被限流 403）回退 jsdelivr 的 version.json
                String tag = fetchField(
                        "https://api.github.com/repos/ArciCE0721/flyme-lyric/releases/latest", "tag_name");
                if (tag == null || tag.isEmpty()) {
                    tag = fetchField(
                            "https://cdn.jsdelivr.net/gh/ArciCE0721/flyme-lyric@main/version.json", "version");
                    if (tag != null && !tag.isEmpty()) tag = "v" + tag;
                }
                final String latest = tag;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (latest == null || latest.isEmpty()) {
                            if (!silent) Toast.makeText(MainActivity.this,
                                    "检查更新失败，请检查网络", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (isNewer(latest, versionName())) {
                            tvVersion.setText("发现新版本 " + latest + " · 点击更新");
                            if (!silent) showUpdateDialog(latest);
                        } else if (!silent) {
                            Toast.makeText(MainActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }


    /** 拉一个 JSON 接口并取指定字段，失败返回 null。 */
    private static String fetchField(String url, String field) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestProperty("User-Agent", "FlymeLyric");
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new org.json.JSONObject(bos.toString("UTF-8")).optString(field, "");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void showUpdateDialog(final String tag) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 " + tag)
                .setMessage("是否前往 GitHub 下载？")
                .setPositiveButton("去下载", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)));
                    } catch (Exception ignored) { }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private static boolean isNewer(String tag, String current) {
        int[] a = parseVer(tag), b = parseVer(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parseVer(String s) {
        String[] p = (s == null ? "" : s.replaceAll("[^0-9.]", "")).split("\\.");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try { r[i] = Integer.parseInt(p[i]); } catch (Exception e) { r[i] = 0; }
        }
        return r;
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
