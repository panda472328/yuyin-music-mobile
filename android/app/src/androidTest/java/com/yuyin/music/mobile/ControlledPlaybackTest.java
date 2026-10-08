package com.yuyin.music.mobile;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.AudioManager;
import android.media.ImageReader;
import android.hardware.display.VirtualDisplay;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.Display;
import android.view.Surface;
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
import androidx.lifecycle.Lifecycle;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        return mediaHtmlWithData("data:audio/wav;base64," + Base64.encodeToString(wav.array(), Base64.NO_WRAP));
    }
    private String muxedMediaHtml() throws Exception {
        try (InputStream input = instrumentation.getContext().getAssets().open("controlled-video-with-audio.mp4");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int length;
            while ((length = input.read(block)) != -1) bytes.write(block, 0, length);
            return mediaHtmlWithData("data:video/mp4;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        }
    }
    private static String mediaHtmlWithData(String data) {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width'></head><body>" +
                "<video controls playsinline preload='auto' style='width:100%;height:100%' src='" + data + "'></video>" +
                "<script>window.qaAutoNext=false;window.qaLifecyclePauseCount=0;" +
                "document.addEventListener('visibilitychange',function(){if(document.hidden){window.qaLifecyclePauseCount++;document.querySelector('video').pause();}});" +
                "window.addEventListener('pagehide',function(){if(document.hidden){window.qaLifecyclePauseCount++;document.querySelector('video').pause();}});" +
                "document.querySelector('video').addEventListener('ended',function(){window.qaAutoNext=true;this.currentTime=0;this.play();});</script></body></html>";
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
    private String evaluate(String script) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        onMain(() -> source.evaluateJavascript(script, result::complete));
        return result.get(4, TimeUnit.SECONDS);
    }
    private void complete(Pending pending, int seconds, String operation) throws Exception {
        try { pending.result.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) {
            String media = evaluate("JSON.stringify((function(){const v=document.querySelector('video');const m=window.__yuyinNativeMedia;return {found:!!v,sourceKind:v?(v.currentSrc.indexOf('data:video/mp4')===0?'controlled-mp4':v.currentSrc.indexOf('data:audio/wav')===0?'controlled-wav':'other'):null,paused:v?v.paused:null,ended:v?v.ended:null,currentTime:v?v.currentTime:null,duration:v?v.duration:null,readyState:v?v.readyState:null,networkState:v?v.networkState:null,seeking:v?v.seeking:null,videoWidth:v?v.videoWidth:null,videoHeight:v?v.videoHeight:null,mediaError:v&&v.error?v.error.code:null,playError:m?m.playError:null,documentHidden:document.hidden,visibilityState:document.visibilityState};})())");
            throw new AssertionError(operation + " timed out: service=" + snapshot() + " media=" + media +
                    " desiredPlayback=" + field("desiredPlayback") + " startAttempted=" + field("startAttempted") +
                    " awaitingStart=" + field("awaitingStart") + " focusPreflightComplete=" + field("focusPreflightComplete"), timeout);
        }
    }

    @Test public void html5PlaybackSurvivesReparentBackgroundAndScreenOffWithoutWritingUserLibrary() throws Exception {
        Assume.assumeTrue("Explicit isolated emulator opt-in required", "true".equals(InstrumentationRegistry.getArguments().getString("yuyinControlledEmulator")));
        Assume.assumeTrue("Controlled media harness uses API 26+ WebView inspection", Build.VERSION.SDK_INT >= 26);
        AtomicReference<String> auth = new AtomicReference<>();
        onMain(() -> auth.set(NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"))));
        Assume.assumeTrue("Never run controlled tests against a signed-in profile", auth.get().isEmpty());
        Assume.assumeTrue("Never replace an existing playback service", PlaybackService.current() == null);
        String libraryBefore = context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("library", null);
        String preferencesBefore = context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("preferences", null);
        String longMedia = muxedMediaHtml();
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
            complete(started, 20, "Initial controlled MP4 play");
            await(() -> snapshot().optDouble("currentTime") > 0.5 && snapshot().optString("state").equals("playing"), 10000, "Real HTML5 time did not advance");
            assertEquals("BV1ab411c7XZ", snapshot().optJSONObject("song").optString("bvid"));
            assertTrue(wakeHeld());
            assertTrue(((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications().length > 0);
            assertEquals(PlaybackState.STATE_PLAYING, ((MediaSession) field("mediaSession")).getController().getPlaybackState().getState());
            CompletableFuture<String> bridge = new CompletableFuture<>();
            onMain(() -> source.evaluateJavascript("typeof window.Capacitor==='undefined'&&typeof window.androidBridge==='undefined'", bridge::complete));
            assertEquals("true", bridge.get(3, TimeUnit.SECONDS));
            evidence.put("generationProtection", true); evidence.put("nativeBridgeAbsent", true);
            assertEquals(Boolean.TRUE, field("focusPreflightComplete"));
            assertEquals("true", evaluate("document.querySelector('video').videoWidth>0&&document.querySelector('video').videoHeight>0"));
            evidence.put("realVideoTrackPresent", true);
            VirtualDisplay privateDisplay = (VirtualDisplay) field("backgroundDisplay");
            assertNotNull(privateDisplay);
            assertTrue("Background source display must stay private", (privateDisplay.getDisplay().getFlags() & Display.FLAG_PRIVATE) != 0);
            evidence.put("privateServiceSourceDisplay", true);

            Pending seek = new Pending(); onMain(() -> service.seek(10, seek)); complete(seek, 5, "Seek controlled MP4 to 10s");
            await(() -> snapshot().optDouble("currentTime") >= 10, 4000, "Seek did not use actual media position");
            Pending pause = new Pending(); onMain(() -> service.pause(pause)); pause.result.get(3, TimeUnit.SECONDS);
            SystemClock.sleep(350); double pausedAt = snapshot().optDouble("currentTime");
            assertFalse(wakeHeld()); SystemClock.sleep(1000);
            assertEquals("paused", snapshot().optString("state"));
            assertEquals(pausedAt, snapshot().optDouble("currentTime"), 0.15);
            Pending resume = new Pending(); onMain(() -> service.resume(resume)); complete(resume, 10, "Resume controlled MP4 after user pause");
            assertEquals("Same-document resume must not reacquire native audio focus", Boolean.FALSE, field("focusRegistered"));
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
            AtomicReference<FrameLayout> oldSourceFrame = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FrameLayout visible = new FrameLayout(activity);
                oldSourceFrame.set(visible);
                activity.root.addView(visible, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600));
                service.showSource(visible, activity);
            });
            SystemClock.sleep(1300);
            scenario.onActivity(activity -> {
                FrameLayout next = new FrameLayout(activity);
                activity.root.addView(next, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600));
                service.showSource(next, activity);
                service.hideSource(oldSourceFrame.get());
                assertSame("Stale dialog dismiss cannot detach the new source window", next, source.getParent());
                service.hideSource(next);
            });
            assertSame(source, field("source"));
            assertTrue(snapshot().optDouble("currentTime") > beforeSource + 0.6);
            evidence.put("sourceReparentKeepsMedia", true);

            // A site may pause while hidden independently from Android audio focus. Exercise its
            // lifecycle handlers; visible source getters must prevent that policy without resuming pauses.
            double beforeLifecycleEvent = snapshot().optDouble("currentTime");
            assertEquals("0", evaluate("document.dispatchEvent(new Event('visibilitychange',{bubbles:true}));window.dispatchEvent(new Event('pagehide'));window.qaLifecyclePauseCount"));
            SystemClock.sleep(1000);
            assertEquals("playing", snapshot().optString("state"));
            assertTrue(snapshot().optDouble("currentTime") > beforeLifecycleEvent + 0.4);
            evidence.put("siteVisibilityAndPagehideDoNotPause", true);

            // A pause from the source controls is still a real user pause, never an automatic retry.
            evaluate("document.querySelector('video').pause()");
            await(() -> snapshot().optString("state").equals("paused"), 5000, "Source user pause was ignored");
            double sourcePausedAt = snapshot().optDouble("currentTime");
            SystemClock.sleep(1200);
            assertEquals("paused", snapshot().optString("state"));
            assertEquals(sourcePausedAt, snapshot().optDouble("currentTime"), 0.15);
            assertFalse(wakeHeld());
            Pending sourceResume = new Pending(); onMain(() -> service.resume(sourceResume)); complete(sourceResume, 10, "Resume controlled MP4 after source pause");
            evidence.put("sourceUserPausePreserved", true);

            double beforeBackground = snapshot().optDouble("currentTime");
            assertTrue(instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
            SystemClock.sleep(4000);
            assertEquals("playing", snapshot().optString("state"));
            double backgroundDelta = snapshot().optDouble("currentTime") - beforeBackground;
            assertTrue("Background media clock must advance", backgroundDelta > 2.0);
            evidence.put("backgroundSecondsAdvanced", backgroundDelta);
            assertEquals("0", evaluate("window.qaLifecyclePauseCount"));

            MediaSession activeSession = (MediaSession) field("mediaSession");
            activeSession.getController().getTransportControls().pause();
            await(() -> snapshot().optString("state").equals("paused"), 5000, "Background MediaSession pause was ignored");
            double backgroundPausedAt = snapshot().optDouble("currentTime");
            SystemClock.sleep(1200);
            assertEquals("paused", snapshot().optString("state"));
            assertEquals(backgroundPausedAt, snapshot().optDouble("currentTime"), 0.15);
            assertFalse(wakeHeld());
            activeSession.getController().getTransportControls().play();
            await(() -> snapshot().optString("state").equals("playing"), 10000, "Background MediaSession resume did not play");
            evidence.put("backgroundManualPauseAndResume", true);

            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(temporaryFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT));
            try {
                await(() -> snapshot().optString("state").equals("paused"), 5000, "Background external audio focus did not pause");
                double focusPausedAt = snapshot().optDouble("currentTime");
                SystemClock.sleep(1200);
                assertEquals("paused", snapshot().optString("state"));
                assertEquals(focusPausedAt, snapshot().optDouble("currentTime"), 0.15);
                assertFalse(wakeHeld());
            } finally { manager.abandonAudioFocus(temporaryFocus); }
            await(() -> snapshot().optString("state").equals("playing"), 10000, "Background playback did not resume after focus gain");
            evidence.put("backgroundFocusPauseAndResume", true);

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

            // Home leaves the launcher task in front. Waking the display does not return the app,
            // and ActivityScenario.moveToState cannot override the OS task/window visibility.
            // Bring back the SAME QA Activity through normal task navigation before recreating it.
            onMain(() -> context.startActivity(new Intent(context, QaPlaybackActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            await(() -> scenario.getState() == Lifecycle.State.RESUMED, 10000, "QA task did not return to the foreground");
            assertSame(source, field("source"));
            assertEquals("playing", snapshot().optString("state"));
            double beforeRecreate = snapshot().optDouble("currentTime");
            scenario.recreate();
            scenario.onActivity(activity -> service.attachActivity(activity));
            assertSame(source, field("source"));
            SystemClock.sleep(1500);
            assertEquals("playing", snapshot().optString("state"));
            assertTrue(snapshot().optDouble("currentTime") > beforeRecreate + 0.5);
            evidence.put("capacitorActivityRecreateKeepsSource", true);

            double beforeDestroy = snapshot().optDouble("currentTime");
            scenario.moveToState(Lifecycle.State.DESTROYED);
            assertSame(source, field("source"));
            SystemClock.sleep(3000);
            assertEquals("playing", snapshot().optString("state"));
            double destroyedDelta = snapshot().optDouble("currentTime") - beforeDestroy;
            assertTrue("Destroyed Activity must not stop service media", destroyedDelta > 1.5);
            assertEquals("true", evaluate("!document.querySelector('video').paused"));
            evidence.put("activityDestroyedSecondsAdvanced", destroyedDelta);

            Pending ending = new Pending(); localPlay(song("BV1ab411c7Xa", "QA ending", 2), mediaHtml(2), ending);
            complete(ending, 10, "Play controlled ending WAV after Activity destroy");
            await(() -> snapshot().optString("state").equals("ended"), 10000, "Ended event was not read from real HTML5 media");
            CompletableFuture<String> autoNext = new CompletableFuture<>();
            onMain(() -> source.evaluateJavascript("window.qaAutoNext", autoNext::complete));
            assertEquals("false", autoNext.get(3, TimeUnit.SECONDS));
            assertFalse(wakeHeld()); evidence.put("endedAndWakeReleased", true);
            Presentation retainedPresentation = (Presentation) field("backgroundPresentation");
            VirtualDisplay retainedDisplay = (VirtualDisplay) field("backgroundDisplay");
            Surface retainedSurface = ((ImageReader) field("backgroundFrames")).getSurface();
            onMain(service::stopPlayback);
            await(() -> PlaybackService.current() == null, 4000, "Service did not stop");
            assertFalse("Stopping releases the service source window", retainedPresentation.isShowing());
            await(() -> !retainedDisplay.getDisplay().isValid(), 4000, "Stopping did not release the private display");
            assertFalse("Stopping releases the frame queue surface", retainedSurface.isValid());
            assertNull(field("backgroundPresentation")); assertNull(field("backgroundDisplay")); assertNull(field("backgroundFrames"));
            evidence.put("privateHostResourcesReleased", true);
            assertEquals(libraryBefore, context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("library", null));
            assertEquals(preferencesBefore, context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("preferences", null));
            evidence.put("userLibraryAndPreferencesUnchanged", true);
            evidence.put("scope", "generated H.264 + AAC video and local WAV with site hidden-page pause policy in Android WebView; production MainActivity/Capacitor lifecycle with inert UI; no Bilibili account or real-stream acceptance");
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
