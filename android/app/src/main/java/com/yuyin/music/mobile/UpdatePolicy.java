package com.yuyin.music.mobile;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;

/** URL and version rules deliberately separate from the account request bridge. */
final class UpdatePolicy {
    static final String MANIFEST_URL = "https://raw.githubusercontent.com/panda472328/yuyin-music-mobile/main/updates/stable.json";
    static final String GITEE_API = "https://gitee.com/api/v5/repos/openmusic/openmusic-mobile";
    static final String DOMESTIC_MANIFEST_URL = GITEE_API + "/contents/updates/domestic.json?ref=main";
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
    static String filename(String version) {
        if (!validVersion(version)) throw new IllegalArgumentException("INVALID_VERSION");
        return "Yuyin-Mobile-" + version + ".apk";
    }
    static String domesticArtifactUrl(long releaseId, long attachmentId) {
        if (releaseId < 1 || releaseId > Integer.MAX_VALUE || attachmentId < 1 || attachmentId > Integer.MAX_VALUE) throw new IllegalArgumentException("INVALID_ATTACHMENT_ID");
        return GITEE_API + "/releases/" + releaseId + "/attach_files/" + attachmentId + "/download";
    }
    static String domesticNotesUrl(String version) { return "https://gitee.com/openmusic/openmusic-mobile/releases/tag/android-v" + version; }
    static int compareVersions(String left, String right) {
        if (!validVersion(left) || !validVersion(right)) throw new IllegalArgumentException("INVALID_VERSION");
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < 3; i++) { int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i])); if (comparison != 0) return comparison; }
        return 0;
    }
    static URI requestUri(String value, boolean redirectedArtifact) throws URISyntaxException {
        if (value == null || value.length() > 8192) throw new URISyntaxException("", "Invalid update URL");
        URI uri = new URI(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) throw new URISyntaxException("", "Invalid update URL");
        boolean allowed = MANIFEST_URL.equals(value) || DOMESTIC_MANIFEST_URL.equals(value);
        String path = uri.getRawPath();
        if ("github.com".equals(uri.getHost()) && uri.getRawQuery() == null && path != null) {
            String prefix = "/panda472328/yuyin-music-mobile/releases/download/android-v";
            if (path.startsWith(prefix)) {
                String[] segments = path.substring(prefix.length()).split("/", -1);
                allowed = segments.length == 2 && validVersion(segments[0]) && artifactUrl(segments[0]).equals(value);
            }
        }
        if (redirectedArtifact && "release-assets.githubusercontent.com".equals(uri.getHost()) && path != null && path.startsWith("/github-production-release-asset/")) allowed = true;
        if (value.startsWith(GITEE_API + "/releases/") && uri.getRawQuery() == null) {
            String[] segments = value.substring((GITEE_API + "/releases/").length()).split("/", -1);
            if (segments.length == 4 && "attach_files".equals(segments[1]) && "download".equals(segments[3]) && segments[0].matches("[1-9][0-9]{0,9}") && segments[2].matches("[1-9][0-9]{0,9}")) {
                long release = Long.parseLong(segments[0]), attachment = Long.parseLong(segments[2]);
                allowed = release <= Integer.MAX_VALUE && attachment <= Integer.MAX_VALUE && domesticArtifactUrl(release, attachment).equals(value);
            }
        }
        if (!allowed) throw new URISyntaxException("", "Update address is not allowed");
        return uri;
    }
    /** One official attachment API -> Foruda hop, bound to the exact APK filename. */
    static URI domesticRedirect(String address, String initial, String expectedFilename) throws URISyntaxException {
        URI origin = requestUri(initial, false);
        if (!origin.toString().startsWith(GITEE_API + "/releases/") || expectedFilename == null || !expectedFilename.matches("Yuyin-Mobile-(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.apk")) throw new URISyntaxException("", "Invalid attachment origin");
        if (address == null || address.length() > 8192) throw new URISyntaxException("", "Invalid attachment redirect");
        URI uri = new URI(address);
        if (!"https".equals(uri.getScheme()) || !"foruda.gitee.com".equals(uri.getHost()) || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null || uri.getRawPath() == null || !uri.getRawPath().matches("(?i)/attach_file/[1-9][0-9]{0,18}/" + java.util.regex.Pattern.quote(expectedFilename))) throw new URISyntaxException("", "Invalid attachment redirect");
        String query = uri.getRawQuery();
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        if (query == null || query.split("&", -1).length != 3) throw new URISyntaxException("", "Invalid attachment signature");
        for (String field : query.split("&", -1)) {
            String[] pair = field.split("=", -1);
            if (pair.length != 2 || fields.put(pair[0], pair[1]) != null) throw new URISyntaxException("", "Invalid attachment signature");
        }
        if (!fields.getOrDefault("token", "").matches("[0-9a-f]{32}") || !fields.getOrDefault("ts", "").matches("[0-9]{10}") || !expectedFilename.equals(fields.get("attname"))) throw new URISyntaxException("", "Invalid attachment signature");
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
