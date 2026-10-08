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
import java.util.function.Consumer;

/** Checks and stages a signed release without exposing arbitrary native URLs to the WebView. */
public final class UpdateManager {
    public static final String MANIFEST_URL = UpdatePolicy.MANIFEST_URL;
    private static final long MAX_MANIFEST_BYTES = 256 * 1024;
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
                synchronized (lock) { stagedApk = apk; }
                result = readyState(manifest, apk);
            } catch (UpdateException failure) {
                result = errorState(failure.getMessage(), failure.code);
            } catch (Exception failure) {
                result = errorState("下载更新失败，请重试。", "UPDATE_DOWNLOAD_FAILED");
            }
            synchronized (lock) {
                if (cancelled) result = availableState(manifest, installedVersionCode());
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
                result.put("message", "请允许余音安装更新，然后返回应用继续安装。");
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
        HttpURLConnection connection = open(MANIFEST_URL, false);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new UpdateException("更新服务返回 HTTP " + status + "。", "UPDATE_HTTP_ERROR");
            byte[] data = readLimited(connection.getInputStream(), MAX_MANIFEST_BYTES, 10000);
            return UpdateManifest.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    private File downloadAndVerify(UpdateManifest manifest) throws Exception {
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
        HttpURLConnection connection = open(manifest.url, true);
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
                long started = SystemClock.elapsedRealtime();
                int previousProgress = -1;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled) throw new UpdateException("下载已取消。", "UPDATE_CANCELLED");
                    total += count;
                    if (total > MAX_APK_BYTES || total > manifest.size || SystemClock.elapsedRealtime() - started > 15 * 60 * 1000L) {
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
        if (info == null || !context.getPackageName().equals(info.packageName)) throw new UpdateException("更新包不是余音 Android 版本。", "UPDATE_PACKAGE_MISMATCH");
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

    private HttpURLConnection open(String address, boolean artifact) throws Exception {
        URI uri = UpdatePolicy.requestUri(address, false);
        for (int redirects = 0; redirects <= 4; redirects++) {
            if (artifact && cancelled) throw new UpdateException("下载已取消。", "UPDATE_CANCELLED");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS); connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET"); connection.setRequestProperty("User-Agent", "Yuyin-Mobile-Updater"); connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", artifact ? "application/vnd.android.package-archive" : "application/json");
            if (artifact) activeConnection = connection;
            final int response;
            try { response = connection.getResponseCode(); }
            catch (Exception failure) { connection.disconnect(); if (artifact) activeConnection = null; throw failure; }
            if (response != 301 && response != 302 && response != 303 && response != 307 && response != 308) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (!artifact || location == null || redirects == 4) throw new UpdateException("更新地址跳转异常。", "UPDATE_REDIRECT_DENIED");
            uri = UpdatePolicy.requestUri(uri.resolve(location).toString(), true);
        }
        throw new UpdateException("更新地址跳转过多。", "UPDATE_REDIRECT_DENIED");
    }

    private static byte[] readLimited(InputStream source, long limit, long timeoutMs) throws Exception {
        try (InputStream input = source) {
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count; long started = SystemClock.elapsedRealtime();
            while ((count = input.read(buffer)) != -1) {
                if (result.size() + count > limit || SystemClock.elapsedRealtime() - started > timeoutMs) throw new UpdateException("更新清单过大或响应超时。", "UPDATE_MANIFEST_LIMIT");
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
        final String version, url, sha256, releaseNotesUrl, notes, publishedAt;
        final long versionCode, size;
        private UpdateManifest(String version, long versionCode, String url, String sha256, long size, String releaseNotesUrl, String notes, String publishedAt) { this.version = version; this.versionCode = versionCode; this.url = url; this.sha256 = sha256; this.size = size; this.releaseNotesUrl = releaseNotesUrl; this.notes = notes; this.publishedAt = publishedAt; }
        static UpdateManifest parse(String body) throws Exception {
            if (body == null || body.length() > MAX_MANIFEST_BYTES) throw new UpdateException("更新清单过大。", "UPDATE_MANIFEST_LIMIT");
            JSONObject root = new JSONObject(body);
            if (UpdatePolicy.positiveInteger(root.opt("schemaVersion"), 1) != 1 || !"android".equals(root.optString("platform"))) throw new UpdateException("更新清单版本不受支持。", "UPDATE_MANIFEST_INVALID");
            String version = root.optString("version", "");
            if (!UpdatePolicy.validVersion(version)) throw new UpdateException("更新版本号无效。", "UPDATE_MANIFEST_INVALID");
            long versionCode = UpdatePolicy.positiveInteger(root.opt("versionCode"), 2100000000);
            JSONObject artifact = root.optJSONObject("artifact");
            if (versionCode < 1 || artifact == null) throw new UpdateException("更新清单缺少版本信息。", "UPDATE_MANIFEST_INVALID");
            String expectedUrl = UpdatePolicy.artifactUrl(version);
            String url = artifact.optString("url", "");
            String sha = artifact.optString("sha256", "");
            long size = UpdatePolicy.positiveInteger(artifact.opt("size"), MAX_APK_BYTES);
            if (!expectedUrl.equals(url) || sha.length() != 64 || !sha.matches("[0-9a-fA-F]{64}") || size < 1 || size > MAX_APK_BYTES) throw new UpdateException("更新文件信息无效。", "UPDATE_MANIFEST_INVALID");
            String releaseNotesUrl = root.optString("releaseNotesUrl", "");
            if (!UpdatePolicy.releaseNotesUrl(version).equals(releaseNotesUrl)) throw new UpdateException("更新说明地址无效。", "UPDATE_MANIFEST_INVALID");
            if (!(root.opt("notes") instanceof String) || root.optString("notes").length() > 8000) throw new UpdateException("更新说明格式无效。", "UPDATE_MANIFEST_INVALID");
            String published = root.optString("publishedAt", "");
            if (!published.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3})?Z")) throw new UpdateException("更新时间格式无效。", "UPDATE_MANIFEST_INVALID");
            return new UpdateManifest(version, versionCode, url, sha.toLowerCase(Locale.ROOT), size, releaseNotesUrl, root.optString("notes"), published);
        }
        JSObject toJson() { JSObject result = new JSObject(); result.put("version", version); result.put("versionCode", versionCode); result.put("url", url); result.put("size", size); result.put("releaseNotesUrl", releaseNotesUrl); result.put("notes", notes); result.put("publishedAt", publishedAt); return result; }
    }
    private static final class UpdateException extends Exception { final String code; UpdateException(String message, String code) { super(message); this.code = code; } }
}
