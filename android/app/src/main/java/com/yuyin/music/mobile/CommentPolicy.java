package com.yuyin.music.mobile;

import java.net.URLEncoder;
import java.util.Map;
import java.util.StringJoiner;

/** Fixed write operations; credentials never come from the renderer. */
final class CommentPolicy {
    static boolean validId(String value) { return value != null && value.matches("[1-9][0-9]{0,18}"); }
    static boolean validMid(long value) { return value > 0 && value <= 9007199254740991L; }
    static String csrf(String cookies) {
        String result = null;
        if (cookies != null) for (String field : cookies.split(";")) {
            String[] pair = field.trim().split("=", 2);
            if (!pair[0].equals("bili_jct")) continue;
            if (result != null || pair.length != 2 || !pair[1].matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("登录凭据异常，请重新登录。");
            result = pair[1];
        }
        if (result == null) throw new IllegalArgumentException("请先登录 Bilibili 后再评论或点赞。");
        return result;
    }
    static String form(Map<String, String> values) {
        StringJoiner result = new StringJoiner("&");
        for (Map.Entry<String, String> entry : values.entrySet()) result.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        return result.toString();
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException("UTF-8 unavailable", impossible); }
    }
    private CommentPolicy() { }
}
