package com.yuyin.music.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Anonymous memory HTTP and a future debug fixture, never a real account or release key. */
@RunWith(AndroidJUnit4.class)
public class ControlledDomesticUpdateTest {
    private static final String VERSION = "1.0.0";
    private static final int CODE = 100;
    private static final String DOMESTIC_APK = UpdatePolicy.domesticArtifactUrl(12345, 67890);
    private static final String CDN = "https://foruda.gitee.com/attach_file/1662036200660558629/" + UpdatePolicy.filename(VERSION) + "?token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&ts=1791780000&attname=" + UpdatePolicy.filename(VERSION);

    @Test public void contentsRequiresExactFileCanonicalBase64AndUtf8() throws Exception {
        JSONObject valid = contents(manifest(new byte[] {1, 2, 3}, true));
        assertEquals("gitee", UpdateManager.UpdateManifest.parseDomesticContents(valid.toString()).provider);
        for (String key : new String[] {"type", "encoding", "name", "path"}) {
            JSONObject invalid = new JSONObject(valid.toString()).put(key, "unexpected");
            rejectContents(invalid);
        }
        rejectContents(new JSONObject(valid.toString()).put("size", 1));
        rejectContents(new JSONObject(valid.toString()).put("size", "3"));
        rejectContents(new JSONObject(valid.toString()).put("content", "a==="));
        rejectContents(new JSONObject(valid.toString()).put("content", "/w==").put("size", 1));
        rejectContents(new JSONObject(valid.toString()).put("content", "Zh==").put("size", 1));
        JSONObject foreign = manifest(new byte[] {1, 2, 3}, true);
        foreign.getJSONObject("source").put("releaseId", 12346);
        rejectContents(contents(foreign));
        JSONObject pc = manifest(new byte[] {1, 2, 3}, true).put("platform", "windows");
        rejectContents(contents(pc));
    }

    @Test public void selectionPrefersDomesticBytesWithoutHidingNewerOrConflictingFiles() throws Exception {
        UpdateManager.UpdateManifest github = UpdateManager.UpdateManifest.parse(manifest(new byte[] {1}, false).toString());
        UpdateManager.UpdateManifest domestic = UpdateManager.UpdateManifest.parseDomesticContents(contents(manifest(new byte[] {1}, true)).toString());
        assertEquals("gitee", UpdateManager.UpdateManifest.select(github, domestic).provider);
        assertEquals("gitee", UpdateManager.UpdateManifest.select(domestic, github).provider);
        JSONObject older = manifest(new byte[] {1}, false).put("version", "0.1.4").put("versionCode", 5).put("releaseNotesUrl", UpdatePolicy.releaseNotesUrl("0.1.4"));
        older.getJSONObject("artifact").put("url", UpdatePolicy.artifactUrl("0.1.4"));
        UpdateManager.UpdateManifest old = UpdateManager.UpdateManifest.parse(older.toString());
        assertEquals(VERSION, UpdateManager.UpdateManifest.select(old, domestic).version);
        assertEquals(VERSION, UpdateManager.UpdateManifest.select(github, old).version);
        JSONObject different = manifest(new byte[] {2}, true);
        try { UpdateManager.UpdateManifest.select(github, UpdateManager.UpdateManifest.parseDomesticContents(contents(different).toString())); fail("Conflicting bytes accepted"); } catch (Exception expected) {}
        different = manifest(new byte[] {1}, true).put("versionCode", 101);
        try { UpdateManager.UpdateManifest.select(github, UpdateManager.UpdateManifest.parseDomesticContents(contents(different).toString())); fail("Conflicting code accepted"); } catch (Exception expected) {}
        older.put("versionCode", 100);
        try { UpdateManager.UpdateManifest.select(github, UpdateManager.UpdateManifest.parse(older.toString())); fail("Reused code accepted"); } catch (Exception expected) {}
    }

    @Test public void actualUpdaterUsesDomesticDownloadFallbackCancellationAndBoundedChecks() throws Exception {
        assumeTrue("Dedicated debug device required", "true".equals(InstrumentationRegistry.getArguments().getString("yuyinControlledEmulator")));
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences store = target.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE);
        assumeFalse("Never use a real music library", store.contains("library") || store.contains("preferences"));
        String candidatePath = InstrumentationRegistry.getArguments().getString("yuyinCandidateApk");
        assumeTrue(candidatePath != null && candidatePath.startsWith("/data/local/tmp/yuyin-update-"));
        byte[] apk;
        try (InputStream input = new FileInputStream(candidatePath); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) { byte[] block = new byte[65536]; int n; while ((n = input.read(block)) != -1) bytes.write(block, 0, n); apk = bytes.toByteArray(); }
        FakeNetwork network = new FakeNetwork(apk);
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() { @Override protected URLConnection openConnection(URL url) { return network.connection(url); } } : null);
        ExecutorService io = Executors.newSingleThreadExecutor();
        UpdateManager manager = null;
        try {
            for (String mode : new String[] {"valid", "domesticHttp", "domesticHash", "domesticTruncated", "foreignRedirect", "secondRedirect"}) {
                cleanup(target); network.reset(mode);
                manager = new UpdateManager(target, new Handler(Looper.getMainLooper()), io, state -> {});
                assertEquals("gitee", await(manager::check).getJSObject("update").getString("provider"));
                JSObject ready = await(manager::download);
                assertEquals(mode + ": " + ready, "ready", ready.getString("state"));
                assertEquals("valid".equals(mode) ? "gitee" : "github", ready.getJSObject("update").getString("provider"));
                assertEquals("valid".equals(mode) ? 0 : 1, network.githubDownloads.get());
                assertEquals(0, network.foreignRequests.get());
                assertFalse(new File(target.getCacheDir(), "updates/Yuyin-Mobile-" + VERSION + ".download.apk").exists());
                manager.destroy();
            }
            cleanup(target); network.reset("githubDown");
            manager = new UpdateManager(target, new Handler(Looper.getMainLooper()), io, state -> {});
            assertEquals("gitee", await(manager::check).getJSObject("update").getString("provider"));
            assertEquals("ready", await(manager::download).getString("state")); manager.destroy();

            for (String mode : new String[] {"domesticDown", "badContents"}) {
                cleanup(target); network.reset(mode);
                manager = new UpdateManager(target, new Handler(Looper.getMainLooper()), io, state -> {});
                assertEquals("github", await(manager::check).getJSObject("update").getString("provider")); manager.destroy();
            }
            network.reset("conflict");
            manager = new UpdateManager(target, new Handler(Looper.getMainLooper()), io, state -> {});
            assertEquals("UPDATE_SOURCE_CONFLICT", await(manager::check).getString("code"));
            assertEquals("NO_UPDATE", await(manager::download).getString("code")); manager.destroy();

            network.reset("valid");
            manager = new UpdateManager(target, new Handler(Looper.getMainLooper()), io, state -> {});
            assertEquals(VERSION, await(manager::check).getJSObject("update").getString("version"));
            network.mode = "bothOlder";
            assertEquals("The session must retain its known newer update", VERSION, await(manager::check).getJSObject("update").getString("version")); manager.destroy();

            cleanup(target); network.reset("cancel");
            manager = new UpdateManager(target, new Handler(Looper.getMainLooper()), io, state -> {});
            assertEquals("available", await(manager::check).getString("state"));
            CountDownLatch done = new CountDownLatch(1); AtomicReference<JSObject> result = new AtomicReference<>();
            manager.download(state -> { result.set(state); done.countDown(); });
            assertTrue(network.streamOpened.await(10, TimeUnit.SECONDS)); manager.cancel();
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertEquals("available", result.get().getString("state"));
            assertEquals("Cancellation cannot start a backup download", 0, network.githubDownloads.get()); manager.destroy();

            network.reset("domesticStall");
            manager = new UpdateManager(target, new Handler(Looper.getMainLooper()), io, state -> {});
            long started = SystemClock.elapsedRealtime();
            assertEquals("github", await(manager::check).getJSObject("update").getString("provider"));
            assertTrue("Independent check budget", SystemClock.elapsedRealtime() - started < 14500);
            assertFalse(network.sawCredentials); assertFalse(store.contains("library")); assertFalse(store.contains("preferences"));
        } finally { if (manager != null) manager.destroy(); io.shutdownNow(); cleanup(target); }
    }

    private static void rejectContents(JSONObject value) throws Exception { try { UpdateManager.UpdateManifest.parseDomesticContents(value.toString()); fail("Bad domestic contents accepted"); } catch (Exception expected) {} }
    private static JSONObject manifest(byte[] apk, boolean domestic) throws Exception {
        JSONObject artifact = new JSONObject().put("url", domestic ? DOMESTIC_APK : UpdatePolicy.artifactUrl(VERSION)).put("sha256", hash(apk)).put("size", apk.length);
        JSONObject value = new JSONObject().put("schemaVersion", domestic ? 2 : 1).put("platform", "android").put("version", VERSION).put("versionCode", CODE).put("artifact", artifact).put("releaseNotesUrl", domestic ? UpdatePolicy.domesticNotesUrl(VERSION) : UpdatePolicy.releaseNotesUrl(VERSION)).put("notes", "受控更新").put("publishedAt", "2026-10-14T00:00:00.000Z");
        if (domestic) value.put("provider", "gitee").put("source", new JSONObject().put("releaseId", 12345).put("attachmentId", 67890).put("filename", UpdatePolicy.filename(VERSION)));
        return value;
    }
    private static JSONObject contents(JSONObject value) throws Exception { byte[] bytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); return new JSONObject().put("type", "file").put("encoding", "base64").put("name", "domestic.json").put("path", "updates/domestic.json").put("size", bytes.length).put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).put("download_url", "https://untrusted.example/ignored"); }
    private static String hash(byte[] bytes) throws Exception { StringBuilder text = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format(Locale.ROOT, "%02x", b & 255)); return text.toString(); }
    private static JSObject await(Consumer<Consumer<JSObject>> operation) throws Exception { CountDownLatch done = new CountDownLatch(1); AtomicReference<JSObject> value = new AtomicReference<>(); operation.accept(state -> { value.set(state); done.countDown(); }); assertTrue("Operation timed out", done.await(20, TimeUnit.SECONDS)); return value.get(); }
    private static void cleanup(Context context) { for (String suffix : new String[] {".apk", ".download.apk"}) { File file = new File(context.getCacheDir(), "updates/Yuyin-Mobile-" + VERSION + suffix); if (file.exists()) assertTrue("Remove only this test's update fixture", file.delete()); } }

    private static final class FakeNetwork {
        final byte[] apk;
        volatile String mode;
        volatile boolean sawCredentials;
        final AtomicInteger githubDownloads = new AtomicInteger(), foreignRequests = new AtomicInteger();
        volatile CountDownLatch streamOpened, releaseStream;
        FakeNetwork(byte[] apk) { this.apk = apk; reset("valid"); }
        void reset(String mode) { this.mode = mode; githubDownloads.set(0); foreignRequests.set(0); streamOpened = new CountDownLatch(1); releaseStream = new CountDownLatch(1); }
        byte[] json(boolean domestic) {
            try {
                JSONObject value = manifest(apk, domestic);
                if ("conflict".equals(mode) && domestic) value.getJSONObject("artifact").put("sha256", hash(new byte[] {1}));
                if ("bothOlder".equals(mode)) { value.put("version", "0.1.4").put("versionCode", 5).put("releaseNotesUrl", domestic ? UpdatePolicy.domesticNotesUrl("0.1.4") : UpdatePolicy.releaseNotesUrl("0.1.4")); if (domestic) value.getJSONObject("source").put("filename", UpdatePolicy.filename("0.1.4")); else value.getJSONObject("artifact").put("url", UpdatePolicy.artifactUrl("0.1.4")); }
                if (domestic) value = contents(value);
                if (domestic && "badContents".equals(mode)) value.put("path", "updates/stable.json");
                return value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        HttpURLConnection connection(URL address) {
            boolean domesticManifest = UpdatePolicy.DOMESTIC_MANIFEST_URL.equals(address.toString()), githubManifest = UpdatePolicy.MANIFEST_URL.equals(address.toString()), domesticArtifact = DOMESTIC_APK.equals(address.toString()), cdn = "foruda.gitee.com".equals(address.getHost());
            if (!domesticManifest && !githubManifest && !domesticArtifact && !cdn && !UpdatePolicy.artifactUrl(VERSION).equals(address.toString()) && !"release-assets.githubusercontent.com".equals(address.getHost())) foreignRequests.incrementAndGet();
            return new HttpURLConnection(address) {
                @Override public void connect() {}
                @Override public void disconnect() { if (cdn && "cancel".equals(mode)) releaseStream.countDown(); }
                @Override public boolean usingProxy() { return false; }
                @Override public int getResponseCode() throws IOException {
                    if (getRequestProperty("Cookie") != null || getRequestProperty("Authorization") != null) sawCredentials = true;
                    if (domesticManifest && "domesticStall".equals(mode)) { try { Thread.sleep(20000); } catch (InterruptedException ignored) {} throw new IOException("Controlled stalled source"); }
                    if ((domesticManifest && "domesticDown".equals(mode)) || (githubManifest && "githubDown".equals(mode)) || (domesticArtifact && "domesticHttp".equals(mode))) return 503;
                    if (domesticManifest || githubManifest) return 200;
                    if ("github.com".equals(address.getHost())) { githubDownloads.incrementAndGet(); return 302; }
                    if (domesticArtifact || (cdn && "secondRedirect".equals(mode))) return 302;
                    return 200;
                }
                @Override public String getHeaderField(String key) { if (!"Location".equals(key)) return null; if (domesticArtifact || cdn) return "foreignRedirect".equals(mode) ? "https://attacker.example/fixture.apk" : CDN; return "https://release-assets.githubusercontent.com/github-production-release-asset/123/controlled?token=synthetic"; }
                @Override public long getContentLengthLong() { return domesticManifest || githubManifest ? json(domesticManifest).length : apk.length; }
                @Override public InputStream getInputStream() {
                    if (domesticManifest || githubManifest) return new ByteArrayInputStream(json(domesticManifest));
                    byte[] bytes = apk.clone();
                    if (cdn && "domesticHash".equals(mode)) bytes[bytes.length - 1] ^= 1;
                    if (cdn && "domesticTruncated".equals(mode)) return new ByteArrayInputStream(bytes, 0, bytes.length - 10);
                    if (cdn && "cancel".equals(mode)) return new ByteArrayInputStream(bytes) { @Override public synchronized int read(byte[] block, int start, int length) { streamOpened.countDown(); try { releaseStream.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} throw new IllegalStateException("Controlled cancelled download"); } };
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }
}
