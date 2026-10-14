package com.yuyin.music.mobile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** In-memory transport only: no CookieManager, network or source-site mutations. */
@RunWith(AndroidJUnit4.class)
public class ControlledCommentsTest {
    private static final String COOKIES = "bili_jct=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;DedeUserID=7";
    private static final String SESSION = NativePolicy.authenticationSignature(COOKIES);
    private static final String READ = "{\"code\":0,\"data\":{\"replies\":[{\"oid\":123,\"rpid_str\":\"11\",\"root\":0,\"replies\":[{\"oid\":123,\"rpid_str\":\"22\",\"root_str\":\"11\"}]}]}}";
    private interface Operation { void run() throws Exception; }
    private static void rejects(Operation action) throws Exception {
        try { action.run(); fail("Invalid operation must fail"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
    private static CommentTargets targets() {
        CommentTargets targets = new CommentTargets();
        targets.remember(URI.create("https://api.bilibili.com/x/v2/reply?oid=123&type=1"), READ, SESSION);
        return targets;
    }
    private static JSObject like() {
        JSObject args = new JSObject(); args.put("action", "like"); args.put("bvid", "BV1234567890");
        args.put("expectedMid", 7); args.put("id", "11"); args.put("liked", true); return args;
    }
    private static BilibiliCommentActions.Request transport(AtomicInteger posts, long mid, boolean failPost) {
        return (endpoint, cookies, form) -> {
            assertEquals(COOKIES, cookies);
            if (endpoint.equals("/x/web-interface/nav")) return new JSONObject("{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":" + mid + "}}");
            if (endpoint.startsWith("/x/web-interface/view?")) return new JSONObject("{\"code\":0,\"data\":{\"bvid\":\"BV1234567890\",\"aid\":123}}");
            posts.incrementAndGet(); assertNotNull(form);
            assertTrue(endpoint.equals("/x/v2/reply/action") || endpoint.equals("/x/v2/reply/add"));
            assertTrue(form.contains("oid=123")); assertTrue(form.contains("csrf=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
            if (failPost) throw new IllegalArgumentException("Controlled uncertain send outcome");
            return new JSONObject("{\"code\":0,\"data\":{\"reply\":{\"oid\":123,\"rpid_str\":\"33\",\"root\":0}}}");
        };
    }
    @Test public void observedTargetsAreBoundToVideoAndSession() throws Exception {
        CommentTargets targets = targets(); assertEquals("0", targets.require("123", "11", SESSION));
        assertEquals("11", targets.require("123", "22", SESSION));
        rejects(() -> targets.require("456", "11", SESSION));
        rejects(() -> targets.require("123", "11", "other-session"));
        rejects(() -> targets.require("123", "999", SESSION));
        CommentTargets malformed = new CommentTargets();
        malformed.remember(URI.create("https://api.bilibili.com/x/v2/reply?oid=123"), READ.replace("\"code\":0", "\"code\":0.5"), SESSION);
        rejects(() -> malformed.require("123", "11", SESSION));
    }
    @Test public void verifiedLikeSendsExactlyOnce() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        JSObject result = BilibiliCommentActions.execute(like(), COOKIES, () -> true, targets(), transport(posts, 7, false));
        assertEquals("123", result.optString("oid")); assertEquals(1, posts.get());
    }
    @Test public void wrongAccountUnobservedTargetAndChangedSessionNeverPost() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        rejects(() -> BilibiliCommentActions.execute(like(), COOKIES, () -> true, targets(), transport(posts, 8, false)));
        rejects(() -> BilibiliCommentActions.execute(like(), COOKIES, () -> true, new CommentTargets(), transport(posts, 7, false)));
        rejects(() -> BilibiliCommentActions.execute(like(), COOKIES, () -> false, targets(), transport(posts, 7, false)));
        assertEquals(0, posts.get());
    }
    @Test public void failedSendIsNotRetried() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        rejects(() -> BilibiliCommentActions.execute(like(), COOKIES, () -> true, targets(), transport(posts, 7, true)));
        assertEquals(1, posts.get());
    }
    @Test public void nestedReplyCannotBecomeTopLevelRootAndCreatedTargetIsRemembered() throws Exception {
        AtomicInteger posts = new AtomicInteger(); CommentTargets targets = targets();
        JSObject args = like(); args.put("action", "create"); args.put("message", "受控虚构评论"); args.put("rootId", "22"); args.put("parentId", "22");
        rejects(() -> BilibiliCommentActions.execute(args, COOKIES, () -> true, targets, transport(posts, 7, false)));
        assertEquals(0, posts.get()); args.put("rootId", ""); args.put("parentId", "");
        BilibiliCommentActions.execute(args, COOKIES, () -> true, targets, transport(posts, 7, false));
        assertEquals(1, posts.get()); assertEquals("0", targets.require("123", "33", SESSION));
    }
}
