package com.yuyin.music.mobile;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

/** Shared boundaries for the native bridge. Remote pages never receive this bridge. */
final class NativePolicy {
    static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private NativePolicy() { }

    static boolean validBvid(String value) { return value != null && BVID.matcher(value).matches(); }
    static boolean validStoreKey(String key) { return "library".equals(key) || "preferences".equals(key); }
    static String authenticationSignature(String cookies) {
        if (cookies == null) return "";
        ArrayList<String> selected = new ArrayList<>();
        for (String token : cookies.split(";")) {
            String value = token.trim(); String name = value.split("=", 2)[0];
            if (name.equals("SESSDATA") || name.equals("DedeUserID") || name.equals("bili_jct")) selected.add(value);
        }
        Collections.sort(selected);
        return String.join(";", selected);
    }

    static URI requestUri(String value) throws URISyntaxException {
        if (value == null || value.length() > 8192) throw new URISyntaxException("", "Invalid request URL");
        URI uri = new URI(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null ||
                uri.getPort() != -1 || uri.getFragment() != null ||
                !allowedEndpoint(host, uri.getPath())) {
            throw new URISyntaxException(value, "This host is not available to the music API");
        }
        return uri;
    }

    private static boolean allowedEndpoint(String host, String path) {
        if (path == null) return false;
        if (host.equals("api.bilibili.com")) return path.equals("/x/web-interface/nav") ||
                path.equals("/x/web-interface/search/type") || path.equals("/x/web-interface/view") ||
                path.equals("/x/player/wbi/v2") || path.equals("/x/player/v2") ||
                path.equals("/x/v3/fav/folder/created/list-all") || path.equals("/x/v3/fav/resource/list");
        if (host.equals("lrclib.net")) return path.equals("/api/search") || path.equals("/api/get");
        if (host.equals("aisubtitle.hdslb.com")) return path.startsWith("/bfs/ai_subtitle/");
        return host.matches("i[0-3]\\.hdslb\\.com") && path.startsWith("/bfs/subtitle/");
    }

    static boolean officialPage(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && uri.getRawUserInfo() == null &&
                    uri.getPort() == -1 && (host.equals("bilibili.com") || host.endsWith(".bilibili.com"));
        } catch (Exception ignored) { return false; }
    }

    static boolean expectedVideo(String value, String bvid) {
        try {
            URI uri = new URI(value);
            return validBvid(bvid) && officialPage(value) && "www.bilibili.com".equals(uri.getHost()) && firstPart(uri) &&
                    (uri.getPath().equals("/video/" + bvid) || uri.getPath().equals("/video/" + bvid + "/"));
        } catch (Exception ignored) { return false; }
    }
    private static boolean firstPart(URI uri) {
        if (uri.getRawQuery() == null) return true;
        for (String field : uri.getRawQuery().split("&")) {
            String[] pair = field.split("=", 2);
            try {
                if (URLDecoder.decode(pair[0], "UTF-8").equals("p") &&
                        (pair.length != 2 || !URLDecoder.decode(pair[1], "UTF-8").equals("1"))) return false;
            } catch (Exception ignored) { return false; }
        }
        return true;
    }
}
