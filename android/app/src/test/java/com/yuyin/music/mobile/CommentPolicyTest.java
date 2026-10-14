package com.yuyin.music.mobile;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class CommentPolicyTest {
    @Test public void onlyNativeCsrfWithAnUnambiguousCookieCanAuthorizeWrites() {
        String fake = "a".repeat(32);
        assertEquals(fake, CommentPolicy.csrf("buvid3=fake; bili_jct=" + fake));
        for (String invalid : new String[]{"", "bili_jct=bad", "bili_jct=" + fake + ";bili_jct=" + fake}) {
            try { CommentPolicy.csrf(invalid); fail("must reject invalid fixture"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void replyIdentifiersAndIdentityStayWithinSupportedBoundaries() {
        assertTrue(CommentPolicy.validId("1234567890123456789"));
        assertFalse(CommentPolicy.validId("0")); assertFalse(CommentPolicy.validId("1&csrf=fake"));
        assertFalse(CommentPolicy.validMid(0)); assertFalse(CommentPolicy.validMid(9007199254740992L));
    }
    @Test public void publicCommentTextCannotInjectAnotherFormField() {
        Map<String,String> data = new LinkedHashMap<>(); data.put("message", "测试 &csrf=other+value");
        assertEquals("message=%E6%B5%8B%E8%AF%95+%26csrf%3Dother%2Bvalue", CommentPolicy.form(data));
    }
    @Test public void readsCannotInvokeMutationEndpointsOrUnknownSuggestionHosts() throws Exception {
        assertEquals("s.search.bilibili.com", NativePolicy.requestUri("https://s.search.bilibili.com/main/suggest?term=test").getHost());
        NativePolicy.requestUri("https://api.bilibili.com/x/v2/reply?oid=123&type=1");
        for (String path : new String[]{"https://api.bilibili.com/x/v2/reply/add", "https://api.bilibili.com/x/v2/reply/action", "https://s.search.bilibili.com/other"}) {
            try { NativePolicy.requestUri(path); fail("must reject write through read bridge"); }
            catch (java.net.URISyntaxException expected) { }
        }
    }
}
