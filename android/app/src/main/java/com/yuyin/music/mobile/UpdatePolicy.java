package com.yuyin.music.mobile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;

/** URL and version rules deliberately separate from the account request bridge. */
final class UpdatePolicy {
    static final String MANIFEST_URL = "https://raw.githubusercontent.com/panda472328/yuyin-music-mobile/main/updates/stable.json";
    static final long MAX_APK_BYTES = 200000000L;
    private UpdatePolicy() {}
    static boolean validVersion(String version) {
        return version != null && version.matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})");
    }
    static String artifactUrl(String version) {
        if (!validVersion(version)) throw new IllegalArgumentException("INVALID_VERSION");
        return "https://github.com/panda472328/yuyin-music-mobile/releases/download/android-v" + version + "/Yuyin-Mobile-" + version + ".apk";
    }
    static String releaseNotesUrl(String version) { return "https://github.com/panda472328/yuyin-music-mobile/releases/tag/android-v" + version; }
    static URI requestUri(String value, boolean redirectedArtifact) throws URISyntaxException {
        if (value == null || value.length() > 8192) throw new URISyntaxException("", "Invalid update URL");
        URI uri = new URI(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) throw new URISyntaxException("", "Invalid update URL");
        boolean allowed = MANIFEST_URL.equals(value);
        String path = uri.getRawPath();
        if ("github.com".equals(uri.getHost()) && uri.getRawQuery() == null && path != null) {
            String prefix = "/panda472328/yuyin-music-mobile/releases/download/android-v";
            if (path.startsWith(prefix)) {
                String[] segments = path.substring(prefix.length()).split("/", -1);
                allowed = segments.length == 2 && validVersion(segments[0]) && artifactUrl(segments[0]).equals(value);
            }
        }
        if (redirectedArtifact && "release-assets.githubusercontent.com".equals(uri.getHost()) && path != null && path.startsWith("/github-production-release-asset/")) allowed = true;
        if (!allowed) throw new URISyntaxException("", "Update address is not allowed");
        return uri;
    }
    static long positiveInteger(Object value, long max) {
        if (!(value instanceof Number)) return -1;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.floor(number) && number >= 1 && number <= max ? ((Number) value).longValue() : -1;
    }
    static boolean sameSignatures(String[] installed, String[] downloaded) {
        if (installed == null || downloaded == null || installed.length == 0 || downloaded.length == 0) return false;
        return new HashSet<>(Arrays.asList(installed)).equals(new HashSet<>(Arrays.asList(downloaded)));
    }
}
