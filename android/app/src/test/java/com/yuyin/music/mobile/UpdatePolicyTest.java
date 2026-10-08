package com.yuyin.music.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    @Test public void onlyThisRepositoryAndItsReleaseCdnAreAvailable() throws Exception {
        assertEquals(UpdatePolicy.MANIFEST_URL, UpdatePolicy.requestUri(UpdatePolicy.MANIFEST_URL, false).toString());
        assertEquals(UpdatePolicy.artifactUrl("0.1.3"), UpdatePolicy.requestUri(UpdatePolicy.artifactUrl("0.1.3"), false).toString());
        assertNotNull(UpdatePolicy.requestUri("https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=synthetic", true));
        String[] denied = {"https://attacker.example/a.apk", "https://github.com/other/repository/releases/download/android-v0.1.3/Yuyin-Mobile-0.1.3.apk", UpdatePolicy.artifactUrl("0.1.3") + "?token=x", "http://github.com/panda472328/yuyin-music-mobile/releases/download/android-v0.1.3/Yuyin-Mobile-0.1.3.apk", "https://user:pass@release-assets.githubusercontent.com/github-production-release-asset/123/abc", "https://release-assets.githubusercontent.com/other/path", "https://github.com:443/panda472328/yuyin-music-mobile/releases/download/android-v0.1.3/Yuyin-Mobile-0.1.3.apk"};
        for (String address : denied) {
            try { UpdatePolicy.requestUri(address, true); fail(address); } catch (java.net.URISyntaxException expected) {}
        }
        try { UpdatePolicy.requestUri("https://release-assets.githubusercontent.com/github-production-release-asset/123/abc", false); fail("Initial requests cannot use the CDN"); } catch (java.net.URISyntaxException expected) {}
    }
    @Test public void updateVersionsAndNumbersRejectCoercionAndOverflow() {
        assertTrue(UpdatePolicy.validVersion("0.1.3"));
        for (String version : new String[] {"01.2.3", "0.1.3-beta", "../x", "1.2", "1.2.3.4"}) assertFalse(version, UpdatePolicy.validVersion(version));
        assertEquals(4, UpdatePolicy.positiveInteger(4, 100));
        for (Object value : new Object[] {"4", 4.5, Double.POSITIVE_INFINITY, 0, -1, 101}) assertEquals(-1, UpdatePolicy.positiveInteger(value, 100));
    }
    @Test public void downloadedSignersMustMatchTheInstalledApplication() {
        assertTrue(UpdatePolicy.sameSignatures(new String[] {"signer-a"}, new String[] {"signer-a"}));
        assertTrue(UpdatePolicy.sameSignatures(new String[] {"b", "a"}, new String[] {"a", "b"}));
        assertFalse(UpdatePolicy.sameSignatures(new String[] {"a"}, new String[] {"a", "b"}));
        assertFalse(UpdatePolicy.sameSignatures(new String[] {"a"}, new String[] {"different"}));
        assertFalse(UpdatePolicy.sameSignatures(new String[] {}, new String[] {}));
    }
}
