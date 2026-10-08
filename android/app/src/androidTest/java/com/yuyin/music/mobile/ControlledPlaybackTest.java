package com.yuyin.music.mobile;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.AudioManager;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/**
 * Runs only when explicitly selected on an isolated emulator. It exercises the real service and HTML5
 * media with a generated local WAV, not Bilibili streams. Debug host + reflection stay outside release.
 */
@RunWith(AndroidJUnit4.class)
public class ControlledPlaybackTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private PlaybackService service;
    private WebView source;
    private final JSONObject evidence = new JSONObject();
    private void onMain(Runnable action) { instrumentation.runOnMainSync(action); }
    private JSObject snapshot() {
        AtomicReference<JSObject> result = new AtomicReference<>();
        onMain(() -> result.set(service.status()));
        return result.get();
    }
    private Object field(String name) throws Exception {
        Field value = PlaybackService.class.getDeclaredField(name); value.setAccessible(true); return value.get(service);
    }
    private void await(BooleanSupplier condition, long timeoutMillis, String message) {
        long start = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - start < timeoutMillis) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        fail(message + (service == null ? "" : " " + snapshot().toString()));
    }
    private static final class Pending implements PlaybackService.Completion {
        final CompletableFuture<JSObject> result = new CompletableFuture<>();
        @Override public void resolve(JSObject value) { result.complete(value); }
        @Override public void reject(String message, String code) { result.completeExceptionally(new IllegalStateException(code)); }
    }
    private static JSObject song(String bvid, String title, int seconds) {
        JSObject value = new JSObject(); value.put("id", bvid); value.put("bvid", bvid); value.put("source", "bilibili");
        value.put("title", title); value.put("artist", "受控本地媒体验证"); value.put("cover", "");
        value.put("duration", seconds); value.put("playCount", 0); value.put("url", "https://www.bilibili.com/video/" + bvid + "/");
        return value;
    }
    private static String mediaHtml(int seconds) {
        int sampleRate = 8000; int count = seconds * sampleRate; int bytes = count * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        for (int i = 0; i < count; i++) wav.putShort((short) (Math.sin(i * 2 * Math.PI * 220 / sampleRate) * 1200));
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width'></head><body>" +
                "<video controls playsinline preload='auto' style='width:100%;height:100%' src='data:audio/wav;base64," +
                Base64.encodeToString(wav.array(), Base64.NO_WRAP) + "'></video>" +
                "<script>window.qaAutoNext=false;document.querySelector('video').addEventListener('ended',function(){window.qaAutoNext=true;this.currentTime=0;this.play();});</script></body></html>";
    }
    private void localPlay(JSObject selected, String html, Pending result) {
        onMain(() -> {
            service.play(selected, result);
            source.stopLoading();
            source.loadDataWithBaseURL(selected.optString("url"), html, "text/html", "UTF-8", selected.optString("url"));
        });
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor ignored = instrumentation.getUiAutomation().executeShellCommand(command)) {
            SystemClock.sleep(350);
        }
    }
    private boolean wakeHeld() throws Exception { return ((PowerManager.WakeLock) field("wakeLock")).isHeld(); }

    @Test public void html5PlaybackSurvivesReparentBackgroundAndScreenOffWithoutWritingUserLibrary() throws Exception {
        Assume.assumeTrue("Explicit isolated emulator opt-in required", "true".equals(InstrumentationRegistry.getArguments().getString("yuyinControlledEmulator")));
        Assume.assumeTrue("Controlled media harness uses API 26+ WebView inspection", Build.VERSION.SDK_INT >= 26);
        AtomicReference<String> auth = new AtomicReference<>();
        onMain(() -> auth.set(NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"))));
        Assume.assumeTrue("Never run controlled tests against a signed-in profile", auth.get().isEmpty());
        Assume.assumeTrue("Never replace an existing playback service", PlaybackService.current() == null);
        String libraryBefore = context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("library", null);
        String preferencesBefore = context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("preferences", null);
        String longMedia = mediaHtml(90);
        try (ActivityScenario<QaPlaybackActivity> scenario = ActivityScenario.launch(QaPlaybackActivity.class)) {
            ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
            await(() -> {
                AtomicReference<PlaybackService> active = new AtomicReference<>();
                onMain(() -> active.set(PlaybackService.current()));
                return active.get() != null;
            }, 5000, "Service did not start");
            service = PlaybackService.current();
            source = (WebView) field("source");
            scenario.onActivity(activity -> service.attachActivity(activity));
            // Tests replace only the transport layer: all HTTP is blocked, original navigation callbacks remain.
            onMain(() -> {
                WebViewClient productionClient = source.getWebViewClient();
                source.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (request.getUrl().getScheme().equals("https") || request.getUrl().getScheme().equals("http"))
                            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                        return null;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return productionClient.shouldOverrideUrlLoading(view, request); }
                    @Override public void onPageFinished(WebView view, String url) { productionClient.onPageFinished(view, url); }
                    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) { productionClient.onReceivedError(view, request, error); }
                });
            });
            Pending interrupted = new Pending(); Pending started = new Pending();
            onMain(() -> {
                service.play(song("BV1ab411c7XY", "QA interrupted generation", 90), interrupted);
                service.play(song("BV1ab411c7XZ", "QA local HTML5 tone", 90), started);
                source.stopLoading();
                source.loadDataWithBaseURL("https://www.bilibili.com/video/BV1ab411c7XZ/", longMedia, "text/html", "UTF-8", "https://www.bilibili.com/video/BV1ab411c7XZ/");
            });
            try { interrupted.result.get(2, TimeUnit.SECONDS); fail("Old play request must be rejected"); }
            catch (ExecutionException expected) { assertEquals("PLAYBACK_INTERRUPTED", expected.getCause().getMessage()); }
            started.result.get(20, TimeUnit.SECONDS);
            await(() -> snapshot().optDouble("currentTime") > 0.5 && snapshot().optString("state").equals("playing"), 10000, "Real HTML5 time did not advance");
            assertEquals("BV1ab411c7XZ", snapshot().optJSONObject("song").optString("bvid"));
            assertTrue(wakeHeld());
            assertTrue(((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications().length > 0);
            assertEquals(PlaybackState.STATE_PLAYING, ((MediaSession) field("mediaSession")).getController().getPlaybackState().getState());
            CompletableFuture<String> bridge = new CompletableFuture<>();
            onMain(() -> source.evaluateJavascript("typeof window.Capacitor==='undefined'&&typeof window.androidBridge==='undefined'", bridge::complete));
            assertEquals("true", bridge.get(3, TimeUnit.SECONDS));
            evidence.put("generationProtection", true); evidence.put("nativeBridgeAbsent", true);

            Pending seek = new Pending(); onMain(() -> service.seek(10, seek)); seek.result.get(5, TimeUnit.SECONDS);
            await(() -> snapshot().optDouble("currentTime") >= 10, 4000, "Seek did not use actual media position");
            Pending pause = new Pending(); onMain(() -> service.pause(pause)); pause.result.get(3, TimeUnit.SECONDS);
            SystemClock.sleep(350); double pausedAt = snapshot().optDouble("currentTime");
            assertFalse(wakeHeld()); SystemClock.sleep(1000);
            assertEquals("paused", snapshot().optString("state"));
            assertEquals(pausedAt, snapshot().optDouble("currentTime"), 0.15);
            Pending resume = new Pending(); onMain(() -> service.resume(resume)); resume.result.get(10, TimeUnit.SECONDS);
            await(() -> snapshot().optDouble("currentTime") > pausedAt + 0.4, 4000, "Resume did not advance media");
            evidence.put("pauseResumeSeek", true);

            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioManager.OnAudioFocusChangeListener temporaryFocus = change -> { };
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(temporaryFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT));
            try {
                await(() -> snapshot().optString("state").equals("paused"), 5000, "Transient audio focus did not pause real media");
                assertFalse(wakeHeld());
            } finally { manager.abandonAudioFocus(temporaryFocus); }
            await(() -> snapshot().optString("state").equals("playing"), 5000, "Playback did not resume after focus returned");
            evidence.put("transientAudioFocusResume", true);

            double beforeSource = snapshot().optDouble("currentTime");
            scenario.onActivity(activity -> {
                FrameLayout visible = new FrameLayout(activity);
                activity.root.addView(visible, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600));
                service.showSource(visible, activity);
            });
            SystemClock.sleep(1300);
            onMain(service::hideSource);
            assertSame(source, field("source"));
            assertTrue(snapshot().optDouble("currentTime") > beforeSource + 0.6);
            evidence.put("sourceReparentKeepsMedia", true);

            double beforeBackground = snapshot().optDouble("currentTime");
            assertTrue(instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
            SystemClock.sleep(4000);
            assertEquals("playing", snapshot().optString("state"));
            double backgroundDelta = snapshot().optDouble("currentTime") - beforeBackground;
            assertTrue("Background media clock must advance", backgroundDelta > 2.0);
            evidence.put("backgroundSecondsAdvanced", backgroundDelta);

            double beforeLock = snapshot().optDouble("currentTime");
            shell("input keyevent 223");
            SystemClock.sleep(4000);
            assertFalse(((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive());
            assertEquals("playing", snapshot().optString("state"));
            double lockDelta = snapshot().optDouble("currentTime") - beforeLock;
            assertTrue("Screen-off media clock must advance", lockDelta > 2.0);
            assertTrue(wakeHeld());
            evidence.put("screenOffSecondsAdvanced", lockDelta);
            shell("input keyevent 224"); shell("wm dismiss-keyguard");

            Pending ending = new Pending(); localPlay(song("BV1ab411c7Xa", "QA ending", 2), mediaHtml(2), ending);
            ending.result.get(10, TimeUnit.SECONDS);
            await(() -> snapshot().optString("state").equals("ended"), 10000, "Ended event was not read from real HTML5 media");
            CompletableFuture<String> autoNext = new CompletableFuture<>();
            onMain(() -> source.evaluateJavascript("window.qaAutoNext", autoNext::complete));
            assertEquals("false", autoNext.get(3, TimeUnit.SECONDS));
            assertFalse(wakeHeld()); evidence.put("endedAndWakeReleased", true);
            onMain(service::stopPlayback);
            await(() -> PlaybackService.current() == null, 4000, "Service did not stop");
            assertEquals(libraryBefore, context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("library", null));
            assertEquals(preferencesBefore, context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("preferences", null));
            evidence.put("userLibraryAndPreferencesUnchanged", true);
            evidence.put("scope", "controlled local WAV in real Android WebView; no Bilibili account or real-stream acceptance");
            try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), "qa-controlled-playback-evidence.json"))) {
                output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            shell("input keyevent 224"); shell("wm dismiss-keyguard");
            PlaybackService active = PlaybackService.current();
            if (active != null) onMain(active::stopPlayback);
        }
    }
}
