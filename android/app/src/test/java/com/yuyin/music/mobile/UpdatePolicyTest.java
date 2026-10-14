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
    @Test public void domesticRequestsAreLimitedToMobileContentsAndNumericAttachmentIds() throws Exception {
        assertNotNull(UpdatePolicy.requestUri(UpdatePolicy.DOMESTIC_MANIFEST_URL, false));
        assertNotNull(UpdatePolicy.requestUri(UpdatePolicy.domesticArtifactUrl(12345, 67890), false));
        for (String address : new String[] {UpdatePolicy.DOMESTIC_MANIFEST_URL.replace("openmusic-mobile", "openmusic"), UpdatePolicy.DOMESTIC_MANIFEST_URL.replace("ref=main", "ref=other"), UpdatePolicy.domesticArtifactUrl(12345, 67890) + "?access_token=synthetic", UpdatePolicy.GITEE_API + "/releases/0/attach_files/1/download", UpdatePolicy.GITEE_API + "/releases/2147483648/attach_files/1/download"}) {
            try { UpdatePolicy.requestUri(address, false); fail(address); } catch (java.net.URISyntaxException expected) {}
        }
        assertTrue(UpdatePolicy.compareVersions("0.1.10", "0.1.9") > 0);
        assertEquals(0, UpdatePolicy.compareVersions("0.1.4", "0.1.4"));
    }
    @Test public void forudaIsAllowedOnlyAfterTheOfficialApiWithTheExactFilename() throws Exception {
        String origin = UpdatePolicy.domesticArtifactUrl(12345, 67890), filename = UpdatePolicy.filename("0.1.4");
        String redirect = "https://foruda.gitee.com/attach_file/1662036200660558629/" + filename + "?token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&ts=1791780000&attname=" + filename;
        assertNotNull(UpdatePolicy.domesticRedirect(redirect, origin, filename));
        assertNotNull(UpdatePolicy.domesticRedirect(redirect.replace("/Yuyin-Mobile", "/yuyin-mobile"), origin, filename));
        for (String address : new String[] {redirect.replace("https:", "http:"), redirect.replace("foruda.gitee.com", "foruda.gitee.com.evil.example"), redirect.replace("foruda.gitee.com", "user:pass@foruda.gitee.com"), redirect.replace("foruda.gitee.com", "foruda.gitee.com:443"), redirect.replace("/attach_file/", "/other/"), redirect.replace("0.1.4", "0.1.5"), redirect + "&access_token=synthetic", redirect + "&token=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", redirect.replace("ts=1791780000", "ts=invalid"), redirect + "#fragment", redirect.replace("attname=Yuyin", "attname=yuyin")}) {
            try { UpdatePolicy.domesticRedirect(address, origin, filename); fail("Unexpected redirect"); } catch (java.net.URISyntaxException expected) {}
        }
        try { UpdatePolicy.requestUri(redirect, true); fail("CDN cannot be an initial URL"); } catch (java.net.URISyntaxException expected) {}
        try { UpdatePolicy.domesticRedirect(redirect, UpdatePolicy.artifactUrl("0.1.4"), filename); fail("Foreign origin"); } catch (java.net.URISyntaxException expected) {}
    }
}
