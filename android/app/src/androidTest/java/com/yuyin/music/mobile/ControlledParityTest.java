package com.yuyin.music.mobile;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Synthetic data and actual Android windows/media. No transport fixture is compiled into Release. */
@RunWith(AndroidJUnit4.class)
public class ControlledParityTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private void main(Runnable task) { instrumentation.runOnMainSync(task); }
    private void isolated() {
        Assume.assumeTrue("Dedicated emulator opt-in required", "true".equals(InstrumentationRegistry.getArguments().getString("yuyinControlledEmulator")));
        AtomicReference<String> auth = new AtomicReference<>(); main(() -> auth.set(NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"))));
        Assume.assumeTrue("Never use a signed-in profile", auth.get().isEmpty());
        Assume.assumeTrue("Never replace a running service", PlaybackService.current() == null);
        Assume.assumeTrue("Use an empty dedicated data store", !PlaybackStore.get(context).contains("library") && !PlaybackStore.get(context).contains("preferences") && !PlaybackStore.get(context).contains("floating_lyrics"));
    }
    private void await(BooleanSupplier condition, long timeout, String reason) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(80); }
        fail(reason);
    }
    private Object field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private JSObject status(PlaybackService service) { AtomicReference<JSObject> value = new AtomicReference<>(); main(() -> value.set(service.status())); return value.get(); }
    private void shell(String command) throws Exception { try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command); InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) { byte[] bytes = new byte[4096]; while (in.read(bytes) != -1) { /* discard shell output */ } } }
    private void input(float x, float y, float endX, float endY) {
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 8; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 8 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + (endX-x)*i/8, y + (endY-y)*i/8, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(25);
        }
    }
    private float[] center(View view) { final float[] point = new float[2]; main(() -> { int[] loc = new int[2]; view.getLocationOnScreen(loc); point[0] = loc[0] + view.getWidth()/2f; point[1] = loc[1] + view.getHeight()/2f; }); return point; }
    private JSObject song(String bvid, String title) { JSObject song = new JSObject(); song.put("id", bvid); song.put("bvid", bvid); song.put("source", "bilibili"); song.put("title", title); song.put("artist", "虚构测试歌手"); song.put("duration", 120); song.put("url", "https://www.bilibili.com/video/"+bvid+"/"); return song; }
    private JSONObject library() throws Exception { return new JSONObject().put("version",1).put("favorites",new JSONArray()).put("playlists",new JSONArray()).put("history",new JSONArray()).put("queue",new JSONArray()).put("settings",new JSONObject().put("volume",.4).put("playMode","sequence").put("autoPlayFirst",false)); }
    private void cleanup(String journal) {
        SharedPreferences.Editor edit = PlaybackStore.get(context).edit();
        for (String key : new String[] {"library","preferences","floating_lyrics","sleep_until","library_recovery","preferences_recovery","background_history_recovery"}) edit.remove(key);
        if (journal == null) edit.remove("background_history"); else edit.putString("background_history", journal);
        assertTrue(edit.commit());
    }

    @Test public void providerFixturesHonorGuestWbiCaptionGapsAndLrcOffsetDirection() throws Exception {
        BackgroundLyrics.Result lrc = BackgroundLyrics.lrc("[offset: 500]\n[00:01.00][00:03,20]第一句\n[00:01.00]第一句\n[00:60.00]无效\n[01:00:02.00]小时\n[00:05.00]<00:05.10>第二句", "文本");
        assertEquals(lrc.lines.toString(),4, lrc.lines.length()); assertEquals(.5,lrc.lines.getJSONObject(0).getDouble("time"),.001); assertEquals(2.7,lrc.lines.getJSONObject(1).getDouble("time"),.001); assertEquals("第二句",lrc.lines.getJSONObject(2).getString("text"));
        assertEquals(3601.5,lrc.lines.getJSONObject(3).getDouble("time"),.001);
        JSONObject images = new JSONObject().put("img_url","https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png").put("sub_url","https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png");
        AtomicInteger calls = new AtomicInteger();
        BackgroundLyrics.Result captions = BackgroundLyrics.load(song("BV0000000001","测试歌"),"bilibili", url -> {
            calls.incrementAndGet();
            if (url.contains("/view?")) return "{\"code\":0,\"data\":{\"cid\":2,\"aid\":1}}";
            if (url.endsWith("/nav")) return new JSONObject().put("code",-101).put("data",new JSONObject().put("wbi_img",images)).toString();
            if (url.contains("/wbi/v2?")) { assertTrue(url.contains("aid=1&bvid=BV0000000001&cid=2")); return "{\"code\":0,\"data\":{\"subtitle\":{\"subtitles\":[{\"lan\":\"ai-zh\",\"lan_doc\":\"中文自动生成\",\"subtitle_url\":\"https://aisubtitle.hdslb.com/bfs/ai_subtitle/qa.json\"}]}}}"; }
            return "{\"body\":[{\"from\":1,\"to\":2,\"content\":\"测试第一句\"},{\"from\":3,\"to\":4,\"content\":\"测试第二句\"}]}";
        });
        assertEquals(4,calls.get()); assertEquals(4,captions.lines.length()); assertEquals("",captions.lines.getJSONObject(1).getString("text"));
        BackgroundLyrics.Result searched = BackgroundLyrics.load(song("BV0000000001","测试歌手 - 测试歌曲"),"lrclib", url -> "[{\"id\":1,\"trackName\":\"测试歌曲\",\"artistName\":\"测试歌手\",\"albumName\":\"测试专辑\",\"duration\":120,\"instrumental\":false,\"syncedLyrics\":\"[00:01.00]搜索歌词\",\"plainLyrics\":null}]");
        assertEquals("搜索歌词",searched.lines.getJSONObject(0).getString("text"));
    }

    @Test public void backupStreamsAndAtomicRestoreKeepBackgroundHistoryAndCorruptOriginals() throws Exception {
        isolated(); String journal = PlaybackStore.get(context).getString("background_history",null);
        File document = new File(context.getCacheDir(),"qa-parity-backup.json");
        try {
            JSONObject original = library().put("favorites",new JSONArray().put(song("BV0000000001","现有收藏")));
            assertTrue(PlaybackStore.get(context).edit().putString("library",original.toString()).putString("preferences","{\"version\":1}").commit());
            assertTrue(PlaybackStore.history(context,song("BV0000000002","后台历史")));
            String restored = PlaybackStore.restore(context,original.toString(),"{\"version\":1,\"theme\":\"pixel\"}",new JSONObject().put("font","serif").put("enabled",false));
            assertEquals(1,new JSONObject(restored).getJSONArray("favorites").length());
            assertTrue(new JSONObject(restored).getJSONArray("history").length()>0);
            String before = PlaybackStore.get(context).getString("library",null);
            try { PlaybackStore.restore(context,"{}",null,null); fail("Invalid backup accepted"); } catch (Exception expected) { assertEquals(before,PlaybackStore.get(context).getString("library",null)); }
            assertTrue(PlaybackStore.get(context).edit().putString("library","broken synthetic JSON").putString("preferences","broken preferences").putString("background_history","broken journal").commit());
            PlaybackStore.restore(context,original.toString(),"{\"version\":1}",null);
            assertEquals("broken synthetic JSON",PlaybackStore.get(context).getString("library_recovery",null));
            assertEquals("broken preferences",PlaybackStore.get(context).getString("preferences_recovery",null));
            Uri uri = Uri.fromFile(document);
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) { BackupFiles.write(out,restored); }
            try (InputStream in = context.getContentResolver().openInputStream(uri)) { assertEquals(restored,BackupFiles.read(in)); }
            try { BackupFiles.read(new ByteArrayInputStream(new byte[] {(byte)0xc3,(byte)0x28})); fail("Malformed UTF-8 accepted"); } catch (java.nio.charset.CharacterCodingException expected) { }
            try { BackupFiles.read(new ByteArrayInputStream(new byte[BackupFiles.MAX_BYTES+1])); fail("Oversize accepted"); } catch (IllegalArgumentException expected) { }
        } finally { cleanup(journal); document.delete(); }
    }
    private static final class DocumentCall extends PluginCall {
        final CompletableFuture<JSObject> result = new CompletableFuture<>();
        DocumentCall(String method, JSObject data) { super(null,"YuyinMobile","qa-document-"+method,method,data); }
        @Override public void resolve(JSObject value) { result.complete(value); }
        @Override public void reject(String message, String code) { result.completeExceptionally(new IllegalStateException(code)); }
    }
    @Test public void systemDocumentPickersLaunchOnMainAndCancellationLeavesStoreUntouched() throws Exception {
        isolated();
        String before=PlaybackStore.get(context).getString("library",null);
        try(ActivityScenario<QaPlaybackActivity> scenario=ActivityScenario.launch(QaPlaybackActivity.class)) {
            AtomicReference<YuyinMobilePlugin> plugin=new AtomicReference<>(); scenario.onActivity(activity -> plugin.set((YuyinMobilePlugin)activity.getBridge().getPlugin("YuyinMobile").getInstance()));
            JSObject data=new JSObject(); data.put("data",new JSONObject().put("kind","yuyin-mobile-backup").put("schemaVersion",1).put("platform","android").put("library",library()).toString());
            DocumentCall export=new DocumentCall("exportBackup",data);
            // Call from instrumentation thread to model the asynchronous Capacitor plugin thread.
            plugin.get().exportBackup(export);
            await(() -> { android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow(); return root!=null && root.getPackageName()!=null && root.getPackageName().toString().contains("documentsui"); },6000,"Export picker did not open");
            shell("input keyevent 4"); assertFalse(export.result.get(6,TimeUnit.SECONDS).getBoolean("saved"));
            DocumentCall importing=new DocumentCall("importBackup",new JSObject()); plugin.get().importBackup(importing);
            await(() -> { android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow(); return root!=null && root.getPackageName()!=null && root.getPackageName().toString().contains("documentsui"); },6000,"Import picker did not open");
            shell("input keyevent 4"); assertTrue(importing.result.get(6,TimeUnit.SECONDS).isNull("data"));
            assertEquals(before,PlaybackStore.get(context).getString("library",null));
        }
    }

    @Test public void backgroundQueueTimerHistoryAndFloatingWindowsUseNativeInput() throws Exception {
        isolated(); String journal = PlaybackStore.get(context).getString("background_history",null); boolean permissionBefore = FloatingLyricsWindow.permission(context);
        AtomicInteger underlyingTouches = new AtomicInteger(); PlaybackService[] active = new PlaybackService[1];
        try (ActivityScenario<QaPlaybackActivity> scenario = ActivityScenario.launch(QaPlaybackActivity.class)) {
            shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            assertTrue(FloatingLyricsWindow.permission(context));
            JSONObject library = library().put("queue",new JSONArray().put(song("BV0000000001","虚构 A")).put(song("BV0000000002","虚构 B")));
            PlaybackStore.restore(context,library.toString(),"{\"version\":1,\"lyricsProvider\":\"bilibili\",\"lyricOffsets\":{}}",null);
            ContextCompat.startForegroundService(context,new Intent(context,PlaybackService.class));
            await(() -> PlaybackService.current()!=null,5000,"Service not created"); active[0]=PlaybackService.current(); PlaybackService service=active[0];
            main(() -> { /* Wait for Service.onCreate, which publishes instance before constructing WebView. */ });
            WebView source=(WebView)field(service,"source");
            String base64; try (InputStream in=instrumentation.getContext().getAssets().open("controlled-video-with-audio.mp4"); ByteArrayOutputStream bytes=new ByteArrayOutputStream()) { byte[] block=new byte[8192]; int n; while((n=in.read(block))!=-1)bytes.write(block,0,n); base64=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP); }
            String html="<!doctype html><html><body><video playsinline src='data:video/mp4;base64,"+base64+"'></video></body></html>";
            main(() -> { WebViewClient production=source.getWebViewClient(); source.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) { return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8))); }
                @Override public void onPageFinished(WebView view,String url) { production.onPageFinished(view,url); }
            }); service.play(song("BV0000000001","虚构 A"),null); });
            await(() -> "playing".equals(status(service).optString("state")),15000,"Controlled source did not play");
            MediaSession session=(MediaSession)field(service,"mediaSession");
            long actions=session.getController().getPlaybackState().getActions(); assertTrue((actions&PlaybackState.ACTION_SKIP_TO_NEXT)!=0); assertTrue((actions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0);
            session.getController().getTransportControls().skipToNext();
            await(() -> "BV0000000002".equals(status(service).optJSONObject("song").optString("bvid")) && "playing".equals(status(service).optString("state")),15000,"Native next failed");
            session.getController().getTransportControls().skipToPrevious();
            await(() -> "BV0000000001".equals(status(service).optJSONObject("song").optString("bvid")) && "playing".equals(status(service).optString("state")),15000,"Native previous failed");
            // A generation guarded real ended snapshot invokes native progression, without a React listener.
            double endingAt=status(service).optDouble("duration")-.4;
            main(() -> service.seek(endingAt,null));
            await(() -> "BV0000000002".equals(status(service).optJSONObject("song").optString("bvid")) && "playing".equals(status(service).optString("state")),15000,"Automatic queue progression failed");
            JSONArray lines=new JSONArray().put(new JSONObject().put("time",0).put("text","虚构悬浮歌词")).put(new JSONObject().put("time",10).put("text","下一句"));
            @SuppressWarnings("unchecked") java.util.Map<String,BackgroundLyrics.Result> lyricCache=(java.util.Map<String,BackgroundLyrics.Result>)field(service,"lyricCache");
            main(() -> { lyricCache.put("bilibili:BV0000000001",new BackgroundLyrics.Result(lines,"")); lyricCache.put("bilibili:BV0000000002",new BackgroundLyrics.Result(lines,"")); });
            assertTrue(FloatingLyricsWindow.save(context,new JSONObject().put("enabled",true)));
            main(() -> { try { service.setFloatingLines("BV0000000002","bilibili",lines,-.25,""); } catch(Exception e){throw new RuntimeException(e);} });
            FloatingLyricsWindow overlay=(FloatingLyricsWindow)field(service,"floatingLyrics");
            scenario.onActivity(activity -> { service.attachActivity(activity); activity.root.setOnTouchListener((v,event) -> { if(event.getAction()==MotionEvent.ACTION_UP)underlyingTouches.incrementAndGet(); return true; }); });
            SystemClock.sleep(200);
            View text=(View)field(overlay,"text"); assertNotNull(text); float[] point=center(text); double originalY=FloatingLyricsWindow.settings(context).getDouble("y");
            input(point[0],point[1],point[0],point[1]+80); SystemClock.sleep(250);
            assertTrue(FloatingLyricsWindow.settings(context).getDouble("y")>originalY);
            SystemClock.sleep(3300); assertEquals(View.GONE,((View)field(overlay,"toolbarHost")).getVisibility());
            text=(View)field(overlay,"text"); point=center(text); input(point[0],point[1],point[0],point[1]); SystemClock.sleep(200); assertEquals(View.VISIBLE,((View)field(overlay,"toolbarHost")).getVisibility());
            LinearLayout tools=(LinearLayout)field(overlay,"toolbar"); View font=tools.getChildAt(3); point=center(font); input(point[0],point[1],point[0],point[1]); SystemClock.sleep(200); assertEquals("serif",FloatingLyricsWindow.settings(context).getString("font"));
            assertTrue(instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)); SystemClock.sleep(500);
            View rotated=(View)field(overlay,"text"); int[] location=new int[2]; main(() -> rotated.getLocationOnScreen(location));
            android.graphics.Point screen=new android.graphics.Point(); main(() -> ((android.view.WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getSize(screen));
            assertTrue(location[0]>=0 && location[0]+rotated.getWidth()<=screen.x); assertTrue(location[1]>=0 && location[1]+rotated.getHeight()<=screen.y);
            assertTrue(instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)); SystemClock.sleep(500);
            JSONObject locked=FloatingLyricsWindow.settings(context).put("locked",true).put("opacity",.6); assertTrue(FloatingLyricsWindow.save(context,locked)); SystemClock.sleep(250);
            tools=(LinearLayout)field(overlay,"toolbar"); assertEquals(1,tools.getChildCount()); assertEquals("解锁悬浮歌词",tools.getChildAt(0).getContentDescription());
            android.graphics.Bitmap image=instrumentation.getUiAutomation().takeScreenshot();
            if(image!=null) { try(OutputStream output=new FileOutputStream(new File(context.getCacheDir(),"qa-parity-locked-overlay.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output); } finally { image.recycle(); } }
            double lockedY=FloatingLyricsWindow.settings(context).getDouble("y"); text=(View)field(overlay,"text"); point=center(text); int before=underlyingTouches.get(); input(point[0],point[1],point[0],point[1]+40); SystemClock.sleep(100);
            assertTrue("Locked text must pass real touch to underlying Activity",underlyingTouches.get()>before); assertEquals(lockedY,FloatingLyricsWindow.settings(context).getDouble("y"),.0001);
            point=center(tools.getChildAt(0)); input(point[0],point[1],point[0],point[1]); SystemClock.sleep(200); assertFalse(FloatingLyricsWindow.settings(context).getBoolean("locked"));
            // Cached lyrics are reloaded by the service after background skip with no Activity.
            main(() -> { try { service.setFloatingLines("BV0000000002","bilibili",lines,-.25,""); } catch(Exception e){throw new RuntimeException(e);} });
            scenario.moveToState(Lifecycle.State.DESTROYED);
            session.getController().getTransportControls().skipToPrevious(); await(() -> "BV0000000001".equals(status(service).optJSONObject("song").optString("bvid")) && "playing".equals(status(service).optString("state")),15000,"Background previous failed");
            session.getController().getTransportControls().skipToNext(); await(() -> "BV0000000002".equals(status(service).optJSONObject("song").optString("bvid")) && "playing".equals(status(service).optString("state")),15000,"Background next failed");
            assertEquals("BV0000000002",field(overlay,"bvid"));
            long until=System.currentTimeMillis()+2000; assertTrue(PlaybackStore.get(context).edit().putLong("sleep_until",until).commit());
            shell("input keyevent 223"); await(() -> "paused".equals(status(service).optString("state")),7000,"Screen-off native timer did not pause");
            await(() -> !PlaybackStore.get(context).contains("sleep_until"),3000,"Expired timer was not cleared");
            assertTrue(new JSONObject(PlaybackStore.library(context)).getJSONArray("history").length()>=2);
        } finally {
            shell("input keyevent 224"); shell("wm dismiss-keyguard");
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            if(active[0]!=null)main(active[0]::stopPlayback);
            await(() -> PlaybackService.current()==null,4000,"Service did not stop");
            cleanup(journal); if(!permissionBefore)shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW deny");
        }
    }
}
