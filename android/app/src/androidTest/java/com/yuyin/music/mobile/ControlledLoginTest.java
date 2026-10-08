package com.yuyin.music.mobile;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Response-controlled dialog regression. No cookies are injected and no real account is logged in. */
@RunWith(AndroidJUnit4.class)
public class ControlledLoginTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private static Long identity(int status, String body) throws Exception {
        Method parser = YuyinMobilePlugin.class.getDeclaredMethod("loginIdentityResponse", int.class, String.class);
        parser.setAccessible(true);
        return (Long) parser.invoke(null, status, body);
    }
    private static Object field(YuyinMobilePlugin plugin, String name) throws Exception {
        Field value = YuyinMobilePlugin.class.getDeclaredField(name); value.setAccessible(true); return value.get(plugin);
    }
    private static void field(YuyinMobilePlugin plugin, String name, Object value) throws Exception {
        Field target = YuyinMobilePlugin.class.getDeclaredField(name); target.setAccessible(true); target.set(plugin, value);
    }
    private static void complete(YuyinMobilePlugin plugin, WebView view, Dialog dialog, long generation, String signature, Long identity) throws Exception {
        complete(plugin, view, dialog, generation, signature, false, identity);
    }
    private static void complete(YuyinMobilePlugin plugin, WebView view, Dialog dialog, long generation, String signature, boolean baseline, Long identity) throws Exception {
        Method method = YuyinMobilePlugin.class.getDeclaredMethod("completeLoginVerification", WebView.class, Dialog.class, long.class, String.class, boolean.class, Long.class);
        method.setAccessible(true); method.invoke(plugin, view, dialog, generation, signature, baseline, identity);
    }
    private void onMain(CheckedAction action) {
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }
    private interface CheckedAction { void run() throws Exception; }
    private static final class Result extends PluginCall {
        int resolutions;
        String failure;
        Result() { super(null, "YuyinMobile", "controlled-login", "openLogin", new JSObject()); }
        @Override public void resolve() { resolutions++; }
        @Override public void reject(String message, String code) { failure = code; }
    }

    @Test public void officialNavMustConfirmAValidAccountBeforeLoginCanFinish() throws Exception {
        String valid = "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"受控账号\"}}";
        assertEquals(Long.valueOf(42), identity(200, valid));
        assertEquals(Long.valueOf(0), identity(200, "{\"code\":-101}"));
        assertEquals(Long.valueOf(0), identity(200, "{\"code\":0,\"data\":{\"isLogin\":false}}"));
        assertNull(identity(403, valid));
        String[] invalid = {
            "{\"code\":-352,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"验证中\"}}",
            "{\"code\":\"0\",\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"错误\"}}",
            "{\"code\":0,\"data\":{\"isLogin\":\"true\",\"mid\":42,\"uname\":\"错误\"}}",
            "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":\"42\",\"uname\":\"错误\"}}",
            "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":0,\"uname\":\"错误\"}}",
            "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":1.5,\"uname\":\"错误\"}}",
            "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":9007199254740992,\"uname\":\"错误\"}}",
            "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"<b></b>\"}}",
            "{\"code\":0}", "<html>challenge</html>"
        };
        for (String body : invalid) assertNull(identity(200, body));
    }

    @Test public void confirmedLoginClosesTheActualDialogButOldOrUnchangedAccountsDoNot() throws Exception {
        Assume.assumeTrue("Explicit isolated emulator opt-in required", "true".equals(InstrumentationRegistry.getArguments().getString("yuyinControlledEmulator")));
        AtomicReference<String> signature = new AtomicReference<>();
        onMain(() -> signature.set(NativePolicy.authenticationSignature(CookieManager.getInstance().getCookie("https://api.bilibili.com/"))));
        Assume.assumeTrue("Never run controlled login against a signed-in profile", signature.get().isEmpty());
        Context context = instrumentation.getTargetContext();
        String libraryBefore = context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("library", null);
        String preferencesBefore = context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("preferences", null);
        AtomicReference<YuyinMobilePlugin> selected = new AtomicReference<>();
        AtomicReference<Dialog> previousDialog = new AtomicReference<>();
        AtomicReference<WebView> previousView = new AtomicReference<>();
        long[] previousGeneration = new long[1];
        try (ActivityScenario<QaPlaybackActivity> scenario = ActivityScenario.launch(QaPlaybackActivity.class)) {
            // An unregistered instance uses the real Activity/window without emitting fake account events to React.
            scenario.onActivity(activity -> { YuyinMobilePlugin plugin = new YuyinMobilePlugin(); plugin.setBridge(activity.getBridge()); selected.set(plugin); });
            YuyinMobilePlugin plugin = selected.get();
            Result first = new Result();
            try {
                onMain(() -> plugin.openLogin(first));
                onMain(() -> {
                    assertNull(first.failure);
                    Dialog dialog = (Dialog) field(plugin, "dialog");
                    WebView view = (WebView) field(plugin, "loginView");
                    long generation = (Long) field(plugin, "loginGeneration");
                    previousDialog.set(dialog); previousView.set(view); previousGeneration[0] = generation;
                    assertTrue(dialog.isShowing());
                    assertEquals("No local identity establishes a logged-out baseline, never login success", Long.valueOf(0), field(plugin, "loginBaselineIdentity"));
                    assertEquals(0, first.resolutions);
                    view.stopLoading();
                    ((Handler) field(plugin, "main")).removeCallbacks((Runnable) field(plugin, "watchLoginSession"));
                    field(plugin, "loginVerificationPending", true);
                    field(plugin, "loginBaselineIdentity", 41L);
                    complete(plugin, view, dialog, generation, signature.get(), 41L);
                    assertTrue("Managing an unchanged signed-in account keeps the login page open", dialog.isShowing());
                    complete(plugin, view, dialog, generation - 1, signature.get(), 42L);
                    assertTrue("Old dialog generation cannot dismiss a current window", dialog.isShowing());
                    complete(plugin, view, dialog, generation, "different-controlled-signature", 42L);
                    assertTrue("Response for an old Cookie session cannot finish login", dialog.isShowing());
                    complete(plugin, view, dialog, generation, signature.get(), null);
                    complete(plugin, view, dialog, generation, signature.get(), 0L);
                    assertTrue("Network, challenge and logged-out results retain the login window", dialog.isShowing());
                    Long confirmed = identity(200, "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"受控账号\"}}");
                    complete(plugin, view, dialog, generation, signature.get(), confirmed);
                    assertFalse("Confirmed account change automatically returns to the player", dialog.isShowing());
                    complete(plugin, view, dialog, generation, signature.get(), confirmed);
                });
                // Dialog posts OnDismissListener to its Handler. Let that queued callback run before
                // checking resource cleanup and the real promise completion, without blocking main.
                onMain(() -> {
                    assertNull(field(plugin, "loginView")); assertNull(field(plugin, "dialog"));
                    assertEquals(1, first.resolutions);
                    complete(plugin, previousView.get(), previousDialog.get(), previousGeneration[0], signature.get(), 42L);
                    assertEquals("Repeated/stale callback cannot resolve twice", 1, first.resolutions);
                });
                Result next = new Result();
                onMain(() -> plugin.openLogin(next));
                onMain(() -> {
                    Dialog dialog = (Dialog) field(plugin, "dialog");
                    WebView view = (WebView) field(plugin, "loginView");
                    long generation = (Long) field(plugin, "loginGeneration");
                    view.stopLoading();
                    ((Handler) field(plugin, "main")).removeCallbacks((Runnable) field(plugin, "watchLoginSession"));
                    field(plugin, "loginVerificationPending", true);
                    complete(plugin, previousView.get(), previousDialog.get(), previousGeneration[0], signature.get(), 42L);
                    assertTrue("A delayed result from the previous window cannot close the next login", dialog.isShowing());
                    complete(plugin, previousView.get(), previousDialog.get(), previousGeneration[0], signature.get(), true, 41L);
                    assertEquals("A delayed baseline response cannot overwrite the new window baseline", Long.valueOf(0), field(plugin, "loginBaselineIdentity"));
                    assertEquals(0, next.resolutions);
                    complete(plugin, view, dialog, generation, signature.get(), 42L);
                    assertFalse("Logged-out to logged-in transition automatically closes the dialog", dialog.isShowing());
                });
                onMain(() -> {
                    assertNull(field(plugin, "loginView")); assertNull(field(plugin, "dialog"));
                    assertEquals(1, next.resolutions);
                });
                Result challenged = new Result();
                onMain(() -> plugin.openLogin(challenged));
                onMain(() -> {
                    Dialog dialog = (Dialog) field(plugin, "dialog");
                    WebView view = (WebView) field(plugin, "loginView");
                    long generation = (Long) field(plugin, "loginGeneration");
                    view.stopLoading();
                    ((Handler) field(plugin, "main")).removeCallbacks((Runnable) field(plugin, "watchLoginSession"));
                    field(plugin, "loginVerificationPending", true);
                    field(plugin, "loginBaselineIdentity", null);
                    field(plugin, "loginOpeningSignature", signature.get());
                    Long challenge = identity(200, "{\"code\":-352}");
                    complete(plugin, view, dialog, generation, signature.get(), true, challenge);
                    assertNull("A challenge never becomes a logged-out baseline", field(plugin, "loginBaselineIdentity"));
                    complete(plugin, view, dialog, generation, signature.get(), 42L);
                    assertTrue("An unchanged initial session with unknown identity stays open", dialog.isShowing());
                    // Only private fingerprint state changes here; the actual CookieManager remains untouched.
                    field(plugin, "loginOpeningSignature", "previous-controlled-session");
                    complete(plugin, view, dialog, generation, "previous-controlled-session", true, challenge);
                    assertTrue("A failed old baseline still keeps the dialog until current nav confirms login", dialog.isShowing());
                    complete(plugin, view, dialog, generation, signature.get(), challenge);
                    assertTrue(dialog.isShowing());
                    Long confirmed = identity(200, "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"受控账号\"}}");
                    complete(plugin, view, dialog, generation, signature.get(), confirmed);
                    assertFalse("Valid current nav finishes a changed session despite the old baseline challenge", dialog.isShowing());
                });
                onMain(() -> {
                    assertNull(field(plugin, "loginView")); assertNull(field(plugin, "dialog"));
                    assertEquals(1, challenged.resolutions);
                });
            } finally { onMain(plugin::handleOnDestroy); }
        }
        assertEquals(libraryBefore, context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("library", null));
        assertEquals(preferencesBefore, context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE).getString("preferences", null));
    }
}
