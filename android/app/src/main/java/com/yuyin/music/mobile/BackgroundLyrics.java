package com.yuyin.music.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Same provider and saved offset in the service; no Activity is needed after a background skip. */
final class BackgroundLyrics {
    interface Transport { String get(String url) throws Exception; }
    static final class Result { final JSONArray lines; final String plain; Result(JSONArray lines, String plain) { this.lines = lines; this.plain = plain; } }
    private static final int[] MIXIN = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52};
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~"); }
    private static JSONObject data(String value) throws Exception { JSONObject root = new JSONObject(value); if (!(root.opt("code") instanceof Number) || root.optDouble("code") != 0 || root.optJSONObject("data") == null) throw new IllegalStateException("LYRICS_API_UNAVAILABLE"); return root.getJSONObject("data"); }
    static Result load(JSONObject song, String provider, Transport transport) throws Exception { return "lrclib".equals(provider) ? lrclib(song, transport) : bilibili(song, transport); }
    private static String imageKey(String address) throws Exception { String path = new URI(address).getPath(); Matcher match = Pattern.compile("([a-fA-F0-9]{32})\\.[a-zA-Z0-9]+$").matcher(path); if (!match.find()) throw new Exception("INVALID_WBI_KEY"); return match.group(1); }
    static String wbi(String bvid, long cid, long aid, JSONObject image, long timestamp) throws Exception {
        if (!NativePolicy.validBvid(bvid) || cid <= 0 || aid <= 0) throw new Exception("INVALID_VIDEO");
        String original = imageKey(image.getString("img_url")) + imageKey(image.getString("sub_url")); StringBuilder mix = new StringBuilder(); for (int index : MIXIN) mix.append(original.charAt(index));
        String query = "aid=" + aid + "&bvid=" + encode(bvid) + "&cid=" + cid + "&wts=" + timestamp;
        StringBuilder hash = new StringBuilder(); for (byte value : MessageDigest.getInstance("MD5").digest((query + mix.substring(0,32)).getBytes(StandardCharsets.UTF_8))) hash.append(String.format(Locale.ROOT, "%02x", value & 255)); return query + "&w_rid=" + hash;
    }
    private static Result bilibili(JSONObject song, Transport transport) throws Exception {
        String bvid = song.getString("bvid"); JSONObject view = data(transport.get("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid));
        JSONArray pages = view.optJSONArray("pages"); long cid = pages != null && pages.length() > 0 ? pages.getJSONObject(0).getLong("cid") : view.getLong("cid");
        JSONObject navResponse = new JSONObject(transport.get("https://api.bilibili.com/x/web-interface/nav"));
        if (navResponse.optInt("code", 1) != 0 && navResponse.optInt("code", 1) != -101) throw new Exception("LYRICS_API_UNAVAILABLE");
        JSONObject nav = navResponse.getJSONObject("data");
        String query = wbi(bvid, cid, view.getLong("aid"), nav.getJSONObject("wbi_img"), System.currentTimeMillis() / 1000);
        JSONObject player = data(transport.get("https://api.bilibili.com/x/player/wbi/v2?" + query));
        JSONObject subtitle = player.optJSONObject("subtitle"); JSONArray available = subtitle == null ? null : subtitle.optJSONArray("subtitles");
        JSONObject best = null; int bestScore = -1;
        if (available != null) for (int i = 0; i < available.length() && i < 100; i++) {
            JSONObject item = available.optJSONObject(i); if (item == null) continue;
            String url = item.optString("subtitle_url"); if (url.startsWith("//")) url = "https:" + url;
            try { URI uri = NativePolicy.requestUri(url); if ("api.bilibili.com".equals(uri.getHost()) || "lrclib.net".equals(uri.getHost())) continue; } catch (Exception ignored) { continue; }
            String lan = item.optString("lan"), label = item.optString("lan_doc");
            if (lan.isEmpty() || label.isEmpty() || lan.length() > 40 || label.length() > 100) continue;
            boolean ai = lan.matches("(?i)^ai[-_].*") || label.matches("(?i).*自动生成|.*自動生成|.*\\bAI\\b.*") || url.contains("aisubtitle.hdslb.com/");
            boolean chinese = lan.matches("(?i)^(ai[-_])?(zh|cmn)([-_].*)?$") || label.matches(".*(中文|汉语|漢語).*"); int score = (ai ? 100 : 0) + (chinese ? 200 : 0);
            if (score > bestScore) { bestScore = score; best = new JSONObject(item.toString()).put("subtitle_url", url); }
        }
        return best == null ? new Result(new JSONArray(), "") : captions(new JSONObject(transport.get(best.getString("subtitle_url"))));
    }
    static Result captions(JSONObject value) throws Exception {
        JSONArray body = value.getJSONArray("body"); if (body.length() > 5000) throw new Exception("LYRICS_LIMIT");
        List<JSONObject> rows = new ArrayList<>(); int count = 0;
        for (int i = 0; i < body.length(); i++) {
            JSONObject row = body.getJSONObject(i); Object from = row.opt("from"), to = row.opt("to"), text = row.opt("content");
            if (!(from instanceof Number) || !(to instanceof Number) || !(text instanceof String)) throw new Exception("INVALID_CAPTION");
            double a = ((Number) from).doubleValue(), b = ((Number) to).doubleValue();
            if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b < a || b > 86400 || ((String) text).length() > 2000) throw new Exception("INVALID_CAPTION");
            String clean = ((String) text).replaceAll("[\\x00-\\x1f\\x7f]", " ").replaceAll("\\s+", " ").trim().replace('[','［').replace(']','］').replace('<','＜').replace('>','＞');
            if (clean.isEmpty()) continue; count += clean.length(); if (count > 100000) throw new Exception("LYRICS_LIMIT"); rows.add(new JSONObject().put("from", a).put("to", b).put("content", clean));
        }
        rows.sort((a,b) -> { int order = Double.compare(a.optDouble("from"), b.optDouble("from")); return order == 0 ? Double.compare(a.optDouble("to"), b.optDouble("to")) : order; });
        JSONArray lines = new JSONArray(); StringBuilder plain = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) { JSONObject row = rows.get(i); lines.put(new JSONObject().put("time", row.getDouble("from")).put("text", row.getString("content"))); if (plain.length() > 0) plain.append('\n'); plain.append(row.getString("content")); if (row.getDouble("to") > row.getDouble("from") && (i + 1 == rows.size() || row.getDouble("to") < rows.get(i+1).getDouble("from"))) lines.put(new JSONObject().put("time", row.getDouble("to")).put("text", "")); }
        if (lines.length() > 10000) throw new Exception("LYRICS_LIMIT"); return new Result(lines, plain.toString());
    }
    static Result lrc(String synced, String plain) throws Exception {
        if (synced.length() > 100000 || plain.length() > 100000) throw new Exception("LYRICS_LIMIT");
        String tag = "\\[(?:(\\d{1,3}):)?(\\d{1,3}):([0-5]\\d)(?:[.,](\\d{1,3}))?\\]";
        Pattern timestamp = Pattern.compile(tag), leading = Pattern.compile("^\\s*((?:" + tag + "\\s*)+)(.*)$");
        Pattern metadata = Pattern.compile("(?i)^\\[offset:\\s*([+-]?\\d+)\\s*\\]$");
        String[] rows = synced.replaceFirst("^\\uFEFF", "").split("\\r?\\n");
        List<JSONObject> values = new ArrayList<>(); Set<String> seen = new HashSet<>(); double fileOffset = 0;
        for (String row : rows) { Matcher match = metadata.matcher(row.trim()); if (match.matches()) fileOffset = Double.parseDouble(match.group(1)) / 1000; }
        for (String row : rows) {
            Matcher head = leading.matcher(row); if (!head.matches()) continue;
            String text = head.group(head.groupCount()).replaceAll("<(?:\\d{1,3}:)?\\d{1,3}:[0-5]\\d(?:[.,]\\d{1,3})?>", "").trim();
            if (text.matches("(?i)^\\[(ti|ar|al|by|re|ve|offset):.*")) continue;
            Matcher match = timestamp.matcher(head.group(1));
            while (match.find()) {
                int minutes = Integer.parseInt(match.group(2)); if (match.group(1) != null && minutes >= 60) continue;
                double seconds = (match.group(1) == null ? 0 : Integer.parseInt(match.group(1)) * 3600) + minutes * 60 + Integer.parseInt(match.group(3));
                if (match.group(4) != null) seconds += Double.parseDouble("0." + match.group(4));
                double time = Math.round((seconds - fileOffset) * 1000) / 1000.0;
                if (!Double.isFinite(time) || Math.abs(time) > 86400) throw new Exception("LYRICS_LIMIT");
                if (seen.add(time + "\u0000" + text)) values.add(new JSONObject().put("time", time).put("text", text));
            }
        }
        if (values.size() > 10000) throw new Exception("LYRICS_LIMIT"); values.sort(Comparator.comparingDouble(value -> value.optDouble("time"))); JSONArray lines = new JSONArray(); for (JSONObject value : values) lines.put(value); return new Result(lines, plain);
    }
    static String normalized(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    // Keep identity rules in step with src/domain/lyrics.ts; provider fixture tests cover both paths.
    private static String clean(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("<[^>]*>", " ")
            .replaceAll("(?i)(\\b(\\d[48]k|\\d{3,4}p|uhd|hd|hq|fps|hi-?res)\\b|\\d{3,4}P|[248]K|修复版|修復版|修复|修復|高清|超清|画质|畫質|杜比|无损|無損|音质|音質|帧率|幀率|\\d+帧|\\d+幀)", " ")
            .replaceAll("(?i)(official\\s*(music\\s*)?(video|audio|mv)|music\\s*video|lyric(s)?\\s*video|完整版|高音质|高音質|超治愈|超治癒|官方|完整|原版|中英|中文|英文|双语|雙語|字幕|歌词|歌詞|纯享|純享|音频|音頻|音乐|音樂|治愈|治癒|经典|經典|神曲|视听|視聽|单曲|單曲|\\bmv\\b|MV)", " ")
            .replaceAll("[|｜~～!！?？…]+", " ").replaceAll("\\s+", " ").trim();
    }
    private static String videoSongName(String value) { return clean(value.split("(?i)\\b(official|music\\s*video|lyric(s)?\\s*video)\\b|MV|\\d{3,4}P|[248]K",2)[0]).replaceAll("[\\s(（【\\[]+$", "").trim(); }
    private static String withoutVersion(String value) { return value.replaceAll("(?i)[（(][^）)]*(live|version|remaster|现场|現場|演唱会|演唱會|版)[^）)]*[）)]", "").replaceAll("(?i)\\b(live|remastered?|acoustic|studio|version)\\b", "").replaceAll("翻唱版|现场版|現場版|现场|現場|演唱会|演唱會|录音室|錄音室|原唱|翻唱", "").trim(); }
    private static boolean live(String value) { return value.matches("(?is).*\\blive\\b.*|.*(现场|現場|演唱会|演唱會|音乐会|音樂會|巡演|演唱版本).*" ); }
    private static boolean contains(String value, String needle) { String a = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT), b = Normalizer.normalize(needle, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim(); int at = a.indexOf(b); while (at >= 0 && !b.isEmpty()) { int after = at+b.length(); if ((at == 0 || !Character.isLetterOrDigit(a.charAt(at-1))) && (after == a.length() || !Character.isLetterOrDigit(a.charAt(after)))) return true; at = a.indexOf(b, after); } return normalized(a).equals(normalized(b)); }
    private static Result lrclib(JSONObject song, Transport transport) throws Exception {
        String raw = Normalizer.normalize(song.getString("title"), Normalizer.Form.NFKC).replaceAll("<[^>]*>", " ");
        if (raw.matches("(?is).*(合集|串烧|串燒|歌单|歌單|歌曲大全|歌曲集|全专辑|全專輯|\\b(medley|compilation|playlist)\\b).*")) return new Result(new JSONArray(), "");
        String rawTitle = raw.replaceAll("(?i)【[^】]*(修复|修復|高清|超清|私藏|音乐馆|音樂館|[248]K|[0-9]{3,4}P)[^】]*】", " ");
        String title = clean(rawTitle);
        List<String> names = new ArrayList<>(); String artist = ""; String[] parts = Arrays.stream(rawTitle.split("\\s*[-–—―]+\\s*")).map(String::trim).filter(part -> !part.isEmpty()).toArray(String[]::new);
        if (parts.length >= 2) { if (parts[1].matches("^[《「『].*")) names.add(videoSongName(parts[0])); else { artist = clean(parts[0]); names.add(videoSongName(parts[1])); } }
        Matcher quote = Pattern.compile("[《「『]([^》」』]+)[》」』]").matcher(title);
        int quoteCount = 0; while (quote.find()) quoteCount++;
        if (quoteCount > 1 && song.optDouble("duration") > 600) return new Result(new JSONArray(), "");
        quote.reset();
        if (names.isEmpty() && quoteCount == 1 && quote.find()) { names.add(clean(quote.group(1))); artist = clean(title.substring(0,quote.start())).replaceAll("^[【\\[][^】\\]]*[】\\]]\\s*", "").trim(); }
        Matcher bracket = Pattern.compile("【([^】]+)】").matcher(title);
        if (names.isEmpty() && bracket.find() && bracket.start() > 0) { names.add(clean(bracket.group(1))); artist = clean(title.substring(0,bracket.start())); }
        boolean explicit = !names.isEmpty(); if (!explicit) names.add(title.replaceAll("^[【\\[][^】\\]]*[】\\]]\\s*", ""));
        String query = clean(song.optString("searchQuery")); if (!query.isEmpty() && contains(title, query)) names.add(query);
        if (names.stream().noneMatch(name -> normalized(name).length() >= 2) && !query.isEmpty()) names.add(query);
        if (artist.length() > 50 || artist.matches(".*(打开|打開|混剪|音源|海绵宝宝|海綿寶寶|感动|感動|推荐|推薦|盘点|盤點|一起来|一起來).*")) artist = "";
        if (artist.isEmpty()) for (String name : names) if (contains(query, name)) { String prefix = query.substring(0, Math.max(0, query.toLowerCase(Locale.ROOT).indexOf(name.toLowerCase(Locale.ROOT)))).replaceAll("[\\s\\-–—]+$", "").trim(); if (normalized(prefix).length() >= 2 && prefix.length() < 40) artist = prefix; }
        for (int i=0;i<names.size();i++) names.set(i, withoutVersion(names.get(i))); names.removeIf(name -> normalized(name).length() < 2);
        LinkedHashSet<String> queries = new LinkedHashSet<>(); for (String name : names) { if (!artist.isEmpty()) queries.add((artist + " " + name).replaceAll("[《》「」『』【】\\[\\]]", " ").replaceAll("\\s+", " ").trim()); queries.add(name.replaceAll("[《》「」『』【】\\[\\]]", " ").trim()); }
        JSONObject best = null; double bestScore = -Double.MAX_VALUE; int queried = 0;
        for (String q : queries) {
            if (queried++ >= 3) break; JSONArray tracks = new JSONArray(transport.get("https://lrclib.net/api/search?q=" + encode(q.substring(0,Math.min(160,q.length()))))); if (tracks.length() > 500) throw new Exception("LYRICS_LIMIT");
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i); if (track == null || !(track.opt("trackName") instanceof String) || !(track.opt("artistName") instanceof String)) continue;
                String name = withoutVersion(clean(track.getString("trackName"))), key = normalized(name); int score = 0;
                for (String expected : names) if (key.equals(normalized(expected))) score = Math.max(score,100); else if (!explicit && contains(expected,name)) score = Math.max(score,65);
                if (score == 0) continue;
                String synced = track.optString("syncedLyrics", ""), plain = track.optString("plainLyrics", ""); if ("null".equals(synced)) synced = ""; if ("null".equals(plain)) plain = "";
                if (synced.length() > 100000 || plain.length() > 100000 || track.optDouble("duration",-1) < 0 || track.optDouble("duration") > 86400 || !(track.opt("instrumental") instanceof Boolean)) continue;
                boolean hasSynced = false; JSONArray parsed = lrc(synced,plain).lines; for (int j=0;j<parsed.length();j++) if (!parsed.getJSONObject(j).getString("text").isEmpty()) hasSynced=true;
                boolean hasPlain = !plain.trim().isEmpty(); if (!hasSynced && !hasPlain && !track.optBoolean("instrumental")) continue;
                String singer = normalized(track.getString("artistName")); boolean singerMatches = singer.length() >= 2 && (normalized(title).contains(singer) || normalized(artist).contains(singer));
                double delta = track.optDouble("duration") > 0 && song.optDouble("duration") > 0 ? Math.abs(track.optDouble("duration")-song.optDouble("duration")) : Double.POSITIVE_INFINITY;
                double total = score + (singerMatches ? 90 : 0) + (live(track.optString("trackName")+" "+track.optString("albumName")) == live(raw) ? 45 : -45) + (hasSynced ? 8 : hasPlain ? 3 : 0) + Math.max(0,12-delta/10);
                if (total > bestScore) { bestScore = total; best = new JSONObject(track.toString()).put("syncedLyrics", synced).put("plainLyrics", plain); }
            }
            if (best != null) break;
        }
        return best == null ? new Result(new JSONArray(), "") : lrc(best.getString("syncedLyrics"), best.getString("plainLyrics"));
    }
}
