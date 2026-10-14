package com.yuyin.music.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Checks and stages a signed release without exposing arbitrary native URLs to the WebView. */
public final class UpdateManager {
    public static final String MANIFEST_URL = UpdatePolicy.MANIFEST_URL;
    private static final long MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_CONTENTS_BYTES = 128 * 1024;
    private static final long CHECK_BUDGET_MS = 12000;
    private static final long MAX_APK_BYTES = UpdatePolicy.MAX_APK_BYTES;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    public interface Listener { void onState(JSObject state); }

    private final Context context;
    private final Handler main;
    private final ExecutorService io;
    private final Listener listener;
    private final Object lock = new Object();
    private JSObject state;
    private UpdateManifest available;
    private File stagedApk;
    private boolean destroyed;
    private boolean installPending;
    private boolean downloadPending;
    private long revision;
    private long installGeneration;
    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;
    private final Set<HttpURLConnection> manifestConnections = ConcurrentHashMap.newKeySet();

    public UpdateManager(Context context, Handler main, ExecutorService io, Listener listener) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.io = io;
        this.listener = listener;
        this.state = baseState("idle");
    }

    public JSObject status() {
        synchronized (lock) { return copy(state); }
    }

    public void destroy() {
        synchronized (lock) { destroyed = true; cancelled = true; installGeneration++; }
        if (activeConnection != null) activeConnection.disconnect();
        for (HttpURLConnection connection : manifestConnections) connection.disconnect();
    }

    public void check(Consumer<JSObject> completion) {
        synchronized (lock) {
            if (destroyed) { postCompletion(completion, errorState("应用已关闭。", "PLUGIN_DISPOSED")); return; }
            String current = state.optString("state");
            if (installPending || downloadPending || "checking".equals(current) || "downloading".equals(current)) {
                postCompletion(completion, copy(state));
                return;
            }
            if (stagedApk != null && stagedApk.isFile() && available != null) { postCompletion(completion, copy(state)); return; }
            publish(baseState("checking"));
        }
        io.execute(() -> {
            JSObject result;
            try {
                UpdateManifest manifest = fetchManifest();
                long installed = installedVersionCode();
                synchronized (lock) { manifest = UpdateManifest.select(manifest, available); }
                if (manifest.versionCode > installed && UpdatePolicy.compareVersions(manifest.version, installedVersionName()) <= 0) throw new UpdateException("更新版本号未递增，已停止更新。", "UPDATE_SOURCE_CONFLICT");
                if (manifest.versionCode <= installed) {
                    synchronized (lock) { available = null; }
                    result = baseState("upToDate");
                    result.put("installedVersionCode", installed);
                    result.put("checkedAt", System.currentTimeMillis());
                } else {
                    synchronized (lock) { available = manifest; }
                    result = availableState(manifest, installed);
                }
            } catch (UpdateException failure) {
                if ("UPDATE_SOURCE_CONFLICT".equals(failure.code)) synchronized (lock) { available = null; }
                result = errorState(failure.getMessage(), failure.code);
            } catch (Exception failure) {
                result = errorState("检查更新失败，请稍后重试。", "UPDATE_CHECK_FAILED");
            }
            publish(result);
            postCompletion(completion, result);
        });
    }

    public void download(Consumer<JSObject> completion) {
        final UpdateManifest manifest;
        synchronized (lock) {
            if (destroyed) { postCompletion(completion, errorState("应用已关闭。", "PLUGIN_DISPOSED")); return; }
            manifest = available;
            if (installPending || downloadPending || "checking".equals(state.optString("state")) || "downloading".equals(state.optString("state"))) { postCompletion(completion, copy(state)); return; }
            if (manifest == null || manifest.versionCode <= installedVersionCode()) {
                postCompletion(completion, errorState("暂时没有可下载的更新，请先检查更新。", "NO_UPDATE"));
                return;
            }
            cancelled = false;
            downloadPending = true;
            publish(downloadState(manifest, 0));
        }
        io.execute(() -> {
            JSObject result;
            try {
                File apk = downloadAndVerify(manifest);
                synchronized (lock) { stagedApk = apk; result = readyState(available, apk); }
            } catch (UpdateException failure) {
                result = errorState(failure.getMessage(), failure.code);
            } catch (Exception failure) {
                result = errorState("下载更新失败，请重试。", "UPDATE_DOWNLOAD_FAILED");
            }
            synchronized (lock) {
                if (cancelled) result = availableState(available, installedVersionCode());
                downloadPending = false;
                publish(result);
            }
            postCompletion(completion, result);
        });
    }

    public JSObject cancel() {
        synchronized (lock) {
            if (!downloadPending) return copy(state);
            cancelled = true;
            if (available != null) publish(availableState(available, installedVersionCode()));
        }
        if (activeConnection != null) activeConnection.disconnect();
        return status();
    }

    public void resume() {
        synchronized (lock) {
            if (stagedApk != null && available != null && ("permissionRequired".equals(state.optString("state")) || "installing".equals(state.optString("state")))) publish(readyState(available, stagedApk));
        }
    }

    public void install(Consumer<JSObject> completion) {
        final File apk;
        final UpdateManifest manifest;
        final long generation;
        synchronized (lock) {
            if (destroyed) { postCompletion(completion, errorState("应用已关闭。", "PLUGIN_DISPOSED")); return; }
            if (installPending || downloadPending || "installing".equals(state.optString("state")) || "permissionRequired".equals(state.optString("state"))) { postCompletion(completion, copy(state)); return; }
            apk = stagedApk; manifest = available;
            if (apk == null || manifest == null || !apk.isFile()) { postCompletion(completion, errorState("请先下载更新。", "UPDATE_NOT_READY")); return; }
            installPending = true; generation = ++installGeneration;
        }
        io.execute(() -> {
            try { verifyApk(apk, manifest); }
            catch (Exception failure) {
                synchronized (lock) { if (destroyed || generation != installGeneration) return; stagedApk = null; installPending = false; }
                JSObject result = errorState("更新文件验证失败，请重新下载。", "UPDATE_REVERIFY_FAILED"); apk.delete(); publish(result); postCompletion(completion, result); return;
            }
            main.post(() -> {
                synchronized (lock) { if (destroyed || generation != installGeneration || stagedApk != apk || available != manifest) { installPending = false; return; } }
                JSObject result = launchInstall(apk, manifest);
                synchronized (lock) { installPending = false; }
                postCompletion(completion, result);
            });
        });
    }

    private JSObject launchInstall(File apk, UpdateManifest manifest) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
                Intent permission = new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + context.getPackageName()));
                permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(permission);
                JSObject result = availableState(manifest, installedVersionCode()); result.put("state", "permissionRequired");
                result.put("message", "请允许 open音乐安装更新，然后返回应用继续安装。");
                publish(result);
                return result;
            }
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(install);
            JSObject result = availableState(manifest, installedVersionCode()); result.put("state", "installing");
            result.put("message", "已打开系统安装确认。");
            publish(result);
            return result;
        } catch (Exception failure) {
            JSObject result = errorState("无法打开系统安装确认，请稍后重试。", "INSTALL_START_FAILED");
            publish(result);
            return result;
        }
    }

    private UpdateManifest fetchManifest() throws Exception {
        // Separate workers and one bounded wait: an inaccessible mirror cannot
        // consume the other source's connection budget or hide a newer release.
        ExecutorService sources = Executors.newFixedThreadPool(2);
        long deadline = SystemClock.elapsedRealtime() + CHECK_BUDGET_MS;
        Future<UpdateManifest> domestic = sources.submit(() -> fetchSource(UpdatePolicy.DOMESTIC_MANIFEST_URL, deadline));
        Future<UpdateManifest> github = sources.submit(() -> fetchSource(MANIFEST_URL, deadline));
        try {
            UpdateManifest a = awaitSource(domestic, deadline), b = awaitSource(github, deadline);
            if (a == null && b == null) throw new UpdateException("两个更新源暂时都不可用，请稍后重试。", "UPDATE_CHECK_FAILED");
            return UpdateManifest.select(a, b);
        } finally {
            domestic.cancel(true); github.cancel(true); sources.shutdownNow();
            for (HttpURLConnection connection : manifestConnections) connection.disconnect();
        }
    }

    private static UpdateManifest awaitSource(Future<UpdateManifest> task, long deadline) {
        try {
            if (task.isDone()) return task.get();
            return task.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        } catch (Exception unavailable) { return null; }
    }

    private UpdateManifest fetchSource(String url, long deadline) throws Exception {
        HttpURLConnection connection = open(url, false, null, deadline);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new UpdateException("更新服务返回 HTTP " + status + "。", "UPDATE_HTTP_ERROR");
            boolean domestic = UpdatePolicy.DOMESTIC_MANIFEST_URL.equals(url);
            byte[] data = readLimited(connection, domestic ? MAX_CONTENTS_BYTES : MAX_MANIFEST_BYTES, deadline);
            String body = strictUtf8(data);
            return domestic ? UpdateManifest.parseDomesticContents(body) : UpdateManifest.parse(body);
        } finally { connection.disconnect(); manifestConnections.remove(connection); }
    }

    private File downloadAndVerify(UpdateManifest manifest) throws Exception {
        try { return downloadFromSource(manifest); }
        catch (Exception failure) {
            if (cancelled || destroyed || !"gitee".equals(manifest.provider)) throw failure;
            // The fixed GitHub URL must match the selected domestic bytes and
            // version, then pass the same package and installed-signature checks.
            UpdateManifest fallback = manifest.githubFallback();
            synchronized (lock) { available = fallback; }
            JSObject next = downloadState(fallback, 0); next.put("message", "国内下载暂不可用，正在尝试 GitHub 备用源。"); publish(next);
            return downloadFromSource(fallback);
        }
    }

    private File downloadFromSource(UpdateManifest manifest) throws Exception {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) throw new UpdateException("无法创建更新缓存目录。", "UPDATE_STORAGE_FAILED");
        // Android archive signature parsing expects an .apk file, including during verification.
        File temporary = new File(directory, "Yuyin-Mobile-" + manifest.version + ".download.apk");
        File target = new File(directory, "Yuyin-Mobile-" + manifest.version + ".apk");
        if (target.isFile()) {
            try { verifyApk(target, manifest); return target; }
            catch (Exception ignored) { if (!target.delete()) throw new UpdateException("更新缓存已损坏，请清理空间后重试。", "UPDATE_STORAGE_FAILED"); }
        }
        if (temporary.exists() && !temporary.delete()) throw new UpdateException("无法清理旧的更新下载。", "UPDATE_STORAGE_FAILED");
        if (cancelled) throw new UpdateException("下载已取消。", "UPDATE_CANCELLED");
        long deadline = SystemClock.elapsedRealtime() + ("gitee".equals(manifest.provider) ? 2 * 60 * 1000L : 15 * 60 * 1000L);
        HttpURLConnection connection = open(manifest.url, true, UpdatePolicy.filename(manifest.version), deadline);
        activeConnection = connection;
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new UpdateException("更新下载返回 HTTP " + status + "。", "UPDATE_HTTP_ERROR");
            long length = connection.getContentLengthLong();
            if (length > 0 && length != manifest.size) throw new UpdateException("更新文件大小校验失败。", "UPDATE_SIZE_MISMATCH");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int count;
                int previousProgress = -1;
                while (true) {
                    if (cancelled) throw new UpdateException("下载已取消。", "UPDATE_CANCELLED");
                    connection.setReadTimeout(remainingTimeout(deadline, READ_TIMEOUT_MS));
                    count = input.read(buffer);
                    if (count == -1) break;
                    total += count;
                    if (total > MAX_APK_BYTES || total > manifest.size || SystemClock.elapsedRealtime() > deadline) {
                        throw new UpdateException("更新文件超出允许大小或下载超时。", "UPDATE_DOWNLOAD_LIMIT");
                    }
                    output.write(buffer, 0, count);
                    int progress = (int) Math.min(99, total * 100 / manifest.size);
                    if (progress != previousProgress) { publish(downloadState(manifest, progress)); previousProgress = progress; }
                }
                output.flush();
                if (total != manifest.size) throw new UpdateException("更新文件大小校验失败。", "UPDATE_SIZE_MISMATCH");
            }
            verifyApk(temporary, manifest);
            if (!temporary.renameTo(target)) throw new UpdateException("无法保存更新文件。", "UPDATE_STORAGE_FAILED");
            return target;
        } finally { connection.disconnect(); activeConnection = null; if (temporary.isFile()) temporary.delete(); }
    }

    private void verifyApk(File apk, UpdateManifest manifest) throws Exception {
        if (!apk.isFile() || apk.length() != manifest.size) throw new UpdateException("更新文件大小校验失败。", "UPDATE_SIZE_MISMATCH");
        if (!manifest.sha256.equalsIgnoreCase(sha256(apk))) throw new UpdateException("更新文件完整性校验失败。", "UPDATE_HASH_MISMATCH");
        int flags = PackageManager.GET_SIGNATURES | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0);
        PackageManager manager = context.getPackageManager();
        PackageInfo info = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (info == null || !context.getPackageName().equals(info.packageName)) throw new UpdateException("更新包不是 open音乐 Android 版本。", "UPDATE_PACKAGE_MISMATCH");
        long versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (versionCode <= installedVersionCode()) throw new UpdateException("更新版本必须高于已安装版本。", "UPDATE_VERSION_NOT_NEWER");
        if (versionCode != manifest.versionCode || !manifest.version.equals(info.versionName)) throw new UpdateException("更新包版本与清单不一致。", "UPDATE_VERSION_MISMATCH");
        Set<String> installed = certificateDigests(manager.getPackageInfo(context.getPackageName(), flags));
        Set<String> candidate = certificateDigests(info);
        if (!UpdatePolicy.sameSignatures(installed.toArray(new String[0]), candidate.toArray(new String[0]))) throw new UpdateException("更新包签名与已安装版本不一致。", "UPDATE_SIGNATURE_MISMATCH");
    }

    private Set<String> certificateDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        if (signatures == null) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }

    private HttpURLConnection open(String address, boolean artifact, String filename, long deadline) throws Exception {
        URI uri = UpdatePolicy.requestUri(address, false);
        boolean domestic = address.startsWith(UpdatePolicy.GITEE_API + "/releases/");
        for (int redirects = 0; redirects <= 4; redirects++) {
            if (artifact && cancelled) throw new UpdateException("下载已取消。", "UPDATE_CANCELLED");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(remainingTimeout(deadline, CONNECT_TIMEOUT_MS)); connection.setReadTimeout(remainingTimeout(deadline, READ_TIMEOUT_MS));
            connection.setRequestMethod("GET"); connection.setRequestProperty("User-Agent", "Yuyin-Mobile-Updater"); connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", artifact ? "application/vnd.android.package-archive" : "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (artifact) activeConnection = connection; else manifestConnections.add(connection);
            final int response;
            try { response = connection.getResponseCode(); }
            catch (Exception failure) { connection.disconnect(); manifestConnections.remove(connection); if (artifact) activeConnection = null; throw failure; }
            if (response != 301 && response != 302 && response != 303 && response != 307 && response != 308) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            manifestConnections.remove(connection);
            if (!artifact || location == null || redirects == 4 || (domestic && redirects != 0)) throw new UpdateException("更新地址跳转异常。", "UPDATE_REDIRECT_DENIED");
            uri = domestic ? UpdatePolicy.domesticRedirect(location, address, filename) : UpdatePolicy.requestUri(uri.resolve(location).toString(), true);
            if (!domestic && !address.equals(uri.toString()) && !"release-assets.githubusercontent.com".equals(uri.getHost())) throw new UpdateException("更新地址跳转异常。", "UPDATE_REDIRECT_DENIED");
        }
        throw new UpdateException("更新地址跳转过多。", "UPDATE_REDIRECT_DENIED");
    }

    private static int remainingTimeout(long deadline, int maximum) throws UpdateException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new UpdateException("更新请求超时，请稍后重试。", "UPDATE_TIMEOUT");
        return (int) Math.max(1, Math.min(maximum, remaining));
    }

    private static String strictUtf8(byte[] bytes) throws Exception {
        return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] readLimited(HttpURLConnection connection, long limit, long deadline) throws Exception {
        if (connection.getContentLengthLong() > limit) throw new UpdateException("更新清单过大。", "UPDATE_MANIFEST_LIMIT");
        try (InputStream input = connection.getInputStream()) {
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while (true) {
                connection.setReadTimeout(remainingTimeout(deadline, READ_TIMEOUT_MS));
                count = input.read(buffer); if (count == -1) break;
                if (result.size() + count > limit || SystemClock.elapsedRealtime() > deadline) throw new UpdateException("更新清单过大或响应超时。", "UPDATE_MANIFEST_LIMIT");
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }

    private long installedVersionCode() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { return Long.MAX_VALUE; }
    }

    private void publish(JSObject next) {
        synchronized (lock) { if (destroyed) return; next.put("revision", ++revision); state = copy(next); }
        main.post(() -> { synchronized (lock) { if (destroyed) return; } listener.onState(copy(next)); });
    }

    private void postCompletion(Consumer<JSObject> completion, JSObject result) { main.post(() -> completion.accept(copy(result))); }

    private JSObject baseState(String value) {
        JSObject result = new JSObject(); result.put("state", value); result.put("revision", revision); result.put("currentVersion", installedVersionName()); result.put("currentVersionCode", installedVersionCode()); return result;
    }
    private JSObject availableState(UpdateManifest manifest, long installed) {
        JSObject result = baseState("available"); result.put("installedVersionCode", installed); result.put("update", manifest.toJson()); return result;
    }
    private JSObject downloadState(UpdateManifest manifest, int progress) {
        JSObject result = availableState(manifest, installedVersionCode()); result.put("state", "downloading"); result.put("progress", progress); return result;
    }
    private JSObject readyState(UpdateManifest manifest, File file) {
        JSObject result = availableState(manifest, installedVersionCode()); result.put("state", "ready"); result.put("progress", 100); return result;
    }
    private JSObject errorState(String message, String code) { JSObject result = baseState("error"); if (available != null) result.put("update", available.toJson()); result.put("message", message == null ? "更新失败，请重试。" : message); result.put("code", code); result.put("retryable", true); return result; }
    private static JSObject copy(JSObject value) { try { return new JSObject(value.toString()); } catch (Exception ignored) { return new JSObject(); } }
    private String installedVersionName() { try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception ignored) { return ""; } }
    private static String hex(byte[] value) { StringBuilder result = new StringBuilder(value.length * 2); for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item & 0xff)); return result.toString(); }
    private static String sha256(File file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = new BufferedInputStream(new FileInputStream(file))) { byte[] buffer = new byte[64 * 1024]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); } return hex(digest.digest()); }

    public static final class UpdateManifest {
        final String version, url, sha256, releaseNotesUrl, notes, publishedAt, provider;
        final long versionCode, size, releaseId, attachmentId;
        private UpdateManifest(String version, long versionCode, String url, String sha256, long size, String releaseNotesUrl, String notes, String publishedAt, String provider, long releaseId, long attachmentId) { this.version = version; this.versionCode = versionCode; this.url = url; this.sha256 = sha256; this.size = size; this.releaseNotesUrl = releaseNotesUrl; this.notes = notes; this.publishedAt = publishedAt; this.provider = provider; this.releaseId = releaseId; this.attachmentId = attachmentId; }
        static UpdateManifest parse(String body) throws Exception {
            return parse(body, false);
        }
        static UpdateManifest parseDomesticContents(String body) throws Exception {
            if (body == null || body.length() > MAX_CONTENTS_BYTES) throw new UpdateException("国内更新清单过大。", "UPDATE_MANIFEST_LIMIT");
            JSONObject wrapper = new JSONObject(body);
            long size = UpdatePolicy.positiveInteger(wrapper.opt("size"), MAX_MANIFEST_BYTES);
            if (!"file".equals(wrapper.optString("type")) || !"base64".equals(wrapper.optString("encoding")) || !"domestic.json".equals(wrapper.optString("name")) || !"updates/domestic.json".equals(wrapper.optString("path")) || size < 1 || !(wrapper.opt("content") instanceof String)) throw new UpdateException("国内更新文件响应无效。", "UPDATE_MANIFEST_INVALID");
            String content = wrapper.getString("content");
            if (content.length() > MAX_CONTENTS_BYTES) throw new UpdateException("国内更新文件过大。", "UPDATE_MANIFEST_LIMIT");
            String compact = content.replace("\r", "").replace("\n", "");
            if (compact.length() > ((MAX_MANIFEST_BYTES + 2) / 3) * 4 || compact.length() % 4 != 0 || !compact.matches("[A-Za-z0-9+/]*={0,2}")) throw new UpdateException("国内更新编码无效。", "UPDATE_MANIFEST_INVALID");
            byte[] decoded = android.util.Base64.decode(compact, android.util.Base64.NO_WRAP);
            if (decoded.length != size || !android.util.Base64.encodeToString(decoded, android.util.Base64.NO_WRAP).equals(compact)) throw new UpdateException("国内更新编码或大小无效。", "UPDATE_MANIFEST_INVALID");
            return parse(strictUtf8(decoded), true);
        }
        private static UpdateManifest parse(String body, boolean domestic) throws Exception {
            if (body == null || body.length() > MAX_MANIFEST_BYTES) throw new UpdateException("更新清单过大。", "UPDATE_MANIFEST_LIMIT");
            JSONObject root = new JSONObject(body);
            if (UpdatePolicy.positiveInteger(root.opt("schemaVersion"), 2) != (domestic ? 2 : 1) || !"android".equals(root.optString("platform")) || (domestic && !"gitee".equals(root.optString("provider")))) throw new UpdateException("更新清单版本不受支持。", "UPDATE_MANIFEST_INVALID");
            String version = root.optString("version", "");
            if (!UpdatePolicy.validVersion(version)) throw new UpdateException("更新版本号无效。", "UPDATE_MANIFEST_INVALID");
            long versionCode = UpdatePolicy.positiveInteger(root.opt("versionCode"), 2100000000);
            JSONObject artifact = root.optJSONObject("artifact");
            if (versionCode < 1 || artifact == null) throw new UpdateException("更新清单缺少版本信息。", "UPDATE_MANIFEST_INVALID");
            String expectedUrl = UpdatePolicy.artifactUrl(version);
            long releaseId = 0, attachmentId = 0;
            if (domestic) {
                JSONObject source = root.optJSONObject("source");
                if (source == null) throw new UpdateException("国内附件身份缺失。", "UPDATE_MANIFEST_INVALID");
                releaseId = UpdatePolicy.positiveInteger(source.opt("releaseId"), Integer.MAX_VALUE);
                attachmentId = UpdatePolicy.positiveInteger(source.opt("attachmentId"), Integer.MAX_VALUE);
                if (releaseId < 1 || attachmentId < 1 || !UpdatePolicy.filename(version).equals(source.optString("filename"))) throw new UpdateException("国内附件身份无效。", "UPDATE_MANIFEST_INVALID");
                expectedUrl = UpdatePolicy.domesticArtifactUrl(releaseId, attachmentId);
            }
            String url = artifact.optString("url", "");
            String sha = artifact.optString("sha256", "");
            long size = UpdatePolicy.positiveInteger(artifact.opt("size"), MAX_APK_BYTES);
            if (!expectedUrl.equals(url) || sha.length() != 64 || !sha.matches("[0-9a-fA-F]{64}") || size < 1 || size > MAX_APK_BYTES) throw new UpdateException("更新文件信息无效。", "UPDATE_MANIFEST_INVALID");
            String releaseNotesUrl = root.optString("releaseNotesUrl", "");
            if (!(domestic ? UpdatePolicy.domesticNotesUrl(version) : UpdatePolicy.releaseNotesUrl(version)).equals(releaseNotesUrl)) throw new UpdateException("更新说明地址无效。", "UPDATE_MANIFEST_INVALID");
            if (!(root.opt("notes") instanceof String) || root.optString("notes").length() > 8000) throw new UpdateException("更新说明格式无效。", "UPDATE_MANIFEST_INVALID");
            String published = root.optString("publishedAt", "");
            if (!published.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3})?Z")) throw new UpdateException("更新时间格式无效。", "UPDATE_MANIFEST_INVALID");
            return new UpdateManifest(version, versionCode, url, sha.toLowerCase(Locale.ROOT), size, releaseNotesUrl, root.optString("notes"), published, domestic ? "gitee" : "github", releaseId, attachmentId);
        }
        static UpdateManifest select(UpdateManifest a, UpdateManifest b) throws UpdateException {
            if (a == null) return b;
            if (b == null) return a;
            int comparison = UpdatePolicy.compareVersions(a.version, b.version);
            if (comparison == 0 || a.versionCode == b.versionCode) {
                if (comparison != 0 || a.versionCode != b.versionCode || a.size != b.size || !a.sha256.equals(b.sha256)) throw new UpdateException("更新源的同版本文件不一致，已停止更新。", "UPDATE_SOURCE_CONFLICT");
                return "gitee".equals(a.provider) ? a : b;
            }
            if ((comparison > 0) != (a.versionCode > b.versionCode)) throw new UpdateException("更新源版本与构建编号不一致，已停止更新。", "UPDATE_SOURCE_CONFLICT");
            return comparison > 0 ? a : b;
        }
        UpdateManifest githubFallback() { return new UpdateManifest(version, versionCode, UpdatePolicy.artifactUrl(version), sha256, size, UpdatePolicy.releaseNotesUrl(version), notes, publishedAt, "github", 0, 0); }
        JSObject toJson() {
            JSObject result = new JSObject(); result.put("version", version); result.put("versionCode", versionCode); result.put("url", url); result.put("size", size); result.put("releaseNotesUrl", releaseNotesUrl); result.put("notes", notes); result.put("publishedAt", publishedAt); result.put("provider", provider);
            if ("gitee".equals(provider)) { JSObject source = new JSObject(); source.put("releaseId", releaseId); source.put("attachmentId", attachmentId); source.put("filename", UpdatePolicy.filename(version)); result.put("source", source); }
            return result;
        }
    }
    private static final class UpdateException extends Exception { final String code; UpdateException(String message, String code) { super(message); this.code = code; } }
}
