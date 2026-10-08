package com.yuyin.music.mobile;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Image;
import android.media.ImageReader;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/** A started foreground service owns the source WebView independently from Capacitor's UI. */
public final class PlaybackService extends Service {
    static final String ACTION_PAUSE = "com.yuyin.music.mobile.PAUSE";
    static final String ACTION_RESUME = "com.yuyin.music.mobile.RESUME";
    static final String ACTION_STOP = "com.yuyin.music.mobile.STOP";
    private static final String CHANNEL = "yuyin_playback";
    private static final int NOTIFICATION = 401;
    private static final CopyOnWriteArrayList<WeakReference<Listener>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile PlaybackService instance;
    private static double defaultVolume = 0.7;
    interface Listener { void onStatus(JSObject value); void onEnded(JSObject song); }
    interface Completion { void resolve(JSObject value); void reject(String message, String code); }
    static PlaybackService current() { return instance; }
    static void setDefaultVolume(double value) { defaultVolume = value; }
    static void addListener(Listener listener) {
        removeListener(listener);
        LISTENERS.add(new WeakReference<>(listener));
    }
    static void removeListener(Listener listener) {
        for (WeakReference<Listener> ref : LISTENERS) if (ref.get() == null || ref.get() == listener) LISTENERS.remove(ref);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView source;
    private FrameLayout holder;
    private Presentation backgroundPresentation;
    private VirtualDisplay backgroundDisplay;
    private ImageReader backgroundFrames;
    private WeakReference<Activity> attachedActivity = new WeakReference<>(null);
    private FrameLayout visibleSource;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private JSObject song;
    private String state = "idle";
    private String error;
    private String errorCode;
    private double currentTime;
    private double duration;
    private double volume = defaultVolume;
    private long generation;
    private long positionRevision;
    private boolean desiredPlayback;
    private boolean focusPreflightComplete;
    private boolean startAttempted;
    private boolean awaitingStart;
    private boolean evaluationPending;
    private boolean destroyed;
    private boolean focused;
    private boolean focusRegistered;
    private long focusEpoch;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean foreground;
    private boolean resumeAfterFocus;
    private long loadStarted;
    private long stalledSince;
    private long evaluationToken;
    private long lastSessionCheck;
    private String playbackSessionSignature;
    private Completion playCompletion;
    private String previousPublished = "";
    private String previousNotificationState = "";

    /** Only the service-owned source stays visible to Chromium; the Capacitor UI follows its Activity. */
    private static final class SourceWebView extends WebView {
        SourceWebView(Context context) { super(context); }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            // A foreground service and wake lock do not prevent WebView's hidden-window media policy.
            // Keep this source's renderer alive when Home / screen-off hides the Activity window.
            // Media pause and Android audio focus still operate on the actual HTML5 element.
            super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (song != null && SystemClock.elapsedRealtime() - lastSessionCheck > 1000) {
                lastSessionCheck = SystemClock.elapsedRealtime();
                String currentSession = NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"));
                if (playbackSessionSignature != null && !playbackSessionSignature.equals(currentSession)) {
                    playbackSessionSignature = currentSession;
                    fail("Bilibili 账号已变化，请重新验证账号并选择歌曲。", "BILIBILI_SESSION_CHANGED", generation);
                }
            }
            if (song != null && !evaluationPending) read("read", 0, null);
            if (desiredPlayback && awaitingStart && "loading".equals(state) && SystemClock.elapsedRealtime() - loadStarted > 45000) {
                fail("视频未能在 45 秒内播放。请打开源视频检查登录、验证或网络。", "PLAYBACK_TIMEOUT", generation);
            }
            main.postDelayed(this, desiredPlayback ? 150 : 600);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YuyinMusic:playback");
        wakeLock.setReferenceCounted(false);
        mediaSession = new MediaSession(this, "余音播放");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(null); }
            @Override public void onPause() { pause(null); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long position) { seek(position / 1000.0, null); }
        }, main);
        mediaSession.setActive(true);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("余音后台播放和锁屏控制");
            channel.setSound(null, null);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        // Required immediately after startForegroundService, including while the original page loads.
        startForeground(NOTIFICATION, notification());
        foreground = true;
        createBackgroundHost();
        createSource();
        main.post(poll);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PAUSE.equals(action)) pause(null);
            if (ACTION_RESUME.equals(action)) resume(null);
            if (ACTION_STOP.equals(action)) stopPlayback();
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private boolean createBackgroundHost() {
        if (backgroundPresentation != null && backgroundPresentation.isShowing() && holder != null) return true;
        disposeBackgroundHost();
        try {
            // A private, app-owned virtual display supplies a real Window without overlay/capture
            // permissions. Chromium considers a detached WebView hidden even if window visibility
            // is overridden, so a Service-held Java reference alone cannot keep video media running.
            final int size = 64;
            backgroundFrames = ImageReader.newInstance(size, size, PixelFormat.RGBA_8888, 2);
            backgroundFrames.setOnImageAvailableListener(reader -> {
                // Consume and discard immediately: never inspect, copy, capture or save media frames.
                try (Image image = reader.acquireLatestImage()) { /* Release compositor backpressure. */ }
                catch (IllegalStateException ignored) { /* A queued callback may follow service cleanup. */ }
            }, main);
            DisplayManager displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            backgroundDisplay = displays.createVirtualDisplay("YuyinSource", size, size,
                    getResources().getDisplayMetrics().densityDpi, backgroundFrames.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (backgroundDisplay == null) throw new IllegalStateException("Private source display unavailable");
            Presentation presentation = new Presentation(this, backgroundDisplay.getDisplay());
            backgroundPresentation = presentation;
            presentation.getWindow().setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
            holder = new FrameLayout(presentation.getContext());
            holder.setImportantForAccessibility(FrameLayout.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            presentation.setContentView(holder);
            presentation.setOnDismissListener(ignored -> {
                if (backgroundPresentation != presentation || destroyed) return;
                disposeBackgroundHost();
                if (song != null) fail("后台音源窗口已关闭，请重新选择歌曲。", "BACKGROUND_HOST_CLOSED", generation);
            });
            presentation.show();
            return true;
        } catch (RuntimeException ignored) {
            disposeBackgroundHost();
            return false;
        }
    }

    private void disposeBackgroundHost() {
        FrameLayout oldHolder = holder; holder = null;
        if (source != null && source.getParent() == oldHolder) removeParent(source);
        Presentation oldPresentation = backgroundPresentation; backgroundPresentation = null;
        VirtualDisplay oldDisplay = backgroundDisplay; backgroundDisplay = null;
        ImageReader oldFrames = backgroundFrames; backgroundFrames = null;
        if (oldPresentation != null) {
            try { oldPresentation.dismiss(); } catch (RuntimeException ignored) { /* Already removed display. */ }
        }
        if (oldDisplay != null) oldDisplay.release();
        if (oldFrames != null) { oldFrames.setOnImageAvailableListener(null, null); oldFrames.close(); }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void createSource() {
        focusPreflightComplete = false;
        source = new SourceWebView(this);
        source.setBackgroundColor(Color.BLACK);
        source.getSettings().setJavaScriptEnabled(true);
        source.getSettings().setDomStorageEnabled(true);
        source.getSettings().setMediaPlaybackRequiresUserGesture(false);
        source.getSettings().setUserAgentString(NativePolicy.DESKTOP_UA);
        source.getSettings().setAllowFileAccess(false);
        source.getSettings().setAllowContentAccess(false);
        source.getSettings().setSupportMultipleWindows(false);
        source.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) source.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(source, false);
        source.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (song != null && NativePolicy.expectedVideo(url, song.optString("bvid"))) return false;
                if (song != null) fail("源视频发生跳转，请重新选择歌曲。", "SOURCE_PAGE_CHANGED", generation);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (song == null || !NativePolicy.expectedVideo(url, song.optString("bvid"))) return;
                CookieManager.getInstance().flush();
                view.evaluateJavascript(MediaScript.backgroundGuard(song.optString("bvid")), null);
                if (!desiredPlayback) read("pause", 0, null);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                if (request.isForMainFrame() && song != null && NativePolicy.expectedVideo(request.getUrl().toString(), song.optString("bvid"))) {
                    fail("Bilibili 视频页加载失败，请检查网络后重试。", "PAGE_LOAD_FAILED", generation);
                }
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                fail("Bilibili 播放进程已退出，请重新播放。", "MEDIA_PROCESS_EXITED", generation);
                removeParent(view);
                view.destroy();
                source = null;
                disposeBackgroundHost();
                createBackgroundHost();
                createSource();
                if (visibleSource != null) attachTo(visibleSource);
                else if (holder != null) attachTo(holder);
                return true;
            }
        });
        // Never addJavascriptInterface: all remote content stays outside the privileged app bridge.
        if (holder != null && visibleSource == null) attachTo(holder);
    }

    void attachActivity(Activity activity) {
        if (activity == null || activity.isFinishing() || destroyed) return;
        attachedActivity = new WeakReference<>(activity);
        if (visibleSource == null && createBackgroundHost() && source != null && source.getParent() != holder) attachTo(holder);
    }

    void detachActivity(Activity activity) {
        if (attachedActivity.get() != activity) return;
        // Move out of a source dialog as well: its async dismiss must not retain a dead Activity.
        visibleSource = null;
        if (source != null && holder != null && source.getParent() != holder) attachTo(holder);
        else if (source != null && holder == null) removeParent(source);
        attachedActivity = new WeakReference<>(null);
    }

    void showSource(FrameLayout container, Activity activity) {
        attachActivity(activity);
        visibleSource = container;
        attachTo(container);
    }
    void hideSource() {
        visibleSource = null;
        if (createBackgroundHost()) attachTo(holder);
        else {
            if (source != null) removeParent(source);
            if (song != null) fail("手机未能保留后台音源窗口，请重新选择歌曲。", "BACKGROUND_HOST_UNAVAILABLE", generation);
        }
    }
    void hideSource(FrameLayout expectedContainer) {
        // Dismiss runs asynchronously. An old Activity's dialog cannot hide a newer source window.
        if (visibleSource == expectedContainer) hideSource();
    }
    private void attachTo(FrameLayout target) {
        if (source == null || target == null || source.getParent() == target) return;
        removeParent(source);
        target.addView(source, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    private static void removeParent(android.view.View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    static JSObject idleStatus() {
        JSObject result = new JSObject();
        result.put("state", "idle"); result.put("song", JSONObject.NULL); result.put("currentTime", 0);
        result.put("duration", 0); result.put("volume", defaultVolume); result.put("error", JSONObject.NULL);
        return result;
    }
    JSObject status() {
        JSObject result = new JSObject();
        result.put("state", state); result.put("song", song == null ? JSONObject.NULL : song);
        result.put("currentTime", currentTime); result.put("duration", duration); result.put("volume", volume);
        result.put("error", error == null ? JSONObject.NULL : error);
        if (errorCode != null) result.put("errorCode", errorCode);
        return result;
    }

    void play(JSObject selected, Completion completion) {
        generation++;
        positionRevision++;
        rejectPending("已切换歌曲。", "PLAYBACK_INTERRUPTED");
        evaluationToken++;
        evaluationPending = false;
        if (source == null) createSource();
        source.stopLoading();
        // The old document must stop sounding before navigation starts. Future callbacks are generation guarded.
        source.evaluateJavascript("(function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;v.pause();});})()", null);
        song = selected;
        playbackSessionSignature = NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"));
        state = "loading"; error = null; errorCode = null; currentTime = 0;
        duration = Math.max(0, selected.optDouble("duration", 0));
        desiredPlayback = true; focusPreflightComplete = false; startAttempted = false; awaitingStart = true; stalledSince = 0;
        loadStarted = SystemClock.elapsedRealtime();
        playCompletion = completion;
        if (!createBackgroundHost()) {
            fail("手机未能创建后台音源窗口，请返回前台重试。", "BACKGROUND_HOST_UNAVAILABLE", generation);
            return;
        }
        if (visibleSource == null && source.getParent() != holder) attachTo(holder);
        source.loadUrl("https://www.bilibili.com/video/" + selected.optString("bvid") + "/");
        publish(true);
    }

    void pause(Completion completion) {
        desiredPlayback = false;
        awaitingStart = false;
        resumeAfterFocus = false;
        positionRevision++;
        rejectPending("播放请求已暂停。", "PLAYBACK_INTERRUPTED");
        if (song != null && !"ended".equals(state) && !"error".equals(state)) state = "paused";
        releaseResources();
        // Read callbacks captured before this pause cannot restore the playing state.
        read("pause", 0, null);
        publish(true);
        if (completion != null) completion.resolve(status());
    }

    void resume(Completion completion) {
        if (song == null) { if (completion != null) completion.reject("请先选择歌曲。", "NO_SONG"); return; }
        if ("error".equals(state)) { play(song, completion); return; }
        rejectPending("已收到新的播放请求。", "PLAYBACK_INTERRUPTED");
        desiredPlayback = true; startAttempted = false; awaitingStart = true; error = null; errorCode = null;
        playbackSessionSignature = NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"));
        state = "loading"; loadStarted = SystemClock.elapsedRealtime(); playCompletion = completion;
        positionRevision++;
        if (currentTime >= duration && duration > 0) read("seek", 0, null);
        publish(true);
    }

    void seek(double seconds, Completion completion) {
        if (song == null) { if (completion != null) completion.reject("请先选择歌曲。", "NO_SONG"); return; }
        positionRevision++;
        currentTime = Math.max(0, duration > 0 ? Math.min(seconds, duration) : seconds);
        if ("ended".equals(state)) state = "paused";
        read("seek", currentTime, completion);
        publish(true);
    }
    void setVolume(double value, Completion completion) {
        volume = Math.max(0, Math.min(1, value));
        defaultVolume = volume;
        if (song != null) read("volume", volume, completion);
        else if (completion != null) completion.resolve(status());
        publish(true);
    }

    private void read(String command, double value, Completion completion) {
        if (source == null || song == null) { if (completion != null) completion.resolve(status()); return; }
        final long capturedGeneration = generation;
        final long capturedPosition = positionRevision;
        final long token = ++evaluationToken;
        final String bvid = song.optString("bvid");
        evaluationPending = true;
        final boolean[] finished = { false };
        source.evaluateJavascript(MediaScript.build(command, bvid, volume, value), result -> {
            if (finished[0]) return;
            finished[0] = true;
            if (token == evaluationToken) evaluationPending = false;
            if (destroyed || capturedGeneration != generation || capturedPosition != positionRevision) {
                if (completion != null) completion.reject("播放状态已改变，请重试。", "PLAYBACK_INTERRUPTED");
                return;
            }
            try {
                Object decoded = new JSONTokener(result).nextValue();
                JSONObject snapshot = decoded instanceof String ? new JSONObject((String) decoded) : null;
                if (snapshot == null) { if (completion != null) completion.reject("视频尚未准备好。", "MEDIA_NOT_READY"); return; }
                apply(snapshot, capturedGeneration);
                if (completion != null) {
                    if (!snapshot.optBoolean("found")) completion.reject("视频尚未准备好，请稍后重试。", "MEDIA_NOT_READY");
                    else completion.resolve(status());
                }
            } catch (Exception ignored) {
                if (completion != null) completion.reject("无法读取源视频状态。", "MEDIA_INVALID_RESPONSE");
            }
        });
        main.postDelayed(() -> {
            if (!finished[0]) {
                finished[0] = true;
                if (token == evaluationToken) { evaluationPending = false; evaluationToken++; }
                if (completion != null) completion.reject("源视频响应超时。", "MEDIA_RESPONSE_TIMEOUT");
            }
        }, 5000);
    }

    private void apply(JSONObject snapshot, long expectedGeneration) {
        if (expectedGeneration != generation || destroyed) return;
        if (!desiredPlayback && ("error".equals(state) || "ended".equals(state))) {
            if (snapshot.optBoolean("found") && !snapshot.optBoolean("paused") && source != null) {
                source.evaluateJavascript("(function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;v.pause();});})()", null);
            }
            return;
        }
        if (snapshot.optBoolean("navigationMismatch")) {
            // During a fresh load the previous document is harmless; later navigation must stop this song.
            if (!awaitingStart || startAttempted) fail("源视频切换到了其他视频或分 P，请重新选择歌曲。", "SOURCE_PAGE_CHANGED", expectedGeneration);
            return;
        }
        if (!snapshot.optBoolean("found")) {
            String blocked = snapshot.optString("blocked", "");
            if (!blocked.isEmpty() && !blocked.equals("null")) fail("Bilibili 提示：" + blocked + "。请打开登录或源视频完成验证。", "BILIBILI_VERIFICATION_REQUIRED", expectedGeneration);
            return;
        }
        currentTime = finite(snapshot.optDouble("currentTime", 0));
        double detectedDuration = finite(snapshot.optDouble("duration", 0));
        if (detectedDuration > 0) duration = detectedDuration;
        if (snapshot.optInt("mediaError") > 0) { fail("Bilibili 媒体加载失败，请打开源视频检查。", "MEDIA_ERROR", expectedGeneration); return; }
        String playError = snapshot.optString("playError", "");
        if (desiredPlayback && !playError.isEmpty() && !playError.equals("null")) {
            fail("网页未能开始播放。请打开源视频检查，然后返回余音重试。", "MEDIA_PLAY_BLOCKED", expectedGeneration); return;
        }
        if (snapshot.optBoolean("ended")) {
            if (!"ended".equals(state)) {
                desiredPlayback = false; awaitingStart = false; state = "ended"; releaseResources();
                rejectPending("视频已播放结束。", "PLAYBACK_ENDED");
                publish(true);
                for (WeakReference<Listener> ref : LISTENERS) { Listener listener = ref.get(); if (listener != null) listener.onEnded(song); }
            }
            return;
        }
        if (!desiredPlayback) {
            if (!snapshot.optBoolean("paused")) read("pause", 0, null);
            if (!"error".equals(state)) state = "paused";
            if (resumeAfterFocus) { if (wakeLock.isHeld()) wakeLock.release(); }
            else releaseResources();
            publish(false); return;
        }
        if (!startAttempted && snapshot.optInt("readyState") > 0) {
            if (!focusPreflightComplete) {
                // Only preflight the first start of this selected document. On pause Chromium may
                // retain focus temporarily; requesting native focus again on resume steals that focus
                // and queues a delayed LOSS which can pause the just-resumed HTML5 element.
                // A page which already auto-started owns Chromium focus and needs no second owner.
                if (snapshot.optBoolean("paused") && !requestFocus()) {
                    fail("其他应用正在使用音频，请稍后重试。", "AUDIO_FOCUS_DENIED", expectedGeneration); return;
                }
                releaseResources();
                focusPreflightComplete = true;
            }
            startAttempted = true;
            read("play", 0, null);
            return;
        }
        if (!snapshot.optBoolean("paused") && snapshot.optBoolean("playing")) {
            state = "playing"; awaitingStart = false; stalledSince = 0; acquireWakeLock();
            Completion pending = playCompletion; playCompletion = null;
            if (pending != null) pending.resolve(status());
        } else {
            if (!awaitingStart && snapshot.optBoolean("paused")) {
                // Follow the WebView's own focus interruption. Its transient gain may resume the same
                // media automatically; an explicit user pause has desiredPlayback=false and stays paused.
                state = "paused"; stalledSince = 0;
                if (wakeLock.isHeld()) wakeLock.release();
                publish(false);
                return;
            }
            state = "loading";
            if (wakeLock.isHeld()) wakeLock.release();
            if (stalledSince == 0) stalledSince = SystemClock.elapsedRealtime();
            if (SystemClock.elapsedRealtime() - stalledSince > 30000) { fail("源视频长时间没有开始播放，请打开源视频检查。", "MEDIA_STALLED", expectedGeneration); return; }
        }
        publish(false);
    }

    private static double finite(double value) { return Double.isFinite(value) ? Math.max(0, value) : 0; }
    private void fail(String message, String code, long expectedGeneration) {
        if (destroyed || expectedGeneration != generation) return;
        desiredPlayback = false; awaitingStart = false; state = "error"; error = message; errorCode = code;
        positionRevision++;
        releaseResources(); rejectPending(message, code);
        if (source != null) source.evaluateJavascript("(function(){document.querySelectorAll('video,audio').forEach(function(v){v.muted=true;v.pause();});})()", null);
        publish(true);
    }
    private void rejectPending(String message, String code) {
        Completion pending = playCompletion; playCompletion = null;
        if (pending != null) pending.reject(message, code);
    }

    private void handleFocusChange(int change, long expectedFocusEpoch) {
        if (destroyed || !focusRegistered || expectedFocusEpoch != focusEpoch) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focused = true;
            boolean restore = resumeAfterFocus; resumeAfterFocus = false;
            if (restore && song != null) resume(null);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            boolean wasPlaying = desiredPlayback;
            // Keep the transient request registered so Android can deliver the later gain callback.
            desiredPlayback = false; awaitingStart = false; focused = false; positionRevision++;
            resumeAfterFocus = wasPlaying;
            rejectPending("播放因其他音频暂时中断。", "AUDIO_FOCUS_INTERRUPTED");
            if (song != null && !"error".equals(state) && !"ended".equals(state)) state = "paused";
            if (wakeLock.isHeld()) wakeLock.release();
            read("pause", 0, null); publish(true);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause(null); resumeAfterFocus = false;
        }
    }
    private boolean requestFocus() {
        if (focused) return true;
        final long requestedEpoch = ++focusEpoch;
        focusListener = change -> main.post(() -> handleFocusChange(change, requestedEpoch));
        int granted;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focusListener, main).setWillPauseWhenDucked(true).build();
            granted = audioManager.requestAudioFocus(focusRequest);
        } else granted = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        focused = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        focusRegistered = focused;
        return focused;
    }
    private void acquireWakeLock() { if (!wakeLock.isHeld()) wakeLock.acquire(); }
    private void releaseResources() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (focusRegistered && audioManager != null) {
            focusEpoch++;
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
            else audioManager.abandonAudioFocus(focusListener);
        }
        focused = false;
        focusRegistered = false;
    }

    private PendingIntent action(String action, int code) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        boolean playing = "playing".equals(state) || (desiredPlayback && "loading".equals(state));
        Intent launch = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_yuyin).setContentTitle(song == null ? "余音音乐播放器" : song.optString("title", "余音"))
                .setContentText(error != null ? "打开播放器查看问题" : (song == null ? "准备播放" : song.optString("artist", "Bilibili")))
                .setContentIntent(content).setOnlyAlertOnce(true).setOngoing(playing).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", action(playing ? ACTION_PAUSE : ACTION_RESUME, 1)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 2)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1));
        return builder.build();
    }
    private void publish(boolean force) {
        JSObject value = status();
        String serialized = value.toString();
        if (force || !serialized.equals(previousPublished)) {
            previousPublished = serialized;
            for (WeakReference<Listener> ref : LISTENERS) { Listener listener = ref.get(); if (listener == null) LISTENERS.remove(ref); else listener.onStatus(value); }
        }
        int nativeState = "playing".equals(state) ? PlaybackState.STATE_PLAYING : "paused".equals(state) ? PlaybackState.STATE_PAUSED :
                "loading".equals(state) ? PlaybackState.STATE_BUFFERING : "error".equals(state) ? PlaybackState.STATE_ERROR : PlaybackState.STATE_STOPPED;
        PlaybackState.Builder playback = new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                .setState(nativeState, (long) (currentTime * 1000), nativeState == PlaybackState.STATE_PLAYING ? 1f : 0f);
        if (error != null) playback.setErrorMessage(error);
        mediaSession.setPlaybackState(playback.build());
        String notice = state + ":" + (song == null ? "" : song.optString("bvid"));
        if (force || !notice.equals(previousNotificationState)) {
            previousNotificationState = notice;
            if (song != null) mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, song.optString("title"))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, song.optString("artist")).putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (duration * 1000)).build());
            if (foreground) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
        }
    }
    void stopPlayback() {
        generation++; positionRevision++; desiredPlayback = false;
        rejectPending("播放已停止。", "PLAYBACK_INTERRUPTED");
        releaseResources();
        if (source != null) { source.stopLoading(); source.loadUrl("about:blank"); }
        state = "idle"; song = null; error = null; errorCode = null; currentTime = 0; duration = 0;
        publish(true);
        foreground = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        destroyed = true; generation++; positionRevision++;
        main.removeCallbacksAndMessages(null);
        rejectPending("播放服务已关闭。", "PLAYER_DISPOSED");
        releaseResources();
        if (source != null) { removeParent(source); source.stopLoading(); source.destroy(); source = null; }
        disposeBackgroundHost();
        visibleSource = null;
        attachedActivity = new WeakReference<>(null);
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        if (instance == this) instance = null;
        state = "idle"; song = null; error = null; errorCode = null; currentTime = 0; duration = 0;
        for (WeakReference<Listener> ref : LISTENERS) { Listener listener = ref.get(); if (listener != null) listener.onStatus(idleStatus()); }
        CookieManager.getInstance().flush();
        super.onDestroy();
    }
}
