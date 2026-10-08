package com.yuyin.music.mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Uses a fresh debug-only device, a separately built future debug APK and memory HTTP responses. */
@RunWith(AndroidJUnit4.class)
public class ControlledUpdateTest {
    @Test public void manifestRejectsMalformedVersionsAndForeignArtifacts() throws Exception {
        JSONObject json = manifest(new byte[] {1, 2, 3});
        assertEquals(4, UpdateManager.UpdateManifest.parse(json.toString()).versionCode);
        for (Object value : new Object[] {"4", 4.5, -1, 2100000001L}) {
            JSONObject invalid = new JSONObject(json.toString()); invalid.put("versionCode", value);
            try { UpdateManager.UpdateManifest.parse(invalid.toString()); fail("Version code coercion"); } catch (Exception expected) {}
        }
        for (String value : new String[] {"https://attacker.example/app.apk", UpdatePolicy.artifactUrl("0.1.3") + "?other=1"}) {
            JSONObject invalid = new JSONObject(json.toString()); invalid.getJSONObject("artifact").put("url", value);
            try { UpdateManager.UpdateManifest.parse(invalid.toString()); fail("Foreign URL"); } catch (Exception expected) {}
        }
    }

    @Test public void fileProviderSharesOnlyTheUpdateCache() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File update = new File(context.getCacheDir(), "updates/controlled-update.apk");
        assertEquals("content", FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", update).getScheme());
        for (File other : new File[] {new File(context.getCacheDir(), "outside.apk"), new File(context.getFilesDir(), "preferences.json")}) {
            try { FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", other); fail("Private path exposed"); } catch (IllegalArgumentException expected) {}
        }
    }

    @Test public void controlledDownloadChecksAndInstallationIntents() throws Exception {
        assumeTrue("Explicit independent emulator required", "true".equals(InstrumentationRegistry.getArguments().getString("yuyinControlledEmulator")));
        assumeTrue(Build.VERSION.SDK_INT >= 26);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences store = target.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE);
        assumeFalse("Do not operate on an existing music library", store.contains("library") || store.contains("preferences"));
        File oldFixture = new File(target.getCacheDir(), "updates/Yuyin-Mobile-0.1.3.apk");
        if (oldFixture.isFile()) assertTrue("Remove only this controlled fixture cache", oldFixture.delete());
        String candidatePath = InstrumentationRegistry.getArguments().getString("yuyinCandidateApk");
        assumeTrue(candidatePath != null && candidatePath.startsWith("/data/local/tmp/yuyin-update-"));
        byte[] candidate = read(new FileInputStream(candidatePath));
        String wrongSignaturePath = InstrumentationRegistry.getArguments().getString("yuyinWrongSignatureApk");
        assumeTrue(wrongSignaturePath != null && wrongSignaturePath.startsWith("/data/local/tmp/yuyin-update-"));
        FakeNetwork network = new FakeNetwork(candidate, read(new FileInputStream(wrongSignaturePath)));
        // URL handler exists only in the instrumentation APK. Production release has no injection API.
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) { return network.connection(url); }
        } : null);
        AtomicReference<Intent> launched = new AtomicReference<>();
        AtomicInteger launchCount = new AtomicInteger();
        Context controlled = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public void startActivity(Intent intent) { launched.set(intent); launchCount.incrementAndGet(); }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        UpdateManager manager = new UpdateManager(controlled, new Handler(Looper.getMainLooper()), io, state -> {});
        try {
            assertEquals("available", await(manager::check).getString("state"));
            assertEquals("error", await(done -> { network.mode = "foreignRedirect"; manager.download(done); }).getString("state"));
            assertEquals("error", await(done -> { network.mode = "httpError"; manager.download(done); }).getString("state"));
            assertEquals("UPDATE_HTTP_ERROR", manager.status().getString("code"));
            assertEquals("error", await(done -> { network.mode = "wrongBytes"; manager.download(done); }).getString("state"));
            assertEquals("UPDATE_HASH_MISMATCH", manager.status().getString("code"));
            assertEquals("error", await(done -> { network.mode = "truncated"; manager.download(done); }).getString("state"));
            assertEquals("UPDATE_SIZE_MISMATCH", manager.status().getString("code"));
            network.mode = "wrongSignature";
            assertEquals("available", await(manager::check).getString("state"));
            assertEquals("error", await(manager::download).getString("state"));
            assertEquals("UPDATE_SIGNATURE_MISMATCH", manager.status().getString("code"));
            network.mode = "cancel";
            assertEquals("available", await(manager::check).getString("state"));
            CountDownLatch cancelled = new CountDownLatch(1);
            manager.download(result -> { assertEquals("available", result.getString("state")); cancelled.countDown(); });
            assertTrue(network.streamOpened.await(10, TimeUnit.SECONDS));
            assertEquals("available", manager.cancel().getString("state"));
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
            JSObject ready = await(done -> { network.mode = "valid"; manager.download(done); });
            assertEquals(ready.toString(), "ready", ready.getString("state"));
            shell("appops set com.yuyin.music.mobile REQUEST_INSTALL_PACKAGES deny");
            assertEquals("permissionRequired", await(manager::install).getString("state"));
            assertEquals("android.settings.MANAGE_UNKNOWN_APP_SOURCES", launched.get().getAction());
            assertEquals("package:com.yuyin.music.mobile", launched.get().getData().toString());
            manager.resume(); assertEquals("ready", manager.status().getString("state"));
            shell("appops set com.yuyin.music.mobile REQUEST_INSTALL_PACKAGES allow");
            int beforeInstall = launchCount.get();
            CountDownLatch firstInstall = new CountDownLatch(1);
            manager.install(result -> { assertEquals("installing", result.getString("state")); firstInstall.countDown(); });
            await(manager::install);
            assertTrue(firstInstall.await(10, TimeUnit.SECONDS));
            assertEquals(beforeInstall + 1, launchCount.get());
            Intent installation = launched.get();
            assertEquals(Intent.ACTION_VIEW, installation.getAction());
            assertEquals("content", installation.getData().getScheme());
            assertEquals("com.yuyin.music.mobile.fileprovider", installation.getData().getAuthority());
            assertEquals("application/vnd.android.package-archive", installation.getType());
            assertTrue((installation.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertEquals(0, installation.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            boolean systemInstallerShown = false;
            if ("true".equals(InstrumentationRegistry.getArguments().getString("yuyinCheckSystemInstaller"))) {
                target.startActivity(installation);
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
                    String text = treeText(root);
                    if (root != null && String.valueOf(root.getPackageName()).contains("packageinstaller") && text.contains("余音") && (text.contains("安装") || text.toLowerCase(Locale.ROOT).contains("install"))) { systemInstallerShown = true; root.recycle(); break; }
                    if (root != null) root.recycle(); SystemClock.sleep(100);
                }
                assertTrue("The Android system did not show a valid install confirmation", systemInstallerShown);
                InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            }
            manager.resume(); assertEquals("ready", manager.status().getString("state"));
            assertFalse(store.contains("library")); assertFalse(store.contains("preferences"));
            assertFalse(network.sawCookie);
            CountDownLatch allowVerify = new CountDownLatch(1);
            io.execute(() -> { try { allowVerify.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} });
            manager.install(result -> fail("Disposed updates must not publish an installation result"));
            manager.destroy(); allowVerify.countDown(); io.shutdown();
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {});
            assertEquals(beforeInstall + 1, launchCount.get());
            JSONObject evidence = new JSONObject(); evidence.put("systemInstallerShown", systemInstallerShown); evidence.put("candidateVersion", "0.1.3"); evidence.put("candidateVersionCode", 4); evidence.put("candidateSha256", hash(candidate)); evidence.put("libraryAndPreferencesUnchanged", true); evidence.put("scope", "Fresh debug device, memory HTTP, same debug signing identity; installer cancelled without upgrading");
            try (FileOutputStream output = new FileOutputStream(new File(target.getCacheDir(), "qa-update-evidence.json"))) { output.write(evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        } finally { manager.destroy(); io.shutdownNow(); shell("appops set com.yuyin.music.mobile REQUEST_INSTALL_PACKAGES default"); }
    }

    private static JSObject await(Consumer<Consumer<JSObject>> operation) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<JSObject> value = new AtomicReference<>();
        operation.accept(result -> { value.set(result); latch.countDown(); });
        assertTrue("Update operation timed out", latch.await(30, TimeUnit.SECONDS)); return value.get();
    }
    private static JSONObject manifest(byte[] apk) throws Exception {
        JSONObject artifact = new JSONObject(); artifact.put("url", UpdatePolicy.artifactUrl("0.1.3")); artifact.put("sha256", hash(apk)); artifact.put("size", apk.length);
        JSONObject json = new JSONObject(); json.put("schemaVersion", 1); json.put("platform", "android"); json.put("version", "0.1.3"); json.put("versionCode", 4); json.put("artifact", artifact); json.put("releaseNotesUrl", UpdatePolicy.releaseNotesUrl("0.1.3")); json.put("notes", "受控测试更新"); json.put("publishedAt", "2026-10-08T00:00:00.000Z"); return json;
    }
    private static byte[] read(InputStream source) throws Exception { try (InputStream input = source) { ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] data = new byte[65536]; int count; while ((count = input.read(data)) != -1) result.write(data, 0, count); return result.toByteArray(); } }
    private static String hash(byte[] bytes) throws Exception { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder(); for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString(); }
    private static void shell(String command) throws Exception { try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command)) { read(new FileInputStream(descriptor.getFileDescriptor())); } }
    private static String treeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder result = new StringBuilder(String.valueOf(node.getText()));
        for (int index = 0; index < node.getChildCount(); index++) { AccessibilityNodeInfo child = node.getChild(index); result.append(" ").append(treeText(child)); if (child != null) child.recycle(); }
        return result.toString();
    }

    private static final class FakeNetwork {
        final byte[] candidate;
        final byte[] wrongSigner;
        final CountDownLatch streamOpened = new CountDownLatch(1);
        final CountDownLatch releaseStream = new CountDownLatch(1);
        volatile String mode = "valid";
        volatile boolean sawCookie;
        FakeNetwork(byte[] candidate, byte[] wrongSigner) { this.candidate = candidate; this.wrongSigner = wrongSigner; }
        byte[] bytes() { return "wrongSignature".equals(mode) ? wrongSigner : candidate; }
        byte[] json() { try { return manifest(bytes()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); } catch (Exception failure) { throw new IllegalStateException(failure); } }
        HttpURLConnection connection(URL address) {
            return new HttpURLConnection(address) {
                @Override public void connect() {}
                @Override public void disconnect() { if ("cancel".equals(mode) && address.getHost().equals("release-assets.githubusercontent.com")) releaseStream.countDown(); }
                @Override public boolean usingProxy() { return false; }
                @Override public int getResponseCode() { if (getRequestProperty("Cookie") != null) sawCookie = true; return address.toString().equals(UpdatePolicy.MANIFEST_URL) ? 200 : "httpError".equals(mode) ? 503 : address.getHost().equals("github.com") ? 302 : 200; }
                @Override public String getHeaderField(String key) { return "Location".equals(key) ? "foreignRedirect".equals(mode) ? "https://attacker.example/app.apk" : "https://release-assets.githubusercontent.com/github-production-release-asset/123/controlled?token=synthetic" : null; }
                @Override public long getContentLengthLong() { return address.toString().equals(UpdatePolicy.MANIFEST_URL) ? json().length : bytes().length; }
                @Override public InputStream getInputStream() {
                    if (address.toString().equals(UpdatePolicy.MANIFEST_URL)) return new ByteArrayInputStream(json());
                    byte[] bytes = bytes().clone();
                    if ("wrongBytes".equals(mode)) bytes[bytes.length - 1] ^= 1;
                    if ("truncated".equals(mode)) return new ByteArrayInputStream(bytes, 0, bytes.length - 10);
                    if ("cancel".equals(mode)) return new ByteArrayInputStream(bytes) {
                        @Override public synchronized int read(byte[] block, int start, int length) {
                            streamOpened.countDown();
                            try { if (!releaseStream.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Cancellation was not delivered"); } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                            throw new IllegalStateException("Controlled cancelled stream");
                        }
                    };
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }
}
