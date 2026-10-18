package io.github.ice.flymelyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 AMLL / Apple Music 的 TTML 逐字歌词。
 * 只取每行 <p> 的起始时间和正文，忽略翻译(x-translation)与音译(x-roman)，
 * 否则同一句会显示两遍。
 */
public class Ttml implements LyricDoc {

    private final List<Lrc.Line> lines = new ArrayList<>();

    private static final Pattern P_TAG = Pattern.compile("<p\\b([^>]*)>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern BEGIN = Pattern.compile("begin=\"([^\"]+)\"");
    private static final Pattern AUX = Pattern.compile(
            "<span[^>]*ttm:role=\"x-(?:translation|roman)\"[^>]*>.*?</span>", Pattern.DOTALL);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    public Ttml(String raw) {
        if (raw == null) return;
        Matcher m = P_TAG.matcher(raw);
        while (m.find()) {
            Matcher b = BEGIN.matcher(m.group(1));
            if (!b.find()) continue;
            long time = parseTime(b.group(1));
            String inner = AUX.matcher(m.group(2)).replaceAll("");
            String text = unescape(ANY_TAG.matcher(inner).replaceAll(""));
            text = text.replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) lines.add(new Lrc.Line(time, text));
        }
        lines.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Override
    public String lineAt(long posMs) {
        String cur = null;
        for (Lrc.Line l : lines) {
            if (l.timeMs <= posMs) cur = l.text;
            else break;
        }
        return cur;
    }

    private static long parseTime(String s) {
        try {
            String[] parts = s.trim().split(":");
            double total = 0;
            for (String p : parts) total = total * 60 + Double.parseDouble(p);
            return (long) (total * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }
}
