package io.github.ice.flymelyric;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.List;

public class LyricListenerService extends NotificationListenerService {

    private static final String TAG = "LyricBridge";
    private static final String CHANNEL = "lyric_status";
    private static final int NID = 42;

    /** Flyme 状态栏歌词：Ticker 一直显示，直到下一次更新 */
    private static final int FLAG_ALWAYS_SHOW_TICKER = 0x1000000;
    /** Flyme 状态栏歌词：只更新 Ticker，不刷新通知其它内容 */
    private static final int FLAG_ONLY_UPDATE_TICKER = 0x2000000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private LyricDoc lrc;
    private String songKey = "";
    private String metaKey = "";
    private String artKey = "";
    private String lastLine = "";
    private long lastPostAt = 0;
    private boolean fetching = false;
    private String fetchingKey = "";
    private boolean lastIconState = true;
    private Icon iconCache;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { step(); } catch (Throwable t) { Log.e(TAG, "tick failed", t); }
            // 播放时 700ms 轮询，空闲时退避到 5s，降低待机功耗
            handler.postDelayed(this, State.playing ? 700 : 5000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "状态栏歌词", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public void onListenerConnected() {
        State.connected = true;
        State.status = "已连接";
        Log.i(TAG, "listener connected");
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override public void onListenerDisconnected() {
        State.connected = false;
        State.status = "通知使用权已断开";
        handler.removeCallbacks(tick);
        cancelNotification();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void step() {
        MediaController c = pickController();
        if (c == null) {
            State.playing = false;
            State.line = "";
            cancelNotification();
            return;
        }
        PlaybackState ps = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        if (md == null) return;

        String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) artist = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist.isEmpty()) artist = str(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String appleId = str(md, "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID");
        if (appleId.isEmpty()) appleId = str(md, MediaMetadata.METADATA_KEY_MEDIA_ID);

        State.title = title;
        State.artist = artist;

        String key = title + "|" + artist;
        if (!key.equals(artKey)) {
            Bitmap bm = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bm != null) {
                artKey = key;
                State.art = bm;
                State.artVersion++;
            }
        }

        if (State.reloadRequested) {
            State.reloadRequested = false;
            LyricsFetcher.invalidate(this, title, artist);
            lrc = null;
            lastLine = "";
            lastPostAt = 0;
            requestLyrics(title, artist, dur, appleId);
            Log.i(TAG, "manual reload: " + title);
        }

        if (!key.equals(songKey)) {
            songKey = key;
            lrc = null;
            lastLine = "";
            lastPostAt = 0;
            // 先撤掉上一首的歌词，避免新歌还没取到时状态栏挂着旧歌词
            cancelNotification();
            requestLyrics(title, artist, dur, appleId);
        }

        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        State.playing = playing;

        long pos = 0;
        if (ps != null) {
            pos = ps.getPosition();
            if (playing) {
                long dt = SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
                if (dt > 0 && dt < 60000) pos += (long) (ps.getPlaybackSpeed() * dt);
            }
        }

        String line = lrc == null ? "" : lrc.lineAt(pos);
        if (line == null) line = "";
        if (line.isEmpty() && !lastLine.isEmpty()) line = lastLine;
        State.line = line;

        if (playing) {
            boolean showIcon = showIcon();
            if (showIcon != lastIconState) {
                // 开关变化后立刻重发，让图标立即出现/消失
                lastIconState = showIcon;
                lastPostAt = 0;
            }
            long now = SystemClock.elapsedRealtime();
            boolean changed = !line.isEmpty() && !line.equals(lastLine);
            if (!line.isEmpty() && (changed || now - lastPostAt >= 20000)) {
                lastLine = line;
                lastPostAt = now;
                post(title, artist, line);
            }
        } else {
            // 暂停：保留最后一句歌词，不撤掉通知
            if (!lastLine.isEmpty() && lastPostAt == 0) {
                lastPostAt = SystemClock.elapsedRealtime();
                post(title, artist, lastLine);
            }
        }
    }

    private MediaController pickController() {
        MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return null;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new android.content.ComponentName(this, LyricListenerService.class));
        } catch (SecurityException e) {
            State.connected = false;
            State.status = "缺少通知使用权";
            return null;
        }
        MediaController fallback = null;
        for (MediaController mc : list) {
            PlaybackState s = mc.getPlaybackState();
            if (s == null) continue;
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                if ("com.apple.android.music".equals(mc.getPackageName())) {
                    controller = mc;
                    return mc;
                }
                if (fallback == null) fallback = mc;
            }
        }
        return fallback != null ? fallback : controller;
    }

    private void requestLyrics(final String title, final String artist, final long dur, final String appleId) {
        final String key = title + "|" + artist;
        // 同一首歌已在请求中才跳过；切歌后必须允许新请求覆盖旧请求
        if (fetching && key.equals(fetchingKey)) return;
        fetching = true;
        fetchingKey = key;
        new Thread(new Runnable() {
            @Override public void run() {
                final String raw = LyricsFetcher.fetch(getApplicationContext(), title, artist, dur, appleId);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (key.equals(fetchingKey)) { fetching = false; fetchingKey = ""; }
                        if (!key.equals(songKey)) return;
                        if (raw == null) {
                            lrc = null;
                            cancelNotification();
                        } else {
                            String t = raw.trim();
                            lrc = t.startsWith("<") ? new Ttml(raw) : new Lrc(raw);
                        }
                        State.status = raw == null ? "未找到歌词" : "歌词已加载";
                        Log.i(TAG, "lyrics applied, empty=" + (lrc == null || lrc.isEmpty()));
                    }
                });
            }
        }).start();
    }

    private Icon amIcon() {
        if (iconCache != null) return iconCache;
        try {
            Drawable d = getPackageManager().getApplicationIcon("com.apple.android.music");
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0) w = 96;
            if (h <= 0) h = 96;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            iconCache = Icon.createWithBitmap(bmp);
        } catch (Throwable t) {
            iconCache = Icon.createWithResource(this, R.drawable.ic_blank);
        }
        return iconCache;
    }

    private void post(String title, String artist, String line) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.MediaStyle style = new Notification.MediaStyle();
        if (controller != null) {
            try {
                style.setMediaSession(controller.getSessionToken());
            } catch (Throwable ignored) { }
        }
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(amIcon())
                .setContentTitle(title)
                .setContentText(line)
                .setTicker(line)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setStyle(style);
        Notification n = b.build();
        // 魅族状态栏歌词的左图标：必须用本应用的资源 id
        if (showIcon()) {
            n.extras.putInt("ticker_icon", R.drawable.ic_note);
            n.extras.putBoolean("ticker_icon_switch", true);
        }
        n.flags |= FLAG_ALWAYS_SHOW_TICKER | FLAG_ONLY_UPDATE_TICKER;
        nm.notify(NID, n);
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NID);
    }

    private boolean showIcon() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("show_icon", true);
    }

    private static String str(MediaMetadata md, String key) {
        CharSequence cs = md.getText(key);
        return cs == null ? "" : cs.toString();
    }
}
