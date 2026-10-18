package io.github.ice.flymelyric;

/** 歌词文档的通用接口：LRC 和 TTML 都实现它。 */
public interface LyricDoc {
    boolean isEmpty();

    /** 返回 posMs 时刻应该显示的那一句。 */
    String lineAt(long posMs);
}
