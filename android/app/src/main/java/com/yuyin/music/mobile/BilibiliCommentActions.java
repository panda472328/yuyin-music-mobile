package com.yuyin.music.mobile;

import com.getcapacitor.JSObject;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** No generic POST bridge. Every mutation verifies account, video and reply membership. */
final class BilibiliCommentActions {
    private static final String API = "https://api.bilibili.com";
    interface Request { JSONObject send(String endpoint, String cookies, String form) throws Exception; }

    static JSObject execute(JSObject args, String cookies, BooleanSupplier unchanged, CommentTargets targets) throws Exception {
        return execute(args, cookies, unchanged, targets, BilibiliCommentActions::send);
    }
    static JSObject execute(JSObject args, String cookies, BooleanSupplier unchanged, CommentTargets targets, Request request) throws Exception {
        String action = args.optString("action"), bvid = args.optString("bvid");
        long expectedMid = args.optLong("expectedMid", -1);
        if (!(args.opt("expectedMid") instanceof Number) || args.optDouble("expectedMid", -1) != expectedMid || !NativePolicy.validBvid(bvid) || !CommentPolicy.validMid(expectedMid) || !(action.equals("like") || action.equals("create"))) throw new IllegalArgumentException("评论操作参数无效。");
        String session = NativePolicy.authenticationSignature(cookies);
        String csrf = CommentPolicy.csrf(cookies);
        JSONObject nav = validated(request.send("/x/web-interface/nav", cookies, null)).getJSONObject("data");
        if (!nav.optBoolean("isLogin") || nav.optLong("mid", -1) != expectedMid) throw new IllegalArgumentException("Bilibili 账号已变化，请重新验证后操作。");
        requireSession(unchanged);
        JSONObject view = validated(request.send("/x/web-interface/view?bvid=" + bvid, cookies, null)).getJSONObject("data");
        String oid = String.valueOf(view.optLong("aid", -1));
        if (!bvid.equals(view.optString("bvid")) || !CommentPolicy.validId(oid)) throw new IllegalArgumentException("视频信息异常，请重新读取评论。");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("oid", oid); form.put("type", "1"); form.put("csrf", csrf); form.put("csrf_token", csrf);
        String endpoint;
        if (action.equals("like")) {
            String id = args.optString("id");
            if (!CommentPolicy.validId(id) || !(args.opt("liked") instanceof Boolean)) throw new IllegalArgumentException("点赞参数无效。");
            targets.require(oid, id, session);
            form.put("rpid", id); form.put("action", args.optBoolean("liked") ? "1" : "0");
            endpoint = "/x/v2/reply/action";
        } else {
            String message = args.optString("message").trim();
            if (message.isEmpty() || message.length() > 1000 || message.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*")) throw new IllegalArgumentException("评论内容需要 1 到 1000 个字符。");
            String root = args.optString("rootId"), parent = args.optString("parentId");
            if (!root.isEmpty() || !parent.isEmpty()) {
                if (!CommentPolicy.validId(root) || !CommentPolicy.validId(parent)) throw new IllegalArgumentException("回复参数无效。");
                String rootParent = targets.require(oid, root, session), parentRoot = targets.require(oid, parent, session);
                if (!rootParent.equals("0") || !(parent.equals(root) || root.equals(parentRoot))) throw new IllegalArgumentException("回复所属评论不一致。");
                form.put("root", root); form.put("parent", parent);
            }
            form.put("message", message); form.put("plat", "1"); endpoint = "/x/v2/reply/add";
        }
        requireSession(unchanged);
        JSONObject result = validated(request.send(endpoint, cookies, CommentPolicy.form(form)));
        requireSession(unchanged);
        JSONObject data = result.optJSONObject("data");
        if (data != null && action.equals("create")) targets.created(oid, data.optJSONObject("reply"), session);
        JSObject response = new JSObject(); response.put("oid", oid); response.put("data", result.opt("data"));
        return response;
    }
    private static void requireSession(BooleanSupplier unchanged) {
        if (!unchanged.getAsBoolean()) throw new IllegalArgumentException("账号已变化，请刷新评论。未确认的发送结果请到源站核对。");
    }
    private static JSONObject validated(JSONObject result) {
        Object code = result.opt("code");
        if (!(code instanceof Number) || ((Number) code).doubleValue() != 0) throw new IllegalArgumentException("Bilibili 未接受该操作，请在源站检查登录、验证或评论限制。");
        return result;
    }
    private static JSONObject send(String endpoint, String cookies, String form) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URI(API + endpoint).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
            connection.setRequestMethod(form == null ? "GET" : "POST");
            connection.setRequestProperty("User-Agent", NativePolicy.DESKTOP_UA);
            connection.setRequestProperty("Referer", "https://www.bilibili.com/");
            connection.setRequestProperty("Origin", "https://www.bilibili.com");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cookie", cookies);
            if (form != null) {
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            if (connection.getResponseCode() != 200) throw new IllegalArgumentException("评论服务不可用，请在 Bilibili 完成验证后重试。");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long start = System.nanoTime();
            try (InputStream input = connection.getInputStream()) {
                byte[] block = new byte[8192]; int size;
                while ((size = input.read(block)) != -1) {
                    if (bytes.size() + size > 2 * 1024 * 1024 || System.nanoTime() - start > 20_000_000_000L) throw new IllegalArgumentException("评论响应异常，请刷新核对结果。");
                    bytes.write(block, 0, size);
                }
            }
            JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            return validated(result);
        } finally { connection.disconnect(); }
    }
    private BilibiliCommentActions() { }
}
