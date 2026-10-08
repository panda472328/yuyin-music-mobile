package com.yuyin.music.mobile;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Trusted local UI bridge. Source and login WebViews never load Capacitor or a native JS bridge. */
@CapacitorPlugin(name = "YuyinMobile")
public final class YuyinMobilePlugin extends Plugin implements PlaybackService.Listener {
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private static final int MAX_STORE = 8 * 1024 * 1024;
    private static final int MAX_LOGIN_RESPONSE = 64 * 1024;
    private static final long LOGIN_CHECK_INTERVAL = 1500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ExecutorService storeIo = Executors.newSingleThreadExecutor();
    private Dialog dialog;
    private WebView loginView;
    private String loginCookieSignature;
    private long loginGeneration;
    private long lastLoginCheck;
    private boolean loginVerificationPending;
    private String loginInitialCookies;
    private String loginOpeningSignature;
    private Long loginBaselineIdentity;
    private boolean destroyed;
    private final Runnable watchLoginSession = new Runnable() {
        @Override public void run() {
            if (destroyed || loginView == null) return;
            String current = authenticationCookieSignature();
            if (!current.equals(loginCookieSignature)) {
                loginCookieSignature = current;
                CookieManager.getInstance().flush();
                notifyListeners("sessionChanged", new JSObject(), true);
            }
            verifyLoginSession();
            main.postDelayed(this, 1000);
        }
    };

    private String authenticationCookieSignature() {
        String all = CookieManager.getInstance().getCookie("https://api.bilibili.com/");
        // This fingerprint stays inside Java and is never sent to the local UI, logs, or remote sites.
        return NativePolicy.authenticationSignature(all);
    }

    /** Cookie changes only prompt a check; successful official nav is the login authority. */
    private void verifyLoginSession() {
        if (destroyed || loginView == null || dialog == null || !dialog.isShowing() || loginVerificationPending) return;
        String currentCookies = CookieManager.getInstance().getCookie("https://api.bilibili.com/");
        String currentSignature = NativePolicy.authenticationSignature(currentCookies);
        // Wait for an identity change instead of polling an anonymous or already verified account.
        if (currentSignature.equals(loginOpeningSignature) && (currentSignature.isEmpty() || loginBaselineIdentity != null && loginBaselineIdentity > 0)) return;
        // An old challenged session must not block verification of a newly established session.
        final boolean baseline = loginBaselineIdentity == null && currentSignature.equals(loginOpeningSignature);
        String cookies = baseline ? loginInitialCookies : currentCookies;
        String signature = NativePolicy.authenticationSignature(cookies);
        if (SystemClock.elapsedRealtime() - lastLoginCheck < LOGIN_CHECK_INTERVAL) return;
        final WebView expectedView = loginView;
        final Dialog expectedDialog = dialog;
        final long generation = loginGeneration;
        lastLoginCheck = SystemClock.elapsedRealtime();
        loginVerificationPending = true;
        io.execute(() -> {
            Long identity = null;
            HttpURLConnection connection = null;
            try {
                URI uri = NativePolicy.requestUri("https://api.bilibili.com/x/web-interface/nav");
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", NativePolicy.DESKTOP_UA);
                connection.setRequestProperty("Referer", "https://www.bilibili.com/");
                connection.setRequestProperty("Origin", "https://www.bilibili.com");
                connection.setRequestProperty("Accept", "application/json");
                if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
                int status = connection.getResponseCode();
                if (status == 200) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    long started = SystemClock.elapsedRealtime();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] block = new byte[4096]; int length;
                        while ((length = input.read(block)) != -1) {
                            if (buffer.size() + length > MAX_LOGIN_RESPONSE || SystemClock.elapsedRealtime() - started > 10000) {
                                throw new IllegalStateException("LOGIN_RESPONSE_LIMIT");
                            }
                            buffer.write(block, 0, length);
                        }
                    }
                    identity = loginIdentityResponse(status, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
                // Network errors, challenges and expired sessions keep the official login window open.
            } finally { if (connection != null) connection.disconnect(); }
            final Long result = identity;
            main.post(() -> completeLoginVerification(expectedView, expectedDialog, generation, signature, baseline, result));
        });
    }

    /** null means unverified; 0 means an official logged-out result; positive values are validated mids. */
    private static Long loginIdentityResponse(int status, String body) {
        if (status != 200) return null;
        try {
            JSONObject response = new JSONObject(body);
            Object code = response.opt("code");
            if (!(code instanceof Number)) return null;
            if (((Number) code).doubleValue() == -101) return 0L;
            JSONObject data = response.optJSONObject("data");
            if (((Number) code).doubleValue() != 0 || data == null) return null;
            if (Boolean.FALSE.equals(data.opt("isLogin"))) return 0L;
            if (!Boolean.TRUE.equals(data.opt("isLogin"))) return null;
            Object mid = data.opt("mid");
            Object username = data.opt("uname");
            if (!(mid instanceof Number) || !(username instanceof String)) return null;
            double identity = ((Number) mid).doubleValue();
            String name = ((String) username).replaceAll("<[^>]*>", "").replaceAll("[\\x00-\\x1f\\x7f]", "").trim();
            return identity > 0 && identity <= 9007199254740991d && identity == Math.floor(identity) && !name.isEmpty() ? ((Number) mid).longValue() : null;
        } catch (Exception ignored) { return null; }
    }

    private void completeLoginVerification(WebView expectedView, Dialog expectedDialog, long generation, String signature, boolean baseline, Long identity) {
        if (destroyed || generation != loginGeneration || loginView != expectedView || dialog != expectedDialog) return;
        loginVerificationPending = false;
        if (baseline) {
            // Establish with the original Cookie snapshot even if the page has already started switching accounts.
            if (identity != null) {
                loginBaselineIdentity = identity;
                loginInitialCookies = null;
                lastLoginCheck = 0;
                verifyLoginSession();
            }
            return;
        }
        // A slow response for an old account or closed window must never dismiss the current login flow.
        // With an unknown challenged baseline, only a changed session confirmed by nav may finish.
        boolean unchangedAccount = loginBaselineIdentity == null ? signature.equals(loginOpeningSignature) : identity != null && identity.equals(loginBaselineIdentity);
        if (identity == null || identity <= 0 || !signature.equals(authenticationCookieSignature()) || !expectedDialog.isShowing()) return;
        if (unchangedAccount) {
            if (loginBaselineIdentity != null) loginOpeningSignature = signature;
            return;
        }
        CookieManager.getInstance().flush();
        expectedDialog.dismiss();
    }

    @Override public void load() {
        PlaybackService.addListener(this);
        main.post(() -> {
            CookieManager.getInstance().setAcceptCookie(true);
            PlaybackService service = PlaybackService.current();
            if (service != null) { service.attachActivity(getActivity()); onStatus(service.status()); }
        });
    }
    @Override public void onStatus(JSObject value) { if (!destroyed) notifyListeners("status", value, true); }
    @Override public void onEnded(JSObject selected) {
        if (destroyed) return;
        JSObject event = new JSObject(); event.put("song", selected);
        notifyListeners("ended", event, true);
    }

    @PluginMethod public void request(PluginCall call) {
        final URI uri;
        try { uri = NativePolicy.requestUri(call.getString("url")); }
        catch (Exception ignored) { call.reject("此请求地址不在音乐接口允许范围内。", "REQUEST_NOT_ALLOWED"); return; }
        main.post(() -> {
            if (destroyed) { call.reject("页面已关闭。", "PLUGIN_DISPOSED"); return; }
            String cookies = uri.getHost().equals("api.bilibili.com") ? CookieManager.getInstance().getCookie("https://api.bilibili.com/") : null;
            io.execute(() -> fetch(call, uri, cookies));
        });
    }

    private void fetch(PluginCall call, URI uri, String cookies) {
        String requestSession = NativePolicy.authenticationSignature(cookies);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", NativePolicy.DESKTOP_UA);
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            if (uri.getHost().equals("api.bilibili.com")) {
                connection.setRequestProperty("Referer", "https://www.bilibili.com/");
                connection.setRequestProperty("Origin", "https://www.bilibili.com");
                if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
            } else if (uri.getHost().endsWith(".hdslb.com")) connection.setRequestProperty("Referer", "https://www.bilibili.com/");
            long started = SystemClock.elapsedRealtime();
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (stream != null) {
                if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) stream = new GZIPInputStream(stream);
                try (InputStream input = stream) {
                    byte[] block = new byte[8192]; int length;
                    while ((length = input.read(block)) != -1) {
                        if (buffer.size() + length > MAX_RESPONSE) throw new IllegalStateException("RESPONSE_TOO_LARGE");
                        if (SystemClock.elapsedRealtime() - started > 25000) throw new IllegalStateException("REQUEST_TIMEOUT");
                        buffer.write(block, 0, length);
                    }
                }
            }
            String body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            Map<String, List<String>> headers = connection.getHeaderFields();
            main.post(() -> {
                if (uri.getHost().equals("api.bilibili.com")) {
                    String currentSession = NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"));
                    if (!requestSession.equals(currentSession)) {
                        if (!destroyed) call.reject("Bilibili 账号已变化，请重新验证后重试。", "BILIBILI_SESSION_CHANGED");
                        return;
                    }
                    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                        if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie")) {
                            for (String cookie : header.getValue()) CookieManager.getInstance().setCookie("https://api.bilibili.com/", cookie);
                        }
                    }
                    CookieManager.getInstance().flush();
                }
                if (destroyed) return;
                JSObject result = new JSObject(); result.put("status", status); result.put("body", body); call.resolve(result);
            });
        } catch (Exception failure) {
            String code = failure instanceof IllegalStateException ? failure.getMessage() : "NETWORK_ERROR";
            main.post(() -> { if (!destroyed) call.reject("网络请求失败，请检查网络后重试。", code); });
        } finally { if (connection != null) connection.disconnect(); }
    }

    private SharedPreferences store() { return getContext().getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE); }
    @PluginMethod public void readStore(PluginCall call) {
        String key = call.getString("key");
        if (!NativePolicy.validStoreKey(key)) { call.reject("不支持的数据类型。", "INVALID_STORE_KEY"); return; }
        storeIo.execute(() -> {
            String data = store().getString(key, null);
            JSObject result = new JSObject(); result.put("data", data == null ? JSONObject.NULL : data);
            call.resolve(result);
        });
    }
    @PluginMethod public void writeStore(PluginCall call) {
        String key = call.getString("key"); String data = call.getString("data");
        if (!NativePolicy.validStoreKey(key)) { call.reject("不支持的数据类型。", "INVALID_STORE_KEY"); return; }
        if (data == null || data.getBytes(StandardCharsets.UTF_8).length > MAX_STORE) { call.reject("保存的数据过大。", "STORE_TOO_LARGE"); return; }
        try { new JSONObject(data); } catch (Exception ignored) { call.reject("保存的数据格式不正确。", "INVALID_STORE_JSON"); return; }
        storeIo.execute(() -> {
            // commit, not apply: a successful promise means the data already reached app-private disk storage.
            boolean saved = store().edit().putString(key, data).commit();
            if (!saved) call.reject("保存失败，请检查手机存储空间。", "STORE_WRITE_FAILED");
            else { JSObject result = new JSObject(); result.put("saved", true); call.resolve(result); }
        });
    }

    @PluginMethod public void getStatus(PluginCall call) {
        main.post(() -> { PlaybackService service = PlaybackService.current(); call.resolve(service == null ? PlaybackService.idleStatus() : service.status()); });
    }
    private PlaybackService.Completion completion(PluginCall call) {
        AtomicBoolean finished = new AtomicBoolean(false);
        return new PlaybackService.Completion() {
            @Override public void resolve(JSObject value) { if (!destroyed && finished.compareAndSet(false, true)) call.resolve(value); }
            @Override public void reject(String message, String code) { if (!destroyed && finished.compareAndSet(false, true)) call.reject(message, code); }
        };
    }
    private void withService(PluginCall call, boolean start, Consumer<PlaybackService> action) {
        main.post(() -> {
            if (destroyed) { call.reject("页面已关闭。", "PLUGIN_DISPOSED"); return; }
            PlaybackService service = PlaybackService.current();
            if (service != null) { service.attachActivity(getActivity()); action.accept(service); return; }
            if (!start) { call.resolve(PlaybackService.idleStatus()); return; }
            try { ContextCompat.startForegroundService(getContext(), new Intent(getContext(), PlaybackService.class)); }
            catch (Exception ignored) { call.reject("手机限制了后台播放服务，请在应用前台重试。", "SERVICE_START_BLOCKED"); return; }
            final long started = SystemClock.elapsedRealtime();
            Runnable awaitService = new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    PlaybackService active = PlaybackService.current();
                    if (active != null) { active.attachActivity(getActivity()); action.accept(active); }
                    else if (SystemClock.elapsedRealtime() - started > 5000) call.reject("后台播放服务未能启动。", "SERVICE_START_TIMEOUT");
                    else main.postDelayed(this, 40);
                }
            };
            main.post(awaitService);
        });
    }
    @PluginMethod public void play(PluginCall call) {
        JSObject song = call.getObject("song");
        if (song == null || !NativePolicy.validBvid(song.optString("bvid")) || !song.optString("source").equals("bilibili")) {
            call.reject("歌曲数据不正确。", "INVALID_SONG"); return;
        }
        if (song.toString().getBytes(StandardCharsets.UTF_8).length > 16000) { call.reject("歌曲数据过大。", "INVALID_SONG"); return; }
        String bvid = song.optString("bvid"); song.put("id", bvid); song.put("url", "https://www.bilibili.com/video/" + bvid + "/");
        withService(call, true, service -> {
            requestNotificationPermission();
            service.play(song, completion(call));
        });
    }
    @PluginMethod public void pause(PluginCall call) { withService(call, false, service -> service.pause(completion(call))); }
    @PluginMethod public void resume(PluginCall call) { withService(call, false, service -> service.resume(completion(call))); }
    @PluginMethod public void seek(PluginCall call) {
        Double value = call.getDouble("seconds");
        if (value == null || !Double.isFinite(value) || value < 0) { call.reject("播放位置不正确。", "INVALID_POSITION"); return; }
        withService(call, false, service -> service.seek(value, completion(call)));
    }
    @PluginMethod public void setVolume(PluginCall call) {
        Double value = call.getDouble("volume");
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) { call.reject("音量不正确。", "INVALID_VOLUME"); return; }
        main.post(() -> {
            PlaybackService.setDefaultVolume(value);
            PlaybackService service = PlaybackService.current();
            if (service == null) call.resolve(PlaybackService.idleStatus());
            else service.setVolume(value, completion(call));
        });
    }
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && getActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 704);
        }
    }

    private LinearLayout dialogLayout(Dialog surface, String title, String tip) {
        LinearLayout layout = new LinearLayout(getActivity()); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(getActivity()); bar.setPadding(18, 12, 18, 10);
        TextView label = new TextView(getActivity()); label.setText(title); label.setTextColor(Color.BLACK); label.setTextSize(17);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(getActivity()); close.setText("返回余音"); close.setOnClickListener(view -> surface.dismiss());
        bar.addView(close); layout.addView(bar);
        TextView instruction = new TextView(getActivity()); instruction.setText(tip); instruction.setTextColor(Color.DKGRAY); instruction.setPadding(20, 0, 20, 12);
        layout.addView(instruction); return layout;
    }
    private Dialog newDialog(PluginCall call) {
        if (dialog != null) { call.reject("已有一个 Bilibili 窗口，请先返回播放器。", "DIALOG_ALREADY_OPEN"); return null; }
        Dialog surface = new Dialog(getActivity()); surface.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog = surface; return surface;
    }
    private void showFullscreen(Dialog surface, LinearLayout layout) {
        surface.setContentView(layout); surface.show();
        if (surface.getWindow() != null) {
            surface.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            surface.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }
    @SuppressWarnings("SetJavaScriptEnabled")
    @PluginMethod public void openLogin(PluginCall call) {
        main.post(() -> {
            if (destroyed || getActivity().isFinishing()) { call.reject("页面已关闭。", "PLUGIN_DISPOSED"); return; }
            Dialog surface = newDialog(call); if (surface == null) return;
            LinearLayout layout = dialogLayout(surface, "Bilibili 官方登录", "官方账号验证成功后会自动返回余音，也可以点“返回余音”手动验证。余音不保存你的密码。");
            WebView remote = new WebView(getActivity()); loginView = remote;
            loginGeneration++;
            loginVerificationPending = false; lastLoginCheck = 0;
            loginInitialCookies = CookieManager.getInstance().getCookie("https://api.bilibili.com/");
            loginOpeningSignature = NativePolicy.authenticationSignature(loginInitialCookies);
            // No local identity means no account to preserve. This never substitutes for current official nav.
            loginBaselineIdentity = loginOpeningSignature.isEmpty() ? 0L : null;
            if (loginBaselineIdentity != null) loginInitialCookies = null;
            loginCookieSignature = loginOpeningSignature;
            notifyListeners("sessionChanged", new JSObject(), true);
            main.postDelayed(watchLoginSession, 1000);
            remote.getSettings().setJavaScriptEnabled(true); remote.getSettings().setDomStorageEnabled(true);
            remote.getSettings().setAllowFileAccess(false); remote.getSettings().setAllowContentAccess(false);
            remote.getSettings().setSupportMultipleWindows(false);
            remote.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            remote.getSettings().setLoadWithOverviewMode(true); remote.getSettings().setUseWideViewPort(true);
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(remote, false);
            remote.setWebChromeClient(new WebChromeClient());
            remote.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return request.isForMainFrame() && !NativePolicy.officialPage(request.getUrl().toString());
                }
                @Override public void onPageFinished(WebView view, String url) {
                    CookieManager.getInstance().flush();
                    if (loginView == view) verifyLoginSession();
                }
            });
            layout.addView(remote, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            surface.setOnDismissListener(ignored -> {
                CookieManager.getInstance().flush(); remote.stopLoading(); remote.destroy();
                main.removeCallbacks(watchLoginSession);
                if (loginView == remote) {
                    loginView = null; loginGeneration++; loginVerificationPending = false;
                    loginInitialCookies = null; loginOpeningSignature = null; loginBaselineIdentity = null;
                }
                if (dialog == surface) dialog = null;
                if (!destroyed) { notifyListeners("sessionChanged", new JSObject(), true); call.resolve(); }
            });
            showFullscreen(surface, layout);
            // Official HTTPS page only; no hidden cookie import from the PC app and no credentials returned to JS.
            remote.loadUrl("https://passport.bilibili.com/login");
        });
    }
    @PluginMethod public void openSource(PluginCall call) {
        main.post(() -> {
            PlaybackService service = PlaybackService.current();
            if (service == null || service.status().optString("state").equals("idle")) { call.reject("请先选择歌曲。", "NO_SONG"); return; }
            Dialog surface = newDialog(call); if (surface == null) return;
            LinearLayout layout = dialogLayout(surface, "Bilibili 原视频", "这是当前歌曲的官方页面。返回余音后，音源会继续保留。");
            FrameLayout sourceFrame = new FrameLayout(getActivity());
            layout.addView(sourceFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            service.showSource(sourceFrame, getActivity());
            surface.setOnDismissListener(ignored -> {
                service.hideSource(sourceFrame); CookieManager.getInstance().flush(); if (dialog == surface) dialog = null;
                if (!destroyed) call.resolve();
            });
            showFullscreen(surface, layout);
        });
    }
    @Override protected void handleOnResume() {
        main.post(() -> {
            PlaybackService service = PlaybackService.current();
            if (service != null) { service.attachActivity(getActivity()); onStatus(service.status()); }
            verifyLoginSession();
        });
    }
    @Override protected void handleOnDestroy() {
        destroyed = true;
        PlaybackService.removeListener(this);
        if (dialog != null) dialog.dismiss();
        PlaybackService service = PlaybackService.current();
        if (service != null) service.detachActivity(getActivity());
        main.removeCallbacksAndMessages(null);
        io.shutdown();
        storeIo.shutdown();
        // Do not stop the service or pause its WebView when the Capacitor UI goes away.
        CookieManager.getInstance().flush();
    }
}
