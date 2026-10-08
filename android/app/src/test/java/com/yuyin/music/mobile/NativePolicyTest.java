package com.yuyin.music.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativePolicyTest {
    @Test public void credentialsStayInsideKnownHttpsApiEndpoints() throws Exception {
        assertEquals("api.bilibili.com", NativePolicy.requestUri("https://api.bilibili.com/x/web-interface/nav").getHost());
        assertEquals("lrclib.net", NativePolicy.requestUri("https://lrclib.net/api/search?q=hello").getHost());
        assertEquals("aisubtitle.hdslb.com", NativePolicy.requestUri("https://aisubtitle.hdslb.com/bfs/ai_subtitle/hello.json").getHost());
        String[] denied = {
                "http://api.bilibili.com/x/web-interface/nav", "https://api.bilibili.com:443/x/web-interface/nav",
                "https://attacker@api.bilibili.com/x/web-interface/nav", "https://api.bilibili.com.attacker.example/x/web-interface/nav",
                "https://api.bilibili.com/x/web-interface/nav#secret", "https://api.bilibili.com/x/passport/login",
                "https://lrclib.net/admin", "https://aisubtitle.hdslb.com/other", "file:///data/data/com.yuyin.music.mobile/files/preferences.json"
        };
        for (String address : denied) {
            try { NativePolicy.requestUri(address); fail("Should reject " + address); }
            catch (java.net.URISyntaxException expected) { /* explicit boundary */ }
        }
    }
    @Test public void remoteViewsAcceptOfficialHttpsPagesWithoutNativeSchemes() {
        assertTrue(NativePolicy.officialPage("https://passport.bilibili.com/login"));
        assertTrue(NativePolicy.officialPage("https://www.bilibili.com/"));
        assertFalse(NativePolicy.officialPage("intent://bilibili.com/login"));
        assertFalse(NativePolicy.officialPage("file:///android_asset/public/index.html"));
        assertFalse(NativePolicy.officialPage("https://bilibili.com.attacker.example/login"));
        assertFalse(NativePolicy.officialPage("https://name:secret@passport.bilibili.com/login"));
    }
    @Test public void videoNavigationAndStorageDoNotEscapeTheirNamespace() {
        assertTrue(NativePolicy.expectedVideo("https://www.bilibili.com/video/BV1ab411c7XY/?p=1", "BV1ab411c7XY"));
        assertFalse(NativePolicy.expectedVideo("https://www.bilibili.com/video/BV1ab411c7XZ/", "BV1ab411c7XY"));
        assertFalse(NativePolicy.expectedVideo("https://www.bilibili.com/video/BV1ab411c7XY/other", "BV1ab411c7XY"));
        assertFalse(NativePolicy.expectedVideo("https://www.bilibili.com/video/BV1ab411c7XY/?p=2", "BV1ab411c7XY"));
        assertFalse(NativePolicy.expectedVideo("https://www.bilibili.com/video/BV1ab411c7XY/?p=1&%70=2", "BV1ab411c7XY"));
        assertFalse(NativePolicy.validBvid("../../preferences"));
        assertTrue(NativePolicy.validStoreKey("preferences"));
        assertTrue(NativePolicy.validStoreKey("library"));
        assertFalse(NativePolicy.validStoreKey("../preferences"));
        assertFalse(NativePolicy.validStoreKey("pc-library"));
    }
    @Test public void playbackSessionWatchIgnoresTelemetryCookiesAndCookieOrder() {
        assertEquals(NativePolicy.authenticationSignature("SESSDATA=one; DedeUserID=10; buvid3=random"),
                NativePolicy.authenticationSignature("buvid3=new; DedeUserID=10; SESSDATA=one"));
        assertNotEquals(NativePolicy.authenticationSignature("SESSDATA=one; DedeUserID=10"),
                NativePolicy.authenticationSignature("SESSDATA=two; DedeUserID=10"));
    }
}
