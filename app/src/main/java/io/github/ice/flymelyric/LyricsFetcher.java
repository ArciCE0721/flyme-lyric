package io.github.ice.flymelyric;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 歌词获取：AMLL（Apple Music ID / 搜索）→ LRCLIB → 网易云。
 *
 * 匹配策略：翻唱、伴奏、现场版经常和原曲同名，直接取搜索结果第一条会拿到错误歌词。
 * 所以每个来源都会用「标题 + 歌手 + 时长」打分，低于阈值宁可不取，也不返回错的。
 */
public final class LyricsFetcher {

    private static final String TAG = "LyricBridge";
    private static final String UA = "Mozilla/5.0 (Linux; Android 16) FlymeLyric/1.0";
    /** 缓存前缀带版本号：匹配逻辑变化后旧缓存自动失效 */
    private static final String CACHE_PREFIX = "v8_";

    private LyricsFetcher() {}

    public static String fetch(Context ctx, String title, String artist, long durationMs, String appleId) {
        if (title == null || title.isEmpty()) return null;
        SharedPreferences sp = ctx.getSharedPreferences("lyrics", Context.MODE_PRIVATE);
        String key = cacheKey(title, artist);
        String cached = sp.getString(key, null);
        if (cached != null && !cached.isEmpty()) return cached;

        long dur = durationMs > 0 ? durationMs : 0;
        String lrc = fromAmll(title, artist, appleId);
        String src = "amll";
        if (!usable(lrc)) { lrc = fromLrclib(title, artist, dur); src = "lrclib"; }
        if (!usable(lrc)) { lrc = fromNetease(title, artist, dur); src = "netease"; }

        // 跨语言曲名兜底：Apple Music 常把日文歌名显示成英文（プラチナ -> Platinum），
        // 用 Apple ID 反查日本区元数据，拿到原名再搜一次。
        if (!usable(lrc) && appleId != null && !appleId.isEmpty()) {
            String[] loc = localizedMeta(appleId);
            if (loc != null && !loc[0].isEmpty()
                    && (!norm(loc[0]).equals(norm(title)) || !norm(loc[1]).equals(norm(artist)))) {
                long d2 = parseLong(loc[2], dur);
                Log.i(TAG, "retry with localized name: " + loc[0] + " / " + loc[1]);
                lrc = fromAmll(loc[0], loc[1], null);
                if (!usable(lrc)) lrc = fromLrclib(loc[0], loc[1], d2);
                if (!usable(lrc)) lrc = fromNetease(loc[0], loc[1], d2);
                if (usable(lrc)) src = "localized";
            }
        }

        if (!usable(lrc)) lrc = null;
        if (lrc != null) sp.edit().putString(key, lrc).apply();
        Log.i(TAG, "fetch [" + title + " / " + artist + "] -> "
                + (lrc == null ? "null" : lrc.length() + " chars via " + src));
        return lrc;
    }

    public static void invalidate(Context ctx, String title, String artist) {
        ctx.getSharedPreferences("lyrics", Context.MODE_PRIVATE).edit()
                .remove(cacheKey(title, artist)).apply();
    }

    private static String cacheKey(String title, String artist) {
        return CACHE_PREFIX + Integer.toHexString((title + "|" + artist).hashCode());
    }

    /** 至少要解析出一句歌词才算可用，避免把「纯音乐」「作曲 : xxx」这类空壳当成歌词。 */
    private static boolean usable(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            String t = raw.trim();
            LyricDoc doc = t.startsWith("<") ? new Ttml(raw) : new Lrc(raw);
            return !doc.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 用 Apple ID 反查日本区元数据（歌词库里通常用日文原名）。返回 {曲名, 歌手, 时长ms} */
    private static String[] localizedMeta(String appleId) {
        try {
            JSONObject o = json("https://itunes.apple.com/lookup?id=" + enc(appleId) + "&country=jp");
            if (o == null || o.optInt("resultCount") <= 0) return null;
            JSONArray arr = o.optJSONArray("results");
            if (arr == null || arr.length() == 0) return null;
            JSONObject r = arr.optJSONObject(0);
            if (r == null) return null;
            return new String[]{ r.optString("trackName", ""), r.optString("artistName", ""),
                    String.valueOf(r.optLong("trackTimeMillis", 0)) };
        } catch (Exception e) {
            Log.w(TAG, "lookup " + e);
            return null;
        }
    }

    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s); } catch (Exception e) { return fallback; }
    }

    /* ---------------- AMLL ---------------- */

    private static String fromAmll(String title, String artist, String appleId) {
        try {
            if (appleId != null && !appleId.isEmpty()) {
                String ttml = amllGet("appleMusicId=" + enc(appleId));
                if (ttml != null) {
                    Log.i(TAG, "amll hit by appleId " + appleId);
                    return ttml;
                }
            }
            String u = "https://api.amll.dev/v1/lyrics/search?musicName=" + enc(title)
                    + "&artistName=" + enc(artist) + "&pageSize=5";
            JSONObject o = json(u);
            if (o == null || o.optInt("status") != 200) return null;
            JSONObject data = o.optJSONObject("data");
            if (data == null) return null;
            JSONArray items = data.optJSONArray("items");
            if (items == null) return null;
            for (int i = 0; i < Math.min(items.length(), 5); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                if (!amllMatches(it, title, artist)) continue;
                long id = it.optLong("id", 0);
                if (id == 0) continue;
                String ttml = amllGet("id=" + id);
                if (ttml != null) {
                    Log.i(TAG, "amll hit by search id=" + id);
                    return ttml;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "amll " + e);
        }
        return null;
    }

    /** AMLL 搜索结果的标题/歌手要能对上，避免取到同名翻唱。 */
    private static boolean amllMatches(JSONObject it, String title, String artist) {
        JSONArray names = it.optJSONArray("musicNames");
        JSONArray artists = it.optJSONArray("artistNames");
        if (names == null || names.length() == 0) return true;
        boolean titleOk = false;
        for (int i = 0; i < names.length(); i++) {
            if (looseMatch(names.optString(i), title)) { titleOk = true; break; }
        }
        if (!titleOk) return false;
        if (artists == null || artists.length() == 0) return true;
        for (int i = 0; i < artists.length(); i++) {
            if (looseMatch(artists.optString(i), artist)) return true;
        }
        return false;
    }

    /* ---------------- LRCLIB ---------------- */

    private static String fromLrclib(String title, String artist, long durMs) {
        long sec = durMs > 0 ? Math.round(durMs / 1000.0) : 0;
        try {
            String u = "https://lrclib.net/api/get?track_name=" + enc(title)
                    + "&artist_name=" + enc(artist)
                    + (sec > 0 ? "&duration=" + sec : "");
            JSONObject o = json(u);
            if (o != null) {
                String s = o.optString("syncedLyrics", "");
                if (!s.isEmpty()) return s;
            }
        } catch (Exception e) { Log.w(TAG, "lrclib get " + e); }
        try {
            String u = "https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist);
            String best = pickLrclib(array(u), title, artist, durMs, true);
            if (best != null) return best;
            u = "https://lrclib.net/api/search?track_name=" + enc(title);
            best = pickLrclib(array(u), title, artist, durMs, false);
            if (best != null) return best;
        } catch (Exception e) { Log.w(TAG, "lrclib search " + e); }
        return null;
    }

    private static String pickLrclib(JSONArray arr, String title, String artist, long durMs,
                                     boolean needArtist) {
        if (arr == null) return null;
        String best = null;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String s = o.optString("syncedLyrics", "");
            if (s.isEmpty()) continue;
            if (!looseMatch(o.optString("trackName"), title)) continue;
            java.util.List<String> one = new java.util.ArrayList<>();
            one.add(o.optString("artistName", ""));
            if (!artistMatches(artist, one)) continue;
            double dur = o.optDouble("duration", 0);
            if (durMs > 0 && dur > 0) {
                long diff = Math.abs(Math.round(dur * 1000) - durMs);
                if (diff > 8000) continue;
                if (diff < bestDiff) { bestDiff = diff; best = s; }
                continue;
            }
            if (best == null) best = s;
        }
        return best;
    }

    /* ---------------- 网易云 ---------------- */

    private static String fromNetease(String title, String artist, long durMs) {
        try {
            long bestId = 0;
            int bestScore = Integer.MIN_VALUE;

            // 依次尝试多种查询：完整、仅歌名、歌名+歌手首段
            // 很多日文/角色曲的艺术家字段带 (CV:xxx)，直接拼进去会搜不到
            java.util.LinkedHashSet<String> queries = new java.util.LinkedHashSet<>();
            String baseArtist = artist == null ? "" : artist.trim();
            String shortArtist = baseArtist.split("[（(、/]")[0].trim();
            if (!baseArtist.isEmpty()) queries.add(title + " " + baseArtist);
            queries.add(title);
            if (!shortArtist.isEmpty() && !shortArtist.equals(baseArtist)) {
                queries.add(title + " " + shortArtist);
            }

            for (String q : queries) {
                String u = "https://music.163.com/api/search/get/web?s=" + enc(q) + "&type=1&limit=10";
                JSONObject o = json(u);
                if (o == null) continue;
                JSONObject result = o.optJSONObject("result");
                if (result == null) continue;
                JSONArray songs = result.optJSONArray("songs");
                if (songs == null) continue;

                for (int i = 0; i < songs.length(); i++) {
                    JSONObject s = songs.optJSONObject(i);
                    if (s == null) continue;
                    String name = s.optString("name", "");
                    long sd = s.optLong("duration", 0);
                    java.util.List<String> artists = new ArrayList<>();
                    JSONArray ars = s.optJSONArray("artists");
                    if (ars != null) {
                        for (int k = 0; k < ars.length(); k++) {
                            JSONObject ar = ars.optJSONObject(k);
                            if (ar != null) artists.add(ar.optString("name", ""));
                        }
                    }
                    int score = score(name, artists, sd, title, artist, durMs);
                    if (score > bestScore) { bestScore = score; bestId = s.optLong("id", 0); }
                }
                // 已经找到足够可信的结果就不用再试别的查询
                if (bestScore >= 20) break;
            }

            if (bestId == 0 || bestScore < 12) {
                Log.i(TAG, "netease no good match, bestScore=" + bestScore);
                return null;
            }
            String lu = "https://music.163.com/api/song/lyric?id=" + bestId + "&lv=-1&kv=-1&tv=-1";
            JSONObject lo = json(lu);
            if (lo == null) return null;
            JSONObject lrc = lo.optJSONObject("lrc");
            if (lrc == null) return null;
            String s = lrc.optString("lyric", "");
            if (s.isEmpty() || !s.contains("[")) return null;
            Log.i(TAG, "netease pick id=" + bestId + " score=" + bestScore);
            return s;
        } catch (Exception e) {
            Log.w(TAG, "netease " + e);
            return null;
        }
    }

    /** 标题/歌手/时长打分，越像分越高；明显是翻唱或时长差太大直接负分。 */
    private static int score(String gotName, List<String> gotArtists, long gotDurMs,
                             String wantTitle, String wantArtist, long wantDurMs) {
        int s = 0;
        if (looseMatch(gotName, wantTitle)) s += 10;
        else return -20;

        String n = norm(gotName);
        String[] bad = {"cover", "翻唱", "伴奏", "纯音乐", "钢琴", "吉他", "remix", "live",
                "现场", "demo", "片段", "版", "instrumental", "karaoke", "dj"};
        for (String b : bad) {
            if (n.contains(b)) { s -= 8; break; }
        }

        boolean artistHit = artistMatches(wantArtist, gotArtists);
        boolean titleExact = norm(gotName).equals(norm(wantTitle));
        long durDiff = (wantDurMs > 0 && gotDurMs > 0) ? Math.abs(gotDurMs - wantDurMs) : -1;

        if (artistHit) {
            s += 8;
        } else if (titleExact && durDiff >= 0 && durDiff <= 2000) {
            // 歌手名对不上（角色名、马甲、跨语言译名）但标题完全一致且时长几乎相同，放行
            s += 2;
        } else {
            return -20;
        }

        if (durDiff >= 0) {
            if (durDiff <= 3000) s += 10;
            else if (durDiff <= 8000) s += 3;
            else s -= 10;
        }
        return s;
    }

    /** 多歌手字段（如「A、B、C」）任意一段能对上就算命中。 */
    private static boolean artistMatches(String want, java.util.List<String> gots) {
        if (want == null || want.isEmpty() || gots == null || gots.isEmpty()) return false;
        String[] tokens = want.split("[、,，/&;；]|\\bfeat\\.?|\\bft\\.?|\\bvs\\.?");
        for (String g : gots) {
            if (looseMatch(g, want)) return true;
            for (String tk : tokens) {
                String cur = tk.trim();
                if (cur.length() >= 2 && looseMatch(g, cur)) return true;
            }
        }
        return false;
    }

    /** 去掉括号、空格、大小写后比较，允许一方包含另一方。 */
    private static boolean looseMatch(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("（[^）]*）", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("[\\s_\\-·,，.。'\"!！?？:：;；、<>《》「」『』“”‘’—–~～]", "");
    }

    /* ---------------- HTTP ---------------- */

    private static String amllGet(String query) {
        JSONObject o = json("https://api.amll.dev/v1/lyrics/get?" + query);
        if (o == null || o.optInt("status") != 200) return null;
        JSONObject d = o.optJSONObject("data");
        if (d == null) return null;
        String lyr = d.optString("lyrics", "");
        return lyr.isEmpty() ? null : lyr;
    }

    private static JSONObject json(String u) {
        try {
            String body = http(u);
            return body == null ? null : new JSONObject(body);
        } catch (Exception e) {
            Log.w(TAG, "json " + e);
            return null;
        }
    }

    private static JSONArray array(String u) {
        try {
            String body = http(u);
            return body == null ? null : new JSONArray(body);
        } catch (Exception e) {
            Log.w(TAG, "array " + e);
            return null;
        }
    }

    private static String http(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://music.163.com/");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        if (code < 200 || code >= 300) {
            Log.w(TAG, "http " + code + " " + u);
            return null;
        }
        return bos.toString("UTF-8");
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
