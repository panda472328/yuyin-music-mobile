package com.yuyin.music.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Membership comes from native HTTPS reads, never from renderer-provided metadata. */
final class CommentTargets {
    private static final class Target {
        final String oid, root, session;
        Target(String oid, String root, String session) { this.oid = oid; this.root = root; this.session = session; }
    }
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    void remember(URI uri, String body, String session) {
        if (!"api.bilibili.com".equals(uri.getHost()) || !("/x/v2/reply".equals(uri.getPath()) || "/x/v2/reply/reply".equals(uri.getPath()))) return;
        try {
            String oid = null;
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && URLDecoder.decode(pair[0], "UTF-8").equals("oid")) {
                    if (oid != null) return;
                    oid = URLDecoder.decode(pair[1], "UTF-8");
                }
            }
            if (!CommentPolicy.validId(oid)) return;
            JSONObject payload = new JSONObject(body);
            if (!(payload.opt("code") instanceof Number) || ((Number) payload.opt("code")).doubleValue() != 0) return;
            JSONObject data = payload.getJSONObject("data");
            if (targets.size() > 3000) targets.clear();
            addArray(data.optJSONArray("replies"), oid, session, 0);
            addArray(data.optJSONArray("top_replies"), oid, session, 0);
            add(data.optJSONObject("root"), oid, session, 0);
            JSONObject top = data.optJSONObject("top");
            if (top != null) for (java.util.Iterator<String> keys = top.keys(); keys.hasNext();) add(top.optJSONObject(keys.next()), oid, session, 0);
        } catch (Exception ignored) { /* Invalid responses authorize no mutation targets. */ }
    }
    private void addArray(JSONArray values, String oid, String session, int depth) {
        if (values == null) return;
        for (int i = 0; i < Math.min(100, values.length()); i++) add(values.optJSONObject(i), oid, session, depth);
    }
    private void add(JSONObject item, String oid, String session, int depth) {
        if (item == null || !oid.equals(item.optString("oid"))) return;
        String id = item.optString("rpid_str", item.optString("rpid")), root = item.optString("root_str", item.optString("root", "0"));
        if (!CommentPolicy.validId(id) || !(root.equals("0") || CommentPolicy.validId(root))) return;
        targets.put(id, new Target(oid, root, session));
        if (depth == 0) addArray(item.optJSONArray("replies"), oid, session, 1);
    }
    String require(String oid, String id, String session) {
        Target target = targets.get(id);
        if (target == null || !oid.equals(target.oid) || !session.equals(target.session)) throw new IllegalArgumentException("评论尚未读取或账号已变化，请刷新后重试。");
        return target.root;
    }
    void created(String oid, JSONObject reply, String session) { add(reply, oid, session, 0); }
}
