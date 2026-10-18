package io.github.ice.flymelyric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Lrc implements LyricDoc {

    public static class Line {
        public final long timeMs;
        public final String text;
        Line(long t, String s) { timeMs = t; text = s; }
    }

    private final List<Line> lines = new ArrayList<>();

    private static final Pattern TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    /** [offset:+500] 表示整体提前 500ms（LRC 规范：正值让歌词更早出现） */
    private static final Pattern OFFSET =
            Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    public Lrc(String raw) {
        if (raw == null) return;

        long offset = 0;
        Matcher om = OFFSET.matcher(raw);
        if (om.find()) {
            try { offset = Long.parseLong(om.group(1)); } catch (Exception ignored) { }
        }

        for (String row : raw.split("\\r?\\n")) {
            Matcher m = TAG.matcher(row);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String f = m.group(3);
                long ms = 0;
                if (f != null) {
                    if (f.length() == 1) ms = Long.parseLong(f) * 100;
                    else if (f.length() == 2) ms = Long.parseLong(f) * 10;
                    else ms = Long.parseLong(f.substring(0, 3));
                }
                times.add(min * 60000 + sec * 1000 + ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = row.substring(end).trim();
            if (text.isEmpty()) continue;
            // 一行可能有多个时间戳（副歌复用），每个都要生成一条
            for (long t : times) {
                lines.add(new Line(Math.max(0, t - offset), text));
            }
        }
        lines.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
    }

    public boolean isEmpty() { return lines.isEmpty(); }

    public List<Line> lines() { return lines; }

    public String lineAt(long posMs) {
        String cur = null;
        for (Line l : lines) {
            if (l.timeMs <= posMs) cur = l.text;
            else break;
        }
        return cur;
    }
}
