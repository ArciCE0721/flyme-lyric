package io.github.ice.flymelyric;

public final class State {
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String line = "";
    public static volatile String status = "等待通知使用权";
    public static volatile boolean playing = false;
    public static volatile boolean connected = false;

    /** 当前歌曲封面，用于界面背景 */
    public static volatile android.graphics.Bitmap art;
    public static volatile int artVersion;

    /** 用户手动要求重新获取当前歌词 */
    public static volatile boolean reloadRequested = false;

    private State() {}
}
